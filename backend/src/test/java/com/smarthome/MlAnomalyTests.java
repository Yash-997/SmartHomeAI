package com.smarthome;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import com.smarthome.ml.AnomalyKind;
import com.smarthome.ml.AnomalySeverity;
import com.smarthome.ml.AnomalyStatus;
import com.smarthome.ml.Finding;
import com.smarthome.ml.MlAnomaly;
import com.smarthome.telemetry.TelemetryReading;

import tools.jackson.databind.JsonNode;

/**
 * Anomaly detection through the real scanner and API, on SIMULATED sensor histories written to the test database.
 */
class MlAnomalyTests extends MlTestBase {

	private static final Duration LOOKBACK = Duration.ofHours(3);

	private String anomaliesUrl(Paired p) {
		return "/api/devices/" + p.uuid() + "/ml/anomalies";
	}

	private List<MlAnomaly> all(Paired p) {
		return mlAnomalies.findByDeviceUuidOrderByLastSeenAtDesc(p.uuid(), PageRequest.of(0, 100));
	}

	private List<MlAnomaly> ofKind(Paired p, AnomalyKind kind) {
		return all(p).stream().filter(a -> a.getKind() == kind).toList();
	}

	/** A device with a normal, quiet history (ends 40 minutes ago) ready to be scanned. */
	private Paired deviceWithBaseline(Instant now) throws Exception {
		Paired p = pair(newUserToken());
		seedNormalHistory(p, now, 400, 40);
		return p;
	}

	private void add(Paired p, Instant at, Double temp, Double hum, Integer gas) {
		save(List.of(reading(p, at, temp, hum, false, gas)));
	}

	// ---- statistical outliers --------------------------------------------------------------------

