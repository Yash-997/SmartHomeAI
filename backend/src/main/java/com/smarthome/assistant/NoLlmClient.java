package com.smarthome.assistant;

import java.util.List;

import com.smarthome.assistant.LlmUnavailableException.Reason;

/** Used when LLM_PROVIDER is unset (or unknown): the assistant still retrieves and shows references. */
public class NoLlmClient implements LlmClient {

	private final String reason;

	public NoLlmClient(String reason) {
		this.reason = reason;
	}

	@Override
	public boolean configured() {
		return false;
	}

	@Override
	public String provider() {
		return "none";
	}

	@Override
	public String model() {
		return "";
	}

	@Override
	public String notConfiguredReason() {
		return reason;
	}

	@Override
	public String complete(String system, List<Message> messages) throws LlmUnavailableException {
		throw new LlmUnavailableException(Reason.NOT_CONFIGURED, "The language model is not configured. " + reason);
	}
}
