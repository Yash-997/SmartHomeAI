package com.smarthome.predict;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * One day at one-minute resolution. {@code motion} is what the PIR saw. {@code present} is the occupancy label; it exists only
 * for synthetic data (null for live readings, which have no labels). {@code observed} is null when every minute has data;
 * the sensor arrays are null when not available and hold NaN for a missing value.
 */
public record DayTrace(LocalDate date, boolean[] motion, boolean[] present, boolean[] observed, float[] temperature,
		float[] humidity, float[] gas) {

	public static final int MINUTES = 1440;

	public DayTrace(LocalDate date, boolean[] motion, boolean[] present) {
		this(date, motion, present, null, null, null, null);
	}

	public boolean weekend() {
		return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
	}

	boolean seen(int minute) {
		return observed == null || observed[minute];
	}

	/** Motion minutes in the {@code window} minutes ending at {@code minute} (inclusive). Never looks ahead. */
	int motionCount(int minute, int window) {
		int n = 0;
		for (int i = Math.max(0, minute - window + 1); i <= minute; i++) {
			if (motion[i]) {
				n++;
			}
		}
		return n;
	}

	/** Minutes since the latest motion at or before {@code minute}; {@code cap} if none. Never looks ahead. */
	int sinceMotion(int minute, int cap) {
		for (int i = minute; i >= 0 && minute - i < cap; i--) {
			if (motion[i]) {
				return minute - i;
			}
		}
		return cap;
	}

	/** Share of the last {@code window} minutes with no message at all. */
	double missingShare(int minute, int window) {
		int from = Math.max(0, minute - window + 1), missing = 0;
		for (int i = from; i <= minute; i++) {
			if (!seen(i)) {
				missing++;
			}
		}
		return missing / (double) (minute - from + 1);
	}

	/** Latest value minus the mean of the {@code window} minutes up to now; 0 when there is nothing to compare. */
	static double change(float[] values, int minute, int window) {
		if (values == null) {
			return 0;
		}
		double sum = 0, latest = Double.NaN;
		int n = 0;
		for (int i = Math.max(0, minute - window + 1); i <= minute; i++) {
			if (!Float.isNaN(values[i])) {
				sum += values[i];
				n++;
				latest = values[i];
			}
		}
		return n < 2 ? 0 : latest - sum / n;
	}

	/** Labelled return: the minute that ends the longest daytime absence, or -1 if there was none. */
	int returnMinute() {
		int bestLength = 59, best = -1, start = -1;
		for (int i = 4 * 60; i < MINUTES; i++) {
			if (!present[i]) {
				if (start < 0) {
					start = i;
				}
			} else if (start >= 0) {
				if (i - start > bestLength) {
					bestLength = i - start;
					best = i;
				}
				start = -1;
			}
		}
		return best;
	}
}
