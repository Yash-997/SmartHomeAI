package com.smarthome.assistant.tools;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.smarthome.analytics.TimeRange;

/** Consistent, unambiguous formatting of times and periods in tool output (the user's zone, 24-hour clock). */
public final class Fmt {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("d MMM HH:00", Locale.ENGLISH);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

	private Fmt() {
	}

	public static String time(Instant i, ZoneId zone) {
		return i == null ? "never" : TIME.format(i.atZone(zone));
	}

	public static String hour(Instant i, ZoneId zone) {
		return HOUR.format(i.atZone(zone));
	}

	public static String day(Instant i, ZoneId zone) {
		return DAY.format(i.atZone(zone));
	}

	/** "the last 7 days (6 Nov 2026 10:00 to 13 Nov 2026 10:00, Asia/Kolkata)". */
	public static String period(TimeRange r) {
		return r.label() + " (" + time(r.from(), r.zone()) + " to " + time(r.to(), r.zone()) + ", " + r.zone().getId() + ")";
	}

	/** "around 13 Nov 2026 14:15" for a 15-minute aggregation window. */
	public static String window(Instant start, ZoneId zone) {
		return start == null ? "" : " (in the 15 minutes from " + time(start, zone) + ")";
	}

	public static String age(long seconds) {
		if (seconds < 90) {
			return seconds + " seconds ago";
		}
		if (seconds < 7200) {
			return seconds / 60 + " minutes ago";
		}
		return seconds < 172800 ? seconds / 3600 + " hours ago" : seconds / 86400 + " days ago";
	}

	public static String pct(double v) {
		return String.format(Locale.ROOT, "%.1f%%", v);
	}

	public static String signed(double v, int decimals) {
		return String.format(Locale.ROOT, "%+." + decimals + "f", v);
	}
}
