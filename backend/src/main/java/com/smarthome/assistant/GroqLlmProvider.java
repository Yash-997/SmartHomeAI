package com.smarthome.assistant;

import java.util.List;

import com.smarthome.assistant.AssistantProperties.Groq;
import com.smarthome.assistant.AssistantProperties.Llm;

import tools.jackson.databind.ObjectMapper;

/**
 * Groq through its OpenAI-compatible endpoint (https://api.groq.com/openai/v1). The request/response handling, tool
 * calling included, is the existing {@link HttpLlmClient} "groq" mode; this class only feeds it the GROQ_* settings.
 */
public class GroqLlmProvider implements LlmProvider {

	private final HttpLlmClient delegate;
	private final Groq cfg;

	public GroqLlmProvider(Llm llm, ObjectMapper mapper) {
		this.cfg = llm.groq();
		this.delegate = new HttpLlmClient(new Llm("groq", cfg.baseUrl(), cfg.apiKey(), cfg.model(), llm.timeoutSeconds(), llm.maxOutputTokens(),
				llm.temperature()), mapper);
	}

	@Override
	public String provider() {
		return "groq";
	}

	@Override
	public String model() {
		return delegate.model();
	}

	@Override
	public String notConfiguredReason() {
		if (cfg.model() == null || cfg.model().isBlank()) {
			return "No Groq model is set (GROQ_MODEL).";
		}
		if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
			return "No Groq API key is set (GROQ_API_KEY).";
		}
		return delegate.notConfiguredReason();
	}

	@Override
	public boolean configured() {
		return notConfiguredReason().isEmpty();
	}

	@Override
	public String complete(String system, List<Message> messages) throws LlmUnavailableException {
		return delegate.complete(system, messages);
	}

	@Override
	public boolean supportsTools() {
		return configured();
	}

	@Override
	public Turn completeWithTools(String system, List<Message> messages, List<ToolSpec> tools) throws LlmUnavailableException {
		return delegate.completeWithTools(system, messages, tools);
	}
}
