package com.smarthome.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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

/** Primary/fallback behaviour of {@link LlmProviderOrchestrator}, with scripted providers and with the real HTTP clients. */
class LlmProviderOrchestratorTest {

	/** A provider that plays back scripted turns or failures and records what it was sent. */
	static final class FakeProvider implements LlmProvider {
		final String name;
		final boolean configured;
		final Deque<Object> script = new ArrayDeque<>();
		final List<List<Message>> received = new ArrayList<>();

		FakeProvider(String name, boolean configured) {
			this.name = name;
			this.configured = configured;
		}

		FakeProvider then(Object turnOrFailure) {
			script.add(turnOrFailure);
			return this;
		}

		int calls() {
			return received.size();
		}

		@Override
		public boolean configured() {
			return configured;
		}

		@Override
		public String provider() {
			return name;
		}

		@Override
		public String model() {
			return name + "-model";
		}

		@Override
		public String notConfiguredReason() {
			return configured ? "" : "No " + name + " key.";
		}

		@Override
		public boolean supportsTools() {
			return configured;
		}

		@Override
		public String complete(String system, List<Message> messages) throws LlmUnavailableException {
			return completeWithTools(system, messages, List.of()).text();
		}

		@Override
		public Turn completeWithTools(String system, List<Message> messages, List<ToolSpec> tools) throws LlmUnavailableException {
			if (!configured) {
				throw new LlmUnavailableException(Reason.NOT_CONFIGURED, "not configured");
			}
			received.add(List.copyOf(messages));
			Object next = script.isEmpty() ? new Turn(name + " answer", List.of()) : script.poll();
			if (next instanceof LlmUnavailableException e) {
				throw e;
			}
			return (Turn) next;
		}
	}

	private static LlmUnavailableException fail(Reason r) {
		return new LlmUnavailableException(r, "failure " + r);
	}

	private static final List<Message> ASK = List.of(Message.user("earlier"), Message.assistant("earlier answer"), Message.user("now?"));
	private static final List<ToolSpec> TOOLS = List.of(new ToolSpec("get_latest_readings", "d", Map.of("type", "object")));

	private final AtomicLong clock = new AtomicLong(0);
	private final FakeProvider gemini = new FakeProvider("gemini", true);
	private final FakeProvider groq = new FakeProvider("groq", true);

	private LlmProviderOrchestrator orchestrator(int cooldownSeconds) {
		return new LlmProviderOrchestrator(gemini, groq, cooldownSeconds, clock::get);
	}

	@Test
	void geminiSuccessDoesNotTouchGroq() throws Exception {
		LlmProviderOrchestrator o = orchestrator(60);
		assertEquals("gemini answer", o.complete("S", ASK));
		assertEquals(0, groq.calls());
		assertEquals("gemini", o.provider());
		assertEquals("gemini-model (fallback: groq groq-model)", o.model());
		assertTrue(o.configured() && o.supportsTools());
	}

	@Test
	void groqAloneWhenGeminiHasNoKey() throws Exception {
		FakeProvider noKey = new FakeProvider("gemini", false);
		LlmProviderOrchestrator o = new LlmProviderOrchestrator(noKey, groq, 60, clock::get);
		assertEquals("groq answer", o.complete("S", ASK));
		assertEquals("groq", o.provider());
		assertEquals(0, noKey.calls(), "an unconfigured provider is never called");
	}

	@Test
	void rateLimitFallsBackToGroq() throws Exception {
		gemini.then(fail(Reason.RATE_LIMITED));
		assertEquals("groq answer", orchestrator(60).complete("S", ASK));
		assertEquals(1, gemini.calls());
		assertEquals(1, groq.calls());
	}

	@Test
	void timeoutFallsBackToGroq() throws Exception {
		gemini.then(fail(Reason.TIMEOUT));
		assertEquals("groq answer", orchestrator(60).completeWithTools("S", ASK, TOOLS).text());
	}

