package com.smarthome.ml;

import java.time.ZonedDateTime;

/**
 * A trained occupancy model, as stored. {@code cellN} / {@code cellPos} count, for each of the 168 hours of the week,
 * how many training slots fell there and how many of them had motion. They let a prediction say how much
 * evidence stands behind it ("support") and what was actually observed, so the number is never a black box.
 */
public record OccupancyModel(int version, int bucketMinutes, String timeZone, double[] weights, int[] cellN, int[] cellPos,
		double baseRate) {

	/** 1: time features only. 2: plus the hour-of-week motion frequency. Stored version-1 models keep working. */
	public static final int CURRENT_VERSION = 2;

	/** Probability that the PIR sees motion at the given local time. */
	public double probability(ZonedDateTime local) {
		return LogisticRegression.probability(weights, features(local));
	}

	/** The feature vector this model version uses. */
	public double[] features(ZonedDateTime local) {
		double[] x = OccupancyFeatures.of(local);
		if (version < 2) {
			return x;
		}
		int c = OccupancyFeatures.cell(local);
		return OccupancyFeatures.withFrequency(x, OccupancyFeatures.frequency(cellN[c], cellPos[c], baseRate));
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
