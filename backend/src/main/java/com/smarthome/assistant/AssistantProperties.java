package com.smarthome.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the AI assistant (prefix {@code app.assistant}). With nothing configured the assistant still starts:
 * it retrieves from the database with a built-in local embedder and says plainly that no language model is set up.
 * Keys are read from the environment and are never logged or returned by any endpoint.
 */
@ConfigurationProperties(prefix = "app.assistant")
public record AssistantProperties(@DefaultValue Llm llm, @DefaultValue Embedding embedding, @DefaultValue Rag rag, @DefaultValue Chat chat) {

	/**
	 * The language model(s). {@code primaryProvider} (blank = {@code provider}) is asked first; on a recoverable failure
	 * (429, 5xx, timeout, network) one attempt goes to {@code fallbackProvider}. {@code baseUrl}/{@code apiKey}/{@code model}
	 * are for the openai and anthropic providers; gemini and groq have their own sections.
	 */
	public record Llm(
			/** none, gemini, groq, openai (any OpenAI-compatible server: OpenAI, Ollama, LM Studio, OpenRouter...) or anthropic. */
			@DefaultValue("none") String provider,
			/** Blank = the provider's usual address. For Ollama: http://localhost:11434/v1 */
			@DefaultValue("") String baseUrl,
			@DefaultValue("") String apiKey,
			@DefaultValue("") String model,
			@DefaultValue("30") int timeoutSeconds,
			@DefaultValue("700") int maxOutputTokens,
			@DefaultValue("0.2") double temperature,
			/** Blank = {@code provider}. */
			@DefaultValue("") String primaryProvider,
			/** none (default) or another provider name; ignored when it equals the primary. */
			@DefaultValue("none") String fallbackProvider,
			/** After a recoverable primary failure, requests go straight to the fallback for this long. 0 = always try the primary. */
			@DefaultValue("60") int failoverCooldownSeconds,
			@DefaultValue Gemini gemini,
			@DefaultValue Groq groq) {

		@ConstructorBinding
		public Llm {
		}

		/** The single-provider form (openai/anthropic/groq through {@link HttpLlmClient}); no fallback. */
		public Llm(String provider, String baseUrl, String apiKey, String model, int timeoutSeconds, int maxOutputTokens, double temperature) {
			this(provider, baseUrl, apiKey, model, timeoutSeconds, maxOutputTokens, temperature, "", "none", 60,
					new Gemini("", "gemini-3-flash-preview", "", 2048, "low"), new Groq("", "openai/gpt-oss-120b", ""));
		}
	}

	/** Google Gemini (generateContent API). */
	public record Gemini(
			@DefaultValue("") String apiKey,
			@DefaultValue("gemini-3-flash-preview") String model,
			/** Blank = https://generativelanguage.googleapis.com/v1beta */
			@DefaultValue("") String baseUrl,
			/** Thinking models count reasoning tokens against this limit, so it is higher than the plain-text one. */
			@DefaultValue("2048") int maxOutputTokens,
			/** Gemini 3 thinking level (minimal, low, medium, high); blank = the model's default. Ignored for older models. */
			@DefaultValue("low") String thinkingLevel) {
	}

	/** Groq (OpenAI-compatible chat completions). */
	public record Groq(
			@DefaultValue("") String apiKey,
			@DefaultValue("openai/gpt-oss-120b") String model,
			/** Blank = https://api.groq.com/openai/v1 */
			@DefaultValue("") String baseUrl) {
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
			/** Characters of context (database query results first, then retrieved chunks) sent to the model. */
			@DefaultValue("10000") int maxContextChars,
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
