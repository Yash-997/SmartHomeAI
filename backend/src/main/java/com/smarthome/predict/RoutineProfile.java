package com.smarthome.predict;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The routine learned from motion history alone (no labels, no configured times). Quantiles are weighted towards recent days
 * ({@link PredictiveModel#HALF_LIFE_DAYS}) so the routine adapts, while one unusual day barely moves a median.
 */
public record RoutineProfile(Integer wake, Integer wakeSpread, Integer leave, Integer returnFrom, Integer returnTo, int days) {

	private static final int DAY_START = 4 * 60;
	private static final int MIN_ABSENCE_GAP = 150;
	private static final int MIN_DEVIATION = 45;
	private static final int MAX_DEVIATION_LIMIT = 75;
	private static final int SUSTAINED_DAYS = 3;

	public enum Kind { NONE, TOO_EARLY_TO_TELL, ISOLATED, SUSTAINED }

	public record Deviation(Kind kind, Integer observedWake, Integer differenceMinutes, int consecutiveDays, String explanation) {
	}

	/** First motion of the day that is followed by more activity (a single night-time trip does not count). */
	static int observedWake(DayTrace day, int upTo) {
		for (int m = DAY_START; m <= upTo; m++) {
			if (day.motion()[m] && day.motionCount(Math.min(upTo, m + 20), Math.min(upTo, m + 20) - m + 1) >= 3) {
				return m;
			}
		}
		return -1;
	}

	/** {last motion before, first motion after} the longest daytime quiet gap, or null. */
	static int[] quietGap(DayTrace day) {
		int previous = -1;
		int[] best = null;
		for (int m = DAY_START; m < 22 * 60 + 30; m++) {
			if (day.motion()[m] && day.motionCount(m + 15, 31) >= 3) { // ignore lone false triggers
				if (previous >= 0 && m - previous >= MIN_ABSENCE_GAP && (best == null || m - previous > best[1] - best[0])) {
					best = new int[] { previous, m };
				}
				previous = m;
			}
		}
		return best;
	}

	public static RoutineProfile learn(List<DayTrace> history, boolean weekend) {
		List<double[]> wakes = new ArrayList<>(), leaves = new ArrayList<>(), returns = new ArrayList<>(); // {minute, weight}
		int days = 0;
		for (DayTrace d : history) {
			if (d.weekend() != weekend) {
				continue;
			}
			days++;
			double weight = Math.pow(0.5, ChronoUnit.DAYS.between(d.date(), history.get(history.size() - 1).date())
					/ PredictiveModel.HALF_LIFE_DAYS);
			int wake = observedWake(d, DayTrace.MINUTES - 1);
			if (wake >= 0) {
				wakes.add(new double[] { wake, weight });
			}
			int[] gap = quietGap(d);
			if (gap != null) {
				leaves.add(new double[] { gap[0], weight });
				returns.add(new double[] { gap[1], weight });
			}
		}
		for (List<double[]> values : List.of(wakes, leaves, returns)) {
			values.sort(Comparator.comparingDouble(v -> v[0]));
		}
		return new RoutineProfile(at(wakes, 0.5),
				wakes.isEmpty() ? null : at(wakes, 0.75) - at(wakes, 0.25),
				at(leaves, 0.5), at(returns, 0.2), at(returns, 0.8), days);
	}

	/** Compares today (up to {@code now}) and the preceding comparable days with the learned wake time. */
	public Deviation wakeDeviation(List<DayTrace> history, DayTrace today, int now) {
		if (wake == null) {
			return new Deviation(Kind.NONE, null, null, 0, "No wake-up routine has been learned yet.");
		}
		// Capped, so that a run of unusual days cannot widen the "normal" range until it stops being noticed.
		int limit = Math.max(MIN_DEVIATION, Math.min(2 * wakeSpread, MAX_DEVIATION_LIMIT));
		int observed = observedWake(today, now);
		int difference;
		if (observed >= 0) {
			difference = observed - wake;
		} else if (now > wake + limit) {
			difference = now - wake; // still no morning activity: late by at least this much
		} else {
			return new Deviation(Kind.TOO_EARLY_TO_TELL, null, null, 0, "Not enough of today has passed to judge the wake-up time.");
		}
		if (Math.abs(difference) <= limit) {
			return new Deviation(Kind.NONE, observed < 0 ? null : observed, difference, 0, "Today's wake-up is within the normal range.");
		}
		int run = 1;
		for (int i = history.size() - 1; i >= 0; i--) {
			DayTrace d = history.get(i);
			if (d.weekend() != today.weekend()) {
				continue;
			}
			int w = observedWake(d, DayTrace.MINUTES - 1);
			if (w < 0 || Math.abs(w - wake) <= limit || Integer.signum(w - wake) != Integer.signum(difference)) {
				break;
			}
			run++;
		}
		boolean sustained = run >= SUSTAINED_DAYS;
		String direction = difference > 0 ? "later" : "earlier";
		return new Deviation(sustained ? Kind.SUSTAINED : Kind.ISOLATED, observed < 0 ? null : observed, difference, run,
				(observed < 0 ? "No wake-up yet, at least " : "Wake-up ") + Math.abs(difference) + " minutes " + direction
						+ " than learned. " + (sustained
								? run + " comparable days in a row: a sustained change. The learned median moves only as such days become the majority."
								: "Seen on " + run + " day(s) so far: treated as isolated. The learned routine is unchanged."));
	}

	private static Integer at(List<double[]> sorted, double q) {
		if (sorted.isEmpty()) {
			return null;
		}
		double target = q * sorted.stream().mapToDouble(v -> v[1]).sum(), seen = 0;
		for (double[] v : sorted) {
			seen += v[1];
			if (seen >= target) {
				return (int) v[0];
			}
		}
		return (int) sorted.get(sorted.size() - 1)[0];
	}
}
