package com.smarthome.ml;

import java.time.ZonedDateTime;

/**
 * Turns a local date and time into the numbers the occupancy model uses. Only time is used: there is no hidden
 * information, so everything the model "knows" is visible here.
 *
 * <ul>
 *   <li>Time of day as six sine/cosine pairs (cycles of 24 h down to 4 h). A cyclic encoding makes 23:59 and 00:01
 *       neighbours, and several harmonics let the daily curve have several peaks (a first version with three harmonics
 *       smoothed a two-hour morning peak away, which the simulated-routine tests caught).</li>
 *   <li>Day of week as six 0/1 indicators (Monday is the reference).</li>
 *   <li>Weekend-specific copies of the time-of-day terms, so Saturday and Sunday can follow a different daily rhythm.</li>
 *   <li>Model version 2 adds one more: the motion frequency of that hour of the week in the training history, as a
 *       smoothed log-odds ({@link #frequency}). It lets the model follow a household's habits more closely than the
 *       smooth curves alone. When training, each slot's own label is left out of its frequency (leave-one-out), so the
 *       feature never "sees the answer".</li>
 * </ul>
 */
public final class OccupancyFeatures {

	/** Six cycles per day (periods of 24, 12, 8, 6, 4.8 and 4 hours): enough to follow a two-hour morning peak. */
	public static final int HARMONICS = 6;
	private static final int HARMONIC_TERMS = 2 * HARMONICS;
	public static final int DIM = HARMONIC_TERMS + 6 + HARMONIC_TERMS;
	/** Dimension with the motion-frequency feature (model version 2). */
	public static final int DIM_V2 = DIM + 1;
	/** Weight of the overall rate when smoothing an hour's motion frequency: like adding this many "average" slots. */
	public static final double FREQUENCY_PRIOR = 4.0;
	/** Description of the feature set, stored with each training run. */
	public static final String FEATURE_SET = "v2: time-of-day, weekday, weekend, hour-of-week motion frequency";

	private OccupancyFeatures() {
	}

	public static double[] of(ZonedDateTime local) {
		double hours = local.getHour() + local.getMinute() / 60.0 + local.getSecond() / 3600.0;
		double[] harmonics = new double[HARMONIC_TERMS];
		for (int k = 1; k <= HARMONICS; k++) {
			double angle = 2 * Math.PI * k * hours / 24.0;
			harmonics[2 * (k - 1)] = Math.sin(angle);
			harmonics[2 * (k - 1) + 1] = Math.cos(angle);
		}
		double[] x = new double[DIM];
		System.arraycopy(harmonics, 0, x, 0, HARMONIC_TERMS);
		int dow = local.getDayOfWeek().getValue();   // Monday = 1 ... Sunday = 7
		if (dow >= 2) {
			x[HARMONIC_TERMS + dow - 2] = 1;
		}
		if (dow >= 6) {
			System.arraycopy(harmonics, 0, x, HARMONIC_TERMS + 6, HARMONIC_TERMS);
		}
		return x;
	}

	/**
	 * Smoothed motion frequency of one hour of the week, as log-odds: (motion slots + prior x base rate) / (slots + prior),
	 * clamped to 2 %..98 % so an hour seen only a few times cannot dominate.
	 */
	public static double frequency(int slots, int motionSlots, double baseRate) {
		double rate = (Math.max(0, motionSlots) + FREQUENCY_PRIOR * baseRate) / (Math.max(0, slots) + FREQUENCY_PRIOR);
		double c = Math.min(0.98, Math.max(0.02, rate));
		return Math.log(c / (1 - c));
	}

	/** The time features plus the frequency feature (version 2 layout). */
	public static double[] withFrequency(double[] timeFeatures, double frequency) {
		double[] x = new double[timeFeatures.length + 1];
		System.arraycopy(timeFeatures, 0, x, 0, timeFeatures.length);
		x[timeFeatures.length] = frequency;
		return x;
	}

	/** Day of week (Monday = 0) times 24 plus hour: indexes the 168 weekly hour cells used for support counts. */
	public static int cell(ZonedDateTime local) {
		return (local.getDayOfWeek().getValue() - 1) * 24 + local.getHour();
	}
}
