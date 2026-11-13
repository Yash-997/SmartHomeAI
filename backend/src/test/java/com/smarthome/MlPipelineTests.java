package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.smarthome.device.Device;
import com.smarthome.device.DeviceStatusRecorder;
import com.smarthome.ml.AnomalyKind;
import com.smarthome.ml.DataCollectionService;
import com.smarthome.ml.MlModel;
import com.smarthome.ml.MlTrainingRunRepository;

import tools.jackson.databind.JsonNode;

/**
 * The historical-data and training pipeline through the real API and database: collection counts, "Collecting
 * historical data", versioned training runs, automatic retraining rules, the learned pattern, connectivity history and
 * unusual-activity detection. Histories are SIMULATED test fixtures written to the test database.
 */
class MlPipelineTests extends MlTestBase {

	@Autowired JdbcTemplate jdbc;
	@Autowired MlTrainingRunRepository runRepo;
	@Autowired DeviceStatusRecorder statusRecorder;
	@Autowired DataCollectionService collection;

	private String url(Paired p, String path) {
		return "/api/devices/" + p.uuid() + "/ml" + path;
	}

	private JsonNode getJson(Paired p, String path) throws Exception {
		return mapper.readTree(mvc.perform(get(url(p, path)).header("Authorization", p.auth())).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
	}

	private JsonNode train(Paired p, String zone) throws Exception {
		occupancy.forgetRateLimits();
		return mapper.readTree(mvc.perform(post(url(p, "/occupancy/train" + (zone == null ? "" : "?timeZone=" + zone))).header("Authorization", p.auth()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	// ---- collection and "Collecting historical data" ---------------------------------------------

	@Test
	void aNewDeviceIsCollectingAndCountsOnlyWhatWasReallyStored() throws Exception {
		Paired p = onlineDevice(newUserToken());
		jdbc.update("delete from telemetry_readings where device_uuid = ?", p.uuid());   // start from an empty history (test only)
		Instant now = Instant.now();
		save(List.of(reading(p, now.minusSeconds(120), 23.5, 51.0, true, 300), reading(p, now.minusSeconds(60), 23.6, null, false, null),
				reading(p, now.minus(3, ChronoUnit.DAYS), 22.0, 50.0, null, 310)));
		collection.forget(p.uuid());

		JsonNode o = getJson(p, "/status").get("occupancy");
		assertEquals("NOT_TRAINED", o.get("state").asString());
		assertTrue(o.get("collecting").asBoolean());
		assertTrue(o.get("message").asString().startsWith("Collecting historical data."), o.get("message").asString());
		assertFalse(o.get("predictionsAvailable").asBoolean());
		assertEquals(0, o.get("modelVersion").asInt());
		JsonNode data = o.get("data");
		assertEquals(3, data.get("totalRecords").asLong(), "every stored message counts, whatever it carried");
		assertEquals(2, data.get("motionReadings").asLong(), "only messages with a motion value feed occupancy learning");
		assertEquals(2, data.get("recordsLast24h").asLong());
		assertNotNull(data.get("collectionStartedAt").asString(null));
		assertEquals(0, data.get("dataset").get("usableSlots").asInt(), "two readings make no usable 15-minute slot");
		assertFalse(data.get("dataset").get("enoughForTraining").asBoolean());

		// Nothing is predicted while collecting.
		mvc.perform(get(url(p, "/occupancy/predict")).header("Authorization", p.auth())).andExpect(status().isConflict());
		mvc.perform(get(url(p, "/occupancy/pattern")).header("Authorization", p.auth())).andExpect(status().isConflict());
	}

	@Test
	void theDatasetSnapshotUsesTheSameRulesAsTraining() throws Exception {
		Paired p = onlineDevice(newUserToken());
		seedMotionHistory(p, Instant.now(), 4, 11);
		collection.forget(p.uuid());
		JsonNode ds = getJson(p, "/status").get("occupancy").get("data").get("dataset");
		assertTrue(ds.get("usableSlots").asInt() >= 300, ds.toString());
		assertTrue(ds.get("enoughForTraining").asBoolean());
		JsonNode trained = train(p, "UTC");
		assertEquals(ds.get("usableSlots").asInt(), trained.get("counts").get("usableSlots").asInt(), "what the dashboard promised is what training used");
	}

	// ---- versions and training history -----------------------------------------------------------

	@Test
	void everyRunIsRecordedAndEachNewModelGetsTheNextVersion() throws Exception {
		Paired p = onlineDevice(newUserToken());
		JsonNode empty = train(p, "UTC");
		assertEquals("INSUFFICIENT_DATA", empty.get("state").asString());
		assertEquals(0, empty.get("modelVersion").asInt());

		seedMotionHistory(p, Instant.now(), 8, 21);
		JsonNode v1 = train(p, "UTC");
		assertEquals("READY", v1.get("state").asString(), v1.get("message").asString());
		assertEquals(1, v1.get("modelVersion").asInt());
		assertFalse(v1.get("collecting").asBoolean());
		assertNotNull(v1.get("lastSuccessfulTrainingAt").asString(null));
		JsonNode v2 = train(p, "UTC");
		assertEquals(2, v2.get("modelVersion").asInt());
		assertEquals(3, v2.get("trainingRuns").asLong());

		JsonNode runs = getJson(p, "/occupancy/runs");
		assertEquals(3, runs.size());
		assertEquals(2, runs.get(0).get("version").asInt(), "newest first");
		assertEquals("MANUAL", runs.get(0).get("trigger").asString());
		assertTrue(runs.get(0).get("becameCurrent").asBoolean());
		assertNotNull(runs.get(0).get("metrics").get("auc"), "evaluation stored with the run");
		assertTrue(runs.get(0).get("featureSet").asString().startsWith("v2"));
		assertTrue(runs.get(2).get("version").isNull(), "the insufficient run produced no model");
		assertEquals("INSUFFICIENT_DATA", runs.get(2).get("status").asString());

		String other = "Bearer " + newUserToken();
		mvc.perform(get(url(p, "/occupancy/runs")).header("Authorization", other)).andExpect(status().isNotFound());
	}

	@Test
	void anInsufficientRunIsRecordedButNeverReplacesTheWorkingModel() throws Exception {
		Paired p = onlineDevice(newUserToken());
		seedMotionHistory(p, Instant.now(), 8, 31);
		assertEquals(1, train(p, "UTC").get("modelVersion").asInt());
		jdbc.update("delete from telemetry_readings where device_uuid = ?", p.uuid());   // the history is gone (test only)
		JsonNode after = train(p, "UTC");
		assertEquals(1, after.get("modelVersion").asInt(), "the working model stays");
		assertTrue(after.get("predictionsAvailable").asBoolean());
		JsonNode last = getJson(p, "/occupancy/runs").get(0);
		assertEquals("INSUFFICIENT_DATA", last.get("status").asString());
		assertFalse(last.get("becameCurrent").asBoolean());
	}

	// ---- automatic training ----------------------------------------------------------------------

	@Test
	void automaticTrainingStartsWhenRealDataExistsAndDoesNotRetrainWithoutNewData() throws Exception {
		Paired p = onlineDevice(newUserToken());
		Instant now = Instant.now();
		seedMotionHistory(p, now, 8, 41);

		occupancy.autoTrainAll(now);
		MlModel m = mlModels.findByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY).orElseThrow();
		assertEquals(1, m.getVersion());
		assertEquals("Asia/Kolkata", m.getTimeZone(), "automatic training uses the configured local time zone");
		assertEquals("AUTO", getJson(p, "/occupancy/runs").get(0).get("trigger").asString());

		long runsBefore = runRepo.countByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY);
		occupancy.autoTrainAll(now.plus(1, ChronoUnit.HOURS));
		occupancy.autoTrainAll(now.plus(30, ChronoUnit.HOURS));   // interval passed, but no new readings
		assertEquals(runsBefore, runRepo.countByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY), "no pointless retraining");
	}

	@Test
	void automaticTrainingSkipsDevicesWithoutRecentData() throws Exception {
		Paired p = onlineDevice(newUserToken());
		jdbc.update("delete from telemetry_readings where device_uuid = ?", p.uuid());
		seedMotionHistory(p, Instant.now().minus(10, ChronoUnit.DAYS), 8, 51);   // old history only
		occupancy.autoTrainAll(Instant.now());
		assertTrue(mlModels.findByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY).isEmpty());
		assertEquals(0, runRepo.countByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY));
	}

