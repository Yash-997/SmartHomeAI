package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.smarthome.alert.AlertDispatcher;
import com.smarthome.alert.GasAlert;
import com.smarthome.alert.GasAlertRepository;
import com.smarthome.ml.AnomalyKind;
import com.smarthome.ml.AnomalyModelService;
import com.smarthome.ml.MlAnomaly;
import com.smarthome.ml.MlModel;
import com.smarthome.ml.MlTrainingRun;
import com.smarthome.telemetry.TelemetryReading;
import com.smarthome.user.UserRepository;

import tools.jackson.databind.JsonNode;

/**
 * The Isolation Forest on the REAL telemetry path (telemetry_readings, the live scanner, the anomaly table, the
 * dashboard API, the report and the anomaly email). The history is SIMULATED in the shape the ESP32 firmware sends
 * (one message every 30 s with temperature, humidity, MQ-2 raw and PIR), written into the real telemetry table; one
 * message also goes through the real MQTT ingest. It is kept apart from the synthetic-dataset tests, which use their
 * own tables and labels.
 */
class IsolationForestPipelineTests extends MlTestBase {

	private static final AtomicLong MQTT_SEQ = new AtomicLong(900_000);

	@Autowired AnomalyModelService anomalyModels;
	@Autowired RecordingAlertMailer mailer;
	@Autowired AlertDispatcher alertDispatcher;
	@Autowired UserRepository userRepo;
	@Autowired GasAlertRepository gasAlerts;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void resetMailer() {
		alertDispatcher.awaitIdle();
		mailer.reset();
	}

	/**
	 * SIMULATED ESP32-shaped history, one message per 30 s, from {@code days} ago until {@code until}: a daily temperature
	 * cycle, humidity falling as it warms, the MQ-2 following humidity around {@code gasLevel}, motion in the daytime.
	 */
	private void seedEsp32History(Paired p, Instant until, int days, int gasLevel) {
		Random r = new Random(21);
		Instant start = until.minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
		List<TelemetryReading> rows = new ArrayList<>();
		for (Instant t = start; t.isBefore(until); t = t.plusSeconds(30)) {
			double hour = t.atZone(java.time.ZoneId.of("Asia/Kolkata")).getHour() + t.atZone(java.time.ZoneId.of("Asia/Kolkata")).getMinute() / 60.0;
			double temp = Math.round((26 + 2 * Math.sin(2 * Math.PI * (hour - 9) / 24) + r.nextGaussian() * 0.1) * 10) / 10.0;
			double hum = Math.round((65 - 1.4 * (temp - 26) + r.nextGaussian() * 0.5) * 10) / 10.0;
			int gas = (int) Math.round(gasLevel + 1.5 * (hum - 65) + r.nextGaussian() * 6);
			boolean motion = hour >= 7 && hour < 23 && r.nextDouble() < 0.2;
			rows.add(reading(p, t, temp, hum, motion, gas));
		}
		save(rows);
	}

	/** The temperature the seeded history would have at that moment (same daily curve), with its usual noise. */
	private static double normalTemperature(Instant t, Random noise) {
		java.time.ZonedDateTime local = t.atZone(java.time.ZoneId.of("Asia/Kolkata"));
		double hour = local.getHour() + local.getMinute() / 60.0;
		return Math.round((26 + 2 * Math.sin(2 * Math.PI * (hour - 9) / 24) + noise.nextGaussian() * 0.1) * 10) / 10.0;
	}

