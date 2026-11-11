package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.smarthome.alert.AlertDispatcher;
import com.smarthome.alert.GasAlert;
import com.smarthome.alert.GasAlert.Delivery;
import com.smarthome.alert.GasAlert.State;
import com.smarthome.alert.GasAlertRepository;
import com.smarthome.alert.GasAlertService;
import com.smarthome.user.UserRepository;

import tools.jackson.databind.JsonNode;

/**
 * Gas/smoke email alerts. No hardware and no real SMTP server: readings go through the real telemetry ingest, and the
 * mailer is a recording fake. Test configuration: threshold 2500, recovery below 2125 (15 % margin), 2 confirming readings.
 */
class GasAlertTests extends AutomationTestBase {

	private static final AtomicInteger SEQ = new AtomicInteger(50_000);

	@Autowired RecordingAlertMailer mailer;
	@Autowired GasAlertRepository alertRepo;
	@Autowired GasAlertService alertService;
	@Autowired AlertDispatcher alertDispatcher;
	@Autowired UserRepository userRepo;

	@BeforeEach
	void reset() {
		alertDispatcher.awaitIdle();      // emails queued by earlier tests (e.g. Away motion) must not land in this test
		mailer.reset();
		alertService.resetCounters();
	}

	/** Sends one gas reading through the real ingest path and waits for the alert worker to finish. */
	private void gas(Paired p, int value) {
		sendTelemetry(p, "{\"seq\":" + SEQ.incrementAndGet() + ",\"gasRaw\":" + value + "}");
		alertDispatcher.awaitIdle();
	}

	private List<GasAlert> incidents(Paired p) {
		return alertRepo.findByOwnerUserIdOrderByStartedAtDesc(p.userId(), org.springframework.data.domain.PageRequest.of(0, 50));
	}

	private Paired newDevice() throws Exception {
		return onlineDevice(newUserToken());
	}

	// ---- starting an incident ------------------------------------------------------------------

	@Test
	void normalReadingsNeverCreateAnIncidentOrAnEmail() throws Exception {
		Paired p = newDevice();
		gas(p, 400);
		gas(p, 2499);
		gas(p, 2499);
		assertTrue(incidents(p).isEmpty());
		assertTrue(mailer.sent().isEmpty());
	}

	@Test
	void oneNoisyReadingAboveTheThresholdDoesNotAlert() throws Exception {
		Paired p = newDevice();
		gas(p, 3000);
		gas(p, 500);
		gas(p, 3000);                       // the streak was broken, so this is the first of a new one
		assertTrue(incidents(p).isEmpty());
		assertTrue(mailer.sent().isEmpty());
	}

	@Test
	void sustainedGasSendsExactlyOneEmergencyEmailToTheOwner() throws Exception {
		Paired p = newDevice();
		String ownerEmail = userRepo.findById(p.userId()).orElseThrow().getEmail();
		gas(p, 2600);
		gas(p, 2800);                       // second confirming reading starts the incident
		gas(p, 3900);
		gas(p, 3700);
		gas(p, 2700);

		assertEquals(1, mailer.sent().size(), "one alert per incident, however long the gas stays high");
		RecordingAlertMailer.Sent mail = mailer.sent().get(0);
		assertEquals(ownerEmail, mail.to());
		assertTrue(mail.subject().startsWith("GAS/SMOKE ALERT"), mail.subject());
		assertTrue(mail.body().contains("2800"), "the reading that confirmed the incident");
		assertTrue(mail.html().contains("GAS/SMOKE ALERT") && mail.html().contains("2800 raw ADC (0-4095)"), "HTML part with the raw unit");
		assertFalse(mail.html().matches("(?s).*\\d\\s*ppm.*"), "ADC values are never labelled ppm");

		List<GasAlert> all = incidents(p);
		assertEquals(1, all.size());
		GasAlert a = all.get(0);
		assertEquals(State.ACTIVE, a.getState());
		assertEquals(Delivery.SENT, a.getAlertEmailStatus());
		assertEquals(2800, a.getTriggerGasRaw());
		assertEquals(3900, a.getPeakGasRaw());
		assertEquals(2700, a.getLastGasRaw());
		assertNull(a.getRecoveryEmailStatus());
	}

	@Test
	void readingsWithoutAGasValueChangeNothing() throws Exception {
		Paired p = newDevice();
		gas(p, 3000);
		sendTelemetry(p, "{\"seq\":" + SEQ.incrementAndGet() + ",\"motion\":true}");
		alertDispatcher.awaitIdle();
		gas(p, 3000);
		assertEquals(1, mailer.sent().size(), "the motion-only message must not reset the streak");
	}