	// ---- learned pattern -------------------------------------------------------------------------

	@Test
	void theLearnedWeeklyPatternHas168HoursWithEvidenceAndPredictions() throws Exception {
		Paired p = onlineDevice(newUserToken());
		seedMotionHistory(p, Instant.now(), 15, 61);
		train(p, "UTC");
		JsonNode pattern = getJson(p, "/occupancy/pattern");
		assertEquals("UTC", pattern.get("timeZone").asString());
		assertEquals(1, pattern.get("modelVersion").asInt());
		JsonNode cells = pattern.get("cells");
		assertEquals(168, cells.size());
		JsonNode mondayNight = cells.get(3);                     // Monday 03:00
		JsonNode mondayMorning = cells.get(8);                   // Monday 08:00
		assertEquals(1, mondayNight.get("dayOfWeek").asInt());
		assertEquals(3, mondayNight.get("hour").asInt());
		assertTrue(mondayNight.get("slots").asInt() > 0);
		assertTrue(mondayNight.get("modelProbability").asDouble() < mondayMorning.get("modelProbability").asDouble(),
				"quiet night, busy morning (the simulated routine)");
	}

	// ---- connectivity history --------------------------------------------------------------------

	@Test
	void onlineAndOfflineChangesAreRecordedOncePerChange() throws Exception {
		Paired p = onlineDevice(newUserToken());
		Device d = devices.findById(p.uuid()).orElseThrow();
		Instant now = Instant.now();
		assertTrue(statusRecorder.record(d, now).isPresent());
		assertTrue(statusRecorder.record(d, now.plusSeconds(60)).isEmpty(), "no change, no row");

		jdbc.update("update devices set last_seen_at = ? where id = ?", Timestamp.from(now.minus(Duration.ofMinutes(10))), p.uuid());
		Device silent = devices.findById(p.uuid()).orElseThrow();
		assertEquals("OFFLINE", statusRecorder.record(silent, now).orElseThrow().getState());

		JsonNode c = getJson(p, "/connectivity");
		assertEquals("OFFLINE", c.get("currentState").asString());
		assertEquals(1, c.get("offlineEventsLast7Days").asLong());
		assertEquals(2, c.get("events").size());
		assertEquals("OFFLINE", c.get("events").get(0).get("state").asString(), "newest first");
		assertTrue(c.get("events").get(0).get("reason").asString().contains("silent"));
		mvc.perform(get(url(p, "/connectivity")).header("Authorization", "Bearer " + newUserToken())).andExpect(status().isNotFound());
	}

