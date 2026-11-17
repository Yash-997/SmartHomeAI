package com.smarthome.assistant;

import com.smarthome.assistant.LlmUnavailableException.Reason;

/**
 * One concrete language-model backend (Gemini, Groq, an OpenAI-compatible server, Anthropic). It speaks the same
 * {@link LlmClient} contract the assistant already uses: system text, conversation turns, optional tools, and a
 * {@link LlmClient.Turn} back, so any provider can take over a conversation mid-way. {@link LlmProviderOrchestrator}
 * combines a primary and a fallback provider into the single {@link LlmClient} the assistant sees.
 */
public interface LlmProvider extends LlmClient {

	/**
	 * Whether a failure is worth one attempt at another provider: rate limits, timeouts, network errors and 5xx. Bad
	 * requests, rejected keys, unknown models and missing configuration are not; another provider would not fix the
	 * request and failing over would hide the misconfiguration.
	 */
	static boolean recoverable(LlmUnavailableException e) {
		Reason r = e.reason();
		return r == Reason.RATE_LIMITED || r == Reason.TIMEOUT || r == Reason.NETWORK || r == Reason.SERVER_ERROR;
	}
}
