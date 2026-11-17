package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.smarthome.assistant.LlmClient.Message;
import com.smarthome.assistant.LlmClient.ToolCall;
import com.smarthome.assistant.LlmClient.Turn;
import com.smarthome.assistant.tools.ToolRegistry;
import com.smarthome.assistant.tools.ToolTypes.ToolContext;
import com.smarthome.assistant.tools.ToolTypes.ToolResult;

import tools.jackson.databind.JsonNode;

/**
 * The smart-home intelligence: database-computed statistics, the validated tool layer, the tool-calling loop with the
 * (scripted, simulated) model, follow-up resolution, the deterministic gas banner, command proposals and streaming.
 */
class AssistantIntelligenceTests extends AssistantTestBase {

	@Autowired ToolRegistry registry;

	private static final AtomicInteger SEQ = new AtomicInteger(50_000);

	private void readings(Paired p, double... temps) {
		for (double t : temps) {
			reading(p, SEQ.incrementAndGet(), t, 50.0, false, 900);
		}
	}

	private ToolContext ctx(Paired p, Map<UUID, String> devices) {
		return new ToolContext(p.userId(), ZoneId.of("UTC"), Instant.now(), devices);
	}

	// ---- tools: exact numbers from SQL ---------------------------------------------------------

	@Test
	void statisticsAreComputedExactlyByTheDatabase() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Kestrel Attic");
		readings(p, 20.0, 22.0, 24.0, 26.0);

		ToolResult r = registry.call(ctx(p, Map.of(p.uuid(), "Kestrel Attic")), "get_sensor_statistics", "{\"period\":\"last_24h\",\"metric\":\"temperature\"}");

