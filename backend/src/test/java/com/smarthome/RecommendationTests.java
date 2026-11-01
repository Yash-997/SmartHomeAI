package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.smarthome.ml.AnomalyKind;

import tools.jackson.databind.JsonNode;

/**
 * Recommendations come from real stored data (SIMULATED histories written to the test database; no hardware). The tests check
 * what is suggested and why, that nothing changes before approval, and that gas is never involved.
 */
class RecommendationTests extends IntelTestBase {

	private JsonNode list(String token) throws Exception {
		return json(getAs(token, "/api/recommendations").andExpect(status().isOk()));
	}

	private static List<JsonNode> withTitle(JsonNode result, String startsWith) {
		List<JsonNode> out = new ArrayList<>();
		for (JsonNode r : result.get("recommendations")) {
			if (r.get("title").asString().startsWith(startsWith)) {
				out.add(r);
			}
		}
		return out;
	}

	private int ruleCount(String token) throws Exception {
		return json(getAs(token, "/api/automations").andExpect(status().isOk())).size();
	}

	/** A device whose occupancy model is trained on 21 days of SIMULATED history (quiet from 23:00 to 07:00). */
	private Paired trainedDevice(String token, String name) throws Exception {
		Paired p = onlineNamed(token, name);
		seedMotionHistory(p, Instant.now(), 21, 11);
		occupancy.trainManually(p.userId(), p.uuid(), "UTC");
		return p;
	}

	/** A device with a normal temperature history (about 22 C) and three unusual 45 C readings, found by the real scanner. */
	private Paired deviceWithTemperatureOutliers(String token, String name) throws Exception {
		Instant now = Instant.now();
		Paired p = pairNamed(token, name);
		seedNormalHistory(p, now, 400, 40);
		for (int i = 3; i >= 1; i--) {
			save(List.of(reading(p, now.minusSeconds(i * 100L), 45.0, 50.0, false, 1500)));
			scanner.scan(p.uuid(), now.minusSeconds(i * 100L - 10), Duration.ofHours(3));
		}
		return p;
	}

	// ---- access --------------------------------------------------------------------------------

