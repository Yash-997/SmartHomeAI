package com.smarthome.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.smarthome.assistant.AssistantProperties.Embedding;
import com.smarthome.assistant.AssistantProperties.Llm;
import com.smarthome.assistant.LlmClient.Message;
import com.smarthome.assistant.LlmUnavailableException.Reason;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real HTTP clients against a throwaway local server that speaks the OpenAI and Anthropic formats. This proves the
 * request shape, headers and error handling; it does not prove anything about any real provider's model quality.
 */
class HttpModelClientsTest {

	private static final String KEY = "sk-test-key-do-not-leak-0123456789";
	private final ObjectMapper mapper = JsonMapper.builder().build();
	private HttpServer server;
	private volatile int status = 200;
	private volatile String reply = "";
	private volatile long delayMs = 0;
	private final AtomicReference<String> path = new AtomicReference<>();
	private final AtomicReference<String> body = new AtomicReference<>();
	private final AtomicReference<com.sun.net.httpserver.Headers> headers = new AtomicReference<>();

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", ex -> {
			path.set(ex.getRequestURI().getPath());
			body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			headers.set(ex.getRequestHeaders());
			if (delayMs > 0) {
				try {
					Thread.sleep(delayMs);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			}
			byte[] out = reply.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "application/json");
			try {
				ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
				if (out.length > 0) {
					ex.getResponseBody().write(out);
				}
			} catch (IOException ignored) {
				// the client gave up (timeout test)
			}
			ex.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private String base() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private HttpLlmClient openAi(String key, String model, int timeout) {
		return new HttpLlmClient(new Llm("openai", base() + "/v1", key, model, timeout, 300, 0.1), mapper);
	}

	private HttpLlmClient anthropic(String key, String model) {
		return new HttpLlmClient(new Llm("anthropic", base(), key, model, 5, 300, 0.1), mapper);
	}

	private static final List<Message> ASK = List.of(Message.user("hello"));

	// ---- OpenAI-compatible ---------------------------------------------------------------------

	@Test
	void openAiRequestHasTheRightShapeAndKeyOnlyInTheAuthorizationHeader() throws Exception {
		reply = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hi there [S1]\"}}]}";
		String text = openAi(KEY, "llama3", 5).complete("SYSTEM TEXT", ASK);

		assertEquals("Hi there [S1]", text);
		assertEquals("/v1/chat/completions", path.get());
		assertEquals("Bearer " + KEY, headers.get().getFirst("Authorization"));
		JsonNode req = mapper.readTree(body.get());
		assertEquals("llama3", req.get("model").asString());
		assertEquals("system", req.get("messages").get(0).get("role").asString());
		assertEquals("SYSTEM TEXT", req.get("messages").get(0).get("content").asString());
		assertEquals("hello", req.get("messages").get(1).get("content").asString());
		assertFalse(body.get().contains(KEY), "the key must never be in the request body");
	}

	@Test
	void aLocalServerWithoutAKeyIsAccepted() throws Exception {
		reply = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
		HttpLlmClient c = openAi("", "llama3", 5);
		assertTrue(c.configured());
		assertEquals("ok", c.complete("s", ASK));
		assertEquals(null, headers.get().getFirst("Authorization"));
	}

	@Test
	void withoutAModelOrAKeyForTheHostedDefaultTheClientIsNotConfigured() {
		assertFalse(openAi("", "", 5).configured());
		assertTrue(openAi("", "", 5).notConfiguredReason().contains("LLM_MODEL"));
		HttpLlmClient hosted = new HttpLlmClient(new Llm("openai", "", "", "gpt-x", 5, 300, 0.1), mapper);
		assertFalse(hosted.configured());
		assertTrue(hosted.notConfiguredReason().contains("LLM_API_KEY"));
		assertEquals(Reason.NOT_CONFIGURED, assertThrows(LlmUnavailableException.class, () -> hosted.complete("s", ASK)).reason());
	}

	// ---- Anthropic -----------------------------------------------------------------------------

	@Test
	void anthropicRequestUsesTheMessagesFormat() throws Exception {
		reply = "{\"content\":[{\"type\":\"text\",\"text\":\"Part one. \"},{\"type\":\"tool_use\",\"id\":\"x\"},{\"type\":\"text\",\"text\":\"Part two.\"}]}";
		HttpLlmClient c = anthropic(KEY, "");
		assertEquals(HttpLlmClient.ANTHROPIC_DEFAULT_MODEL, c.model());
		assertEquals("Part one. Part two.", c.complete("SYS", ASK));

		assertEquals("/v1/messages", path.get());
		assertEquals(KEY, headers.get().getFirst("x-api-key"));
		assertEquals("2023-06-01", headers.get().getFirst("anthropic-version"));
		JsonNode req = mapper.readTree(body.get());
		assertEquals("SYS", req.get("system").asString());
		assertEquals("user", req.get("messages").get(0).get("role").asString());
		assertEquals(300, req.get("max_tokens").asInt());
		assertFalse(body.get().contains(KEY));
	}

