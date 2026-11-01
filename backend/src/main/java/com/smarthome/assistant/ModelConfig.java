package com.smarthome.assistant;

import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

/** Chooses the model clients from configuration. Tests replace {@link LlmClient} with a scripted fake. */
@Configuration
public class ModelConfig {

	@Bean
	LlmClient llmClient(AssistantProperties props, ObjectMapper mapper) {
		String provider = props.llm().provider() == null ? "none" : props.llm().provider().trim().toLowerCase(Locale.ROOT);
		return switch (provider) {
			case "openai", "anthropic" -> new HttpLlmClient(props.llm(), mapper);
			case "", "none" -> new NoLlmClient("No language model provider is set (LLM_PROVIDER).");
			default -> new NoLlmClient("Unknown LLM_PROVIDER '" + provider.replaceAll("[^a-z0-9_-]", "?") + "' (use openai or anthropic).");
		};
	}

	@Bean
	EmbeddingClient embeddingClient(AssistantProperties props, ObjectMapper mapper) {
		String provider = props.embedding().provider() == null ? "local" : props.embedding().provider().trim().toLowerCase(Locale.ROOT);
		return "openai".equals(provider) ? new HttpEmbeddingClient(props.embedding(), props.llm(), mapper) : new LocalHashEmbedding();
	}
}