	@Test
	void serverErrorAndNetworkErrorFallBack() throws Exception {
		gemini.then(fail(Reason.SERVER_ERROR)).then(fail(Reason.NETWORK));
		LlmProviderOrchestrator o = orchestrator(0);
		assertEquals("groq answer", o.complete("S", ASK));
		assertEquals("groq answer", o.complete("S", ASK));
		assertEquals(2, gemini.calls());
	}

	@Test
	void authAndBadRequestErrorsDoNotFallBack() {
		gemini.then(fail(Reason.AUTH)).then(fail(Reason.BAD_RESPONSE)).then(fail(Reason.MODEL_NOT_FOUND));
		LlmProviderOrchestrator o = orchestrator(60);
		assertEquals(Reason.AUTH, assertThrows(LlmUnavailableException.class, () -> o.complete("S", ASK)).reason());
		assertEquals(Reason.BAD_RESPONSE, assertThrows(LlmUnavailableException.class, () -> o.complete("S", ASK)).reason());
		assertEquals(Reason.MODEL_NOT_FOUND, assertThrows(LlmUnavailableException.class, () -> o.complete("S", ASK)).reason());
		assertEquals(0, groq.calls());
	}

	@Test
	void bothFailingIsOneControlledErrorAfterExactlyTwoCalls() {
		gemini.then(fail(Reason.SERVER_ERROR));
		groq.then(fail(Reason.RATE_LIMITED));
		LlmUnavailableException e = assertThrows(LlmUnavailableException.class, () -> orchestrator(60).complete("S", ASK));
		assertEquals(Reason.RATE_LIMITED, e.reason());
		assertTrue(e.getMessage().startsWith("Both AI providers failed."), e.getMessage());
		assertTrue(e.getMessage().contains("Gemini") && e.getMessage().contains("Groq"));
		assertEquals(1, gemini.calls());
		assertEquals(1, groq.calls(), "no retries beyond one fallback attempt");
	}

	@Test
	void afterARecoverableFailureGeminiIsSkippedForTheCooldownThenTriedAgain() throws Exception {
		gemini.then(fail(Reason.RATE_LIMITED));
		LlmProviderOrchestrator o = orchestrator(60);
		o.complete("S", ASK);
		clock.addAndGet(30_000_000_000L);
		assertEquals("groq answer", o.complete("S", ASK));
		assertEquals(1, gemini.calls(), "rate-limited Gemini is not hammered");
		clock.addAndGet(31_000_000_000L);
		assertEquals("gemini answer", o.complete("S", ASK));
		assertEquals(2, gemini.calls());
	}

	@Test
	void conversationContextReachesTheFallbackUnchanged() throws Exception {
		gemini.then(fail(Reason.TIMEOUT));
		orchestrator(60).completeWithTools("S", ASK, TOOLS);
		assertEquals(ASK, groq.received.get(0));
	}

	@Test
	void failoverMidToolConversationNeverRepeatsATool() throws Exception {
		// Round 1: Gemini asks for a tool. The caller (AssistantService) runs it once and adds the result.
		ToolCall call = new ToolCall("call_1", "get_latest_readings", "{}", "SIG");
		gemini.then(new Turn("", List.of(call))).then(fail(Reason.SERVER_ERROR));
		LlmProviderOrchestrator o = orchestrator(60);
		List<Message> msgs = new ArrayList<>(ASK);
		Turn first = o.completeWithTools("S", msgs, TOOLS);
		int toolRuns = 0;
		for (ToolCall c : first.toolCalls()) {
			toolRuns++;
			msgs.add(Message.assistantCalls(first.text(), List.of(c)));
			msgs.add(Message.toolResult(c.id(), c.name(), "[S1] 21 C"));
		}
		// Round 2: Gemini fails; Groq gets the call and its result and answers from them.
		Turn second = o.completeWithTools("S", msgs, TOOLS);
		assertEquals("groq answer", second.text());
		assertEquals(1, toolRuns);
		List<Message> seen = groq.received.get(0);
		assertEquals("call_1", seen.get(3).toolCalls().get(0).id());
		assertEquals("tool", seen.get(4).role());
		assertEquals("call_1", seen.get(4).toolCallId());
	}

