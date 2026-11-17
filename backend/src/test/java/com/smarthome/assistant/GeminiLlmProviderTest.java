package com.smarthome.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.smarthome.assistant.AssistantProperties.Gemini;
import com.smarthome.assistant.AssistantProperties.Groq;
import com.smarthome.assistant.AssistantProperties.Llm;
import com.smarthome.assistant.LlmClient.Message;
import com.smarthome.assistant.LlmClient.ToolCall;
import com.smarthome.assistant.LlmClient.ToolSpec;
import com.smarthome.assistant.LlmClient.Turn;
import com.smarthome.assistant.LlmUnavailableException.Reason;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The Gemini client against a local server that speaks the generateContent format (request shape, parsing, errors). */
class GeminiLlmProviderTest {

	static final String KEY = "AIza-test-key-do-not-leak-0123456789";
	private final ObjectMapper mapper = JsonMapper.builder().build();
	private StubModelServer server;

	@BeforeEach
	void start() throws Exception {
		server = new StubModelServer();
	}

	@AfterEach
	void stop() {
		server.close();
	}

	static Llm cfg(String geminiBase, String key, String model, int timeout) {
		return new Llm("gemini", "", "", "", timeout, 700, 0.2, "gemini", "none", 60, new Gemini(key, model, geminiBase, 2048, "low"),
				new Groq("", "openai/gpt-oss-120b", ""));
	}

	private GeminiLlmProvider gemini(int timeout) {
		return new GeminiLlmProvider(cfg(server.base() + "/v1beta", KEY, "gemini-3-flash-preview", timeout), mapper);
	}

	static String textReply(String text) {
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + text + "\"}]},\"finishReason\":\"STOP\"}]}";
	}

	private static final ToolSpec TOOL = new ToolSpec("get_latest_readings", "Latest sensor readings",
			Map.of("type", "object", "properties", Map.of("device", Map.of("type", "string")), "required", List.of(), "additionalProperties", false));

	@Test
	void successfulReplyAndRequestShape() throws Exception {
		server.reply(200, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"thinking...\",\"thought\":true},"
				+ "{\"text\":\"It is 22 C [S1]\"}]}}]}");
		String text = gemini(5).complete("SYSTEM TEXT",
				List.of(Message.user("first question"), Message.assistant("first answer"), Message.user("how warm is it?")));

		assertEquals("It is 22 C [S1]", text, "thought parts are not part of the answer");
		StubModelServer.Request r = server.last();
		assertEquals("/v1beta/models/gemini-3-flash-preview:generateContent", r.path());
		assertEquals(KEY, r.headers().getFirst("x-goog-api-key"));
		assertNull(r.query(), "the key must never be in the URL");
		assertFalse(r.body().contains(KEY), "the key must never be in the body");

		JsonNode req = mapper.readTree(r.body());
		assertEquals("SYSTEM TEXT", req.at("/systemInstruction/parts/0/text").asString());
		JsonNode contents = req.get("contents");
		assertEquals(3, contents.size(), "conversation history is sent");
		assertEquals("user", contents.get(0).get("role").asString());
		assertEquals("model", contents.get(1).get("role").asString());
		assertEquals("first answer", contents.at("/1/parts/0/text").asString());
		assertEquals("how warm is it?", contents.at("/2/parts/0/text").asString());
		assertEquals("low", req.at("/generationConfig/thinkingConfig/thinkingLevel").asString());
		assertTrue(req.at("/generationConfig/temperature").isMissingNode(), "Gemini 3 keeps its default temperature");
		assertTrue(req.get("tools") == null, "no tools on a plain completion");
	}

	@Test
	void toolCallIsParsedWithItsThoughtSignature() throws Exception {
		server.reply(200, "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"get_latest_readings\","
				+ "\"args\":{\"device\":\"kitchen\"}},\"thoughtSignature\":\"SIG-abc\"}]}}]}");
		Turn t = gemini(5).completeWithTools("S", List.of(Message.user("kitchen temperature?")), List.of(TOOL));

		assertTrue(t.wantsTools());
		ToolCall c = t.toolCalls().get(0);
		assertEquals("get_latest_readings", c.name());
		assertEquals("kitchen", mapper.readTree(c.arguments()).get("device").asString());
		assertEquals("SIG-abc", c.signature());
		assertFalse(c.id().isBlank(), "a call id is made up when Gemini sends none, so a fallback provider can continue");

		JsonNode req = mapper.readTree(server.last().body());
		JsonNode decl = req.at("/tools/0/functionDeclarations/0");
		assertEquals("get_latest_readings", decl.get("name").asString());
		assertFalse(decl.at("/parametersJsonSchema/additionalProperties").asBoolean(true), "the JSON schema is passed as it is");
		assertEquals("AUTO", req.at("/toolConfig/functionCallingConfig/mode").asString());
	}

