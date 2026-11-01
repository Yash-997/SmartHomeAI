package com.smarthome.assistant;

import java.util.Map;

/** Exception types for the assistant; mapped to HTTP statuses in GlobalExceptionHandler. */
public final class AssistantExceptions {

	private AssistantExceptions() {
	}

	/** 400 with a per-field message. */
	public static class InvalidChatRequestException extends RuntimeException {
		private final Map<String, String> fieldErrors;

		public InvalidChatRequestException(String field, String message) {
			super("Invalid request");
			this.fieldErrors = Map.of(field, message);
		}

		public Map<String, String> fieldErrors() {
			return fieldErrors;
		}
	}

	/** 404: not found, or belongs to someone else (the two look the same). */
	public static class ConversationNotFoundException extends RuntimeException {
		public ConversationNotFoundException() {
			super("Conversation not found");
		}
	}

	/** 404. */
	public static class SuggestionNotFoundException extends RuntimeException {
		public SuggestionNotFoundException() {
			super("Suggestion not found");
		}
	}

	/** 409: already approved, rejected or expired. */
	public static class SuggestionNotPendingException extends RuntimeException {
		public SuggestionNotPendingException(String message) {
			super(message);
		}
	}

	/** 404. */
	public static class DocumentNotFoundException extends RuntimeException {
		public DocumentNotFoundException() {
			super("Document not found");
		}
	}

	/** 429. */
	public static class TooManyChatRequestsException extends RuntimeException {
		public TooManyChatRequestsException() {
			super("You are sending messages too quickly. Please wait a moment.");
		}
	}

	/** 503: the data store or the embedding service needed to find references is not available. The message is safe to show. */
	public static class AssistantUnavailableException extends RuntimeException {
		public AssistantUnavailableException(String message) {
			super(message);
		}
	}
}
