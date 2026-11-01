package com.smarthome;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class AutomationApiTests extends AutomationTestBase {

	private String json(Paired p, String name) {
		return ruleJson(name, p.uuid(), null, TELEMETRY, "AND",
				List.of(cond("temperature", ">", "30"), cond("motion", "==", "true")), "set_pir_led", "on", 20, 30);
	}

	private ResultActions putRule(Paired p, UUID id, String body) throws Exception {
		return mvc.perform(put("/api/automations/" + id).header("Authorization", p.auth())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	// ---- CRUD ----------------------------------------------------------------------------------

	@Test
	void createReturnsTheRuleWithDefaultsFilledIn() throws Exception {
		Paired p = onlineDevice(newUserToken());
		String minimal = "{\"name\":\"  Cool light  \",\"deviceId\":\"" + p.uuid() + "\",\"trigger\":{\"type\":\"TELEMETRY\"},"
				+ "\"conditions\":[" + cond("temperature", ">=", "30.5") + "],\"action\":{\"action\":\"set_pir_led\",\"value\":\"on\"}}";
		postRule(p.token(), minimal).andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(jsonPath("$.name").value("Cool light"))
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.deviceId").value(p.uuid().toString()))
				.andExpect(jsonPath("$.targetDeviceId").value(p.uuid().toString()))
				.andExpect(jsonPath("$.trigger.type").value("TELEMETRY"))
				.andExpect(jsonPath("$.logic").value("AND"))
				.andExpect(jsonPath("$.priority").value(10))
				.andExpect(jsonPath("$.cooldownSeconds").value(60))
				.andExpect(jsonPath("$.conditions[0].sensor").value("temperature"))
				.andExpect(jsonPath("$.conditions[0].operator").value(">="))
				.andExpect(jsonPath("$.conditions[0].value").value(30.5))
				.andExpect(jsonPath("$.action.action").value("set_pir_led"));
	}

	@Test
	void listGetUpdateEnableDeleteRoundTrip() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID id = createRule(p, json(p, "First"));
		createRule(p, json(p, "Second"));

		mvc.perform(get("/api/automations").header("Authorization", p.auth()))
				.andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2))).andExpect(jsonPath("$[0].name").value("First"));
		mvc.perform(get("/api/automations/" + id).header("Authorization", p.auth()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.conditions", hasSize(2))).andExpect(jsonPath("$.conditions[1].value").value(true));

		// full update replaces conditions, logic, action, priority and cooldown
		putRule(p, id, ruleJson("First v2", p.uuid(), null, TELEMETRY, "OR", List.of(cond("gas", ">", "2500")), "set_gas_led", "on", 80, 120))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("First v2"))
				.andExpect(jsonPath("$.logic").value("OR"))
				.andExpect(jsonPath("$.conditions", hasSize(1)))
				.andExpect(jsonPath("$.conditions[0].value").value(2500))
				.andExpect(jsonPath("$.action.action").value("set_gas_led"))
				.andExpect(jsonPath("$.priority").value(80))
				.andExpect(jsonPath("$.cooldownSeconds").value(120));
		assertEquals(1, rule(id).getConditions().size());

		mvc.perform(patch("/api/automations/" + id + "/enabled").header("Authorization", p.auth())
				.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
		assertEquals(false, rule(id).isEnabled());
		mvc.perform(patch("/api/automations/" + id + "/enabled").header("Authorization", p.auth())
				.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
				.andExpect(jsonPath("$.enabled").value(true));
		mvc.perform(patch("/api/automations/" + id + "/enabled").header("Authorization", p.auth())
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());

		mvc.perform(delete("/api/automations/" + id).header("Authorization", p.auth())).andExpect(status().isNoContent());
		mvc.perform(get("/api/automations/" + id).header("Authorization", p.auth())).andExpect(status().isNotFound());
		mvc.perform(get("/api/automations").header("Authorization", p.auth())).andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void scheduledRuleRoundTripsItsScheduleFields() throws Exception {
		Paired p = onlineDevice(newUserToken());
		String trigger = "{\"type\":\"SCHEDULE\",\"time\":\"22:30\",\"days\":[\"mon\",\"FRI\"],\"timeZone\":\"Asia/Kolkata\"}";
		UUID id = createRule(p, ruleJson("Night", p.uuid(), null, trigger, null, List.of(), "set_pir_led", "off", null, null));
		mvc.perform(get("/api/automations/" + id).header("Authorization", p.auth()))
				.andExpect(jsonPath("$.trigger.type").value("SCHEDULE"))
				.andExpect(jsonPath("$.trigger.time").value("22:30"))
				.andExpect(jsonPath("$.trigger.days[0]").value("MON"))
				.andExpect(jsonPath("$.trigger.days[1]").value("FRI"))
				.andExpect(jsonPath("$.trigger.timeZone").value("Asia/Kolkata"))
				.andExpect(jsonPath("$.conditions", hasSize(0)));
	}

	@Test
	void gasConditionsAreWholeRawCounts() throws Exception {
		Paired p = onlineDevice(newUserToken());
		postRule(p.token(), ruleJson("Gas", p.uuid(), null, TELEMETRY, null, List.of(cond("gas", ">", "1800.5")), "set_pir_led", "on", null, null))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors['conditions[0].value']").value(containsString("whole number")));
	}

	// ---- ownership -----------------------------------------------------------------------------

	@Test
	void anotherUserCannotSeeChangeEnableOrDeleteMyRules() throws Exception {
		Paired mine = onlineDevice(newUserToken());
		UUID id = createRule(mine, json(mine, "Mine"));
		String mallory = "Bearer " + newUserToken();

		mvc.perform(get("/api/automations/" + id).header("Authorization", mallory)).andExpect(status().isNotFound());
		mvc.perform(put("/api/automations/" + id).header("Authorization", mallory).contentType(MediaType.APPLICATION_JSON)
				.content(json(mine, "Hijacked"))).andExpect(status().isNotFound());
		mvc.perform(patch("/api/automations/" + id + "/enabled").header("Authorization", mallory).contentType(MediaType.APPLICATION_JSON)
				.content("{\"enabled\":false}")).andExpect(status().isNotFound());
		mvc.perform(delete("/api/automations/" + id).header("Authorization", mallory)).andExpect(status().isNotFound());
		mvc.perform(get("/api/automations/" + id + "/executions").header("Authorization", mallory)).andExpect(status().isNotFound());
		mvc.perform(get("/api/automations").header("Authorization", mallory)).andExpect(jsonPath("$", hasSize(0)));

		// untouched
		assertEquals("Mine", rule(id).getName());
		assertTrue(rule(id).isEnabled());
	}

	@Test
	void aRuleCannotReferenceSomeoneElsesDevice() throws Exception {
		Paired mine = onlineDevice(newUserToken());
		Paired theirs = onlineDevice(newUserToken());
		long before = rules.countByOwnerUserId(mine.userId());

		// as the trigger device
		postRule(mine.token(), json(theirs, "Spy")).andExpect(status().isNotFound());
		// as the action target
		postRule(mine.token(), ruleJson("Remote", mine.uuid(), theirs.uuid(), TELEMETRY, "AND",
				List.of(cond("temperature", ">", "1")), "set_pir_led", "on", null, null)).andExpect(status().isNotFound());
		// and when updating an existing rule
		UUID id = createRule(mine, json(mine, "Mine"));
		putRule(mine, id, ruleJson("Mine", mine.uuid(), theirs.uuid(), TELEMETRY, "AND", List.of(cond("temperature", ">", "1")), "set_pir_led", "on", null, null))
				.andExpect(status().isNotFound());
		assertEquals(before + 1, rules.countByOwnerUserId(mine.userId()));
		assertEquals(mine.uuid(), rule(id).getTargetDeviceUuid());
	}

	@Test
	void aRuleMayActOnAnotherDeviceThatIsAlsoMine() throws Exception {
		String token = newUserToken();
		Paired sensor = onlineDevice(token);
		Paired lamp = onlineDevice(token);
		postRule(token, ruleJson("Cross", sensor.uuid(), lamp.uuid(), TELEMETRY, "AND", List.of(cond("motion", "==", "true")), "set_pir_led", "on", null, null))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.targetDeviceId").value(lamp.uuid().toString()));
	}

	@Test
	void ownerIsAlwaysTheTokenNeverTheRequestBody() throws Exception {
		Paired mine = onlineDevice(newUserToken());
		Paired other = onlineDevice(newUserToken());
		String body = json(mine, "Sneaky").replaceFirst("\\{", "{\"ownerUserId\":\"" + other.userId() + "\",\"owner\":\"x\",\"admin\":true,");
		UUID id = UUID.fromString(mapper.readTree(postRule(mine.token(), body).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString()).get("id").asString());
		assertEquals(mine.userId(), rule(id).getOwnerUserId());
		mvc.perform(get("/api/automations").header("Authorization", other.auth())).andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void everyEndpointRequiresAuthentication() throws Exception {
		UUID any = UUID.randomUUID();
		mvc.perform(get("/api/automations")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/automations/" + any)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/automations/executions")).andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/automations/" + any)).andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/automations/" + any + "/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
				.andExpect(status().isUnauthorized());
		postRule("not-a-real-token", "{}").andExpect(status().isUnauthorized());
	}

	@Test
	void disowningADeviceRemovesRulesThatTouchIt() throws Exception {
		String token = newUserToken();
		Paired sensor = onlineDevice(token);
		Paired lamp = onlineDevice(token);
		UUID reads = createRule(sensor, json(sensor, "Reads the sensor"));
		UUID acts = createRule(sensor, ruleJson("Acts on the lamp", sensor.uuid(), lamp.uuid(), TELEMETRY, "AND",
				List.of(cond("temperature", ">", "1")), "set_pir_led", "on", null, null));
		UUID unrelated = createRule(lamp, json(lamp, "Lamp only"));

		mvc.perform(delete("/api/devices/" + lamp.uuid()).header("Authorization", lamp.auth())).andExpect(status().isNoContent());

		assertTrue(rules.findById(acts).isEmpty(), "rule acting on the disowned device is gone");
		assertTrue(rules.findById(unrelated).isEmpty(), "rule reading from the disowned device is gone");
		assertTrue(rules.findById(reads).isPresent(), "rules that never touched it are kept");
	}

	@Test
	void ruleCountPerUserIsLimited() throws Exception {
		Paired p = onlineDevice(newUserToken());
		for (int i = 0; i < 50; i++) {
			postRule(p.token(), json(p, "Rule " + i)).andExpect(status().isCreated());
		}
		postRule(p.token(), json(p, "One too many")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.rules").value(containsString("at most 50")));
	}

	// ---- validation ----------------------------------------------------------------------------

	@Test
	void invalidRulesAreRejectedWithFieldMessages() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID d = p.uuid();
		record Bad(String field, String body) {
		}
		List<Bad> bad = List.of(
				new Bad("name", ruleJson("   ", d, null, TELEMETRY, null, List.of(cond("temperature", ">", "1")), "set_pir_led", "on", null, null)),
				new Bad("name", ruleJson("x".repeat(61), d, null, TELEMETRY, null, List.of(cond("temperature", ">", "1")), "set_pir_led", "on", null, null)),
				new Bad("conditions", ruleJson("A", d, null, TELEMETRY, null, List.of(), "set_pir_led", "on", null, null)),
				new Bad("conditions", ruleJson("A", d, null, TELEMETRY, null, java.util.Collections.nCopies(11, cond("temperature", ">", "1")), "set_pir_led", "on", null, null)),
				new Bad("conditions[0].sensor", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("pressure", ">", "1")), "set_pir_led", "on", null, null)),
				new Bad("conditions[0].operator", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("temperature", "=~", "1")), "set_pir_led", "on", null, null)),
				new Bad("conditions[0].operator", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("motion", ">", "true")), "set_pir_led", "on", null, null)),
				new Bad("conditions[0].value", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("motion", "==", "1")), "set_pir_led", "on", null, null)),
				new Bad("conditions[0].value", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("temperature", ">", "\"30\"")), "set_pir_led", "on", null, null)),
				new Bad("conditions[0].value", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("temperature", ">", "null")), "set_pir_led", "on", null, null)),
				new Bad("conditions[0].value", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("temperature", ">", "500")), "set_pir_led", "on", null, null)),
				new Bad("conditions[0].value", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("humidity", "<", "-1")), "set_pir_led", "on", null, null)),
				new Bad("conditions[0].value", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("gas", ">", "9999")), "set_pir_led", "on", null, null)),
				new Bad("conditions[0].value", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("temperature", ">", "{\"$gt\":1}")), "set_pir_led", "on", null, null)),
				new Bad("logic", ruleJson("A", d, null, TELEMETRY, "XOR", List.of(cond("temperature", ">", "1")), "set_pir_led", "on", null, null)),
				new Bad("action.action", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("temperature", ">", "1")), "reboot", "now", null, null)),
				new Bad("action.value", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("temperature", ">", "1")), "set_pir_led", "blink", null, null)),
				new Bad("action.value", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("temperature", ">", "1")), "set_pir_led", "ON", null, null)),
				new Bad("priority", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("temperature", ">", "1")), "set_pir_led", "on", 101, null)),
				new Bad("priority", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("temperature", ">", "1")), "set_pir_led", "on", -1, null)),
				new Bad("cooldownSeconds", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("temperature", ">", "1")), "set_pir_led", "on", null, 4)),
				new Bad("cooldownSeconds", ruleJson("A", d, null, TELEMETRY, null, List.of(cond("temperature", ">", "1")), "set_pir_led", "on", null, 86401)),
				new Bad("trigger.type", ruleJson("A", d, null, "{\"type\":\"WEBHOOK\"}", null, List.of(cond("temperature", ">", "1")), "set_pir_led", "on", null, null)),
				new Bad("trigger.time", ruleJson("A", d, null, "{\"type\":\"SCHEDULE\",\"time\":\"25:00\",\"timeZone\":\"UTC\"}", null, List.of(), "set_pir_led", "on", null, null)),
				new Bad("trigger.time", ruleJson("A", d, null, "{\"type\":\"SCHEDULE\",\"time\":\"9:30\",\"timeZone\":\"UTC\"}", null, List.of(), "set_pir_led", "on", null, null)),
				new Bad("trigger.time", ruleJson("A", d, null, "{\"type\":\"SCHEDULE\",\"timeZone\":\"UTC\"}", null, List.of(), "set_pir_led", "on", null, null)),
				new Bad("trigger.days", ruleJson("A", d, null, "{\"type\":\"SCHEDULE\",\"time\":\"10:00\",\"days\":[\"FUNDAY\"],\"timeZone\":\"UTC\"}", null, List.of(), "set_pir_led", "on", null, null)),
				new Bad("trigger.timeZone", ruleJson("A", d, null, "{\"type\":\"SCHEDULE\",\"time\":\"10:00\",\"timeZone\":\"Mars/Base\"}", null, List.of(), "set_pir_led", "on", null, null)),
				new Bad("trigger.timeZone", ruleJson("A", d, null, "{\"type\":\"SCHEDULE\",\"time\":\"10:00\",\"timeZone\":\"+05:30\"}", null, List.of(), "set_pir_led", "on", null, null)),
				new Bad("trigger.timeZone", ruleJson("A", d, null, "{\"type\":\"SCHEDULE\",\"time\":\"10:00\"}", null, List.of(), "set_pir_led", "on", null, null)));
		for (Bad b : bad) {
			postRule(p.token(), b.body()).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.fieldErrors['" + b.field() + "']").exists());
		}
		assertEquals(0, rules.countByOwnerUserId(p.userId()), "none of them was stored");
		postRule(p.token(), "not json").andExpect(status().isBadRequest());
		postRule(p.token(), "{}").andExpect(status().isBadRequest());
	}

	@Test
	void theGasWarningLedCannotBeTurnedOffByAutomation() throws Exception {
		Paired p = onlineDevice(newUserToken());
		postRule(p.token(), ruleJson("Hide gas", p.uuid(), null, TELEMETRY, null, List.of(cond("temperature", ">", "1")), "set_gas_led", "off", null, null))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors['action.value']").value(containsString("gas warning LED")));
		// and not by editing an existing rule into one
		UUID ok = createRule(p, json(p, "Fine"));
		putRule(p, ok, ruleJson("Fine", p.uuid(), null, TELEMETRY, null, List.of(cond("temperature", ">", "1")), "set_gas_led", "off", null, null))
				.andExpect(status().isBadRequest());
		assertEquals("set_pir_led", rule(ok).getAction());
		// forcing it on, or returning to automatic, is allowed
		postRule(p.token(), ruleJson("Gas on", p.uuid(), null, TELEMETRY, null, List.of(cond("gas", ">", "2500")), "set_gas_led", "on", null, null)).andExpect(status().isCreated());
		postRule(p.token(), ruleJson("Gas auto", p.uuid(), null, TELEMETRY, null, List.of(cond("gas", "<", "500")), "set_gas_led", "auto", null, null)).andExpect(status().isCreated());
	}

	@Test
	void conditionsAreDataNotCode() throws Exception {
		Paired p = onlineDevice(newUserToken());
		// strings that look like SQL, scripts or expressions are rejected by the fixed vocabulary
		postRule(p.token(), ruleJson("x'; DROP TABLE users;--", p.uuid(), null, TELEMETRY, null,
				List.of("{\"sensor\":\"temperature; DROP TABLE users\",\"operator\":\">\",\"value\":1}"), "set_pir_led", "on", null, null))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors['conditions[0].sensor']").exists());
		postRule(p.token(), ruleJson("expr", p.uuid(), null, TELEMETRY, null,
				List.of("{\"sensor\":\"temperature\",\"operator\":\">\",\"value\":\"1 || true\"}"), "set_pir_led", "on", null, null))
				.andExpect(status().isBadRequest());
	}

	@Test
	void historyEndpointsOnlyShowMyExecutions() throws Exception {
		Paired mine = onlineDevice(newUserToken());
		Paired other = onlineDevice(newUserToken());
		UUID id = simpleRule(mine, "Hot", "temperature", ">", "30", "set_pir_led", "on", 10, 10);
		engine.onTelemetry(event(mine, java.time.Instant.now(), 35.0, null, null, null), java.time.Instant.now());

		mvc.perform(get("/api/automations/" + id + "/executions").header("Authorization", mine.auth()))
				.andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].outcome").value("EXECUTED"))
				.andExpect(jsonPath("$[0].triggerValues.temperature").value(35.0))
				.andExpect(jsonPath("$[0].evaluation[0].result").value("TRUE"))
				.andExpect(jsonPath("$[0].commandStatus").value("SENT"));
		mvc.perform(get("/api/automations/executions").header("Authorization", mine.auth())).andExpect(jsonPath("$", hasSize(1)));
		mvc.perform(get("/api/automations/executions").header("Authorization", other.auth())).andExpect(jsonPath("$", hasSize(0)));
	}
}
