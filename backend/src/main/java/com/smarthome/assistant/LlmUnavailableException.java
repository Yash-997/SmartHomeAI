package com.smarthome.assistant;

/** The language model (or embedding model) could not be used right now. The message is safe to show to users. */
public class LlmUnavailableException extends Exception {

	public enum Reason { NOT_CONFIGURED, AUTH, MODEL_NOT_FOUND, RATE_LIMITED, TIMEOUT, NETWORK, SERVER_ERROR, BAD_RESPONSE }

	private final Reason reason;

	public LlmUnavailableException(Reason reason, String userMessage) {
		super(userMessage);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}

	/** Maps an HTTP status from a model API to a reason with a generic, secret-free message. */
	static LlmUnavailableException fromStatus(int status, String what) {
		if (status == 401 || status == 403) {
			return new LlmUnavailableException(Reason.AUTH, "The " + what + " rejected the configured API key.");
		}
		if (status == 404) {
			return new LlmUnavailableException(Reason.MODEL_NOT_FOUND, "The " + what + " could not find the configured model or address.");
		}
		if (status == 429) {
			return new LlmUnavailableException(Reason.RATE_LIMITED, "The " + what + " is rate limiting requests. Try again shortly.");
		}
		if (status >= 500) {
			return new LlmUnavailableException(Reason.SERVER_ERROR, "The " + what + " reported an internal error.");
		}
		return new LlmUnavailableException(Reason.BAD_RESPONSE, "The " + what + " refused the request (HTTP " + status + ").");
	}
}
