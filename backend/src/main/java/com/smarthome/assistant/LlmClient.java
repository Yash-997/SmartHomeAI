package com.smarthome.assistant;

import java.util.List;
import java.util.Map;

/**
 * A chat language model. Implementations call an external HTTP API; none of them can act on devices, rules or the
 * database. Every failure surfaces as {@link LlmUnavailableException} with a message that is safe to show to a user
 * (it never contains keys, URLs or response bodies).
 */
public interface LlmClient {

	/**
	 * One turn of a conversation. {@code toolCalls} is set on an assistant turn that asked for tools; {@code toolCallId}
	 * (and {@code toolName}) on a "tool" turn carrying one tool's result back to the model.
	 */
	record Message(String role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName) {
		public Message(String role, String content) {
			this(role, content, List.of(), null, null);
		}

		public static Message user(String content) {
			return new Message("user", content);
		}

		public static Message assistant(String content) {
			return new Message("assistant", content);
		}

		public static Message assistantCalls(String content, List<ToolCall> calls) {
			return new Message("assistant", content == null ? "" : content, List.copyOf(calls), null, null);
		}

		public static Message toolResult(String toolCallId, String toolName, String content) {
			return new Message("tool", content, List.of(), toolCallId, toolName);
		}
	}

	/** A function the model may ask for: name, description and a JSON schema of its arguments. */
	record ToolSpec(String name, String description, Map<String, Object> parameters) {
	}

	/**
	 * The model asking for one function call. {@code arguments} is the raw JSON text the model produced (untrusted).
	 * {@code signature} is an opaque provider token that must be sent back with the call on the next turn (Gemini's
	 * thought signature); null for providers that have none.
	 */
	record ToolCall(String id, String name, String arguments, String signature) {
		public ToolCall(String id, String name, String arguments) {
			this(id, name, arguments, null);
		}
	}

	/** A model reply: text, tool calls, or both. */
	record Turn(String text, List<ToolCall> toolCalls) {
		public boolean wantsTools() {
			return toolCalls != null && !toolCalls.isEmpty();
		}
	}

	/** False when no provider, key or model is set up: the assistant then answers with references only. */
	boolean configured();

	/** "none", "gemini", "groq", "openai" or "anthropic". */
	String provider();

	/** Model name in use, or empty when not configured. */
	String model();

	/** A plain-words reason shown when {@link #configured()} is false. */
	String notConfiguredReason();

	/** @param messages alternating user/assistant turns ending with a user turn */
	String complete(String system, List<Message> messages) throws LlmUnavailableException;

	/** True if {@link #completeWithTools} really passes the tools to the model. */
	default boolean supportsTools() {
		return false;
	}

	/**
	 * A reply that may ask for tool calls. Clients without tool support answer in text only, which the assistant handles
	 * (it has already fetched the likely data itself).
	 */
	default Turn completeWithTools(String system, List<Message> messages, List<ToolSpec> tools) throws LlmUnavailableException {
		return new Turn(complete(system, messages), List.of());
	}
}
