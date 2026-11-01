package com.smarthome.assistant;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.smarthome.assistant.AssistantProperties.Embedding;
import com.smarthome.assistant.AssistantProperties.Llm;
import com.smarthome.assistant.LlmUnavailableException.Reason;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Embeddings from an OpenAI-compatible {@code /embeddings} endpoint (OpenAI, Ollama, LM Studio...). */
public class HttpEmbeddingClient implements EmbeddingClient {

	private static final String DEFAULT_BASE = "https://api.openai.com/v1";
	private static final int BATCH = 32;

	private final Embedding cfg;
	private final Llm llm;
	private final ObjectMapper mapper;

	public HttpEmbeddingClient(Embedding cfg, Llm llm, ObjectMapper mapper) {
		this.cfg = cfg;
		this.llm = llm;
		this.mapper = mapper;
	}

	private String base() {
		String b = !blank(cfg.baseUrl()) ? cfg.baseUrl() : llm.baseUrl();
		return blank(b) ? DEFAULT_BASE : b.trim();
	}

	private String key() {
		return !blank(cfg.apiKey()) ? cfg.apiKey().trim() : (blank(llm.apiKey()) ? "" : llm.apiKey().trim());
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	@Override
	public String modelId() {
		return "openai:" + (blank(cfg.model()) ? "(unset)" : cfg.model().trim());
	}

	@Override
	public boolean local() {
		return false;
	}

	@Override
	public List<float[]> embed(List<String> texts) throws LlmUnavailableException {
		if (blank(cfg.model())) {
			throw new LlmUnavailableException(Reason.NOT_CONFIGURED, "No embedding model is set (EMBEDDING_MODEL).");
		}
		if (key().isEmpty() && base().equals(DEFAULT_BASE)) {
			throw new LlmUnavailableException(Reason.NOT_CONFIGURED, "No API key is set for the embedding service.");
		}
		java.net.URI uri = ModelHttp.uri(base(), "/embeddings", "embedding service");
		Map<String, String> headers = new LinkedHashMap<>();
		if (!key().isEmpty()) {
			headers.put("Authorization", "Bearer " + key());
		}
		List<float[]> out = new ArrayList<>(texts.size());
		for (int from = 0; from < texts.size(); from += BATCH) {
			List<String> batch = texts.subList(from, Math.min(texts.size(), from + BATCH));
			String body = mapper.writeValueAsString(Map.of("model", cfg.model().trim(), "input", batch));
			ModelHttp.Response r = ModelHttp.postJson(uri, headers, body, Duration.ofSeconds(Math.max(1, cfg.timeoutSeconds())), "embedding service");
			if (r.status() / 100 != 2) {
				throw LlmUnavailableException.fromStatus(r.status(), "embedding service");
			}
			out.addAll(parse(r.body(), batch.size()));
		}
		return out;
	}

	private List<float[]> parse(String json, int expected) throws LlmUnavailableException {
		try {
			JsonNode data = mapper.readTree(json).path("data");
			if (!data.isArray() || data.size() != expected) {
				throw new IllegalStateException();
			}
			float[][] vectors = new float[expected][];
			for (JsonNode item : data) {
				int idx = item.path("index").asInt(-1);
				JsonNode emb = item.path("embedding");
				if (idx < 0 || idx >= expected || !emb.isArray() || emb.isEmpty()) {
					throw new IllegalStateException();
				}
				float[] v = new float[emb.size()];
				for (int i = 0; i < v.length; i++) {
					v[i] = (float) emb.get(i).asDouble();
				}
				vectors[idx] = v;
			}
			List<float[]> list = new ArrayList<>(expected);
			for (float[] v : vectors) {
				if (v == null) {
					throw new IllegalStateException();
				}
				list.add(v);
			}
			return list;
		} catch (RuntimeException e) {
			throw new LlmUnavailableException(Reason.BAD_RESPONSE, "The embedding service sent a reply that could not be read.");
		}
	}
}
