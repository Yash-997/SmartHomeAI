package com.smarthome.assistant;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.smarthome.assistant.AssistantProperties.Gemini;
import com.smarthome.assistant.AssistantProperties.Llm;
import com.smarthome.assistant.LlmUnavailableException.Reason;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Google Gemini through its native REST API: {@code POST {base}/models/{model}:generateContent} with the key in the
 * {@code x-goog-api-key} header (never in the URL, so it cannot end up in an access log).
 *
 * <p>Mapping from the assistant's conversation: the system text becomes {@code systemInstruction}; user turns are
 * {@code role:user}; assistant turns are {@code role:model}; an assistant turn that asked for tools becomes
 * {@code functionCall} parts (each carrying back the {@code thoughtSignature} Gemini gave it); the tool results that
 * follow become one {@code role:user} turn of {@code functionResponse} parts. Tools are declared with
 * {@code parametersJsonSchema}, which accepts the registry's JSON Schema as it is (including
 * {@code additionalProperties}).
 */
public class GeminiLlmProvider implements LlmProvider {

	static final String DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta";
	static final String DEFAULT_MODEL = "gemini-3-flash-preview";
	/**
	 * Google's documented placeholder for a function call that has no signature of its own (for example one made by
	 * the fallback provider earlier in the same conversation). Gemini 3 rejects unsigned calls in the current turn.
	 */
	static final String SKIP_SIGNATURE = "skip_thought_signature_validator";
	private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

	private final Llm llm;
	private final Gemini cfg;
	private final ObjectMapper mapper;

	public GeminiLlmProvider(Llm llm, ObjectMapper mapper) {
		this.llm = llm;
		this.cfg = llm.gemini();
		this.mapper = mapper;
	}

	@Override
	public String provider() {
		return "gemini";
	}

	@Override
	public String model() {
		String m = cfg.model() == null ? "" : cfg.model().trim();
		if (m.startsWith("models/")) {
			m = m.substring("models/".length());
		}
		return m;
	}

	private String base() {
		String b = cfg.baseUrl() == null ? "" : cfg.baseUrl().trim();
		return b.isEmpty() ? DEFAULT_BASE : b;
	}

