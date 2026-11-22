package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;

import com.smarthome.alert.AlertDispatcher;
import com.smarthome.ml.AnomalyKind;
import com.smarthome.ml.AnomalySeverity;
import com.smarthome.ml.Finding;
import com.smarthome.ml.MlAnomaly;
import com.smarthome.user.UserRepository;

import tools.jackson.databind.JsonNode;

/**
 * Anomaly detection, the report and the anomaly email together: one anomaly detected by the real scanner is recorded
 * in the history, appears in the generated report and triggers exactly one email with the same facts.
 */
class AnomalyEmailTests extends MlTestBase {

	private static final Duration LOOKBACK = Duration.ofHours(3);

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

	private List<MlAnomaly> anomalies(Paired p) {
		return mlAnomalies.findByDeviceUuidOrderByLastSeenAtDesc(p.uuid(), PageRequest.of(0, 100));
	}

	private Paired deviceWithBaseline(Instant now) throws Exception {
		Paired p = pair(newUserToken());
		seedNormalHistory(p, now, 400, 40);
		return p;
	}

	private void add(Paired p, Instant at, Double temp, Double hum, Integer gas) {
		save(List.of(reading(p, at, temp, hum, false, gas)));
	}

	private JsonNode json(String body) {
		return mapper.readTree(body);
	}

