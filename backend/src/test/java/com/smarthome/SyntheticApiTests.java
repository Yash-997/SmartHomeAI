package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.smarthome.ml.MlModel;
import com.smarthome.ml.SyntheticService;

import tools.jackson.databind.JsonNode;

/**
 * The SYNTHETIC dataset through the real API and database: counts, idempotency, strict separation from real telemetry
 * and from the device's real model, ownership, training and labelling.
 */
class SyntheticApiTests extends MlTestBase {

	@Autowired JdbcTemplate jdbc;
	@Autowired SyntheticService synthetic;

	/** One device with a generated and trained dataset, shared by the read-only tests (generation takes a few seconds). */
	private static Paired shared;
	private static long realReadingsBefore;

	private String url(Paired p, String path) {
		return "/api/devices/" + p.uuid() + "/ml/synthetic" + path;
	}

	private JsonNode call(Paired p, org.springframework.test.web.servlet.RequestBuilder req) throws Exception {
		return mapper.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private long realReadings(Paired p) {
		return jdbc.queryForObject("select count(*) from telemetry_readings where device_uuid = ?", Long.class, p.uuid());
	}

	private long syntheticReadings(Paired p) {
		return jdbc.queryForObject("select count(*) from synthetic_readings r join synthetic_datasets d on d.id = r.dataset_id where d.device_uuid = ?",
				Long.class, p.uuid());
	}

	private synchronized Paired trainedDevice() throws Exception {
		if (shared == null) {
			Paired p = onlineDevice(newUserToken());
			realReadingsBefore = realReadings(p);
			call(p, post(url(p, "/generate")).header("Authorization", p.auth()));
			synthetic.forgetRateLimits();
			call(p, post(url(p, "/train")).header("Authorization", p.auth()));
			shared = p;
		}
		return shared;
	}

	@Test
	void generationCreatesThirtyDaysOfMinuteReadingsInTheSyntheticTablesOnly() throws Exception {
		Paired p = onlineDevice(newUserToken());
		long realBefore = realReadings(p);
		JsonNode s = call(p, post(url(p, "/generate")).header("Authorization", p.auth()));
		assertTrue(s.get("generated").asBoolean());
		assertEquals("SYNTHETIC", s.get("source").asString());
		JsonNode ds = s.get("dataset");
		assertEquals(30, ds.get("days").asInt());
		int offline = ds.get("offlineMinutes").asInt();
		assertEquals(30 * 1440 - offline, ds.get("recordCount").asInt(), "one reading per online minute");
		assertEquals(ds.get("recordCount").asLong(), syntheticReadings(p));
		assertEquals(5, ds.get("scenarios").size());
		assertEquals(realBefore, realReadings(p), "real telemetry is untouched: nothing synthetic is written to it");
		assertTrue(s.get("model").isNull(), "nothing is trained by generating");
	}

	@Test
	void generatingAgainReturnsTheSameDatasetWithoutDuplicates() throws Exception {
		Paired p = trainedDevice();
		long before = syntheticReadings(p);
		JsonNode first = call(p, get(url(p, "")).header("Authorization", p.auth()));
		JsonNode again = call(p, post(url(p, "/generate")).header("Authorization", p.auth()));
		assertFalse(again.get("generated").asBoolean());
		assertEquals(first.get("dataset").get("id").asString(), again.get("dataset").get("id").asString());
		assertEquals(before, syntheticReadings(p), "no duplicate records");
		assertEquals(1L, jdbc.queryForObject("select count(*) from synthetic_datasets where device_uuid = ?", Long.class, p.uuid()));
		assertEquals(0L, jdbc.queryForObject("select count(*) from (select recorded_at from synthetic_readings r join synthetic_datasets d on d.id = r.dataset_id "
				+ "where d.device_uuid = ? group by recorded_at having count(*) > 1) x", Long.class, p.uuid()), "no timestamp twice");
	}

	@Test
	void trainingProducesALabelledSyntheticModelAndNeverTouchesTheRealOne() throws Exception {
		Paired p = trainedDevice();
		JsonNode s = call(p, get(url(p, "")).header("Authorization", p.auth()));
		JsonNode model = s.get("model");
		assertEquals("READY", model.get("state").asString(), model.get("message").asString());
		assertEquals(1, model.get("version").asInt());
		assertTrue(model.get("message").asString().contains("SYNTHETIC"));
		JsonNode m = model.get("metrics");
		assertTrue(m.get("logLoss").asDouble() < m.get("baselineLogLoss").asDouble(), "beats the baseline on the test part");
		assertTrue(m.get("evaluation").asString().startsWith("SYNTHETIC data. Chronological split"));
		JsonNode t = s.get("training");
		assertTrue(t.get("train").get("slots").asInt() > t.get("test").get("slots").asInt());
		assertEquals(60, t.get("embargoMinutes").asInt());
		assertTrue(s.get("notice").asString().contains("not your home"));

		// The real model and the real training history are untouched.
		assertTrue(mlModels.findByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY).isEmpty());
		JsonNode real = call(p, get("/api/devices/" + p.uuid() + "/ml/status").header("Authorization", p.auth())).get("occupancy");
		assertTrue(real.get("collecting").asBoolean(), "real insights still say they are collecting data");
		assertEquals(0, real.get("modelVersion").asInt());
		assertEquals(0, call(p, get("/api/devices/" + p.uuid() + "/ml/occupancy/runs").header("Authorization", p.auth())).size());
		assertEquals("SYNTHETIC", jdbc.queryForObject("select data_source from ml_training_runs where device_uuid = ? and model_type = ?", String.class,
				p.uuid(), MlModel.OCCUPANCY_SYNTHETIC));
		assertEquals(realReadingsBefore, realReadings(p));
		assertEquals(0L, jdbc.queryForObject("select count(*) from ml_anomalies where device_uuid = ?", Long.class, p.uuid()),
				"synthetic scenarios never create anomalies on the real device");
	}

