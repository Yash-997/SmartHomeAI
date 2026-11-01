package com.smarthome.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the AI assistant (prefix {@code app.assistant}). With nothing configured the assistant still starts:
 * it retrieves from the database with a built-in local embedder and says plainly that no language model is set up.
 * Keys are read from the environment and are never logged or returned by any endpoint.
 */
@ConfigurationProperties(prefix = "app.assistant")
public record AssistantProperties(@DefaultValue Llm llm, @DefaultValue Embedding embedding, @DefaultValue Rag rag, @DefaultValue Chat chat) {

	public record Llm(
			/** none (default), openai (any OpenAI-compatible server: OpenAI, Ollama, LM Studio, OpenRouter...) or anthropic. */
			@DefaultValue("none") String provider,
			/** Blank = the provider's usual address. For Ollama: http://localhost:11434/v1 */
			@DefaultValue("") String baseUrl,
			@DefaultValue("") String apiKey,
			@DefaultValue("") String model,
			@DefaultValue("30") int timeoutSeconds,
			@DefaultValue("700") int maxOutputTokens,
			@DefaultValue("0.2") double temperature) {
	}

	public record Embedding(
			/** local (default, built in, no network) or openai (an OpenAI-compatible /embeddings endpoint). */
			@DefaultValue("local") String provider,
			/** Blank = the LLM base URL. */
			@DefaultValue("") String baseUrl,
			/** Blank = the LLM key. */
			@DefaultValue("") String apiKey,
			@DefaultValue("") String model,
			@DefaultValue("20") int timeoutSeconds) {
	}

	public record Rag(
			@DefaultValue("600") int chunkChars,
			@DefaultValue("80") int overlapChars,
			@DefaultValue("6") int topK,
			/** Cosine similarity below this is not evidence. Tuned for the local embedder; raise it for a neural one. */
			@DefaultValue("0.12") double minScore,
			@DefaultValue("6000") int maxContextChars,
			/** Live facts are rebuilt from the database at most this often per user. */
			@DefaultValue("10") int refreshSeconds,
			/** How many days of per-day sensor summaries are indexed. */
			@DefaultValue("7") int historyDays,
			@DefaultValue("20000") int maxDocumentChars,
			@DefaultValue("20") int maxDocumentsPerUser) {
	}

	public record Chat(
			@DefaultValue("1000") int maxMessageChars,
			/** Earlier turns of the conversation sent to the model. */
			@DefaultValue("6") int historyTurns,
			@DefaultValue("15") int requestsPerMinute,
			@DefaultValue("60") int suggestionTtlMinutes,
			@DefaultValue("50") int maxConversationsPerUser) {
	}
}
