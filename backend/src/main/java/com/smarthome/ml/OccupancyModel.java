package com.smarthome.ml;

import java.time.ZonedDateTime;

/**
 * A trained occupancy model, as stored. {@code cellN} / {@code cellPos} count, for each of the 168 hours of the week,
 * how many training slots fell there and how many of them had motion. They let a prediction say how much
 * evidence stands behind it ("support") and what was actually observed, so the number is never a black box.
 */
public record OccupancyModel(int version, int bucketMinutes, String timeZone, double[] weights, int[] cellN, int[] cellPos,
		double baseRate) {

	public static final int CURRENT_VERSION = 1;

	/** Probability that the PIR sees motion at the given local time. */
	public double probability(ZonedDateTime local) {
		return LogisticRegression.probability(weights, OccupancyFeatures.of(local));
	}

	/** Number of training slots from the same weekday-and-hour. */
	public int support(ZonedDateTime local) {
		return cellN[OccupancyFeatures.cell(local)];
	}

	/** Share of those slots that had motion, or null if there were none. */
	public Double observedRate(ZonedDateTime local) {
		int n = support(local);
		return n == 0 ? null : cellPos[OccupancyFeatures.cell(local)] / (double) n;
	}
}
