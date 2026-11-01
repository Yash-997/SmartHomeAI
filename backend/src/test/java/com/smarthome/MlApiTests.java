package com.smarthome;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import com.smarthome.ml.AnomalyKind;
import com.smarthome.ml.AnomalySeverity;
import com.smarthome.ml.Finding;
import com.smarthome.ml.MlModel;
import com.smarthome.ml.OccupancyTrainer.Status;

/**
 * Occupancy ML through the real API, on SIMULATED histories written to the test database. These prove the plumbing
 * (ownership, honesty about missing data, persistence, rate limits, no training from telemetry). They are not evidence
 * about accuracy on a real home.
 */
class MlApiTests extends MlTestBase {

	private static final String MONDAY_8 = "2027-05-03T08:00:00Z";
	private static final String MONDAY_12 = "2027-05-03T12:00:00Z";
	private static final String MONDAY_3 = "2027-05-03T03:00:00Z";

	/** One device with 8 simulated days of history, trained once and shared by the read-only tests. */
	private static Paired trained;

	private synchronized Paired trainedDevice() throws Exception {
		if (trained == null) {
			Paired p = onlineDevice(newUserToken());
			seedMotionHistory(p, Instant.now(), 8, 42);
			mvc.perform(post("/api/devices/" + p.uuid() + "/ml/occupancy/train?timeZone=UTC").header("Authorization", p.auth()))
					.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("READY"));
			trained = p;
		}
		return trained;
	}

	private String url(Paired p, String path) {
		return "/api/devices/" + p.uuid() + "/ml" + path;
	}

	// ---- access control --------------------------------------------------------------------------

	@Test
	void everyEndpointNeedsALoginAndOtherUsersGetNotFound() throws Exception {
		Paired mine = onlineDevice(newUserToken());
		String other = "Bearer " + newUserToken();
		UUID anyAnomaly = UUID.randomUUID();

		mvc.perform(get(url(mine, "/status"))).andExpect(status().isUnauthorized());
		mvc.perform(post(url(mine, "/occupancy/train"))).andExpect(status().isUnauthorized());
		mvc.perform(get(url(mine, "/occupancy/predict"))).andExpect(status().isUnauthorized());
		mvc.perform(get(url(mine, "/occupancy/forecast"))).andExpect(status().isUnauthorized());
		mvc.perform(get(url(mine, "/anomalies"))).andExpect(status().isUnauthorized());
		mvc.perform(post(url(mine, "/anomalies/scan"))).andExpect(status().isUnauthorized());

		mvc.perform(get(url(mine, "/status")).header("Authorization", other)).andExpect(status().isNotFound());
		mvc.perform(post(url(mine, "/occupancy/train")).header("Authorization", other)).andExpect(status().isNotFound());
		mvc.perform(get(url(mine, "/occupancy/predict")).header("Authorization", other)).andExpect(status().isNotFound());
		mvc.perform(get(url(mine, "/occupancy/forecast")).header("Authorization", other)).andExpect(status().isNotFound());
		mvc.perform(get(url(mine, "/anomalies")).header("Authorization", other)).andExpect(status().isNotFound());
		mvc.perform(post(url(mine, "/anomalies/scan")).header("Authorization", other)).andExpect(status().isNotFound());
		mvc.perform(patch(url(mine, "/anomalies/" + anyAnomaly)).header("Authorization", other).contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"RESOLVED\"}")).andExpect(status().isNotFound());
		assertTrue(mlModels.findByDeviceUuidAndModelType(mine.uuid(), MlModel.OCCUPANCY).isEmpty(), "a stranger's request trained nothing");
	}

	@Test
	void aDeviceCredentialIsNotAUserLogin() throws Exception {
		Paired p = onlineDevice(newUserToken());
		mvc.perform(get(url(p, "/status")).header("X-Device-Id", p.deviceId()).header("X-Device-Key", "anything")).andExpect(status().isUnauthorized());
	}

	// ---- honesty before there is data ------------------------------------------------------------

	@Test
	void aFreshDeviceIsNotTrainedAndNothingIsPredicted() throws Exception {
		Paired p = onlineDevice(newUserToken());
		mvc.perform(get(url(p, "/status")).header("Authorization", p.auth()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.occupancy.state").value("NOT_TRAINED"))
				.andExpect(jsonPath("$.occupancy.predictionsAvailable").value(false))
				.andExpect(jsonPath("$.occupancy.metrics").value(nullValue()))
				.andExpect(jsonPath("$.occupancy.requirements.minUsableSlots").value(200))
				.andExpect(jsonPath("$.occupancy.requirements.minDistinctDays").value(3))
				.andExpect(jsonPath("$.occupancy.data.motionReadings").value(1))
				.andExpect(jsonPath("$.anomalies.active").value(0))
				.andExpect(jsonPath("$.notice").value(containsString("advisory")))
				.andExpect(jsonPath("$.notice").value(containsString("gas warning")));
		mvc.perform(get(url(p, "/occupancy/predict")).header("Authorization", p.auth()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(containsString("no occupancy model")));
		mvc.perform(get(url(p, "/occupancy/forecast")).header("Authorization", p.auth())).andExpect(status().isConflict());
	}

	@Test
	void trainingWithTooLittleHistoryExplainsWhyAndInventsNothing() throws Exception {
		Paired p = onlineDevice(newUserToken());
		Instant now = Instant.now();
		save(java.util.stream.IntStream.range(0, 40).mapToObj(i -> reading(p, now.minusSeconds(i * 60L), 22.0, 50.0, i % 7 == 0, 1500)).toList());

		mvc.perform(post(url(p, "/occupancy/train")).header("Authorization", p.auth()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("INSUFFICIENT_DATA"))
				.andExpect(jsonPath("$.predictionsAvailable").value(false))
				.andExpect(jsonPath("$.metrics").value(nullValue()))
				.andExpect(jsonPath("$.message").value(containsString("at least 200")))
				.andExpect(jsonPath("$.counts.motionReadings").value(41));
		var row = mlModels.findByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY).orElseThrow();
		assertEquals(Status.INSUFFICIENT_DATA, row.getStatus());
		assertFalse(row.hasModel());
		assertEquals(null, row.getMetricsJson(), "no accuracy is stored when nothing was evaluated");
		mvc.perform(get(url(p, "/occupancy/predict")).header("Authorization", p.auth()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(containsString("not enough data")));
	}

	// ---- training and prediction -----------------------------------------------------------------

	@Test
	void trainingOnSimulatedHistoryGivesAReadyModelWithHonestMetrics() throws Exception {
		Paired p = trainedDevice();
		mvc.perform(get(url(p, "/status")).header("Authorization", p.auth()))
				.andExpect(jsonPath("$.occupancy.state").value("READY"))
				.andExpect(jsonPath("$.occupancy.predictionsAvailable").value(true))
				.andExpect(jsonPath("$.occupancy.timeZone").value("UTC"))
				.andExpect(jsonPath("$.occupancy.counts.usableSlots").value(8 * 96))
				.andExpect(jsonPath("$.occupancy.metrics.testSlots").value(154))
				.andExpect(jsonPath("$.occupancy.metrics.trainSlots").value(8 * 96 - 154))
				.andExpect(jsonPath("$.occupancy.metrics.evaluation").value(containsString("had not seen")))
				.andExpect(jsonPath("$.occupancy.method").value(containsString("Logistic regression")));
		var m = mapper.readTree(mvc.perform(get(url(p, "/status")).header("Authorization", p.auth())).andReturn().getResponse().getContentAsString())
				.get("occupancy").get("metrics");
		assertTrue(m.get("logLoss").asDouble() < m.get("baselineLogLoss").asDouble(), "better than guessing the average");
		assertTrue(m.get("accuracy").asDouble() > m.get("baselineAccuracy").asDouble());
		assertTrue(m.get("auc").asDouble() > 0.8);
	}

	@Test
	void predictionsFollowTheLearnedRoutineAndReportTheirEvidence() throws Exception {
		Paired p = trainedDevice();
		double morning = probability(p, MONDAY_8);
		double midday = probability(p, MONDAY_12);
		double night = probability(p, MONDAY_3);
		assertTrue(morning > 0.55, "morning " + morning);
		assertTrue(midday < 0.4, "midday " + midday);
		assertTrue(night < 0.25, "night " + night);

		mvc.perform(get(url(p, "/occupancy/predict?at=" + MONDAY_8)).header("Authorization", p.auth()))
				.andExpect(jsonPath("$.dayOfWeek").value("Mon"))
				.andExpect(jsonPath("$.hour").value(8))
				.andExpect(jsonPath("$.predictedOccupied").value(true))
				.andExpect(jsonPath("$.confidence").isNumber())
				.andExpect(jsonPath("$.support").isNumber())
				.andExpect(jsonPath("$.reliable").isBoolean())
				.andExpect(jsonPath("$.quality").value("READY"));
	}

	private double probability(Paired p, String at) throws Exception {
		String body = mvc.perform(get(url(p, "/occupancy/predict?at=" + at)).header("Authorization", p.auth()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		double v = mapper.readTree(body).get("probability").asDouble();
		assertTrue(v >= 0 && v <= 1);
		return v;
	}

	@Test
	void theForecastCoversTheRequestedWindow() throws Exception {
		Paired p = trainedDevice();
		mvc.perform(get(url(p, "/occupancy/forecast?hours=24&stepMinutes=60&from=" + MONDAY_3)).header("Authorization", p.auth()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.timeZone").value("UTC"))
				.andExpect(jsonPath("$.points", hasSize(24)))
				.andExpect(jsonPath("$.points[0].hour").value(3))
				.andExpect(jsonPath("$.points[5].hour").value(8))
				.andExpect(jsonPath("$.points[23].hour").value(2));
		mvc.perform(get(url(p, "/occupancy/forecast?hours=6&stepMinutes=15&from=" + MONDAY_3)).header("Authorization", p.auth()))
				.andExpect(jsonPath("$.points", hasSize(24)));
		mvc.perform(get(url(p, "/occupancy/forecast?hours=0")).header("Authorization", p.auth())).andExpect(status().isBadRequest());
		mvc.perform(get(url(p, "/occupancy/forecast?hours=99")).header("Authorization", p.auth())).andExpect(status().isBadRequest());
		mvc.perform(get(url(p, "/occupancy/forecast?stepMinutes=7")).header("Authorization", p.auth()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.stepMinutes").exists());
		mvc.perform(get(url(p, "/occupancy/predict?at=yesterday")).header("Authorization", p.auth())).andExpect(status().isBadRequest());
	}

	@Test
	void theTimeZoneIsChosenAtTrainingTimeAndValidated() throws Exception {
		Paired p = onlineDevice(newUserToken());
		seedMotionHistory(p, Instant.now(), 6, 5);
		mvc.perform(post(url(p, "/occupancy/train?timeZone=Mars/Base")).header("Authorization", p.auth()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.timeZone").exists());
		mvc.perform(post(url(p, "/occupancy/train?timeZone=Asia/Kolkata")).header("Authorization", p.auth()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.timeZone").value("Asia/Kolkata"));
	}

	@Test
	void trainingCannotBeHammered() throws Exception {
		Paired p = onlineDevice(newUserToken());
		mvc.perform(post(url(p, "/occupancy/train")).header("Authorization", p.auth())).andExpect(status().isOk());
		mvc.perform(post(url(p, "/occupancy/train")).header("Authorization", p.auth())).andExpect(status().isTooManyRequests());
	}

	@Test
	void anInsufficientRetrainNeverThrowsAwayAWorkingModel() throws Exception {
		Paired p = onlineDevice(newUserToken());
		seedMotionHistory(p, Instant.now(), 6, 8);
		mvc.perform(post(url(p, "/occupancy/train")).header("Authorization", p.auth())).andExpect(jsonPath("$.state").value("READY"));
		Instant firstTrained = mlModels.findByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY).orElseThrow().getTrainedAt();

		// the history disappears (for example a database clean-up)
		new TransactionTemplate(transactions).executeWithoutResult(s -> telemetryRepo.deleteAllForDevice(p.uuid()));
		Thread.sleep(1100);
		mvc.perform(post(url(p, "/occupancy/train")).header("Authorization", p.auth()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("READY"))
				.andExpect(jsonPath("$.predictionsAvailable").value(true))
				.andExpect(jsonPath("$.message").value(containsString("did not replace the existing model")));
		assertEquals(firstTrained, mlModels.findByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY).orElseThrow().getTrainedAt());
		mvc.perform(get(url(p, "/occupancy/predict?at=" + MONDAY_8)).header("Authorization", p.auth())).andExpect(status().isOk());
	}

	// ---- when training happens -------------------------------------------------------------------

	@Test
	void incomingTelemetryNeverTriggersTraining() throws Exception {
		Paired fresh = onlineDevice(newUserToken());
		for (int i = 0; i < 40; i++) {
			sendTelemetry(fresh, "{\"seq\":" + (900000 + i) + ",\"temperature\":22.0,\"motion\":" + (i % 3 == 0) + "}");
		}
		dispatcher.awaitIdle();
		assertTrue(mlModels.findByDeviceUuidAndModelType(fresh.uuid(), MlModel.OCCUPANCY).isEmpty(), "no model row appears from telemetry");

		Paired p = trainedDevice();
		Instant before = mlModels.findByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY).orElseThrow().getTrainedAt();
		for (int i = 0; i < 30; i++) {
			sendTelemetry(p, "{\"seq\":" + (910000 + i) + ",\"temperature\":22.0,\"motion\":true}");
		}
		dispatcher.awaitIdle();
		assertEquals(before, mlModels.findByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY).orElseThrow().getTrainedAt(), "a trained model is not retrained by telemetry");
	}

	@Test
	void theBackgroundCheckTrainsOnlyWhereItIsWorthwhile() throws Exception {
		Paired withData = onlineDevice(newUserToken());
		seedMotionHistory(withData, Instant.now(), 6, 31);
		Paired oldData = onlineDevice(newUserToken());
		seedMotionHistory(oldData, Instant.now().minus(6, ChronoUnit.DAYS), 4, 32);   // nothing in the last few days
		telemetryRepo.deleteAll(telemetryRepo.findByDeviceUuidOrderByReceivedAtDesc(oldData.uuid(), org.springframework.data.domain.PageRequest.of(0, 50))
				.stream().filter(r -> r.getReceivedAt().isAfter(Instant.now().minus(2, ChronoUnit.DAYS))).toList());

		occupancy.autoTrainAll(Instant.now());

		var created = mlModels.findByDeviceUuidAndModelType(withData.uuid(), MlModel.OCCUPANCY);
		assertTrue(created.isPresent(), "a device with recent data and no model gets one");
		assertEquals(Status.READY, created.get().getStatus());
		assertTrue(mlModels.findByDeviceUuidAndModelType(oldData.uuid(), MlModel.OCCUPANCY).isEmpty(), "no recent data, nothing to learn from");

		Instant trainedAt = created.get().getTrainedAt();
		occupancy.autoTrainAll(Instant.now().plusSeconds(60));
		assertEquals(trainedAt, mlModels.findByDeviceUuidAndModelType(withData.uuid(), MlModel.OCCUPANCY).orElseThrow().getTrainedAt(),
				"a fresh model is left alone");
	}

	// ---- housekeeping ----------------------------------------------------------------------------

	@Test
	void disowningRemovesModelsAndAnomalies() throws Exception {
		Paired p = onlineDevice(newUserToken());
		seedMotionHistory(p, Instant.now(), 5, 3);
		mvc.perform(post(url(p, "/occupancy/train")).header("Authorization", p.auth())).andExpect(status().isOk());
		anomalyService.raise(p.uuid(), new Finding(AnomalyKind.STALE_DATA, "device", AnomalySeverity.WARNING, "test", java.util.Map.of(), Instant.now()), Instant.now());
		assertEquals(1, mlAnomalies.findByDeviceUuidOrderByLastSeenAtDesc(p.uuid(), org.springframework.data.domain.PageRequest.of(0, 10)).size());

		mvc.perform(delete("/api/devices/" + p.uuid()).header("Authorization", p.auth())).andExpect(status().isNoContent());

		assertTrue(mlModels.findByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY).isEmpty());
		assertTrue(mlAnomalies.findByDeviceUuidOrderByLastSeenAtDesc(p.uuid(), org.springframework.data.domain.PageRequest.of(0, 10)).isEmpty());
	}
}
