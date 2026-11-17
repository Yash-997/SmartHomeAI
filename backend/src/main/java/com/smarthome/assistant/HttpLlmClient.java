package com.smarthome.assistant;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.smarthome.assistant.AssistantProperties.Llm;
import com.smarthome.assistant.LlmUnavailableException.Reason;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The real language-model client: the OpenAI chat-completions format (also spoken by Ollama, LM Studio, OpenRouter and
 * others) or the Anthropic Messages format. The API key only ever goes into a request header.
 */
public class HttpLlmClient implements LlmProvider {

	static final String ANTHROPIC_DEFAULT_MODEL = "claude-sonnet-5-5";
	private static final String OPENAI_DEFAULT_BASE = "https://api.openai.com/v1";
	private static final String ANTHROPIC_DEFAULT_BASE = "https://api.anthropic.com";
	private static final String GROQ_DEFAULT_BASE = "https://api.groq.com/openai/v1";

	private final Llm cfg;
	private final ObjectMapper mapper;
	private final boolean anthropic;
	private final boolean groq;

	public HttpLlmClient(Llm cfg, ObjectMapper mapper) {
		this.cfg = cfg;
		this.mapper = mapper;
		this.anthropic = "anthropic".equalsIgnoreCase(cfg.provider());
		this.groq = "groq".equalsIgnoreCase(cfg.provider());
	}

	@Override
	public String provider() {
		return anthropic ? "anthropic" : groq ? "groq" : "openai";
	}

	@Override
	public String model() {
		String m = cfg.model() == null ? "" : cfg.model().trim();
		return m.isEmpty() && anthropic ? ANTHROPIC_DEFAULT_MODEL : m;
	}

	private String base() {
		String b = cfg.baseUrl() == null ? "" : cfg.baseUrl().trim();
		return b.isEmpty() ? (anthropic ? ANTHROPIC_DEFAULT_BASE : groq ? GROQ_DEFAULT_BASE : OPENAI_DEFAULT_BASE) : b;
	}

	private boolean hasKey() {
		return cfg.apiKey() != null && !cfg.apiKey().isBlank();
	}

	@Override
	public String notConfiguredReason() {
		if (model().isEmpty()) {
			return "No model name is set (LLM_MODEL).";
		}
		if ((anthropic || groq) && !hasKey()) {
			return groq ? "No API key is set (GROQ_API_KEY)." : "No API key is set (LLM_API_KEY).";
		}
		if (!anthropic && !hasKey() && base().equals(OPENAI_DEFAULT_BASE)) {
			return "No API key is set (LLM_API_KEY).";
		}
		return "";
	}

	@Override
	public boolean configured() {
		return notConfiguredReason().isEmpty();
	}

	@Override
	public String complete(String system, List<Message> messages) throws LlmUnavailableException {
		String text = extract(post(anthropic ? anthropicBody(system, messages) : openAiBody(system, messages)));
		if (text == null || text.isBlank()) {
			throw new LlmUnavailableException(Reason.BAD_RESPONSE, "The language model returned an empty answer.");
		}
		return text;
	}

	@Override
	public boolean supportsTools() {
		return configured();
	}

	@Override
	public Turn completeWithTools(String system, List<Message> messages, List<ToolSpec> tools) throws LlmUnavailableException {
		String body = anthropic ? anthropicToolBody(system, messages, tools) : openAiToolBody(system, messages, tools);
		Turn t = extractTurn(post(body));
		if ((t.text() == null || t.text().isBlank()) && !t.wantsTools()) {
			throw new LlmUnavailableException(Reason.BAD_RESPONSE, "The language model returned an empty answer.");
		}
		return t;
	}

	/** One request to the provider; returns the 2xx body or throws a safe exception. */
	private String post(String body) throws LlmUnavailableException {
		if (!configured()) {
			throw new LlmUnavailableException(Reason.NOT_CONFIGURED, "The language model is not configured. " + notConfiguredReason());
		}
		Duration timeout = Duration.ofSeconds(Math.max(1, cfg.timeoutSeconds()));
		Map<String, String> headers = new LinkedHashMap<>();
		java.net.URI uri;
		if (anthropic) {
			uri = ModelHttp.uri(base(), "/v1/messages", "language model");
			headers.put("x-api-key", cfg.apiKey().trim());
			headers.put("anthropic-version", "2023-06-01");
		} else {
			uri = ModelHttp.uri(base(), "/chat/completions", "language model");
			if (hasKey()) {
				headers.put("Authorization", "Bearer " + cfg.apiKey().trim());
			}
		}
		ModelHttp.Response r = ModelHttp.postJson(uri, headers, body, timeout, "language model");
		if (r.status() / 100 != 2) {
			throw LlmUnavailableException.fromStatus(r.status(), "language model");
		}
		return r.body();
	}

	// ---- tool calling ------------------------------------------------------------------------------

