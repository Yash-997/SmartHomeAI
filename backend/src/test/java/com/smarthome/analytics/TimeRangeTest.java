package com.smarthome.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class TimeRangeTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	// Friday 13 Nov 2026, 15:30 in Kolkata
	private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

	@Test
	void todayAndYesterdayFollowTheLocalCalendar() {
		TimeRange today = TimeRange.resolve("today", null, null, IST, NOW);
		assertEquals(Instant.parse("2026-11-12T18:30:00Z"), today.from());
		assertEquals(NOW, today.to(), "a current period ends now");
		TimeRange y = TimeRange.resolve("yesterday", null, null, IST, NOW);
		assertEquals(Instant.parse("2026-11-11T18:30:00Z"), y.from());
		assertEquals(Instant.parse("2026-11-12T18:30:00Z"), y.to());
	}

	@Test
	void weeksStartOnMonday() {
		TimeRange w = TimeRange.resolve("this_week", null, null, IST, NOW);
		assertEquals(LocalDate.of(2026, 11, 9), w.from().atZone(IST).toLocalDate());
		TimeRange lw = TimeRange.resolve("last_week", null, null, IST, NOW);
		assertEquals(Duration.ofDays(7), lw.length());
		assertEquals(LocalDate.of(2026, 11, 2), lw.from().atZone(IST).toLocalDate());
	}

	@Test
	void lastMonthIsTheWholeCalendarMonth() {
		TimeRange m = TimeRange.resolve("last_month", null, null, IST, NOW);
		assertEquals(LocalDate.of(2026, 10, 1), m.from().atZone(IST).toLocalDate());
		assertEquals(Duration.ofDays(31), m.length());
	}

	@Test
	void customRangesAreInclusiveAndValidated() {
		TimeRange c = TimeRange.resolve("custom", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3), IST, NOW);
		assertEquals(Duration.ofDays(3), c.length());
		assertThrows(IllegalArgumentException.class, () -> TimeRange.resolve("custom", LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 1), IST, NOW));
		assertThrows(IllegalArgumentException.class, () -> TimeRange.resolve("custom", null, null, IST, NOW));
		assertThrows(IllegalArgumentException.class, () -> TimeRange.resolve("custom", LocalDate.of(2024, 1, 1), LocalDate.of(2026, 1, 1), IST, NOW));
		assertThrows(IllegalArgumentException.class, () -> TimeRange.resolve("custom", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2), IST, NOW));
		assertThrows(IllegalArgumentException.class, () -> TimeRange.resolve("since the dawn of time", null, null, IST, NOW));
	}

	@Test
	void aPeriodThatIncludesTodayIsClippedAtNow() {
		TimeRange c = TimeRange.resolve("custom", LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 20), IST, NOW);
		assertEquals(NOW, c.to());
		assertTrue(c.label().contains("so far"));
	}

	@Test
	void previousAlignsPartialPeriods() {
		TimeRange today = TimeRange.resolve("today", null, null, IST, NOW);
		TimeRange before = today.previous();
		assertEquals(today.length(), before.length(), "today so far is compared with the same hours of yesterday");
		assertEquals(today.from().minus(Duration.ofDays(1)), before.from());
		TimeRange d7 = TimeRange.resolve("last_7_days", null, null, IST, NOW);
		assertEquals(d7.from(), d7.previous().to());
	}
}