	/** Trains the real-data model through the API and returns the response. */
	private JsonNode train(Paired p) throws Exception {
		return mapper.readTree(mvc.perform(post("/api/devices/" + p.uuid() + "/ml/anomaly-model/train").header("Authorization", p.auth()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private List<MlAnomaly> forestAnomalies(Paired p) {
		return mlAnomalies.findByDeviceUuidOrderByLastSeenAtDesc(p.uuid(), PageRequest.of(0, 100)).stream()
				.filter(a -> a.getKind() == AnomalyKind.MULTIVARIATE_OUTLIER).toList();
	}

	private List<RecordingAlertMailer.Sent> forestMails(Paired p) {
		alertDispatcher.awaitIdle();
		String to = userRepo.findById(p.userId()).orElseThrow().getEmail();
		return mailer.sent().stream().filter(m -> m.to().equals(to) && m.body().contains("Multivariate outlier")).toList();
	}

	@Test
	void realTelemetryTrainsAReadyModelKeptApartFromTheSyntheticOne() throws Exception {
		Instant now = Instant.now();
		Paired p = pair(newUserToken());
		seedEsp32History(p, now.minus(5, ChronoUnit.MINUTES), 4, 300);

		JsonNode s = train(p);
		JsonNode real = s.get("real");
		assertEquals("READY", real.get("state").asString(), real.toString());
		assertEquals("REAL", real.get("dataSource").asString());
		assertEquals(1, real.get("version").asInt());
		assertTrue(s.get("liveDetection").asBoolean());
		assertTrue(s.get("synthetic").isNull(), "no synthetic model is created from real data");
		JsonNode eval = real.get("evaluation");
		assertFalse(eval.has("precision"), "no labels on real telemetry: no precision or recall is claimed");
		assertTrue(eval.get("note").asString().contains("cannot be measured"));
		assertTrue(eval.get("testAlarmsPerDay").asDouble() <= 3 * eval.get("alarmBudgetPerDay").asDouble());
		assertEquals(42, real.get("model").get("seed").asLong(), "fixed seed");

		MlModel stored = mlModels.findByDeviceUuidAndModelType(p.uuid(), MlModel.ANOMALY_IF).orElseThrow();
		assertNotNull(stored.getArtifactJson(), "the model and its preprocessing are persisted");
		assertTrue(stored.getArtifactJson().contains("\"preprocessing\"") && stored.getArtifactJson().contains("\"forests\""));
		assertTrue(anomalyModels.readyModel(p.uuid()).isPresent());

		// Retraining on the same history gives the same model (reproducible), as a new version, and both runs are kept.
		double threshold = real.get("model").get("threshold").asDouble();
		anomalyModels.trainReal(p.uuid(), Instant.now(), MlTrainingRun.Trigger.AUTO);
		assertEquals(threshold, anomalyModels.status(p.userId(), p.uuid()).real().model().get("threshold").asDouble(), 1e-9);
		assertEquals(2, mlModels.findByDeviceUuidAndModelType(p.uuid(), MlModel.ANOMALY_IF).orElseThrow().getVersion());
	}

	@Test
	void tooLittleRealHistoryTrainsNoModelAndNothingIsScored() throws Exception {
		Instant now = Instant.now();
		Paired p = pair(newUserToken());
		seedEsp32History(p, now, 1, 300);
		JsonNode real = train(p).get("real");
		assertEquals("INSUFFICIENT_DATA", real.get("state").asString());
		assertTrue(anomalyModels.readyModel(p.uuid()).isEmpty());
		scanner.scan(p.uuid(), now, Duration.ofMinutes(30));
		assertTrue(forestAnomalies(p).isEmpty());
	}

	@Test
	void detectionIsStoredShownReportedAndEmailedOnce() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(10);
		Paired p = pair(newUserToken());
		Instant anomalyStart = now.minus(12, ChronoUnit.MINUTES);
		seedEsp32History(p, anomalyStart, 4, 300);
		JsonNode trained = train(p).get("real");
		assertEquals("READY", trained.get("state").asString(), trained.toString());

		// 11 minutes in which the humidity sensor reads far too dry for the temperature (a failing sensor), at 30 s. Temperature
		// keeps behaving normally (daily curve plus noise), so humidity is the only fault, whatever the time of day.
		Random noise = new Random(5);
		List<TelemetryReading> bad = new ArrayList<>();
		for (Instant t = anomalyStart; t.isBefore(now.minus(1, ChronoUnit.MINUTES)); t = t.plusSeconds(30)) {
			bad.add(reading(p, t, normalTemperature(t, noise), 18.0, false, 300 + bad.size() % 5));
		}
		save(bad);
		// And one message through the real MQTT ingest, as the ESP32 sends it.
		sendTelemetry(p, "{\"seq\":" + MQTT_SEQ.incrementAndGet() + ",\"temperature\":26.0,\"humidity\":18.0,\"gasRaw\":300,\"motion\":false}");

		scanner.scan(p.uuid(), now, Duration.ofMinutes(20));

		// 1. detection -> database, with sensor, value, expected range, timestamp, severity and explanation
		List<MlAnomaly> found = forestAnomalies(p);
		assertEquals(1, found.size(), "one ongoing problem is one row");
		MlAnomaly a = found.get(0);
		assertEquals("humidity", a.getScope());
		assertEquals(18.0, a.getDetectedValue(), 0.01);
		assertNotNull(a.getExpectedLow());
		assertTrue(a.getExpectedLow() > 18.0 && a.getExpectedHigh() > a.getExpectedLow(), "the value is outside the normal range");
		assertNotNull(a.getReadingAt());
		assertTrue(a.getExplanation().contains("Isolation Forest") && a.getExplanation().contains("humidity"), a.getExplanation());
		assertTrue(a.getSeverity().name().equals("WARNING") || a.getSeverity().name().equals("CRITICAL"));

		// 2. dashboard: the anomaly list shows it with its detector and numbers
		JsonNode list = mapper.readTree(mvc.perform(get("/api/devices/" + p.uuid() + "/ml/anomalies").header("Authorization", p.auth()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		JsonNode shown = null;
		for (JsonNode n : list) {
			if ("MULTIVARIATE_OUTLIER".equals(n.get("kind").asString())) {
				shown = n;
			}
		}
		assertNotNull(shown, list.toString());
		assertEquals("ISOLATION_FOREST", shown.get("detector").asString());
		assertEquals(18.0, shown.get("detectedValue").asDouble(), 0.01);
		assertTrue(shown.get("details").has("topFeatures") && shown.get("details").has("score"));

		// 3. report: listed as an anomaly event with the same facts
		JsonNode report = mapper.readTree(mvc.perform(post("/api/reports").header("Authorization", p.auth()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"daily\",\"timeZone\":\"UTC\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		String sections = report.get("content").get("sections").toString();
		assertTrue(sections.contains("Multivariate outlier") && sections.contains("Humidity (%)") && sections.contains("18 %"), sections);

		// 4. email: exactly one, with the same facts
		List<RecordingAlertMailer.Sent> mails = forestMails(p);
		assertEquals(1, mails.size());
		assertTrue(mails.get(0).body().contains("Expected range:") && mails.get(0).body().contains("this time of day"), mails.get(0).body());

		// 5. the problem goes on: the same row is updated, and no second email is sent
		List<TelemetryReading> more = new ArrayList<>();
		for (Instant t = now.minus(1, ChronoUnit.MINUTES).minusSeconds(10); t.isBefore(now.plusSeconds(60)); t = t.plusSeconds(30)) {
			more.add(reading(p, t, normalTemperature(t, noise), 18.0, false, 301 + more.size() % 5));   // contiguous: a gap would end the run
		}
		save(more);
		scanner.scan(p.uuid(), now.plusSeconds(70), Duration.ofMinutes(20));
		List<MlAnomaly> after = forestAnomalies(p);
		assertEquals(1, after.size());
		assertTrue(after.get(0).getOccurrences() > a.getOccurrences());
		assertEquals(1, forestMails(p).size(), "an ongoing anomaly is emailed once");
	}

	@Test
	void theGasAlertFiresEvenWhenTheModelLearnedThatHighGasIsNormal() throws Exception {
		Paired p = pair(newUserToken());
		// A home whose MQ-2 always reads high: the model learns 2800 raw as normal.
		seedEsp32History(p, Instant.now().minus(5, ChronoUnit.MINUTES), 4, 2800);
		assertEquals("READY", train(p).get("real").get("state").asString());
		for (int i = 0; i < 3; i++) {
			sendTelemetry(p, "{\"seq\":" + MQTT_SEQ.incrementAndGet() + ",\"gasRaw\":3000}");
		}
		alertDispatcher.awaitIdle();
		List<GasAlert> incidents = gasAlerts.findAll().stream().filter(g -> g.getDeviceUuid().equals(p.uuid())).toList();
		assertEquals(1, incidents.size(), "the threshold alert does not consult the model");
		assertEquals(GasAlert.State.ACTIVE, incidents.get(0).getState());
	}

	@Test
	void aBrokenModelNeverStopsTheRuleFindings() throws Exception {
		Instant now = Instant.now();
		Paired p = pair(newUserToken());
		seedEsp32History(p, now.minus(3, ChronoUnit.MINUTES), 4, 300);
		assertEquals("READY", train(p).get("real").get("state").asString());
		jdbc.update("update ml_models set artifact_json = '{not json' where device_uuid = ? and model_type = ?", p.uuid(), MlModel.ANOMALY_IF);
		anomalyModels.forget(p.uuid());
		save(List.of(reading(p, now.minusSeconds(60), 26.0, 65.0, false, 4095)));

		scanner.scan(p.uuid(), now, Duration.ofMinutes(10));

		List<MlAnomaly> rows = mlAnomalies.findByDeviceUuidOrderByLastSeenAtDesc(p.uuid(), PageRequest.of(0, 50));
		assertTrue(rows.stream().anyMatch(a -> a.getKind() == AnomalyKind.OUT_OF_RANGE && "gasRaw".equals(a.getScope())),
				"the saturated gas reading is still reported by its rule");
		assertTrue(rows.stream().noneMatch(a -> a.getKind() == AnomalyKind.MULTIVARIATE_OUTLIER));
	}
}