	@Test
	void aDeviceThatNeverConnectedHasNoHistory() throws Exception {
		Paired p = onlineDevice(newUserToken());
		jdbc.update("update devices set last_seen_at = null where id = ?", p.uuid());
		assertTrue(statusRecorder.record(devices.findById(p.uuid()).orElseThrow(), Instant.now()).isEmpty());
		assertEquals(0, getJson(p, "/connectivity").get("events").size());
	}

	// ---- unusual activity ------------------------------------------------------------------------

	@Test
	void motionInANormallyQuietHourIsFlaggedButMotionInABusyHourIsNot() throws Exception {
		Paired p = onlineDevice(newUserToken());
		Instant historyEnd = Instant.parse("2027-05-31T00:00:00Z");   // a Monday, midnight UTC
		seedMotionHistory(p, historyEnd, 22, 71);
		occupancy.forgetRateLimits();
		mvc.perform(post(url(p, "/occupancy/train?timeZone=UTC")).header("Authorization", p.auth())).andExpect(status().isOk());
		assertEquals("READY", mlModels.findByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY).orElseThrow().getStatus().name());

		// Busy hour (Monday 08:10 UTC): motion is normal there.
		Instant busy = Instant.parse("2027-05-31T08:10:00Z");
		save(List.of(reading(p, busy.minusSeconds(30), null, null, true, null)));
		scanner.forget(p.uuid());
		scanner.scan(p.uuid(), busy, Duration.ofMinutes(10));
		assertTrue(mlAnomalies.findAll().stream().noneMatch(a -> a.getDeviceUuid().equals(p.uuid()) && a.getKind() == AnomalyKind.UNUSUAL_ACTIVITY));

		// Quiet hour (Monday 03:10 UTC, a week later): flagged, with the evidence.
		Instant quiet = Instant.parse("2027-06-07T03:10:00Z");
		save(List.of(reading(p, quiet.minusSeconds(30), null, null, true, null)));
		scanner.forget(p.uuid());
		scanner.scan(p.uuid(), quiet, Duration.ofMinutes(10));
		var found = mlAnomalies.findAll().stream().filter(a -> a.getDeviceUuid().equals(p.uuid()) && a.getKind() == AnomalyKind.UNUSUAL_ACTIVITY).toList();
		assertEquals(1, found.size());
		assertTrue(found.get(0).getExplanation().contains("normally quiet"), found.get(0).getExplanation());
		assertEquals("motion", found.get(0).getScope());
	}

	@Test
	void withoutATrainedModelMotionIsNeverJudged() throws Exception {
		Paired p = onlineDevice(newUserToken());
		Instant now = Instant.now();
		save(List.of(reading(p, now.minusSeconds(30), null, null, true, null)));
		scanner.forget(p.uuid());
		scanner.scan(p.uuid(), now, Duration.ofMinutes(10));
		assertNull(mlAnomalies.findAll().stream().filter(a -> a.getDeviceUuid().equals(p.uuid()) && a.getKind() == AnomalyKind.UNUSUAL_ACTIVITY)
				.findFirst().orElse(null));
	}

	// ---- disown ----------------------------------------------------------------------------------

	@Test
	void disowningRemovesTrainingAndConnectivityHistory() throws Exception {
		Paired p = onlineDevice(newUserToken());
		seedMotionHistory(p, Instant.now(), 8, 81);
		train(p, "UTC");
		statusRecorder.record(devices.findById(p.uuid()).orElseThrow(), Instant.now());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/devices/" + p.uuid()).header("Authorization", p.auth()))
				.andExpect(status().isNoContent());
		assertEquals(0, runRepo.countByDeviceUuidAndModelType(p.uuid(), MlModel.OCCUPANCY));
		assertEquals(0L, jdbc.queryForObject("select count(*) from device_status_events where device_uuid = ?", Long.class, p.uuid()));
	}
}
