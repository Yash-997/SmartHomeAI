package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.smarthome.alert.AlertDispatcher;
import com.smarthome.user.UserRepository;

import tools.jackson.databind.JsonNode;

/**
 * Motion-while-away emails: triggered by the real telemetry ingest and Security, delivered by a recording fake mailer.
 */
class MotionEmailTests extends IntelTestBase {

	@Autowired RecordingAlertMailer mailer;
	@Autowired AlertDispatcher alertDispatcher;
	@Autowired UserRepository userRepo;

	@BeforeEach
	void resetMailer() {
		alertDispatcher.awaitIdle();
		mailer.reset();
	}

	private List<RecordingAlertMailer.Sent> mailsTo(Paired p) {
		alertDispatcher.awaitIdle();
		String to = userRepo.findById(p.userId()).orElseThrow().getEmail();
		return mailer.sent().stream().filter(m -> m.to().equals(to)).toList();
	}

	/** Moves this device's history back in time (test database only), as in SecurityModeTests. */
	private void age(Paired p, int seconds) {
		jdbc.update("update security_events set created_at = DATEADD('SECOND', ?, created_at) where device_uuid = ?", -seconds, p.uuid());
		jdbc.update("update telemetry_readings set received_at = DATEADD('SECOND', ?, received_at) where device_uuid = ?", -seconds, p.uuid());
		jdbc.update("update security_settings set mode_changed_at = DATEADD('SECOND', ?, mode_changed_at) where owner_user_id = ?", -seconds,
				p.userId());
	}

	@Test
	void motionWhileAwaySendsOneEmailToTheOwner() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Front door");
		setMode(t, "AWAY");
		motion(p);

		List<RecordingAlertMailer.Sent> mails = mailsTo(p);
		assertEquals(1, mails.size());
		RecordingAlertMailer.Sent m = mails.get(0);
		assertTrue(m.subject().startsWith("Motion detected while you are away"), m.subject());
		assertTrue(m.html().contains("MOTION DETECTED WHILE YOU ARE AWAY") && m.html().contains("Front door"));
		String alertId = events(t).get(0).get("id").asString();
		assertTrue(m.html().contains("?event=" + alertId), "the button opens this exact event");
		assertTrue(m.body().contains("AWAY"), "plain-text part too");
	}

	@Test
	void oneEmailPerEpisodeNotPerTelemetryReport() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Hall");
		setMode(t, "AWAY");
		motion(p);
		for (int i = 0; i < 8; i++) {                     // 4 more minutes of motion, a report every 30 s
			age(p, 30);
			motion(p);
		}
		assertEquals(1, mailsTo(p).size(), "one episode, one email");

		stillness(p);                                     // the episode ends
		age(p, 120);
		motion(p);                                        // a new episode
		assertEquals(2, mailsTo(p).size());
		assertEquals(2, countKind(t, "MOTION_ALERT"));
	}

	@Test
	void homeAndNightModeNeverSendAnAwayEmail() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Living room");
		motion(p);                                        // HOME
		stillness(p);
		setMode(t, "NIGHT");
		motion(p);                                        // NIGHT records an alert but is not "away"
		assertEquals(1, countKind(t, "MOTION_ALERT"));
		assertEquals(0, mailsTo(p).size());
	}

	@Test
	void aFailingMailServerNeverStopsTheAlertAndIsRecordedWithoutSecrets() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Garage");
		setMode(t, "AWAY");
		mailer.failWith("SMTP login was rejected. Check MAIL_USERNAME and the Google App Password.");
		motion(p);
		alertDispatcher.awaitIdle();

		assertEquals(1, countKind(t, "MOTION_ALERT"), "the security event is saved");
		JsonNode failures = json(getAs(t, "/api/security/events?kind=ACTION_FAILED"));
		assertEquals(1, failures.size());
		assertEquals("INFO", failures.get(0).get("severity").asString(), "an email problem is not itself an open alert");
		assertTrue(failures.get(0).get("message").asString().contains("could not be sent"));
		assertEquals(1, json(getAs(t, "/api/security/settings")).get("unacknowledgedAlerts").asInt(), "only the motion alert is open");

		mailer.reset();                                   // the same episode continues: no retry storm, no second email
		age(p, 30);
		motion(p);
		assertEquals(0, mailsTo(p).size());
	}

	@Test
	void withoutMailSetupTheAlertIsStillRecordedAndNothingIsSent() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Shed");
		setMode(t, "AWAY");
		mailer.setConfigured(false);
		motion(p);
		assertEquals(1, countKind(t, "MOTION_ALERT"));
		assertEquals(0, mailsTo(p).size());
		assertEquals(0, json(getAs(t, "/api/security/events?kind=ACTION_FAILED")).size());
	}

	@Test
	void theEmailNeverContainsCredentials() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Porch");
		setMode(t, "AWAY");
		motion(p);
		RecordingAlertMailer.Sent m = mailsTo(p).get(0);
		for (String part : new String[] { m.subject(), m.body(), m.html() }) {
			String lower = part.toLowerCase();
			assertFalse(lower.contains("password") || lower.contains("credential") || lower.contains("api key") || lower.contains("token"),
					"no secrets or secret-looking fields in the email");
		}
		getAs(t, "/api/security/settings").andExpect(status().isOk());
	}
}
