package com.smarthome.assistant;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.smarthome.assistant.AssistantProperties.Llm;

import tools.jackson.databind.ObjectMapper;

/** Chooses the model clients from configuration. Tests replace {@link LlmClient} with a scripted fake. */
@Configuration
public class ModelConfig {

	private static final Logger log = LoggerFactory.getLogger(ModelConfig.class);

	/**
	 * LLM_PRIMARY_PROVIDER (or LLM_PROVIDER when that is unset) is asked first; LLM_FALLBACK_PROVIDER takes over on a
	 * recoverable failure. "none" as the primary turns the language model off.
	 */
	@Bean
	LlmClient llmClient(AssistantProperties props, ObjectMapper mapper) {
		Llm llm = props.llm();
		String primaryName = norm(llm.primaryProvider());
		if (primaryName.isEmpty()) {
			primaryName = norm(llm.provider());
		}
		if (primaryName.isEmpty() || primaryName.equals("none")) {
			return new NoLlmClient("No language model provider is set (LLM_PROVIDER).");
		}
		LlmProvider primary = provider(primaryName, llm, mapper);
		if (primary == null) {
			return new NoLlmClient("Unknown LLM_PROVIDER '" + primaryName.replaceAll("[^a-z0-9_-]", "?") + "' (use gemini, groq, openai or anthropic).");
		}
		String fallbackName = norm(llm.fallbackProvider());
		LlmProvider fallback = null;
		if (!fallbackName.isEmpty() && !fallbackName.equals("none") && !fallbackName.equals(primaryName)) {
			fallback = provider(fallbackName, llm, mapper);
			if (fallback == null) {
				log.warn("Unknown LLM_FALLBACK_PROVIDER '{}'; running without a fallback", fallbackName.replaceAll("[^a-z0-9_-]", "?"));
			}
		}
		if (!primary.configured() && fallback != null && fallback.configured()) {
			log.warn("Primary language model {} is not configured ({}); using {} only", primaryName, primary.notConfiguredReason(), fallback.provider());
		} else if (fallback != null && !fallback.configured()) {
			log.info("Fallback language model {} is not configured; it will not be used", fallback.provider());
		}
		return new LlmProviderOrchestrator(primary, fallback, llm.failoverCooldownSeconds());
	}

	private static LlmProvider provider(String name, Llm llm, ObjectMapper mapper) {
		return switch (name) {
			case "gemini" -> new GeminiLlmProvider(llm, mapper);
			case "groq" -> new GroqLlmProvider(llm, mapper);
			case "openai", "anthropic" -> new HttpLlmClient(new Llm(name, llm.baseUrl(), llm.apiKey(), llm.model(), llm.timeoutSeconds(),
					llm.maxOutputTokens(), llm.temperature()), mapper);
			default -> null;
		};
	}

	private static String norm(String s) {
		return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
	}

	@Bean
	EmbeddingClient embeddingClient(AssistantProperties props, ObjectMapper mapper) {
		String provider = props.embedding().provider() == null ? "local" : props.embedding().provider().trim().toLowerCase(Locale.ROOT);
		return "openai".equals(provider) ? new HttpEmbeddingClient(props.embedding(), props.llm(), mapper) : new LocalHashEmbedding();
	}
}