	@Test
	void everyEndpointNeedsALogin() throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/recommendations")).andExpect(status().isUnauthorized());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/recommendations/refresh")).andExpect(status().isUnauthorized());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/recommendations/" + UUID.randomUUID() + "/approve")).andExpect(status().isUnauthorized());
	}

	// ---- honesty about missing data ------------------------------------------------------------

	@Test
	void withoutDataNothingIsSuggestedAndTheReasonIsGiven() throws Exception {
		String t = newUserToken();
		assertTrue(list(t).get("notes").get(0).asString().contains("Pair a device first"));
		onlineNamed(t, "No history");

		JsonNode r = list(t);
		assertEquals(0, r.get("recommendations").size(), "nothing is invented");
		assertTrue(r.get("notes").toString().contains("occupancy model is not trained"), r.get("notes").toString());
		assertEquals(0, ruleCount(t));
	}

	// ---- quiet hours from the occupancy model --------------------------------------------------

	@Test
	void aTrainedModelWithAQuietNightSuggestsQuietHoursRulesWithEvidenceAndChangesNothing() throws Exception {
		String t = newUserToken();
		Paired p = trainedDevice(t, "Bedroom");

		JsonNode r = list(t);

		List<JsonNode> start = withTitle(r, "Turn the PIR LED off during quiet hours");
		List<JsonNode> end = withTitle(r, "Return the PIR LED to auto");
		assertEquals(1, start.size(), r.toString());
		assertEquals(1, end.size());
		JsonNode s = start.get(0);
		assertEquals("CREATE_RULE", s.get("actionType").asString());
		assertEquals("PENDING", s.get("status").asString());
		assertEquals(p.uuid().toString(), s.get("deviceId").asString());
		assertEquals("SCHEDULE", s.get("rule").get("trigger").get("type").asString());
		assertEquals("set_pir_led", s.get("rule").get("action").get("action").asString());
		assertEquals("off", s.get("rule").get("action").get("value").asString());
		JsonNode ev = s.get("evidence");
		assertTrue(ev.get("quietHours").asInt() >= 4 && ev.get("quietHours").asInt() <= 12, ev.toString());
		assertTrue(ev.get("highestMotionChanceInWindow").asDouble() < 0.10);
		assertEquals("UTC", ev.get("timeZone").asString());
		assertTrue(ev.get("modelAccuracy").asDouble() > ev.get("modelBaselineAccuracy").asDouble() - 0.001, "the model must beat guessing to be trusted");
		assertTrue(s.get("rationale").asString().contains("activity, not about people"));
		assertTrue(s.get("description").asString().contains("set pir led to off"));
		assertEquals(0, ruleCount(t), "a recommendation changes nothing by itself");
		assertEquals(0, publisher.publishedTo(p.deviceId()).size());
	}

	@Test
	void approvingCreatesTheRuleThroughTheNormalServiceAndIsNotOfferedAgain() throws Exception {
		String t = newUserToken();
		trainedDevice(t, "Approve me");
		JsonNode start = withTitle(list(t), "Turn the PIR LED off during quiet hours").get(0);
		String id = start.get("id").asString();

		JsonNode applied = json(postAs(t, "/api/recommendations/" + id + "/approve", null).andExpect(status().isOk()));
		assertEquals("APPLIED", applied.get("status").asString());
		assertNotNull(applied.get("createdRuleId").asString(null));
		assertEquals(1, ruleCount(t));
		JsonNode rule = json(getAs(t, "/api/automations").andExpect(status().isOk())).get(0);
		assertEquals("Quiet hours start", rule.get("name").asString());
		assertEquals("off", rule.get("action").get("value").asString());
		assertEquals(applied.get("createdRuleId").asString(), rule.get("id").asString());

		postAs(t, "/api/recommendations/" + id + "/approve", null).andExpect(status().isConflict());
		assertEquals(1, ruleCount(t), "no second rule");
		assertEquals(0, withTitle(list(t), "Turn the PIR LED off during quiet hours").size(), "an identical rule now exists, so it is not offered again");
		assertEquals(1, withTitle(list(t), "Return the PIR LED to auto").size(), "the other half is still on offer");
	}

	@Test
	void aDismissedRecommendationStaysAwayAndCannotBeApprovedLater() throws Exception {
		String t = newUserToken();
		trainedDevice(t, "Dismiss me");
		String id = withTitle(list(t), "Return the PIR LED to auto").get(0).get("id").asString();

		assertEquals("DISMISSED", json(postAs(t, "/api/recommendations/" + id + "/dismiss", null).andExpect(status().isOk())).get("status").asString());
		postAs(t, "/api/recommendations/" + id + "/approve", null).andExpect(status().isConflict());
		postAs(t, "/api/recommendations/" + id + "/dismiss", null).andExpect(status().isConflict());
		assertEquals(0, withTitle(json(postAs(t, "/api/recommendations/refresh", null).andExpect(status().isOk())), "Return the PIR LED to auto").size());
		assertEquals(0, ruleCount(t));
	}

	@Test
	void anAlreadyExistingMatchingRuleSuppressesTheSuggestion() throws Exception {
		String t = newUserToken();
		Paired p = trainedDevice(t, "Has rule");
		JsonNode start = withTitle(list(t), "Turn the PIR LED off during quiet hours").get(0);
		String time = start.get("rule").get("trigger").get("time").asString();
		createRule(p, ruleJson("My own quiet rule", p.uuid(), null,
				"{\"type\":\"SCHEDULE\",\"time\":\"" + time + "\",\"days\":[\"MON\"],\"timeZone\":\"UTC\"}", "AND", List.of(), "set_pir_led", "off", 10, 60));

		assertEquals(0, withTitle(list(t), "Turn the PIR LED off during quiet hours").size());
	}

	@Test
	void aSuggestionThatStopsBeingValidBecomesStaleInsteadOfStayingOnOffer() throws Exception {
		String t = newUserToken();
		Paired p = trainedDevice(t, "Goes stale");
		String id = withTitle(list(t), "Turn the PIR LED off during quiet hours").get(0).get("id").asString();
		jdbc.update("delete from ml_models where device_uuid = ?", p.uuid());          // the model disappears (for example it was removed)

		JsonNode after = list(t);
		assertEquals(0, after.get("recommendations").size());
		assertEquals("STALE", recommendationRepo.findById(UUID.fromString(id)).orElseThrow().getStatus());
		postAs(t, "/api/recommendations/" + id + "/approve", null).andExpect(status().isConflict());
		assertEquals(0, ruleCount(t));
	}

	// ---- temperature indicator from readings and anomalies -------------------------------------

	@Test
	void repeatedUnusualTemperaturesSuggestAThresholdRuleBasedOnTheRealPercentile() throws Exception {
		String t = newUserToken();
		deviceWithTemperatureOutliers(t, "Hot room");

		JsonNode r = list(t);
		List<JsonNode> found = withTitle(r, "Light the PIR LED when \"Hot room\" is hotter than");
		assertEquals(1, found.size(), r.toString());
		JsonNode ev = found.get(0).get("evidence");
		assertTrue(ev.get("unusualTemperatureReadingsLast7Days").asInt() >= 3);
		assertEquals(403, ev.get("temperatureSamples").asInt());
		double p95 = ev.get("p95").asDouble();
		assertTrue(p95 > 21 && p95 < 24, "p95 of readings around 22 C: " + p95);
		double threshold = ev.get("suggestedThreshold").asDouble();
		assertEquals(Math.round((p95 + 2.0) * 2) / 2.0, threshold, 0.51);
		JsonNode cond = found.get(0).get("rule").get("conditions").get(0);
		assertEquals("temperature", cond.get("sensor").asString());
		assertEquals(">", cond.get("operator").asString());
		assertEquals(threshold, cond.get("value").asDouble(), 0.0001);
		assertEquals("set_pir_led", found.get(0).get("rule").get("action").get("action").asString());
	}

	@Test
	void thatRuleIsOnlyCreatedOnApprovalAndCountsTowardTheNormalLimits() throws Exception {
		String t = newUserToken();
		deviceWithTemperatureOutliers(t, "Approve heat");
		String id = withTitle(list(t), "Light the PIR LED when").get(0).get("id").asString();
		assertEquals(0, ruleCount(t));

		postAs(t, "/api/recommendations/" + id + "/approve", null).andExpect(status().isOk());

		JsonNode rule = json(getAs(t, "/api/automations")).get(0);
		assertEquals("High temperature indicator", rule.get("name").asString());
		assertEquals(300, rule.get("cooldownSeconds").asInt());
		assertEquals(0, withTitle(list(t), "Light the PIR LED when").size(), "the user now has a high-temperature rule on that device");
	}

	@Test
	void fewUnusualReadingsStaySilent() throws Exception {
		String t = newUserToken();
		Instant now = Instant.now();
		Paired p = pairNamed(t, "One blip");
		seedNormalHistory(p, now, 400, 40);
		save(List.of(reading(p, now.minusSeconds(60), 45.0, 50.0, false, 1500)));
		scanner.scan(p.uuid(), now, Duration.ofHours(3));
		assertEquals(1, mlAnomalies.findByDeviceUuidOrderByLastSeenAtDesc(p.uuid(), org.springframework.data.domain.PageRequest.of(0, 10)).stream()
				.filter(a -> a.getKind() == AnomalyKind.STATISTICAL_OUTLIER).count());

		assertEquals(0, withTitle(list(t), "Light the PIR LED when").size(), "one blip is not a pattern");
	}

	@Test
	void gasIsNeverTheSubjectOfARecommendation() throws Exception {
		String t = newUserToken();
		Instant now = Instant.now();
		Paired p = pairNamed(t, "Gas data");
		seedNormalHistory(p, now, 400, 40);
		for (int i = 3; i >= 1; i--) {
			save(List.of(reading(p, now.minusSeconds(i * 100L), 22.0, 50.0, false, 4000)));      // very high raw gas, three times
			scanner.scan(p.uuid(), now.minusSeconds(i * 100L - 10), Duration.ofHours(3));
		}
		assertTrue(mlAnomalies.findByDeviceUuidOrderByLastSeenAtDesc(p.uuid(), org.springframework.data.domain.PageRequest.of(0, 20)).stream()
				.anyMatch(a -> "gasRaw".equals(a.getScope()) || "gas".equals(a.getScope()) || a.getScope().startsWith("gas")), "the gas anomalies exist");

		JsonNode r = list(t);
		assertFalse(r.toString().toLowerCase().contains("set_gas_led"));
		assertFalse(r.toString().toLowerCase().contains("\"sensor\":\"gas\""));
		assertEquals(0, publisher.publishedTo(p.deviceId()).size());
	}

	// ---- rules that mostly fail ----------------------------------------------------------------

	@Test
	void aRuleThatMostlyHitsAnOfflineTargetIsOfferedForDisablingAndKeptNotDeleted() throws Exception {
		String t = newUserToken();
		Paired sensor = onlineNamed(t, "Sensor");
		Paired lamp = pairNamed(t, "Lamp never connected");
		UUID rid = createRule(sensor, ruleJson("Lamp follows heat", sensor.uuid(), lamp.uuid(), TELEMETRY, "AND",
				List.of(cond("temperature", ">", "30")), "set_pir_led", "on", 10, 5));
		Instant now = Instant.now();
		for (int i = 0; i < 6; i++) {
			engine.onTelemetry(event(sensor, now.plusSeconds(i), 35.0, null, null, null), now.plusSeconds(i));
		}

		JsonNode r = list(t);
		List<JsonNode> found = withTitle(r, "Disable the rule");
		assertEquals(1, found.size(), r.toString());
		JsonNode rec = found.get(0);
		assertEquals("DISABLE_RULE", rec.get("actionType").asString());
		assertEquals(rid.toString(), rec.get("ruleId").asString());
		assertTrue(rec.get("evidence").get("runsLast7Days").asInt() >= 5);
		assertTrue(rec.get("evidence").get("failedOrTargetOffline").asInt() >= 5);
		assertTrue(rules.findById(rid).orElseThrow().isEnabled(), "still enabled until approved");

		postAs(t, "/api/recommendations/" + rec.get("id").asString() + "/approve", null).andExpect(status().isOk());
		assertFalse(rules.findById(rid).orElseThrow().isEnabled());
		assertEquals(1, ruleCount(t), "disabled, not deleted");
	}

	// ---- ownership -----------------------------------------------------------------------------

	@Test
	void recommendationsBelongToTheirOwnerAlone() throws Exception {
		String ta = newUserToken();
		String tb = newUserToken();
		trainedDevice(ta, "Alice room");
		onlineNamed(tb, "Bob room");

		JsonNode mine = list(ta);
		String id = withTitle(mine, "Turn the PIR LED off during quiet hours").get(0).get("id").asString();
		assertFalse(list(tb).toString().contains("Alice room"));
		assertEquals(0, list(tb).get("recommendations").size());
		postAs(tb, "/api/recommendations/" + id + "/approve", null).andExpect(status().isNotFound());
		postAs(tb, "/api/recommendations/" + id + "/dismiss", null).andExpect(status().isNotFound());
		postAs(ta, "/api/recommendations/" + UUID.randomUUID() + "/approve", null).andExpect(status().isNotFound());
		assertEquals(0, ruleCount(tb));
		assertEquals(0, ruleCount(ta));
		assertEquals("PENDING", recommendationRepo.findById(UUID.fromString(id)).orElseThrow().getStatus());
	}

	@Test
	void approvalReChecksOwnershipSoADisownedDeviceCannotGetARule() throws Exception {
		String t = newUserToken();
		Paired p = trainedDevice(t, "Soon gone");
		String id = withTitle(list(t), "Turn the PIR LED off during quiet hours").get(0).get("id").asString();
		deleteAs(t, "/api/devices/" + p.uuid()).andExpect(status().isNoContent());

		postAs(t, "/api/recommendations/" + id + "/approve", null).andExpect(status().isNotFound());
		assertEquals(0, ruleCount(t));
		assertEquals("PENDING", recommendationRepo.findById(UUID.fromString(id)).orElseThrow().getStatus(), "the failed approval was rolled back");
	}
}
