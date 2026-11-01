package com.smarthome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.smarthome.assistant.LlmClient;
import com.smarthome.assistant.LlmUnavailableException;
import com.smarthome.assistant.LlmUnavailableException.Reason;

/**
 * Stands in for the real language model in tests. It never calls a network: it records exactly what it was sent and
 * replies with whatever the test scripted. Answers from this class are SIMULATED, not output of a real model.
 */
@Component
@Primary
public class ScriptedLlm implements LlmClient {

	public record Call(String system, List<Message> messages) {
		/** Everything the model could read in this call, as one string. */
		public String everything() {
			StringBuilder sb = new StringBuilder(system).append('\n');
			messages.forEach(m -> sb.append(m.role()).append(": ").append(m.content()).append('\n'));
			return sb.toString();
		}

		public String lastUserMessage() {
			return messages.get(messages.size() - 1).content();
		}
	}

	private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
	private volatile boolean configured = true;
	private volatile LlmUnavailableException failure;
	private volatile Function<Call, String> reply = c -> "I do not know.";

	public void reset() {
		calls.clear();
		configured = true;
		failure = null;
		reply = c -> "I do not know.";
	}

	public void replyWith(String text) {
		reply = c -> text;
	}

	public void replyWith(Function<Call, String> f) {
		reply = f;
	}

	public void failWith(Reason reason, String message) {
		failure = new LlmUnavailableException(reason, message);
	}

	public void setConfigured(boolean configured) {
		this.configured = configured;
	}

	public List<Call> calls() {
		return List.copyOf(calls);
	}

	public Call lastCall() {
		return calls.get(calls.size() - 1);
	}

	@Override
	public boolean configured() {
		return configured;
	}

	@Override
	public String provider() {
		return "scripted";
	}

	@Override
	public String model() {
		return "scripted-test-model";
	}

	@Override
	public String notConfiguredReason() {
		return "No language model provider is set (LLM_PROVIDER).";
	}

	@Override
	public String complete(String system, List<Message> messages) throws LlmUnavailableException {
		Call call = new Call(system, List.copyOf(messages));
		calls.add(call);
		if (failure != null) {
			throw failure;
		}
		return reply.apply(call);
	}
}
