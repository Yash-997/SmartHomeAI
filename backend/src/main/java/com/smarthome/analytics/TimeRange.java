package com.smarthome.analytics;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;

/**
 * A half-open period [from, to) in a time zone, with a human label. Periods that include the present end at "now", so a
 * statistic over "today" never pretends to cover hours that have not happened yet.
 */
public record TimeRange(Instant from, Instant to, ZoneId zone, String preset, String label) {

	/** The presets the assistant tools and the report API accept. */
	public static final List<String> PRESETS = List.of("last_hour", "last_24h", "today", "yesterday", "last_7_days", "this_week", "last_week",
			"last_30_days", "this_month", "last_month", "custom");

	/** Longest period any analysis may cover. Keeps every query bounded. */
	public static final Duration MAX_SPAN = Duration.ofDays(366);

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	public TimeRange {
		if (!from.isBefore(to)) {
			throw new IllegalArgumentException("The period must end after it starts");
		}
		if (Duration.between(from, to).compareTo(MAX_SPAN.plusDays(1)) > 0) {
			throw new IllegalArgumentException("A period can be at most 366 days long");
		}
	}

	public Duration length() {
		return Duration.between(from, to);
	}

	/**
	 * @param preset   one of {@link #PRESETS} (case-insensitive)
	 * @param fromDate first day (inclusive) for {@code custom}
	 * @param toDate   last day (inclusive) for {@code custom}
	 * @throws IllegalArgumentException with a message safe to show to the user
	 */
	public static TimeRange resolve(String preset, LocalDate fromDate, LocalDate toDate, ZoneId zone, Instant now) {
		String p = preset == null ? "last_24h" : preset.trim().toLowerCase(Locale.ROOT);
		ZonedDateTime n = now.atZone(zone);
		LocalDate today = n.toLocalDate();
		return switch (p) {
			case "last_hour" -> new TimeRange(now.minus(Duration.ofHours(1)), now, zone, p, "the last hour");
			case "last_24h" -> new TimeRange(now.minus(Duration.ofHours(24)), now, zone, p, "the last 24 hours");
			case "today" -> new TimeRange(start(today, zone), now, zone, p, "today (" + DAY.format(today) + ", so far)");
			case "yesterday" -> days(today.minusDays(1), today.minusDays(1), zone, now, p, "yesterday (" + DAY.format(today.minusDays(1)) + ")");
			case "last_7_days" -> new TimeRange(now.minus(Duration.ofDays(7)), now, zone, p, "the last 7 days");
			case "last_30_days" -> new TimeRange(now.minus(Duration.ofDays(30)), now, zone, p, "the last 30 days");
			case "this_week" -> {
				LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
				yield new TimeRange(start(monday, zone), now, zone, p, "this week (since Monday " + DAY.format(monday) + ")");
			}
			case "last_week" -> {
				LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
				yield days(monday, monday.plusDays(6), zone, now, p, "last week (" + DAY.format(monday) + " to " + DAY.format(monday.plusDays(6)) + ")");
			}
			case "this_month" -> {
				LocalDate first = today.withDayOfMonth(1);
				yield new TimeRange(start(first, zone), now, zone, p, "this month (since " + DAY.format(first) + ")");
			}
			case "last_month" -> {
				LocalDate first = today.withDayOfMonth(1).minusMonths(1);
				LocalDate last = first.withDayOfMonth(first.lengthOfMonth());
				yield days(first, last, zone, now, p, "last month (" + DAY.format(first) + " to " + DAY.format(last) + ")");
			}
			case "custom" -> {
				if (fromDate == null || toDate == null) {
					throw new IllegalArgumentException("A custom period needs a start date and an end date");
				}
				if (toDate.isBefore(fromDate)) {
					throw new IllegalArgumentException("The end date is before the start date");
				}
				if (fromDate.isAfter(today)) {
					throw new IllegalArgumentException("The period starts in the future");
				}
				String label = fromDate.equals(toDate) ? DAY.format(fromDate) : DAY.format(fromDate) + " to " + DAY.format(toDate);
				yield days(fromDate, toDate, zone, now, p, label);
			}
			default -> throw new IllegalArgumentException("Unknown period '" + p.replaceAll("[^a-z0-9_]", "?") + "'. Use one of " + String.join(", ", PRESETS));
		};
	}

	/** Whole local days [first, last], clipped at now. */
	private static TimeRange days(LocalDate first, LocalDate last, ZoneId zone, Instant now, String preset, String label) {
		Instant from = start(first, zone);
		Instant to = start(last.plusDays(1), zone);
		if (to.isAfter(now)) {
			to = now;
			label = label + " (so far)";
		}
		if (Duration.between(from, to).compareTo(MAX_SPAN.plusDays(1)) > 0) {
			throw new IllegalArgumentException("A period can be at most 366 days long");
		}
		return new TimeRange(from, to, zone, preset, label);
	}

	private static Instant start(LocalDate d, ZoneId zone) {
		return d.atStartOfDay(zone).toInstant();
	}

	/**
	 * The period to compare with: for calendar presets the previous calendar period ("yesterday" for "today", "last week"
	 * for "this week"), otherwise the period of the same length just before this one. Partial current periods are compared
	 * with the same elapsed part of the previous one, so "today so far" is not compared with a whole day.
	 */
	public TimeRange previous() {
		Duration len = length();
		return switch (preset) {
			case "today", "yesterday" -> new TimeRange(from.minus(Duration.ofDays(1)), from.minus(Duration.ofDays(1)).plus(len), zone, "custom",
					"the same hours of the day before");
			case "this_week", "last_week" -> new TimeRange(from.atZone(zone).minusWeeks(1).toInstant(), from.atZone(zone).minusWeeks(1).toInstant().plus(len), zone,
					"custom", "the same part of the week before");
			case "this_month", "last_month" -> {
				Instant f = from.atZone(zone).minusMonths(1).toInstant();
				Instant t = f.plus(len);
				if (t.isAfter(from)) {
					t = from;
				}
				yield new TimeRange(f, t, zone, "custom", "the same part of the month before");
			}
			default -> new TimeRange(from.minus(len), from, zone, "custom", "the " + describe(len) + " before that");
		};
	}

	static String describe(Duration d) {
		long h = d.toHours();
		if (h < 2) {
			return d.toMinutes() + " minutes";
		}
		return h < 48 ? h + " hours" : d.toDays() + " days";
	}
}