	@Test
	void toolResultsAreSentBackAsFunctionResponsesWithTheSignature() throws Exception {
		server.reply(200, textReply("Kitchen is 21 C [S1]"));
		List<Message> msgs = List.of(Message.user("kitchen?"),
				Message.assistantCalls("", List.of(new ToolCall("call_1", "get_latest_readings", "{\"device\":\"kitchen\"}", "SIG-abc"))),
				Message.toolResult("call_1", "get_latest_readings", "[S1] temperature 21 C"));
		Turn t = gemini(5).completeWithTools("S", msgs, List.of(TOOL));

		assertEquals("Kitchen is 21 C [S1]", t.text());
		JsonNode contents = mapper.readTree(server.last().body()).get("contents");
		assertEquals(3, contents.size());
		assertEquals("model", contents.at("/1/role").asString());
		assertEquals("get_latest_readings", contents.at("/1/parts/0/functionCall/name").asString());
		assertEquals("kitchen", contents.at("/1/parts/0/functionCall/args/device").asString());
		assertEquals("SIG-abc", contents.at("/1/parts/0/thoughtSignature").asString());
		assertEquals("user", contents.at("/2/role").asString());
		assertEquals("get_latest_readings", contents.at("/2/parts/0/functionResponse/name").asString());
		assertEquals("[S1] temperature 21 C", contents.at("/2/parts/0/functionResponse/response/result").asString());
	}

	@Test
	void aCallMadeByAnotherProviderGetsThePlaceholderSignature() throws Exception {
		server.reply(200, textReply("ok"));
		List<Message> msgs = List.of(Message.user("q"),
				Message.assistantCalls("", List.of(new ToolCall("call_groq_1", "get_latest_readings", "{}"),
						new ToolCall("call_groq_2", "get_latest_readings", "{}"))),
				Message.toolResult("call_groq_1", "get_latest_readings", "a"), Message.toolResult("call_groq_2", "get_latest_readings", "b"));
		gemini(5).completeWithTools("S", msgs, List.of(TOOL));

		JsonNode contents = mapper.readTree(server.last().body()).get("contents");
		assertEquals(GeminiLlmProvider.SKIP_SIGNATURE, contents.at("/1/parts/0/thoughtSignature").asString());
		assertTrue(contents.at("/1/parts/1/thoughtSignature").isMissingNode(), "only the first parallel call carries a signature");
		assertEquals(2, contents.at("/2/parts").size(), "parallel results go back in one turn");
	}

	@Test
	void httpErrorsMapToSafeReasons() {
		server.reply(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");
		assertEquals(Reason.RATE_LIMITED, assertThrows(LlmUnavailableException.class, () -> gemini(5).complete("S", List.of(Message.user("q")))).reason());
		server.reply(503, "{\"error\":{\"status\":\"UNAVAILABLE\"}}");
		assertEquals(Reason.SERVER_ERROR, assertThrows(LlmUnavailableException.class, () -> gemini(5).complete("S", List.of(Message.user("q")))).reason());
		server.reply(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\"}}");
		assertEquals(Reason.AUTH, assertThrows(LlmUnavailableException.class, () -> gemini(5).complete("S", List.of(Message.user("q")))).reason());
		server.reply(400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}");
		LlmUnavailableException badKey = assertThrows(LlmUnavailableException.class, () -> gemini(5).complete("S", List.of(Message.user("q"))));
		assertEquals(Reason.AUTH, badKey.reason(), "Gemini reports a bad key as 400 API_KEY_INVALID");
		assertFalse(badKey.getMessage().contains(KEY));
		server.reply(400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}");
		assertEquals(Reason.BAD_RESPONSE, assertThrows(LlmUnavailableException.class, () -> gemini(5).complete("S", List.of(Message.user("q")))).reason());
	}

	@Test
	void timeoutIsReported() {
		server.slowReply(2500, textReply("late"));
		assertEquals(Reason.TIMEOUT, assertThrows(LlmUnavailableException.class, () -> gemini(1).complete("S", List.of(Message.user("q")))).reason());
	}

	@Test
	void blockedOrEmptyRepliesAreBadResponses() {
		server.reply(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
		assertEquals(Reason.BAD_RESPONSE, assertThrows(LlmUnavailableException.class, () -> gemini(5).complete("S", List.of(Message.user("q")))).reason());
		server.reply(200, "{\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"MAX_TOKENS\"}]}");
		assertEquals(Reason.BAD_RESPONSE, assertThrows(LlmUnavailableException.class, () -> gemini(5).complete("S", List.of(Message.user("q")))).reason());
		server.reply(200, "not json");
		assertEquals(Reason.BAD_RESPONSE, assertThrows(LlmUnavailableException.class, () -> gemini(5).complete("S", List.of(Message.user("q")))).reason());
	}

	@Test
	void notConfiguredWithoutKeyAndNeverCallsTheNetwork() {
		GeminiLlmProvider noKey = new GeminiLlmProvider(cfg(server.base(), "", "gemini-3-flash-preview", 5), mapper);
		assertFalse(noKey.configured());
		assertTrue(noKey.notConfiguredReason().contains("GEMINI_API_KEY"));
		assertEquals(Reason.NOT_CONFIGURED, assertThrows(LlmUnavailableException.class, () -> noKey.complete("S", List.of(Message.user("q")))).reason());
		GeminiLlmProvider badModel = new GeminiLlmProvider(cfg(server.base(), KEY, "../x?key=1", 5), mapper);
		assertFalse(badModel.configured(), "a model name cannot change the request path");
		assertTrue(server.requests.isEmpty());
	}
}