	// ---- recovery ------------------------------------------------------------------------------

	@Test
	void recoveryEmailIsSentOnceWhenReadingsStayBelowTheRecoveryLevel() throws Exception {
		Paired p = newDevice();
		gas(p, 3000);
		gas(p, 3000);
		gas(p, 2200);                       // under the alert threshold but inside the hysteresis band: still active
		gas(p, 2200);
		assertEquals(1, mailer.sent().size());
		assertEquals(State.ACTIVE, incidents(p).get(0).getState());

		gas(p, 1000);
		assertEquals(1, mailer.sent().size(), "one calm reading is not enough");
		gas(p, 900);

		assertEquals(2, mailer.sent().size());
		assertTrue(mailer.sent().get(1).subject().startsWith("Resolved"));
		GasAlert a = incidents(p).get(0);
		assertEquals(State.RECOVERED, a.getState());
		assertEquals(Delivery.SENT, a.getRecoveryEmailStatus());
		assertEquals(900, a.getLastGasRaw());

		gas(p, 800);
		gas(p, 800);
		assertEquals(2, mailer.sent().size(), "no further emails once the incident is over");
	}

	@Test
	void anActiveIncidentSurvivesABackendRestartAndStillRecoversOnce() throws Exception {
		Paired p = newDevice();
		gas(p, 3000);
		gas(p, 3000);
		assertEquals(1, mailer.sent().size());

		alertService.resetCounters();       // a restart loses the in-memory streaks; the ACTIVE row is in the database
		gas(p, 3200);
		gas(p, 3200);
		assertEquals(1, incidents(p).size(), "high readings after the restart continue the same incident");
		assertEquals(1, mailer.sent().size(), "and do not send a second emergency email");
		assertEquals(3200, incidents(p).get(0).getPeakGasRaw());

		alertService.resetCounters();
		gas(p, 900);
		gas(p, 900);
		GasAlert a = incidents(p).get(0);
		assertEquals(State.RECOVERED, a.getState());
		assertEquals(Delivery.SENT, a.getRecoveryEmailStatus());
		assertEquals(2, mailer.sent().size());
	}

	@Test
	void aFailedRecoveryEmailIsRecordedWithoutAffectingTheAlertStatus() throws Exception {
		Paired p = newDevice();
		gas(p, 3000);
		gas(p, 3000);
		mailer.failWith("SMTP send failed (SocketTimeoutException).");
		gas(p, 500);
		gas(p, 500);

		GasAlert a = incidents(p).get(0);
		assertEquals(State.RECOVERED, a.getState());
		assertEquals(Delivery.SENT, a.getAlertEmailStatus());
		assertEquals(Delivery.FAILED, a.getRecoveryEmailStatus());
		assertTrue(a.getRecoveryEmailError().contains("SocketTimeoutException"));
		assertNull(a.getRecoveryEmailSentAt());
	}

	@Test
	void aSpikeDuringRecoveryRestartsTheCalmCount() throws Exception {
		Paired p = newDevice();
		gas(p, 3000);
		gas(p, 3000);
		gas(p, 1000);
		gas(p, 3000);                       // back up: the calm streak is reset
		gas(p, 1000);
		assertEquals(1, mailer.sent().size());
		assertEquals(State.ACTIVE, incidents(p).get(0).getState());
		gas(p, 1000);
		assertEquals(State.RECOVERED, incidents(p).get(0).getState());
	}

	@Test
	void aNewIncidentAfterRecoveryGetsItsOwnAlert() throws Exception {
		Paired p = newDevice();
		gas(p, 3000);
		gas(p, 3000);
		gas(p, 500);
		gas(p, 500);
		gas(p, 3200);
		gas(p, 3200);

		assertEquals(3, mailer.sent().size());     // alert, recovery, alert
		assertEquals(2, incidents(p).size());
		assertEquals(State.ACTIVE, incidents(p).get(0).getState());
		assertEquals(State.RECOVERED, incidents(p).get(1).getState());
	}

	// ---- delivery status -----------------------------------------------------------------------

	@Test
	void aFailingMailServerIsRecordedAndDoesNotCauseRepeatEmails() throws Exception {
		Paired p = newDevice();
		mailer.failWith("SMTP login was rejected. Check MAIL_USERNAME and the Google App Password.");
		gas(p, 3000);
		gas(p, 3000);
		gas(p, 3100);
		gas(p, 3100);

		GasAlert a = incidents(p).get(0);
		assertEquals(State.ACTIVE, a.getState(), "the incident exists even though the email failed");
		assertEquals(Delivery.FAILED, a.getAlertEmailStatus());
		assertTrue(a.getAlertEmailError().contains("SMTP login"));
		assertTrue(mailer.sent().isEmpty());
	}