	@Test
	void anUnusualReadingIsFlaggedWithAnExplanationAndTheNumbersBehindIt() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);
		add(p, now.minusSeconds(60), 45.0, 50.0, 1500);   // 23 C above normal

		scanner.scan(p.uuid(), now, LOOKBACK);

		var found = ofKind(p, AnomalyKind.STATISTICAL_OUTLIER);
		assertEquals(1, found.size());
		MlAnomaly a = found.get(0);
		assertEquals("temperature", a.getScope());
		assertEquals(AnomalySeverity.CRITICAL, a.getSeverity());
		assertEquals(AnomalyStatus.OPEN, a.getStatus());
		assertTrue(a.getExplanation().contains("45") && a.getExplanation().contains("robust standard deviations")
				&& a.getExplanation().contains("above"), a.getExplanation());
		JsonNode d = mapper.readTree(a.getDetails());
		assertEquals(45.0, d.get("value").asDouble());
		assertEquals(22.0, d.get("median").asDouble(), 0.2);
		assertTrue(d.get("score").asDouble() > 6);
		// the baseline here is the hour-of-day one, built from the readings around this hour (at least 30 are required)
		assertTrue(d.get("baselineSamples").asInt() >= 30);
		assertTrue(d.get("timeOfDayBaseline").asBoolean());

		mvc.perform(get(anomaliesUrl(p)).header("Authorization", p.auth()))
				.andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].kind").value("STATISTICAL_OUTLIER"))
				.andExpect(jsonPath("$[0].severity").value("CRITICAL"))
				.andExpect(jsonPath("$[0].details.method").value(containsString("robust")));
	}

	@Test
	void theSameOngoingProblemUpdatesOneRowInsteadOfPilingUp() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);
		add(p, now.minusSeconds(120), 45.0, 50.0, 1500);
		scanner.scan(p.uuid(), now.minusSeconds(100), LOOKBACK);
		add(p, now.minusSeconds(60), 47.0, 50.0, 1500);
		scanner.scan(p.uuid(), now, LOOKBACK);

		var found = ofKind(p, AnomalyKind.STATISTICAL_OUTLIER);
		assertEquals(1, found.size());
		assertEquals(2, found.get(0).getOccurrences());
		assertTrue(found.get(0).getExplanation().contains("47"), "latest numbers are shown");
		assertEquals(1, all(p).stream().filter(a -> a.getStatus().isActive()).count());
	}

	@Test
	void withoutEnoughHistoryNoStatisticalJudgementIsMade() throws Exception {
		Instant now = Instant.now();
		Paired p = pair(newUserToken());
		seedNormalHistory(p, now, 50, 5);               // far below the 200 needed for a baseline
		add(p, now.minusSeconds(30), 60.0, 50.0, 1500);   // wild, but we do not know what normal is

		scanner.scan(p.uuid(), now, LOOKBACK);

		assertTrue(ofKind(p, AnomalyKind.STATISTICAL_OUTLIER).isEmpty(), "silence is better than a guess");
	}

	@Test
	void ruleBasedChecksStillWorkWithoutABaseline() throws Exception {
		Instant now = Instant.now();
		Paired p = pair(newUserToken());
		add(p, now.minusSeconds(30), 95.0, 50.0, 1500);   // a DHT22 cannot measure this
		scanner.scan(p.uuid(), now, LOOKBACK);
		var range = ofKind(p, AnomalyKind.OUT_OF_RANGE);
		assertEquals(1, range.size());
		assertEquals(AnomalySeverity.CRITICAL, range.get(0).getSeverity());
		assertTrue(range.get(0).getExplanation().contains("sensor or wiring fault"));
	}

	@Test
	void anAdcAtItsMaximumIsAdvisoryOnly() throws Exception {
		Instant now = Instant.now();
		Paired p = pair(newUserToken());
		add(p, now.minusSeconds(30), 22.0, 50.0, 4095);
		scanner.scan(p.uuid(), now, LOOKBACK);
		var range = ofKind(p, AnomalyKind.OUT_OF_RANGE);
		assertEquals(1, range.size());
		assertEquals("gasRaw", range.get(0).getScope());
		assertEquals(AnomalySeverity.WARNING, range.get(0).getSeverity());
		assertTrue(range.get(0).getExplanation().contains("independently"));
	}

	// ---- missing, stuck, stale -------------------------------------------------------------------

	@Test
	void missingValuesAreNeverMistakenForZeroButAreReportedAsMissing() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);
		List<TelemetryReading> gap = new ArrayList<>();
		for (int i = 12; i >= 1; i--) {
			gap.add(reading(p, now.minusSeconds(i * 30L), null, 50.0, false, 1500));   // the DHT22 stopped reporting temperature
		}
		save(gap);

		scanner.scan(p.uuid(), now, LOOKBACK);

		assertTrue(ofKind(p, AnomalyKind.STATISTICAL_OUTLIER).isEmpty(), "a missing temperature must not look like 0 C");
		var missing = ofKind(p, AnomalyKind.MISSING_DATA);
		assertEquals(1, missing.size());
		assertEquals("temperature", missing.get(0).getScope());
		assertEquals(AnomalySeverity.WARNING, missing.get(0).getSeverity());
		assertTrue(ofKind(p, AnomalyKind.MISSING_DATA).stream().noneMatch(a -> a.getScope().equals("humidity")));

		// the sensor comes back: the condition ends and the anomaly closes by itself
		add(p, now.minusSeconds(5), 22.1, 50.0, 1500);
		scanner.scan(p.uuid(), now, LOOKBACK);
		assertEquals(AnomalyStatus.AUTO_RESOLVED, ofKind(p, AnomalyKind.MISSING_DATA).get(0).getStatus());
	}

	@Test
	void aFrozenSensorIsNoticedAndClosesWhenItMovesAgain() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);
		List<TelemetryReading> frozen = new ArrayList<>();
		for (int i = 60; i >= 1; i--) {
			frozen.add(reading(p, now.minusSeconds(i * 30L), 22.0, 50.0, false, 1500 + i));   // gas moves, temp/humidity do not
		}
		save(frozen);
		scanner.scan(p.uuid(), now, LOOKBACK);

		var stuck = ofKind(p, AnomalyKind.STUCK_VALUE);
		assertEquals(2, stuck.size(), "temperature and humidity");
		assertTrue(stuck.stream().allMatch(a -> a.getSeverity() == AnomalySeverity.INFO && a.getStatus() == AnomalyStatus.OPEN));
		assertTrue(stuck.stream().noneMatch(a -> a.getScope().equals("gasRaw")), "gas was changing");

		add(p, now.minusSeconds(2), 22.4, 50.7, 1500);
		scanner.scan(p.uuid(), now, LOOKBACK);
		assertTrue(ofKind(p, AnomalyKind.STUCK_VALUE).stream().allMatch(a -> a.getStatus() == AnomalyStatus.AUTO_RESOLVED));
	}

	@Test
	void aSilentDeviceIsStaleUntilItReportsAgain() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);   // its newest reading is 40 minutes old

		scanner.scan(p.uuid(), now, LOOKBACK);
		var stale = ofKind(p, AnomalyKind.STALE_DATA);
		assertEquals(1, stale.size());
		assertEquals("device", stale.get(0).getScope());
		// the reading is 40 minutes old; microsecond rounding in the database can make that 39 min 59.99 s, shown as 39
		assertTrue(stale.get(0).getExplanation().matches(".*(39|40|41) minutes.*"), stale.get(0).getExplanation());

		scanner.scan(p.uuid(), now.plusSeconds(60), LOOKBACK);
		assertEquals(1, ofKind(p, AnomalyKind.STALE_DATA).size(), "still one row");
		assertTrue(ofKind(p, AnomalyKind.STALE_DATA).get(0).getOccurrences() >= 2);

		add(p, now.plusSeconds(90), 22.0, 50.0, 1500);
		scanner.scan(p.uuid(), now.plusSeconds(100), LOOKBACK);
		assertEquals(AnomalyStatus.AUTO_RESOLVED, ofKind(p, AnomalyKind.STALE_DATA).get(0).getStatus());
	}

	@Test
	void aDeviceThatWentQuietLongAgoIsGivenUpOn() throws Exception {
		Instant now = Instant.now();
		Paired p = pair(newUserToken());
		seedNormalHistory(p, now.minus(10, ChronoUnit.DAYS), 30, 0);   // last reading 10 days ago
		scanner.scan(p.uuid(), now, LOOKBACK);
		assertTrue(all(p).isEmpty(), "no endless alert for a device that is simply gone");
	}

	// ---- readings the backend refused ------------------------------------------------------------

	@Test
	void anImpossibleValueRejectedAtIngestIsRecordedAndEscalatesIfItRepeats() throws Exception {
		Paired p = onlineDevice(newUserToken());
		long storedBefore = telemetryRepo.countByDeviceUuid(p.uuid());

		sendTelemetry(p, "{\"seq\":880001,\"temperature\":900,\"motion\":false}");
		mlDispatcher.awaitIdle();   // the anomaly is written by the ML worker, not on the ingest thread

		var found = ofKind(p, AnomalyKind.OUT_OF_RANGE);
		assertEquals(1, found.size());
		assertEquals("temperature", found.get(0).getScope());
		assertEquals(AnomalySeverity.WARNING, found.get(0).getSeverity());
		assertTrue(found.get(0).getExplanation().contains("900") && found.get(0).getExplanation().contains("rejected"));
		assertEquals(storedBefore, telemetryRepo.countByDeviceUuid(p.uuid()), "the bad message itself is still not stored");

		for (int i = 2; i <= 5; i++) {
			sendTelemetry(p, "{\"seq\":88000" + i + ",\"temperature\":900,\"motion\":false}");
			mlDispatcher.awaitIdle();
		}
		var again = ofKind(p, AnomalyKind.OUT_OF_RANGE);
		assertEquals(1, again.size());
		assertEquals(5, again.get(0).getOccurrences());
		assertEquals(AnomalySeverity.CRITICAL, again.get(0).getSeverity(), "a problem that keeps coming back is escalated");
	}

	@Test
	void aBadReadingFromAnUnregisteredDeviceCreatesNothing() throws Exception {
		long before = mlAnomalies.count();
		telemetryIngest.handle("home/devices/esp32-never-registered/telemetry", "{\"seq\":1,\"temperature\":900}".getBytes(), false);
		mlDispatcher.awaitIdle();
		assertEquals(before, mlAnomalies.count());
	}

	// ---- user controls ---------------------------------------------------------------------------

	private UUID spike(Paired p, Instant now) throws Exception {
		add(p, now.minusSeconds(60), 45.0, 50.0, 1500);
		scanner.scan(p.uuid(), now, LOOKBACK);
		return ofKind(p, AnomalyKind.STATISTICAL_OUTLIER).get(0).getId();
	}

	private org.springframework.test.web.servlet.ResultActions resolve(Paired p, UUID id, String body) throws Exception {
		return mvc.perform(patch(anomaliesUrl(p) + "/" + id).header("Authorization", p.auth()).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void anOwnerCanAcknowledgeThenResolveAndClosedAnomaliesAreFinal() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);
		UUID id = spike(p, now);

		resolve(p, id, "{\"status\":\"ACKNOWLEDGED\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
		mvc.perform(get(anomaliesUrl(p)).header("Authorization", p.auth())).andExpect(jsonPath("$", hasSize(1)));   // still active
		resolve(p, id, "{\"status\":\"RESOLVED\",\"note\":\"  heater was on\\u0007  \"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RESOLVED")).andExpect(jsonPath("$.resolvedAt").isNotEmpty())
				.andExpect(jsonPath("$.resolutionNote").value("heater was on"));

		resolve(p, id, "{\"status\":\"DISMISSED\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(containsString("cannot be changed")));
		mvc.perform(get(anomaliesUrl(p)).header("Authorization", p.auth())).andExpect(jsonPath("$", hasSize(0)));          // no longer active
		mvc.perform(get(anomaliesUrl(p) + "?status=all").header("Authorization", p.auth())).andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void invalidResolutionRequestsAreRefused() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);
		UUID id = spike(p, now);
		resolve(p, id, "{\"status\":\"OPEN\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.status").exists());
		resolve(p, id, "{\"status\":\"AUTO_RESOLVED\"}").andExpect(status().isBadRequest());
		resolve(p, id, "{\"status\":\"banana\"}").andExpect(status().isBadRequest());
		resolve(p, id, "{}").andExpect(status().isBadRequest());
		resolve(p, id, "{\"status\":\"RESOLVED\",\"note\":\"" + "x".repeat(201) + "\"}").andExpect(status().isBadRequest());
		resolve(p, UUID.randomUUID(), "{\"status\":\"RESOLVED\"}").andExpect(status().isNotFound());
		assertEquals(AnomalyStatus.OPEN, mlAnomalies.findById(id).orElseThrow().getStatus(), "nothing above changed it");
	}

	@Test
	void anotherUserCannotSeeOrChangeMyAnomalies() throws Exception {
		Instant now = Instant.now();
		Paired mine = deviceWithBaseline(now);
		UUID id = spike(mine, now);
		Paired theirs = onlineDevice(newUserToken());

		// their own device, my anomaly id: not found (the id is looked up per device)
		mvc.perform(patch("/api/devices/" + theirs.uuid() + "/ml/anomalies/" + id).header("Authorization", theirs.auth())
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISMISSED\"}")).andExpect(status().isNotFound());
		// my device with their token
		mvc.perform(get(anomaliesUrl(mine)).header("Authorization", theirs.auth())).andExpect(status().isNotFound());
		resolve(new Paired(theirs.token(), mine.uuid(), mine.deviceId(), theirs.userId()), id, "{\"status\":\"DISMISSED\"}").andExpect(status().isNotFound());
		assertEquals(AnomalyStatus.OPEN, mlAnomalies.findById(id).orElseThrow().getStatus());
	}

	@Test
	void aDismissedFalsePositiveIsNotRaisedAgainStraightAway() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);
		UUID id = spike(p, now);
		resolve(p, id, "{\"status\":\"DISMISSED\",\"note\":\"known cause\"}").andExpect(status().isOk());

		add(p, now.minusSeconds(20), 46.0, 50.0, 1500);
		scanner.scan(p.uuid(), now.plusSeconds(5), LOOKBACK);

		assertEquals(1, all(p).stream().filter(a -> a.getKind() == AnomalyKind.STATISTICAL_OUTLIER).count(), "no second row");
		assertEquals(0, all(p).stream().filter(a -> a.getKind() == AnomalyKind.STATISTICAL_OUTLIER && a.getStatus().isActive()).count());
	}

	@Test
	void outliersThatStopAreClosedAutomatically() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);
		UUID id = spike(p, now);
		MlAnomaly a = mlAnomalies.findById(id).orElseThrow();
		ReflectionTestUtils.setField(a, "lastSeenAt", now.minus(30, ChronoUnit.MINUTES));
		mlAnomalies.saveAndFlush(a);

		add(p, now.minusSeconds(5), 22.0, 50.0, 1500);   // back to normal
		scanner.scan(p.uuid(), now, LOOKBACK);

		MlAnomaly closed = mlAnomalies.findById(id).orElseThrow();
		assertEquals(AnomalyStatus.AUTO_RESOLVED, closed.getStatus());
		assertNotNull(closed.getResolvedAt());
	}

	// ---- scan endpoint, concurrency, independence -------------------------------------------------

	@Test
	void theCheckNowEndpointRunsAScanAndIsRateLimited() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);
		add(p, now.minusSeconds(30), 45.0, 50.0, 1500);

		mvc.perform(post(anomaliesUrl(p) + "/scan").header("Authorization", p.auth()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.findings").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
				.andExpect(jsonPath("$.activeAnomalies").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
		mvc.perform(post(anomaliesUrl(p) + "/scan").header("Authorization", p.auth())).andExpect(status().isTooManyRequests());
		mvc.perform(get("/api/devices/" + p.uuid() + "/ml/status").header("Authorization", p.auth()))
				.andExpect(jsonPath("$.anomalies.active").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
				.andExpect(jsonPath("$.anomalies.lastScanAt").isNotEmpty());
	}

	@Test
	void simultaneousSightingsOfTheSameProblemStayOneRow() throws Exception {
		Paired p = pair(newUserToken());
		Instant now = Instant.now();
		Finding f = new Finding(AnomalyKind.STUCK_VALUE, "temperature", AnomalySeverity.INFO, "stuck", Map.of(), now);
		ExecutorService pool = Executors.newFixedThreadPool(6);
		List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			futures.add(pool.submit(() -> anomalyService.raise(p.uuid(), f, Instant.now())));
		}
		for (var fu : futures) {
			fu.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();
		var rows = all(p);
		assertEquals(1, rows.size());
		assertEquals(6, rows.get(0).getOccurrences());
	}

	@Test
	void machineLearningNeverSendsACommandOrTouchesTheDevice() throws Exception {
		Instant now = Instant.now();
		Paired p = deviceWithBaseline(now);
		seedMotionHistory(p, now, 5, 1);
		add(p, now.minusSeconds(30), 45.0, 50.0, 4095);   // temperature spike and a saturated gas reading
		scanner.scan(p.uuid(), now, LOOKBACK);
		mvc.perform(post("/api/devices/" + p.uuid() + "/ml/occupancy/train").header("Authorization", p.auth())).andExpect(status().isOk());

		assertFalse(all(p).isEmpty());
		assertEquals(0, published(p), "nothing was published to the device");
		assertTrue(commands.findByDeviceUuidOrderByCreatedAtDesc(p.uuid(), PageRequest.of(0, 10)).isEmpty(), "no command rows");
		assertTrue(rules.findByOwnerUserIdOrderByCreatedAtAsc(p.userId()).isEmpty(), "no automation rules were created or changed");
		assertNull(states.findById(p.uuid()).orElse(null), "no device state was touched");
	}
}
