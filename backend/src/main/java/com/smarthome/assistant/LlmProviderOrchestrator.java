package com.smarthome.assistant;

import java.util.List;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LlmClient} the assistant uses: a primary provider with an optional fallback.
 *
 * <ul>
 * <li>Each model call goes to the primary. If it fails with a recoverable error ({@link LlmProvider#recoverable}: 429,
 * 5xx, timeout, network), the same call is made once to the fallback. There are no retries beyond that, so a call costs
 * at most two requests and at most two timeouts.</li>
 * <li>Bad requests, rejected keys, unknown models and missing configuration are thrown as they are, without failover.</li>
 * <li>After a recoverable primary failure the primary is skipped for {@code cooldownSeconds}, so the remaining rounds of a
 * tool conversation (and the next questions) do not pay for a provider that is rate limited or down.</li>
 * <li>A provider without configuration (no key) is never called: with only one configured, it is used alone.</li>
 * <li>Tools are executed by the assistant between model calls, never here, so a failover cannot run a tool twice: the
 * fallback receives the conversation with the earlier tool calls and their results already in it.</li>
 * </ul>
 */
public class LlmProviderOrchestrator implements LlmClient {

	private static final Logger log = LoggerFactory.getLogger(LlmProviderOrchestrator.class);

	@FunctionalInterface
	private interface Call<T> {
		T on(LlmProvider p) throws LlmUnavailableException;
	}

	private final LlmProvider primary;
	private final LlmProvider fallback;
	private final long cooldownNanos;
	private final LongSupplier nanoClock;
	private volatile long primarySkippedUntil = Long.MIN_VALUE;
	private volatile boolean cooling = false;

	public LlmProviderOrchestrator(LlmProvider primary, LlmProvider fallback, int cooldownSeconds) {
		this(primary, fallback, cooldownSeconds, System::nanoTime);
	}

	LlmProviderOrchestrator(LlmProvider primary, LlmProvider fallback, int cooldownSeconds, LongSupplier nanoClock) {
		this.primary = primary;
		this.fallback = fallback;
		this.cooldownNanos = Math.max(0, cooldownSeconds) * 1_000_000_000L;
		this.nanoClock = nanoClock;
	}

	LlmProvider primary() {
		return primary;
	}

	LlmProvider fallback() {
		return fallback;
	}

	private boolean fallbackUsable() {
		return fallback != null && fallback.configured();
	}

	/** The provider that answers when nothing fails: the primary if configured, else the fallback. */
	private LlmProvider lead() {
		return primary.configured() || !fallbackUsable() ? primary : fallback;
	}

	@Override
	public boolean configured() {
		return primary.configured() || fallbackUsable();
	}

	@Override
	public String provider() {
		return configured() ? lead().provider() : primary.provider();
	}

	@Override
	public String model() {
		if (!configured()) {
			return "";
		}
		if (primary.configured() && fallbackUsable()) {
			return primary.model() + " (fallback: " + fallback.provider() + " " + fallback.model() + ")";
		}
		return lead().model();
	}

	@Override
	public String notConfiguredReason() {
		if (configured()) {
			return "";
		}
		String reason = primary.notConfiguredReason().trim();
		if (fallback != null) {
			reason = reason + " Fallback: " + fallback.notConfiguredReason().trim();
		}
		return reason;
	}

	@Override
	public boolean supportsTools() {
		return configured() && lead().supportsTools();
	}

	@Override
	public String complete(String system, List<Message> messages) throws LlmUnavailableException {
		return run(p -> p.complete(system, messages));
	}

	@Override
	public Turn completeWithTools(String system, List<Message> messages, List<ToolSpec> tools) throws LlmUnavailableException {
		return run(p -> p.supportsTools() ? p.completeWithTools(system, messages, tools) : new Turn(p.complete(system, messages), List.of()));
	}

	private <T> T run(Call<T> call) throws LlmUnavailableException {
		if (!primary.configured()) {
			if (fallbackUsable()) {
				return call.on(fallback);
			}
			return call.on(primary);                 // throws NOT_CONFIGURED with the primary's reason
		}
		boolean skipPrimary = fallbackUsable() && cooling && nanoClock.getAsLong() - primarySkippedUntil < 0;
		if (skipPrimary) {
			return call.on(fallback);
		}
		LlmUnavailableException first;
		try {
			T out = call.on(primary);
			cooling = false;
			return out;
		} catch (LlmUnavailableException e) {
			if (!fallbackUsable() || !LlmProvider.recoverable(e)) {
				throw e;
			}
			first = e;
		}
		if (cooldownNanos > 0) {
			primarySkippedUntil = nanoClock.getAsLong() + cooldownNanos;
			cooling = true;
		}
		log.warn("Language model {} failed ({}); trying fallback {}", primary.provider(), first.reason(), fallback.provider());
		try {
			T out = call.on(fallback);
			log.info("Fallback {} answered after {} failed ({})", fallback.provider(), primary.provider(), first.reason());
			return out;
		} catch (LlmUnavailableException e) {
			log.warn("Fallback {} also failed ({})", fallback.provider(), e.reason());
			throw new LlmUnavailableException(e.reason(), "Both AI providers failed. " + label(primary) + ": " + first.getMessage().strip() + " "
					+ label(fallback) + " (fallback): " + e.getMessage().strip());
		}
	}

	private static String label(LlmProvider p) {
		String n = p.provider();
		return n.isEmpty() ? n : Character.toUpperCase(n.charAt(0)) + n.substring(1);
	}
}