	@Test
	void everyLabelledAnomalyScenarioIsDetectedAndFalsePositivesAreReported() throws Exception {
		Paired p = trainedDevice();
		JsonNode a = call(p, get(url(p, "")).header("Authorization", p.auth())).get("anomalies");
		assertEquals(5, a.get("scenarios").size());
		for (JsonNode sc : a.get("scenarios")) {
			assertTrue(sc.get("detected").asBoolean(), sc.toString());
		}
		assertTrue(a.has("totalFalsePositiveEpisodes"), "false positives are reported, not hidden");
		assertTrue(a.get("normalHours").asDouble() > 24);
	}

	@Test
	void forecastsAreLabelledSynthetic() throws Exception {
		Paired p = trainedDevice();
		JsonNode f = call(p, get(url(p, "/forecast?hours=24")).header("Authorization", p.auth()));
		assertEquals("SYNTHETIC", f.get("source").asString());
		assertEquals(24, f.get("points").size());
		assertEquals("SYNTHETIC", f.get("points").get(0).get("quality").asString());
		// The real prediction endpoint still refuses: there is no real model.
		mvc.perform(get("/api/devices/" + p.uuid() + "/ml/occupancy/predict").header("Authorization", p.auth())).andExpect(status().isConflict());
	}

	@Test
	void trainingOrForecastingBeforeGeneratingIsRefused() throws Exception {
		Paired p = onlineDevice(newUserToken());
		mvc.perform(post(url(p, "/train")).header("Authorization", p.auth())).andExpect(status().isConflict());
		mvc.perform(get(url(p, "/forecast")).header("Authorization", p.auth())).andExpect(status().isConflict());
		JsonNode s = call(p, get(url(p, "")).header("Authorization", p.auth()));
		assertTrue(s.get("dataset").isNull());
	}

	@Test
	void onlyTheOwnerCanSeeOrChangeSyntheticData() throws Exception {
		Paired p = trainedDevice();
		String other = "Bearer " + newUserToken();
		mvc.perform(get(url(p, ""))).andExpect(status().isUnauthorized());
		mvc.perform(post(url(p, "/generate"))).andExpect(status().isUnauthorized());
		mvc.perform(get(url(p, "")).header("Authorization", other)).andExpect(status().isNotFound());
		mvc.perform(post(url(p, "/generate")).header("Authorization", other)).andExpect(status().isNotFound());
		mvc.perform(post(url(p, "/train")).header("Authorization", other)).andExpect(status().isNotFound());
		mvc.perform(get(url(p, "/forecast")).header("Authorization", other)).andExpect(status().isNotFound());
		mvc.perform(post(url(p, "/generate?days=5")).header("Authorization", p.auth())).andExpect(status().isBadRequest());
	}

	@Test
	void disowningRemovesTheSyntheticDataOfThatDeviceOnly() throws Exception {
		Paired keep = trainedDevice();
		long kept = syntheticReadings(keep);
		Paired p = onlineDevice(newUserToken());
		call(p, post(url(p, "/generate")).header("Authorization", p.auth()));
		assertTrue(syntheticReadings(p) > 0);
		mvc.perform(delete("/api/devices/" + p.uuid()).header("Authorization", p.auth())).andExpect(status().isNoContent());
		assertEquals(0L, syntheticReadings(p));
		assertEquals(kept, syntheticReadings(keep), "another device's dataset is untouched");
	}
}
