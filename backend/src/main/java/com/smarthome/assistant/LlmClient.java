package com.smarthome.assistant;

import java.util.List;

/**
 * A chat language model. Implementations call an external HTTP API; none of them can act on devices, rules or the
 * database. Every failure surfaces as {@link LlmUnavailableException} with a message that is safe to show to a user
 * (it never contains keys, URLs or response bodies).
 */
public interface LlmClient {

	record Message(String role, String content) {
		public static Message user(String content) {
			return new Message("user", content);
		}

		public static Message assistant(String content) {
			return new Message("assistant", content);
		}
	}

	/** False when no provider, key or model is set up: the assistant then answers with references only. */
	boolean configured();

	/** "none", "openai" or "anthropic". */
	String provider();

	/** Model name in use, or empty when not configured. */
	String model();

	/** A plain-words reason shown when {@link #configured()} is false. */
	String notConfiguredReason();

	/** @param messages alternating user/assistant turns ending with a user turn */
	String complete(String system, List<Message> messages) throws LlmUnavailableException;
}