	@Test
	void withoutMailSetupTheIncidentIsRecordedAsSkipped() throws Exception {
		Paired p = newDevice();
		mailer.setConfigured(false);
		gas(p, 3000);
		gas(p, 3000);

		GasAlert a = incidents(p).get(0);
		assertEquals(Delivery.SKIPPED, a.getAlertEmailStatus());
		assertTrue(a.getAlertEmailError().contains("MAIL_PASSWORD"));
		assertTrue(mailer.sent().isEmpty());

		gas(p, 100);
		gas(p, 100);
		assertEquals(Delivery.SKIPPED, incidents(p).get(0).getRecoveryEmailStatus());
	}

	// ---- API -----------------------------------------------------------------------------------

	@Test
	void theApiNeedsALogin() throws Exception {
		mvc.perform(get("/api/alerts/gas")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/alerts/gas/config")).andExpect(status().isUnauthorized());
	}

	@Test
	void historyShowsOnlyTheCallersIncidentsWithDeliveryStatus() throws Exception {
		Paired mine = newDevice();
		Paired theirs = newDevice();
		gas(mine, 3000);
		gas(mine, 3000);
		gas(theirs, 3000);
		gas(theirs, 3000);

		JsonNode list = mapper.readTree(mvc.perform(get("/api/alerts/gas").header("Authorization", mine.auth()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertEquals(1, list.size());
		assertEquals(mine.uuid().toString(), list.get(0).get("deviceId").asString());
		assertEquals("ACTIVE", list.get(0).get("state").asString());
		assertEquals("SENT", list.get(0).get("alertEmailStatus").asString());
		assertFalse(list.toString().contains("@"), "no email address in the history");
	}

	@Test
	void configShowsThresholdsAndAMaskedRecipientButNoSecrets() throws Exception {
		Paired p = newDevice();
		String body = mvc.perform(get("/api/alerts/gas/config").header("Authorization", p.auth()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		JsonNode c = mapper.readTree(body);
		assertEquals(2500, c.get("threshold").asInt());
		assertEquals(2125, c.get("recoveryThreshold").asInt());
		assertEquals(2, c.get("confirmReadings").asInt());
		assertTrue(c.get("mailConfigured").asBoolean());
		assertTrue(c.get("recipient").asString().matches("a\\*\\*\\*@example\\.com"), c.get("recipient").asString());
		assertFalse(body.toLowerCase().contains("password"));
	}

	// ---- test email ----------------------------------------------------------------------------

	private org.springframework.test.web.servlet.ResultActions testEmail(Paired p) throws Exception {
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/alerts/gas/test-email")
				.header("Authorization", p.auth()));
	}

	@Test
	void testEmailGoesToTheCallersOwnAddressWithoutCreatingAnIncident() throws Exception {
		Paired p = newDevice();
		String ownerEmail = userRepo.findById(p.userId()).orElseThrow().getEmail();
		JsonNode r = mapper.readTree(testEmail(p).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertEquals("SENT", r.get("status").asString());
		assertEquals(1, mailer.sent().size());
		assertEquals(ownerEmail, mailer.sent().get(0).to());
		assertFalse(r.toString().contains(ownerEmail), "only the masked address is returned");
		assertTrue(incidents(p).isEmpty());
	}

	@Test
	void testEmailIsRateLimitedAndReportsFailuresAndMissingSetup() throws Exception {
		Paired p = newDevice();
		testEmail(p).andExpect(status().isOk());
		testEmail(p).andExpect(status().isTooManyRequests());
		assertEquals(1, mailer.sent().size());

		Paired q = newDevice();
		mailer.failWith("SMTP login was rejected. Check MAIL_USERNAME and the Google App Password.");
		assertEquals("FAILED", mapper.readTree(testEmail(q).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("status").asString());

		Paired r = newDevice();
		mailer.reset();
		mailer.setConfigured(false);
		assertEquals("SKIPPED", mapper.readTree(testEmail(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("status").asString());
		assertTrue(mailer.sent().isEmpty());
	}

	@Test
	void testEmailNeedsALogin() throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/alerts/gas/test-email"))
				.andExpect(status().isUnauthorized());
	}
}
