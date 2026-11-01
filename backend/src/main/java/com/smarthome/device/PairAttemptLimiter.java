package com.smarthome.device;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.smarthome.device.DeviceExceptions.TooManyAttemptsException;

/**
 * Slows down guessing of pairing codes: a client address may make {@value #MAX_FAILURES} failed
 * attempts per window. In-memory and per-instance, which is enough for this single-service project.
 */
@Component
public class PairAttemptLimiter {

	static final int MAX_FAILURES = 10;
	private static final Duration WINDOW = Duration.ofMinutes(10);

	private final ConcurrentHashMap<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

	public void checkAllowed(String client) {
		Deque<Instant> q = failures.get(client);
		if (q == null) {
			return;
		}
		synchronized (q) {
			prune(q);
			if (q.size() >= MAX_FAILURES) {
				throw new TooManyAttemptsException();
			}
		}
	}

	public void recordFailure(String client) {
		Deque<Instant> q = failures.computeIfAbsent(client, k -> new ArrayDeque<>());
		synchronized (q) {
			prune(q);
			q.addLast(Instant.now());
		}
	}

	private static void prune(Deque<Instant> q) {
		Instant cutoff = Instant.now().minus(WINDOW);
		while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
			q.removeFirst();
		}
	}
}