	private String openAiToolBody(String system, List<Message> messages, List<ToolSpec> tools) {
		List<Map<String, Object>> all = new ArrayList<>();
		all.add(Map.of("role", "system", "content", system));
		for (Message m : messages) {
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("role", m.role());
			if ("tool".equals(m.role())) {
				o.put("tool_call_id", m.toolCallId());
				o.put("content", m.content());
			} else if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
				o.put("content", m.content() == null ? "" : m.content());
				List<Map<String, Object>> calls = new ArrayList<>();
				for (ToolCall c : m.toolCalls()) {
					calls.add(Map.of("id", c.id(), "type", "function", "function", Map.of("name", c.name(), "arguments", c.arguments())));
				}
				o.put("tool_calls", calls);
			} else {
				o.put("content", m.content());
			}
			all.add(o);
		}
		List<Map<String, Object>> fns = new ArrayList<>();
		for (ToolSpec t : tools) {
			fns.add(Map.of("type", "function", "function", Map.of("name", t.name(), "description", t.description(), "parameters", t.parameters())));
		}
		Map<String, Object> req = new LinkedHashMap<>();
		req.put("model", model());
		req.put("messages", all);
		if (!fns.isEmpty()) {
			req.put("tools", fns);
			req.put("tool_choice", "auto");
		}
		req.put("temperature", cfg.temperature());
		req.put("max_tokens", cfg.maxOutputTokens());
		return mapper.writeValueAsString(req);
	}

	private String anthropicToolBody(String system, List<Message> messages, List<ToolSpec> tools) {
		List<Map<String, Object>> all = new ArrayList<>();
		List<Map<String, Object>> pendingResults = new ArrayList<>();
		for (Message m : messages) {
			if ("tool".equals(m.role())) {
				pendingResults.add(Map.of("type", "tool_result", "tool_use_id", m.toolCallId(), "content", m.content()));
				continue;
			}
			if (!pendingResults.isEmpty()) {
				all.add(Map.of("role", "user", "content", new ArrayList<>(pendingResults)));
				pendingResults.clear();
			}
			if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
				List<Map<String, Object>> blocks = new ArrayList<>();
				if (m.content() != null && !m.content().isBlank()) {
					blocks.add(Map.of("type", "text", "text", m.content()));
				}
				for (ToolCall c : m.toolCalls()) {
					Object input;
					try {
						input = mapper.readValue(c.arguments() == null || c.arguments().isBlank() ? "{}" : c.arguments(), Map.class);
					} catch (RuntimeException e) {
						input = Map.of();
					}
					blocks.add(Map.of("type", "tool_use", "id", c.id(), "name", c.name(), "input", input));
				}
				all.add(Map.of("role", "assistant", "content", blocks));
			} else {
				all.add(Map.of("role", m.role(), "content", m.content()));
			}
		}
		if (!pendingResults.isEmpty()) {
			all.add(Map.of("role", "user", "content", new ArrayList<>(pendingResults)));
		}
		List<Map<String, Object>> fns = new ArrayList<>();
		for (ToolSpec t : tools) {
			fns.add(Map.of("name", t.name(), "description", t.description(), "input_schema", t.parameters()));
		}
		Map<String, Object> req = new LinkedHashMap<>();
		req.put("model", model());
		req.put("system", system);
		req.put("messages", all);
		if (!fns.isEmpty()) {
			req.put("tools", fns);
		}
		req.put("max_tokens", cfg.maxOutputTokens());
		req.put("temperature", cfg.temperature());
		return mapper.writeValueAsString(req);
	}

	private Turn extractTurn(String json) throws LlmUnavailableException {
		try {
			JsonNode root = mapper.readTree(json);
			List<ToolCall> calls = new ArrayList<>();
			if (anthropic) {
				StringBuilder sb = new StringBuilder();
				for (JsonNode part : root.path("content")) {
					String type = part.path("type").asString("");
					if ("text".equals(type)) {
						sb.append(part.path("text").asString(""));
					} else if ("tool_use".equals(type)) {
						calls.add(new ToolCall(part.path("id").asString(""), part.path("name").asString(""), mapper.writeValueAsString(part.path("input"))));
					}
				}
				return new Turn(sb.toString(), calls);
			}
			JsonNode msg = root.path("choices").path(0).path("message");
			for (JsonNode c : msg.path("tool_calls")) {
				JsonNode fn = c.path("function");
				calls.add(new ToolCall(c.path("id").asString(""), fn.path("name").asString(""), fn.path("arguments").asString("{}")));
			}
			return new Turn(msg.path("content").asString(""), calls);
		} catch (RuntimeException e) {
			throw new LlmUnavailableException(Reason.BAD_RESPONSE, "The language model sent a reply that could not be read.");
		}
	}

	private String openAiBody(String system, List<Message> messages) {
		List<Map<String, String>> all = new ArrayList<>();
		all.add(Map.of("role", "system", "content", system));
		messages.forEach(m -> all.add(Map.of("role", m.role(), "content", m.content())));
		Map<String, Object> req = new LinkedHashMap<>();
		req.put("model", model());
		req.put("messages", all);
		req.put("temperature", cfg.temperature());
		req.put("max_tokens", cfg.maxOutputTokens());
		return mapper.writeValueAsString(req);
	}

	private String anthropicBody(String system, List<Message> messages) {
		List<Map<String, String>> all = new ArrayList<>();
		messages.forEach(m -> all.add(Map.of("role", m.role(), "content", m.content())));
		Map<String, Object> req = new LinkedHashMap<>();
		req.put("model", model());
		req.put("system", system);
		req.put("messages", all);
		req.put("max_tokens", cfg.maxOutputTokens());
		req.put("temperature", cfg.temperature());
		return mapper.writeValueAsString(req);
	}

	/** Pulls the answer text out of either response format; anything unexpected is a BAD_RESPONSE, never a guess. */
	private String extract(String json) throws LlmUnavailableException {
		try {
			JsonNode root = mapper.readTree(json);
			if (anthropic) {
				StringBuilder sb = new StringBuilder();
				for (JsonNode part : root.path("content")) {
					if ("text".equals(part.path("type").asString(""))) {
						sb.append(part.path("text").asString(""));
					}
				}
				return sb.toString();
			}
			return root.path("choices").path(0).path("message").path("content").asString("");
		} catch (RuntimeException e) {
			throw new LlmUnavailableException(Reason.BAD_RESPONSE, "The language model sent a reply that could not be read.");
		}
	}
}