	@Test
	void nothingConfiguredExplainsBoth() {
		LlmProviderOrchestrator o = new LlmProviderOrchestrator(new FakeProvider("gemini", false), new FakeProvider("groq", false), 60, clock::get);
		assertFalse(o.configured());
		assertTrue(o.notConfiguredReason().contains("gemini") && o.notConfiguredReason().contains("groq"));
		assertEquals(Reason.NOT_CONFIGURED, assertThrows(LlmUnavailableException.class, () -> o.complete("S", ASK)).reason());
	}

	@Test
	void noFallbackMeansThePrimaryErrorIsThrownAsIs() {
		gemini.then(fail(Reason.RATE_LIMITED));
		LlmProviderOrchestrator o = new LlmProviderOrchestrator(gemini, null, 60, clock::get);
		assertEquals(Reason.RATE_LIMITED, assertThrows(LlmUnavailableException.class, () -> o.complete("S", ASK)).reason());
	}

	// ---- the real HTTP providers end to end: Gemini 429 -> Groq, with tools -------------------------

	@Test
	void realClientsGemini429ThenGroqAnswersWithTheSameConversation() throws Exception {
		ObjectMapper mapper = JsonMapper.builder().build();
		try (StubModelServer g = new StubModelServer(); StubModelServer q = new StubModelServer()) {
			g.reply(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}");
			q.reply(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_q1\",\"type\":\"function\","
					+ "\"function\":{\"name\":\"get_latest_readings\",\"arguments\":\"{\\\"device\\\":\\\"kitchen\\\"}\"}}]}}]}");
			Llm llm = new Llm("gemini", "", "", "", 5, 700, 0.2, "gemini", "groq", 60, new Gemini("gkey", "gemini-3-flash-preview", g.base(), 2048, "low"),
					new Groq("qkey", "openai/gpt-oss-120b", q.base() + "/openai/v1"));
			LlmProviderOrchestrator o = new LlmProviderOrchestrator(new GeminiLlmProvider(llm, mapper), new GroqLlmProvider(llm, mapper), 60);

			Turn t = o.completeWithTools("SYSTEM", ASK, TOOLS);

			assertEquals(1, g.requests.size());
			assertEquals(1, q.requests.size());
			assertEquals("get_latest_readings", t.toolCalls().get(0).name());
			assertEquals("/openai/v1/chat/completions", q.last().path());
			assertEquals("Bearer qkey", q.last().headers().getFirst("Authorization"));
			JsonNode req = mapper.readTree(q.last().body());
			assertEquals("openai/gpt-oss-120b", req.get("model").asString());
			assertEquals("SYSTEM", req.at("/messages/0/content").asString());
			assertEquals("now?", req.at("/messages/3/content").asString(), "the whole conversation is replayed to the fallback");
			assertEquals("get_latest_readings", req.at("/tools/0/function/name").asString());
			assertFalse(q.last().body().contains("gkey"), "the Gemini key never goes to Groq");
		}
	}

	@Test
	void realGroqProviderSuccess() throws Exception {
		ObjectMapper mapper = JsonMapper.builder().build();
		try (StubModelServer q = new StubModelServer()) {
			q.reply(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Groq says hi\"}}]}");
			Llm llm = new Llm("groq", "", "", "", 5, 700, 0.2, "groq", "none", 60, new Gemini("", "gemini-3-flash-preview", "", 2048, "low"),
					new Groq("qkey", "openai/gpt-oss-120b", q.base() + "/openai/v1"));
			GroqLlmProvider p = new GroqLlmProvider(llm, mapper);
			assertSame("groq", p.provider());
			assertEquals("Groq says hi", p.complete("S", ASK));
			assertTrue(new GroqLlmProvider(new Llm("groq", "", "", "", 5, 700, 0.2), mapper).notConfiguredReason().contains("GROQ_API_KEY"));
		}
	}
}