		assertTrue(r.ok(), r.error());
		String text = r.output().text();
		assertTrue(text.contains("Readings: 4."), text);
		assertTrue(text.contains("average 23.0 °C"), text);
		assertTrue(text.contains("minimum 20.0 °C"), text);
		assertTrue(text.contains("maximum 26.0 °C"), text);
		assertEquals("table", r.output().visuals().get(0).kind());
	}

	@Test
	void aPeriodWithoutReadingsSaysSoInsteadOfReportingZero() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Empty Shed");
		readings(p, 21.0);

		ToolResult r = registry.call(ctx(p, Map.of(p.uuid(), "Empty Shed")), "get_sensor_statistics", "{\"period\":\"custom\",\"from\":\"2020-01-01\",\"to\":\"2020-01-02\"}");

		assertTrue(r.ok(), r.error());
		assertTrue(r.output().text().contains("No readings were stored in this period."), r.output().text());
		assertFalse(r.output().text().contains("average 0"));
	}

	@Test
	void toolsOnlyEverSeeTheCallersOwnDevices() throws Exception {
		String ta = newUserToken();
		String tb = newUserToken();
		Paired a = pairNamed(ta, "Alpha Room");
		Paired b = pairNamed(tb, "Bravo Room");
		readings(a, 10.0);
		readings(b, 30.0);

		// B's tools never see A's 10.0, even with no device filter.
		ToolResult mine = registry.call(ctx(b, Map.of(b.uuid(), "Bravo Room")), "get_sensor_statistics", "{\"period\":\"last_24h\",\"metric\":\"temperature\"}");
		assertTrue(mine.output().text().contains("average 30.0 °C"), mine.output().text());
		assertFalse(mine.output().text().contains("10.0"));

		// Naming A's device (by id or name) is rejected.
		ToolResult byId = registry.call(ctx(b, Map.of(b.uuid(), "Bravo Room")), "get_current_readings", "{\"device\":\"" + a.uuid() + "\"}");
		assertFalse(byId.ok());
		ToolResult byName = registry.call(ctx(b, Map.of(b.uuid(), "Bravo Room")), "get_current_readings", "{\"device\":\"Alpha Room\"}");
		assertFalse(byName.ok());
		assertTrue(byName.error().endsWith("Your devices: Bravo Room"), "only the caller's own devices are listed: " + byName.error());
	}

	@Test
	void invalidToolCallsAreRejectedWithAReasonAndNothingRuns() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Lynx Loft");
		ToolContext c = ctx(p, Map.of(p.uuid(), "Lynx Loft"));

		assertFalse(registry.call(c, "run_sql", "{\"query\":\"select * from users\"}").ok());
		assertTrue(registry.call(c, "run_sql", "{}").error().startsWith("Unknown tool"));
		assertTrue(registry.call(c, "get_sensor_statistics", "{\"period\":\"forever\"}").error().contains("must be one of"));
		assertTrue(registry.call(c, "get_sensor_statistics", "{\"sql\":\"1=1\"}").error().contains("Unknown argument"));
		assertTrue(registry.call(c, "get_sensor_statistics", "[1,2]").error().contains("JSON object"));
		assertTrue(registry.call(c, "get_sensor_statistics", "not json").error().contains("JSON object"));
		assertTrue(registry.call(c, "get_sensor_statistics", "{\"period\":\"custom\",\"from\":\"2026-13-45\"}").error().contains("date"));
		assertTrue(registry.call(c, "get_sensor_statistics", "{\"period\":\"custom\",\"from\":\"2000-01-01\",\"to\":\"2026-01-01\"}").error().contains("366"));
		assertFalse(registry.call(c, "get_current_readings", "{\"device\":\"x' or '1'='1\"}").ok(), "a SQL-looking name is just a name that does not exist");
		assertTrue(registry.call(c, "get_sensor_trend", "{}").error().contains("required"));
	}

	@Test
	void comparisonsCarryTheComputedDifference() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Heron Hall");
		readings(p, 20.0, 22.0);

		ToolResult r = registry.call(ctx(p, Map.of(p.uuid(), "Heron Hall")), "compare_periods",
				"{\"metric\":\"temperature\",\"period\":\"last_24h\",\"compare_with\":\"previous\"}");

		assertTrue(r.ok(), r.error());
		assertTrue(r.output().text().contains("average 21.0 °C in A"), r.output().text());
		assertTrue(r.output().text().contains("cannot compare, one period has no values"), "the previous day had no readings: " + r.output().text());
	}

	@Test
	void theGasLedCanNeverBeProposedOffAndProposalsSendNothing() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Puffin Porch");
		ToolContext c = ctx(p, Map.of(p.uuid(), "Puffin Porch"));

		ToolResult off = registry.call(c, "propose_device_command", "{\"device\":\"Puffin Porch\",\"action\":\"set_gas_led\",\"value\":\"off\"}");
		assertFalse(off.ok());
		assertTrue(off.error().contains("never be turned off"), off.error());

		ToolResult on = registry.call(c, "propose_device_command", "{\"device\":\"Puffin Porch\",\"action\":\"set_pir_led\",\"value\":\"on\"}");
		assertTrue(on.ok(), on.error());
		assertEquals("set_pir_led", on.output().action().action());
		assertEquals(0, publisher.publishedTo(p.deviceId()).size(), "a proposal never sends a command");
	}

	// ---- the chat with tools -------------------------------------------------------------------

	@Test
	void theModelCanCallAToolAndCiteItsResult() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Osprey Office");
		readings(p, 18.0, 19.0, 20.0);
		llm.turnsWith(c -> {
			Message last = c.messages().get(c.messages().size() - 1);
			if (!"tool".equals(last.role())) {
				return new Turn("", List.of(new ToolCall("call_1", "get_sensor_statistics", "{\"period\":\"last_7_days\",\"metric\":\"humidity\"}")));
			}
			String ref = last.content().substring(1, last.content().indexOf(']'));
			return new Turn("Humidity averaged 50.0 % over the last 7 days [" + ref + "].", List.of());
		});

		JsonNode r = ask(token, "Tell me something about Osprey Office");

		assertEquals("ANSWERED", statusOf(r));
		JsonNode msg = r.get("assistantMessage");
		assertEquals(0, msg.get("warnings").size(), msg.get("warnings").toString());
		boolean citedLive = false;
		for (JsonNode s : msg.get("sources")) {
			citedLive |= s.get("cited").asBoolean() && "LIVE_DATA".equals(s.get("type").asString());
		}
		assertTrue(citedLive, "the tool result is a cited source");
		JsonNode tools = msg.get("extras").get("tools");
		assertTrue(tools.toString().contains("get_sensor_statistics"), tools.toString());
		assertFalse(llm.toolLists().get(0).isEmpty(), "the tool catalogue was offered to the model");
		assertFalse(llm.toolLists().get(0).stream().anyMatch(t -> t.name().contains("sql")), "there is no SQL tool");
	}

	@Test
	void aBadToolCallIsReportedBackToTheModelNotExecuted() throws Exception {
		String token = newUserToken();
		pairNamed(token, "Raven Rest");
		AtomicInteger round = new AtomicInteger();
		llm.turnsWith(c -> {
			if (round.getAndIncrement() == 0) {
				return new Turn("", List.of(new ToolCall("x", "drop_tables", "{}")));
			}
			Message last = c.messages().get(c.messages().size() - 1);
			assertTrue(last.content().contains("Unknown tool"), last.content());
			return new Turn("I cannot do that.", List.of());
		});

		JsonNode r = ask(token, "please drop every table");

		assertEquals("ANSWERED", statusOf(r));
		assertTrue(r.get("assistantMessage").get("extras").get("tools").toString().contains("\"ok\":false"));
	}

	@Test
	void toolCallsAreBoundedEvenIfTheModelKeepsAsking() throws Exception {
		String token = newUserToken();
		pairNamed(token, "Loop Lab");
		llm.turnsWith(c -> new Turn("", List.of(new ToolCall("a", "get_system_health", "{}"), new ToolCall("b", "get_system_health", "{}"),
				new ToolCall("c", "get_system_health", "{}"))));

		JsonNode r = ask(token, "loop forever");

		assertEquals("LLM_UNAVAILABLE", statusOf(r), "a model that never answers ends as unavailable, not as an endless loop");
		assertTrue(r.get("assistantMessage").get("extras").get("tools").size() <= 6 + 3);
	}

	@Test
	void aFollowUpQuestionReusesTheConversationsFocus() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Magpie Study");
		readings(p, 21.0, 23.0);
		llm.replyWith("Done.");

		UUID conv = UUID.fromString(ask(token, "What was the average humidity today?").get("conversationId").asString());
		JsonNode r = json(chat(token, conv, "compare it with yesterday").andExpect(status().isOk()));

		String tools = r.get("assistantMessage").get("extras").get("tools").toString();
		assertTrue(tools.contains("compare_periods"), tools);
		assertTrue(tools.contains("humidity"), "\"it\" was resolved to humidity: " + tools);
		assertTrue(tools.contains("yesterday"), tools);
		assertTrue(llm.lastCall().lastUserMessage().contains("Conversation focus"), "the model is told what \"it\" refers to");
	}

	@Test
	void withoutAModelStatisticsAreStillAnsweredFromTheDatabase() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Wren Workshop");
		readings(p, 15.0, 25.0);
		llm.setConfigured(false);

		JsonNode r = ask(token, "What was the average temperature today?");

		assertEquals("NOT_CONFIGURED", statusOf(r));
		assertTrue(answerText(r).contains("average 20.0 °C"), answerText(r));
		assertTrue(llm.calls().isEmpty());
		assertFalse(r.get("assistantMessage").get("extras").get("followUps").isEmpty());
	}

	@Test
	void anActiveGasIncidentAlwaysShowsTheDeterministicSafetyNotice() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Gas Galley");
		sendTelemetry(p, "{\"seq\":" + SEQ.incrementAndGet() + ",\"gasRaw\":3000}");
		sendTelemetry(p, "{\"seq\":" + SEQ.incrementAndGet() + ",\"gasRaw\":3100}");
		llm.replyWith("Everything is fine.");                     // whatever the model says

		JsonNode r = ask(token, "how is my home?");

		JsonNode safety = r.get("assistantMessage").get("extras").get("safety");
		assertNotNull(safety);
		assertTrue(safety.asString().contains("Gas alert ACTIVE"), safety.toString());
		assertTrue(safety.asString().contains("not from the AI"));
	}

	@Test
	void noSafetyNoticeWithoutAnIncident() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Calm Cabin");
		readings(p, 21.0);
		llm.replyWith("ok");

		JsonNode r = ask(token, "what is the temperature now?");

		assertTrue(r.get("assistantMessage").get("extras").get("safety").isNull());
	}

	@Test
	void aProposedCommandIsReturnedForConfirmationAndNothingIsSent() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Finch Flat");
		llm.turnsWith(c -> {
			Message last = c.messages().get(c.messages().size() - 1);
			if (!"tool".equals(last.role())) {
				return new Turn("", List.of(new ToolCall("p1", "propose_device_command", "{\"device\":\"Finch Flat\",\"action\":\"set_pir_led\",\"value\":\"off\"}")));
			}
			return new Turn("I prepared it; please confirm.", List.of());
		});

		JsonNode r = ask(token, "switch the pir led off in finch flat");

		JsonNode actions = r.get("assistantMessage").get("extras").get("actions");
		assertEquals(1, actions.size());
		assertEquals(p.uuid().toString(), actions.get(0).get("deviceId").asString());
		assertEquals("off", actions.get(0).get("value").asString());
		assertEquals(0, publisher.publishedTo(p.deviceId()).size());
	}

	@Test
	void extrasArePersistedWithTheMessage() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Stork Store");
		readings(p, 20.0, 21.0, 22.0);
		llm.replyWith("ok");

		JsonNode r = ask(token, "show the temperature trend for the last 24 hours");
		String conv = r.get("conversationId").asString();

		JsonNode stored = json(getAs(token, "/api/assistant/conversations/" + conv + "/messages").andExpect(status().isOk())).get(1);
		assertTrue(stored.get("extras").get("tools").toString().contains("get_sensor_trend"), stored.get("extras").toString());
		assertFalse(stored.get("extras").get("visuals").isEmpty());
	}

	@Test
	void anInvalidBrowserTimeZoneFallsBackInsteadOfFailing() throws Exception {
		String token = newUserToken();
		pairNamed(token, "Zone Zero");
		llm.replyWith("ok");
		mvc.perform(post("/api/assistant/chat").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"temperature today\",\"timeZone\":\"Mars/Olympus\"}")).andExpect(status().isOk());
	}

	// ---- streaming -----------------------------------------------------------------------------

	@Test
	void theStreamSendsProgressThenTheCheckedAnswer() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Stream Studio");
		readings(p, 21.5);
		llm.replyWith("It is 21.5 °C.");

		MvcResult started = mvc.perform(post("/api/assistant/chat/stream").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"what is the temperature now?\"}")).andExpect(request().asyncStarted()).andReturn();
		String body = waitForEvent(started, "event:done");

		assertTrue(body.contains("event:progress"), body);
		assertTrue(body.contains("\"stage\":\"querying\""), body);
		assertTrue(body.contains("21.5"), body);
		// without this nginx (the frontend container) would buffer every progress event until the end
		assertEquals("no", started.getResponse().getHeader("X-Accel-Buffering"));
		mvc.perform(asyncDispatch(started));
	}

	@Test
	void streamErrorsAreSentAsAnErrorEvent() throws Exception {
		String token = newUserToken();
		MvcResult started = mvc.perform(post("/api/assistant/chat/stream").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.content("{\"message\":\"hi\",\"conversationId\":\"" + UUID.randomUUID() + "\"}")).andExpect(request().asyncStarted()).andReturn();
		String body = waitForEvent(started, "event:error");
		assertTrue(body.contains("\"status\":404"), body);
	}

	@Test
	void theStreamRequiresSignIn() throws Exception {
		mvc.perform(post("/api/assistant/chat/stream").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
				.andExpect(status().isUnauthorized());
	}

	private static String waitForEvent(MvcResult r, String marker) throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		String body = "";
		while (System.currentTimeMillis() < deadline) {
			body = r.getResponse().getContentAsString();
			if (body.contains(marker)) {
				return body;
			}
			Thread.sleep(50);
		}
		assertNull(body, "no " + marker + " within 20 s");
		return body;
	}
}
