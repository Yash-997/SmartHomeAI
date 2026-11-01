package com.smarthome.assistant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.smarthome.assistant.AssistantExceptions.TooManyChatRequestsException;

class ChatRateLimiterTest {

	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
	private final Clock clock = new Clock() {
		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now.get();
		}
	};

	@Test
	void allowsTheConfiguredNumberPerMinuteThenRefuses() {
		ChatRateLimiter limiter = new ChatRateLimiter(3, clock);
		UUID user = UUID.randomUUID();
		for (int i = 0; i < 3; i++) {
			assertDoesNotThrow(() -> limiter.check(user));
		}
		assertThrows(TooManyChatRequestsException.class, () -> limiter.check(user));
	}

	@Test
	void theWindowSlidesAndEachUserHasTheirOwnAllowance() {
		ChatRateLimiter limiter = new ChatRateLimiter(2, clock);
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		limiter.check(a);
		limiter.check(a);
		assertThrows(TooManyChatRequestsException.class, () -> limiter.check(a));
		assertDoesNotThrow(() -> limiter.check(b));          // another user is not affected
		now.set(now.get().plusSeconds(61));
		assertDoesNotThrow(() -> limiter.check(a));          // a minute later it is allowed again
	}
}
