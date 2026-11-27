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


}