	@Test
	void anthropicNeedsAKey() {
		assertFalse(anthropic("", "m").configured());
	}

	// ---- failures ------------------------------------------------------------------------------

	@Test
	void httpErrorsBecomeSafeReasonsWithoutLeakingTheKeyOrTheServerBody() {
		record Case(int status, Reason reason) {
		}
		for (Case c : List.of(new Case(401, Reason.AUTH), new Case(403, Reason.AUTH), new Case(404, Reason.MODEL_NOT_FOUND),
				new Case(429, Reason.RATE_LIMITED), new Case(500, Reason.SERVER_ERROR), new Case(503, Reason.SERVER_ERROR), new Case(400, Reason.BAD_RESPONSE))) {
			status = c.status();
			reply = "{\"error\":\"echo of " + KEY + " at secret-internal-host\"}";
			LlmUnavailableException e = assertThrows(LlmUnavailableException.class, () -> openAi(KEY, "m", 5).complete("s", ASK), "status " + c.status());
			assertEquals(c.reason(), e.reason(), "status " + c.status());
			assertFalse(e.getMessage().contains(KEY));
			assertFalse(e.getMessage().contains("secret-internal-host"));
		}
	}

	@Test
	void aSlowServerTimesOut() {
		delayMs = 2500;
		reply = "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}";
		LlmUnavailableException e = assertThrows(LlmUnavailableException.class, () -> openAi(KEY, "m", 1).complete("s", ASK));
		assertEquals(Reason.TIMEOUT, e.reason());
	}

	@Test
	void anUnreachableServerIsANetworkErrorThatDoesNotNameTheAddress() throws IOException {
		int port;
		try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
			port = s.getLocalPort();      // closed again straight away, so nothing listens there
		}
		HttpLlmClient c = new HttpLlmClient(new Llm("openai", "http://127.0.0.1:" + port + "/v1", "", "m", 2, 300, 0.1), mapper);
		LlmUnavailableException e = assertThrows(LlmUnavailableException.class, () -> c.complete("s", ASK));
		assertEquals(Reason.NETWORK, e.reason());
		assertFalse(e.getMessage().contains("127.0.0.1"));
	}

	@Test
	void garbageOrEmptyRepliesAreBadResponsesNotAnswers() {
		for (String bad : List.of("not json", "{}", "{\"choices\":[]}", "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}")) {
			reply = bad;
			LlmUnavailableException e = assertThrows(LlmUnavailableException.class, () -> openAi(KEY, "m", 5).complete("s", ASK), bad);
			assertEquals(Reason.BAD_RESPONSE, e.reason(), bad);
		}
	}

	@Test
	void aNonHttpAddressIsRejectedBeforeAnyRequest() {
		HttpLlmClient c = new HttpLlmClient(new Llm("openai", "file:///etc/passwd", "k", "m", 2, 300, 0.1), mapper);
		assertEquals(Reason.NOT_CONFIGURED, assertThrows(LlmUnavailableException.class, () -> c.complete("s", ASK)).reason());
		assertEquals(null, path.get());
	}

	// ---- embeddings ----------------------------------------------------------------------------

	private HttpEmbeddingClient embeddings(String model) {
		return new HttpEmbeddingClient(new Embedding("openai", base() + "/v1", "", model, 5), new Llm("openai", "", KEY, "", 5, 300, 0.1), mapper);
	}

	@Test
	void embeddingsAreReturnedInInputOrderEvenIfTheServerShufflesThem() throws Exception {
		reply = "{\"data\":[{\"index\":1,\"embedding\":[0.0,1.0]},{\"index\":0,\"embedding\":[1.0,0.0]}]}";
		List<float[]> v = embeddings("embed-small").embed(List.of("first", "second"));
		assertEquals(2, v.size());
		assertEquals(1.0f, v.get(0)[0]);
		assertEquals(1.0f, v.get(1)[1]);
		assertEquals("/v1/embeddings", path.get());
		assertEquals("Bearer " + KEY, headers.get().getFirst("Authorization"), "falls back to the LLM key");
		JsonNode req = mapper.readTree(body.get());
		assertEquals("embed-small", req.get("model").asString());
		assertEquals(2, req.get("input").size());
		assertEquals("openai:embed-small", embeddings("embed-small").modelId());
	}

	@Test
	void embeddingFailuresAreSafeToo() {
		reply = "{\"data\":[{\"index\":0,\"embedding\":[1.0]}]}";
		LlmUnavailableException wrongCount = assertThrows(LlmUnavailableException.class, () -> embeddings("m").embed(List.of("a", "b")));
		assertEquals(Reason.BAD_RESPONSE, wrongCount.reason());
		assertEquals(Reason.NOT_CONFIGURED, assertThrows(LlmUnavailableException.class, () -> embeddings("").embed(List.of("a"))).reason());
		status = 401;
		LlmUnavailableException auth = assertThrows(LlmUnavailableException.class, () -> embeddings("m").embed(List.of("a")));
		assertEquals(Reason.AUTH, auth.reason());
		assertNotNull(auth.getMessage());
	}
}