	@Override
	public String notConfiguredReason() {
		if (model().isEmpty()) {
			return "No Gemini model is set (GEMINI_MODEL).";
		}
		if (!MODEL_NAME.matcher(model()).matches()) {
			return "The Gemini model name (GEMINI_MODEL) contains characters that are not allowed.";
		}
		if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
			return "No Gemini API key is set (GEMINI_API_KEY).";
		}
		return "";
	}

	@Override
	public boolean configured() {
		return notConfiguredReason().isEmpty();
	}

	@Override
	public boolean supportsTools() {
		return configured();
	}

	@Override
	public String complete(String system, List<Message> messages) throws LlmUnavailableException {
		Turn t = extractTurn(post(body(system, messages, List.of())));
		if (t.text() == null || t.text().isBlank()) {
			throw new LlmUnavailableException(Reason.BAD_RESPONSE, "The language model returned an empty answer.");
		}
		return t.text();
	}

	@Override
	public Turn completeWithTools(String system, List<Message> messages, List<ToolSpec> tools) throws LlmUnavailableException {
		Turn t = extractTurn(post(body(system, messages, tools)));
		if ((t.text() == null || t.text().isBlank()) && !t.wantsTools()) {
			throw new LlmUnavailableException(Reason.BAD_RESPONSE, "The language model returned an empty answer.");
		}
		return t;
	}

	// ---- request -----------------------------------------------------------------------------------

	String body(String system, List<Message> messages, List<ToolSpec> tools) {
		List<Map<String, Object>> contents = new ArrayList<>();
		List<Map<String, Object>> pendingResults = new ArrayList<>();
		for (Message m : messages) {
			if ("tool".equals(m.role())) {
				Map<String, Object> fr = new LinkedHashMap<>();
				fr.put("name", m.toolName() == null ? "" : m.toolName());
				fr.put("response", Map.of("result", m.content() == null ? "" : m.content()));
				pendingResults.add(Map.of("functionResponse", fr));
				continue;
			}
			if (!pendingResults.isEmpty()) {
				contents.add(Map.of("role", "user", "parts", new ArrayList<>(pendingResults)));
				pendingResults.clear();
			}
			boolean assistant = "assistant".equals(m.role());
			List<Map<String, Object>> parts = new ArrayList<>();
			if (m.content() != null && !m.content().isBlank()) {
				parts.add(Map.of("text", m.content()));
			}
			if (assistant && m.toolCalls() != null && !m.toolCalls().isEmpty()) {
				boolean anySigned = m.toolCalls().stream().anyMatch(c -> c.signature() != null && !c.signature().isBlank());
				boolean first = true;
				for (ToolCall c : m.toolCalls()) {
					Map<String, Object> part = new LinkedHashMap<>();
					part.put("functionCall", Map.of("name", c.name(), "args", args(c.arguments())));
					if (c.signature() != null && !c.signature().isBlank()) {
						part.put("thoughtSignature", c.signature());
					} else if (!anySigned && first) {
						part.put("thoughtSignature", SKIP_SIGNATURE);   // a call Gemini did not make (e.g. from the fallback provider)
					}
					parts.add(part);
					first = false;
				}
			}
			if (parts.isEmpty()) {
				parts.add(Map.of("text", ""));
			}
			contents.add(Map.of("role", assistant ? "model" : "user", "parts", parts));
		}
		if (!pendingResults.isEmpty()) {
			contents.add(Map.of("role", "user", "parts", new ArrayList<>(pendingResults)));
		}

		Map<String, Object> req = new LinkedHashMap<>();
		if (system != null && !system.isBlank()) {
			req.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
		}
		req.put("contents", contents);
		if (!tools.isEmpty()) {
			List<Map<String, Object>> decls = new ArrayList<>();
			for (ToolSpec t : tools) {
				decls.add(Map.of("name", t.name(), "description", t.description(), "parametersJsonSchema", t.parameters()));
			}
			req.put("tools", List.of(Map.of("functionDeclarations", decls)));
			req.put("toolConfig", Map.of("functionCallingConfig", Map.of("mode", "AUTO")));
		}
		Map<String, Object> gen = new LinkedHashMap<>();
		gen.put("maxOutputTokens", Math.max(llm.maxOutputTokens(), cfg.maxOutputTokens()));
		if (gemini3()) {
			// Google advises leaving Gemini 3 at its default temperature (lower values can make it loop).
			String level = cfg.thinkingLevel() == null ? "" : cfg.thinkingLevel().trim().toLowerCase(Locale.ROOT);
			if (!level.isEmpty()) {
				gen.put("thinkingConfig", Map.of("thinkingLevel", level));
			}
		} else {
			gen.put("temperature", llm.temperature());
		}
		req.put("generationConfig", gen);
		return mapper.writeValueAsString(req);
	}

	private boolean gemini3() {
		return model().toLowerCase(Locale.ROOT).startsWith("gemini-3");
	}

	private Object args(String json) {
		try {
			Object v = mapper.readValue(json == null || json.isBlank() ? "{}" : json, Map.class);
			return v == null ? Map.of() : v;
		} catch (RuntimeException e) {
			return Map.of();
		}
	}

	private String post(String body) throws LlmUnavailableException {
		if (!configured()) {
			throw new LlmUnavailableException(Reason.NOT_CONFIGURED, "The language model is not configured. " + notConfiguredReason());
		}
		URI uri = ModelHttp.uri(base(), "/models/" + model() + ":generateContent", "language model");
		Duration timeout = Duration.ofSeconds(Math.max(1, llm.timeoutSeconds()));
		Map<String, String> headers = Map.of("x-goog-api-key", cfg.apiKey().trim());
		ModelHttp.Response r = ModelHttp.postJson(uri, headers, body, timeout, "language model");
		if (r.status() / 100 != 2) {
			// Gemini answers a bad key with 400 INVALID_ARGUMENT / API_KEY_INVALID rather than 401.
			if (r.status() == 400 && r.body() != null && r.body().contains("API_KEY_INVALID")) {
				throw LlmUnavailableException.fromStatus(401, "language model");
			}
			throw LlmUnavailableException.fromStatus(r.status(), "language model");
		}
		return r.body();
	}

	// ---- response ----------------------------------------------------------------------------------

	Turn extractTurn(String json) throws LlmUnavailableException {
		JsonNode root;
		try {
			root = mapper.readTree(json);
		} catch (RuntimeException e) {
			throw new LlmUnavailableException(Reason.BAD_RESPONSE, "The language model sent a reply that could not be read.");
		}
		JsonNode candidate = root.path("candidates").path(0);
		if (candidate.isMissingNode()) {
			if (!root.path("promptFeedback").path("blockReason").isMissingNode()) {
				throw new LlmUnavailableException(Reason.BAD_RESPONSE, "The language model declined to answer this request.");
			}
			throw new LlmUnavailableException(Reason.BAD_RESPONSE, "The language model sent a reply that could not be read.");
		}
		try {
			StringBuilder text = new StringBuilder();
			List<ToolCall> calls = new ArrayList<>();
			for (JsonNode part : candidate.path("content").path("parts")) {
				JsonNode fc = part.path("functionCall");
				if (!fc.isMissingNode() && fc.isObject()) {
					String id = fc.path("id").asString("");
					if (id.isBlank()) {
						id = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
					}
					JsonNode a = fc.path("args");
					String sig = part.path("thoughtSignature").asString("");
					calls.add(new ToolCall(id, fc.path("name").asString(""), a.isObject() ? mapper.writeValueAsString(a) : "{}", sig.isBlank() ? null : sig));
				} else if (part.has("text") && !part.path("thought").asBoolean(false)) {
					text.append(part.path("text").asString(""));
				}
			}
			return new Turn(text.toString(), calls);
		} catch (RuntimeException e) {
			throw new LlmUnavailableException(Reason.BAD_RESPONSE, "The language model sent a reply that could not be read.");
		}
	}
}