	@Test
	void oneDetectedAnomalyIsInTheHistoryTheReportAndExactlyOneEmail() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);
		add(p, now.minusSeconds(60), 45.0, 50.0, 1500);                 // 23 °C above this device's normal

		scanner.scan(p.uuid(), now, LOOKBACK);

		// 1. recorded in the history
		List<MlAnomaly> rows = anomalies(p).stream().filter(a -> a.getKind() == AnomalyKind.STATISTICAL_OUTLIER).toList();
		assertEquals(1, rows.size());
		MlAnomaly a = rows.get(0);

		// 2. one email, with the same facts as the anomaly
		List<RecordingAlertMailer.Sent> mails = mailsTo(p);
		assertEquals(1, mails.size());
		RecordingAlertMailer.Sent m = mails.get(0);
		assertTrue(m.subject().startsWith("CRITICAL ANOMALY: "), m.subject());
		assertTrue(m.html().contains("ANOMALY DETECTED"));
		assertTrue(m.body().contains("Temperature (°C)") && m.body().contains("45 °C"), m.body());
		// The typical value comes from an hour-of-day baseline, so it is about 22 °C (21.9 or 22 depending on the hour the test runs).
		assertTrue(m.body().contains("Expected range:") && m.body().matches("(?s).*\\(typical 2[12](\\.\\d)? °C\\).*"), m.body());
		assertTrue(m.body().contains("CRITICAL") && m.body().contains(p.deviceId()), "severity and device ID");
		assertTrue(m.body().contains("robust standard deviations"), "the anomaly description");

		// 3. the delivery outcome is stored on the anomaly and shown by the API
		MlAnomaly stored = mlAnomalies.findById(a.getId()).orElseThrow();
		assertEquals("SENT", stored.getEmailStatus());
		JsonNode list = json(mvc.perform(get("/api/alerts/anomaly").header("Authorization", p.auth())).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		assertEquals(1, list.size());
		assertEquals("SENT", list.get(0).get("emailStatus").asString());
		assertEquals("45 °C", list.get(0).get("value").asString());
		assertEquals(p.deviceId(), list.get(0).get("deviceId").asString());
		JsonNode cfg = json(mvc.perform(get("/api/alerts/anomaly/config").header("Authorization", p.auth())).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		assertEquals("WARNING", cfg.get("minSeverity").asString());

		// 4. the generated report lists it as an anomaly event with the same facts
		JsonNode report = json(mvc.perform(post("/api/reports").header("Authorization", p.auth()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"daily\",\"timeZone\":\"UTC\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		JsonNode anomalySection = null;
		for (JsonNode s : report.get("content").get("sections")) {
			if ("Anomalies".equals(s.get("heading").asString())) {
				anomalySection = s;
			}
		}
		String section = anomalySection.toString();
		assertTrue(section.contains("Anomaly events") && section.contains("Abnormal value") && section.contains("Expected range"), section);
		assertTrue(section.contains("45 °C") && section.contains("CRITICAL") && section.contains(p.deviceId()) && section.contains("sent"), section);
		assertTrue(section.contains("robust standard deviations"), "the description is in the report");
		String summary = report.get("content").get("sections").get(0).toString();
		assertTrue(summary.contains("Anomaly history: 1 critical") && summary.contains("1 anomaly was emailed"), summary);
	}

	@Test
	void theSameOngoingAnomalyIsEmailedOnce() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);
		add(p, now.minusSeconds(120), 45.0, 50.0, 1500);
		scanner.scan(p.uuid(), now.minusSeconds(100), LOOKBACK);
		add(p, now.minusSeconds(60), 47.0, 50.0, 1500);
		scanner.scan(p.uuid(), now, LOOKBACK);

		MlAnomaly a = anomalies(p).stream().filter(x -> x.getKind() == AnomalyKind.STATISTICAL_OUTLIER).findFirst().orElseThrow();
		assertEquals(2, a.getOccurrences());
		assertEquals(1, mailsTo(p).size(), "one ongoing problem, one email");
	}

	@Test
	void theSameProblemReopenedWithinTheCooldownIsNotEmailedAgain() throws Exception {
		Paired p = pair(newUserToken());
		Instant now = Instant.now();
		Finding f = new Finding(AnomalyKind.OUT_OF_RANGE, "temperature", AnomalySeverity.CRITICAL, "temperature 99 °C is outside the range.",
				Map.of("value", 99.0, "usableMin", -40.0, "usableMax", 80.0), now);
		MlAnomaly first = anomalyService.raise(p.uuid(), f, now).orElseThrow();
		assertEquals(1, mailsTo(p).size());
		assertTrue(mailsTo(p).get(0).body().contains("-40 °C to 80 °C"), "expected range from the detector's limits");

		mvc.perform(patch("/api/devices/" + p.uuid() + "/ml/anomalies/" + first.getId()).header("Authorization", p.auth())
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}")).andExpect(status().isOk());
		MlAnomaly second = anomalyService.raise(p.uuid(), f, now.plusSeconds(60)).orElseThrow();
		alertDispatcher.awaitIdle();

		assertTrue(!second.getId().equals(first.getId()), "a new anomaly row (the first was resolved)");
		assertEquals(1, mailsTo(p).size(), "no second email within the cooldown");
		MlAnomaly stored = mlAnomalies.findById(second.getId()).orElseThrow();
		assertEquals("SKIPPED", stored.getEmailStatus());
		assertTrue(stored.getEmailError().contains("minutes ago"), stored.getEmailError());
	}

	@Test
	void aFailedEmailIsRecordedAndMonitoringCarriesOn() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);
		mailer.failWith("SMTP send failed (SocketTimeoutException).");
		add(p, now.minusSeconds(60), 45.0, 50.0, 1500);

		int findings = scanner.scan(p.uuid(), now, LOOKBACK);
		alertDispatcher.awaitIdle();

		assertTrue(findings >= 1, "the scan completed");
		MlAnomaly a = anomalies(p).stream().filter(x -> x.getKind() == AnomalyKind.STATISTICAL_OUTLIER).findFirst().orElseThrow();
		MlAnomaly stored = mlAnomalies.findById(a.getId()).orElseThrow();
		assertEquals("FAILED", stored.getEmailStatus());
		assertEquals("SMTP send failed (SocketTimeoutException).", stored.getEmailError());

		mailer.reset();                                                  // the next scan still works and still detects
		add(p, now.plusSeconds(30), 46.0, 50.0, 1500);
		scanner.scan(p.uuid(), now.plusSeconds(40), LOOKBACK);
		assertEquals(2, mlAnomalies.findById(a.getId()).orElseThrow().getOccurrences());
		assertEquals(0, mailsTo(p).size(), "a failed anomaly is not retried as a duplicate");
	}

	@Test
	void belowTheEmailSeverityNothingIsSentAndUnconfiguredMailIsSkipped() throws Exception {
		Paired p = pair(newUserToken());
		Instant now = Instant.now();
		MlAnomaly info = anomalyService.raise(p.uuid(), new Finding(AnomalyKind.STUCK_VALUE, "humidity", AnomalySeverity.INFO, "humidity is stuck.",
				Map.of("value", 50.0, "identicalReadings", 60), now), now).orElseThrow();
		alertDispatcher.awaitIdle();
		assertNull(mlAnomalies.findById(info.getId()).orElseThrow().getEmailStatus(), "INFO is below the default WARNING");
		assertEquals(0, mailsTo(p).size());

		mailer.setConfigured(false);
		MlAnomaly warn = anomalyService.raise(p.uuid(), new Finding(AnomalyKind.MISSING_DATA, "gasRaw", AnomalySeverity.WARNING, "No gasRaw value.",
				Map.of("missingMessages", 20, "minutes", 10), now), now).orElseThrow();
		alertDispatcher.awaitIdle();
		MlAnomaly stored = mlAnomalies.findById(warn.getId()).orElseThrow();
		assertEquals("SKIPPED", stored.getEmailStatus());
		assertTrue(stored.getEmailError().contains("not configured"));
	}
}
