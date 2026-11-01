package com.smarthome.ml;

import java.util.Arrays;

/**
 * Median and median absolute deviation (MAD): a typical value and spread that a few bad readings cannot distort.
 * (A mean and standard deviation would be dragged around by the very outliers we are looking for.)
 */
public final class RobustStats {

	/** Makes MAD comparable to a standard deviation for normally distributed data. */
	public static final double MAD_TO_SIGMA = 1.4826;

	private RobustStats() {
	}

	public static double median(double[] values) {
		if (values.length == 0) {
			throw new IllegalArgumentException("no values");
		}
		double[] s = values.clone();
		Arrays.sort(s);
		int n = s.length;
		return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
	}

	public static double mad(double[] values, double median) {
		double[] dev = new double[values.length];
		for (int i = 0; i < values.length; i++) {
			dev[i] = Math.abs(values[i] - median);
		}
		return median(dev);
	}

	/** Spread in "standard deviation" units, never smaller than {@code floor} (so a perfectly flat history cannot divide by zero). */
	public static double scale(double mad, double floor) {
		return Math.max(MAD_TO_SIGMA * mad, floor);
	}

	/** How many robust standard deviations {@code value} is from {@code median}. Positive = above. */
	public static double score(double value, double median, double scale) {
		return (value - median) / scale;
	}
}
