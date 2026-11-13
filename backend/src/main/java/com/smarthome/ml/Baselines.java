package com.smarthome.ml;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * What is "normal" for each sensor, learned from the device's own recent history.
 *
 * <p>For each sensor it keeps a robust median and spread (MAD) overall, and for each hour of the day using the
 * readings from that hour and its two neighbours (so a room that is naturally warmer at 15:00 than at 04:00 is not
 * flagged every afternoon). The hour-specific figures are only used when there are enough readings for them; otherwise
 * the overall figures are used; and with too little history in total there is <b>no baseline at all</b> and no
 * statistical judgement is made. Hours are UTC hours; they only group readings, so no time-zone setting is needed.
 */
public final class Baselines {

	/** {@code scale} is the spread in standard-deviation units (1.4826 x MAD, but never below the per-sensor floor). */
	public record Stat(double median, double scale, int n, boolean hourSpecific) {
	}

	private final Stat[] overall = new Stat[Metric.values().length];
	/** How much a sensor normally changes between two consecutive readings (null: not enough history). */
	private final Stat[] delta = new Stat[Metric.values().length];
	private final Stat[][] hourly = new Stat[Metric.values().length][24];
	private final int totalSamples;

	private Baselines(int totalSamples) {
		this.totalSamples = totalSamples;
	}

	public int totalSamples() {
		return totalSamples;
	}

	/** Null when there is not enough history for this sensor to say what normal is. */
	public Stat lookup(Metric metric, Instant at) {
		Stat o = overall[metric.ordinal()];
		if (o == null) {
			return null;
		}
		Stat h = hourly[metric.ordinal()][at.atZone(ZoneOffset.UTC).getHour()];
		return h != null ? h : o;
	}

	/** Typical change between consecutive readings, or null when there are too few consecutive pairs to say. */
	public Stat delta(Metric metric) {
		return delta[metric.ordinal()];
	}

	public static Baselines build(List<SensorSample> samples, MlProperties.Anomaly cfg) {
		Baselines b = new Baselines(samples.size());
		for (Metric metric : Metric.values()) {
			b.delta[metric.ordinal()] = deltas(samples, metric, cfg);
		}
		for (Metric metric : Metric.values()) {
			List<Double> all = new ArrayList<>();
			@SuppressWarnings("unchecked")
			List<Double>[] byHour = new List[24];
			for (int h = 0; h < 24; h++) {
				byHour[h] = new ArrayList<>();
			}
			for (SensorSample s : samples) {
				Double v = metric.of(s);
				if (v == null || v.isNaN() || v.isInfinite()) {
					continue;   // a missing reading contributes nothing: it is not a zero
				}
				all.add(v);
				byHour[s.at().atZone(ZoneOffset.UTC).getHour()].add(v);
			}
			int m = metric.ordinal();
			if (all.size() < cfg.minBaselineSamples()) {
				continue;
			}
			double floor = metric.minScale(cfg);
			b.overall[m] = stat(all, floor, false);
			for (int h = 0; h < 24; h++) {
				List<Double> window = new ArrayList<>(byHour[h]);
				window.addAll(byHour[(h + 23) % 24]);
				window.addAll(byHour[(h + 1) % 24]);
				if (window.size() >= cfg.minHourBaselineSamples()) {
					b.hourly[m][h] = stat(window, floor, true);
				}
			}
		}
		return b;
	}

	/**
	 * Changes between consecutive readings of one sensor (samples may come in either order). Pairs further apart than
	 * the configured gap are skipped: after an outage the first change is not "sudden".
	 */
	private static Stat deltas(List<SensorSample> samples, Metric metric, MlProperties.Anomaly cfg) {
		List<SensorSample> sorted = new ArrayList<>(samples);
		sorted.sort((x, y) -> x.at().compareTo(y.at()));
		List<Double> changes = new ArrayList<>();
		SensorSample prev = null;
		for (SensorSample s : sorted) {
			Double v = metric.of(s);
			if (v == null || v.isNaN() || v.isInfinite()) {
				continue;
			}
			if (prev != null && java.time.Duration.between(prev.at(), s.at()).getSeconds() <= cfg.suddenMaxGapSeconds()) {
				changes.add(v - metric.of(prev));
			}
			prev = s;
		}
		return changes.size() < cfg.minDeltaSamples() ? null : stat(changes, metric.minScale(cfg), false);
	}

	private static Stat stat(List<Double> values, double floor, boolean hourSpecific) {
		double[] a = values.stream().mapToDouble(Double::doubleValue).toArray();
		double median = RobustStats.median(a);
		double scale = RobustStats.scale(RobustStats.mad(a, median), floor);
		return new Stat(median, scale, a.length, hourSpecific);
	}
}
