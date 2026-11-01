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
public class HttpLlmClient implements LlmClient {

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
		if (!configured()) {
			throw new LlmUnavailableException(Reason.NOT_CONFIGURED, "The language model is not configured. " + notConfiguredReason());
		}
		Duration timeout = Duration.ofSeconds(Math.max(1, cfg.timeoutSeconds()));
		Map<String, String> headers = new LinkedHashMap<>();
		String body;
		java.net.URI uri;
		if (anthropic) {
			uri = ModelHttp.uri(base(), "/v1/messages", "language model");
			headers.put("x-api-key", cfg.apiKey().trim());
			headers.put("anthropic-version", "2023-06-01");
			body = anthropicBody(system, messages);
		} else {
			uri = ModelHttp.uri(base(), "/chat/completions", "language model");
			if (hasKey()) {
				headers.put("Authorization", "Bearer " + cfg.apiKey().trim());
			}
			body = openAiBody(system, messages);
		}
		ModelHttp.Response r = ModelHttp.postJson(uri, headers, body, timeout, "language model");
		if (r.status() / 100 != 2) {
			throw LlmUnavailableException.fromStatus(r.status(), "language model");
		}
		String text = extract(r.body());
		if (text == null || text.isBlank()) {
			throw new LlmUnavailableException(Reason.BAD_RESPONSE, "The language model returned an empty answer.");
		}
		return text;
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
