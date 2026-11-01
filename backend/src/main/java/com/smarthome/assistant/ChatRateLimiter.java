package com.smarthome.assistant;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.smarthome.assistant.AssistantExceptions.TooManyChatRequestsException;

/** At most N chat messages per user per minute (each one can cost a model call). In memory: it resets on restart. */
@Component
public class ChatRateLimiter {

	private final int perMinute;
	private final Clock clock;
	private final Map<UUID, Deque<Instant>> recent = new ConcurrentHashMap<>();

	@Autowired
	public ChatRateLimiter(AssistantProperties props, Clock clock) {
		this(props.chat().requestsPerMinute(), clock);
	}

	ChatRateLimiter(int perMinute, Clock clock) {
		this.perMinute = perMinute;
		this.clock = clock;
	}

	/** @throws TooManyChatRequestsException if the user already used up the current minute */
	public void check(UUID userId) {
		Deque<Instant> q = recent.computeIfAbsent(userId, k -> new ArrayDeque<>());
		Instant now = clock.instant();
		synchronized (q) {
			while (!q.isEmpty() && q.peekFirst().isBefore(now.minusSeconds(60))) {
				q.pollFirst();
			}
			if (q.size() >= perMinute) {
				throw new TooManyChatRequestsException();
			}
			q.addLast(now);
		}
	}
}
