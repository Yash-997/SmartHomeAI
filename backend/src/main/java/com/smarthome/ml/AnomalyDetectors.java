package com.smarthome.ml;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The anomaly rules, as pure functions. Each one looks at readings and returns a finding that explains itself in
 * plain words and carries the numbers behind it, or nothing. They never see a "zero" where a reading is missing:
 * a missing value is its own finding (MISSING_DATA) and is invisible to the statistical checks.
 *
 * <p>These findings are advisory. The device's gas warning is firmware logic that does not depend on any of this,
 * and nothing here sends commands.
 */
public final class AnomalyDetectors {

	private static final String GAS_NOTE = "Advisory only: the device's own gas warning works independently of this analysis.";

	private AnomalyDetectors() {
	}

	/** A reading far from normal. Uses the robust z-score: (value - median) / (1.4826 x MAD). */
	public static Optional<Finding> outlier(Metric metric, SensorSample sample, Baselines.Stat baseline, MlProperties.Anomaly cfg) {
		Double value = metric.of(sample);
		if (value == null || baseline == null) {
			return Optional.empty();   // nothing to judge, or no idea what normal is: stay silent rather than guess
		}
		double z = RobustStats.score(value, baseline.median(), baseline.scale());
		double abs = Math.abs(z);
		if (abs < cfg.warnZ()) {
			return Optional.empty();
		}
		AnomalySeverity severity = abs >= cfg.criticalZ() ? AnomalySeverity.CRITICAL : AnomalySeverity.WARNING;
		String unit = unit(metric);
		String explanation = String.format(Locale.ROOT,
				"%s %s is %.1f robust standard deviations %s the typical %s%s for %s (median of %d readings over the last %d days; typical spread about %s%s).",
				metric.scope(), num(value) + unit, abs, z > 0 ? "above" : "below", num(baseline.median()), unit,
				baseline.hourSpecific() ? "this time of day" : "this sensor overall", baseline.n(), cfg.baselineDays(),
				num(baseline.scale()), unit);
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("method", "robust z-score (median and MAD)");
		d.put("value", value);
		d.put("median", baseline.median());
		d.put("spread", baseline.scale());
		d.put("score", Math.round(z * 100) / 100.0);
		d.put("baselineSamples", baseline.n());
		d.put("timeOfDayBaseline", baseline.hourSpecific());
		d.put("warnAt", cfg.warnZ());
		d.put("criticalAt", cfg.criticalZ());
		if (metric == Metric.GAS) {
			d.put("note", GAS_NOTE);
		}
		return Optional.of(new Finding(AnomalyKind.STATISTICAL_OUTLIER, metric.scope(), severity,
				metric == Metric.GAS ? explanation + " " + GAS_NOTE : explanation, d, sample.at()));
	}

	/**
	 * A jump between two consecutive readings that is far outside how much this sensor normally changes from one
	 * reading to the next (robust z-score of the change against the history of changes). Catches a sensor that suddenly
	 * misbehaves, or a real sudden event, even when the new value on its own is not unusual.
	 */
	public static Optional<Finding> suddenChange(Metric metric, SensorSample previous, SensorSample current, Baselines.Stat changes,
			MlProperties.Anomaly cfg) {
		if (previous == null || current == null || changes == null) {
			return Optional.empty();
		}
		Double before = metric.of(previous);
		Double after = metric.of(current);
		if (before == null || after == null) {
			return Optional.empty();
		}
		long seconds = Duration.between(previous.at(), current.at()).getSeconds();
		if (seconds <= 0 || seconds > cfg.suddenMaxGapSeconds()) {
			return Optional.empty();   // not consecutive in time: after a gap a big change is expected
		}
		double change = after - before;
		double z = RobustStats.score(change, changes.median(), changes.scale());
		double abs = Math.abs(z);
		if (abs < cfg.suddenWarnZ()) {
			return Optional.empty();
		}
		AnomalySeverity severity = abs >= cfg.suddenCriticalZ() ? AnomalySeverity.CRITICAL : AnomalySeverity.WARNING;
		String unit = unit(metric);
		String explanation = String.format(Locale.ROOT,
				"%s changed suddenly from %s%s to %s%s (%s%s%s) between two readings %d s apart. From one reading to the next it normally "
						+ "changes by about %s%s (from %d consecutive pairs); this change scores %.1f. Check the sensor, or what happened in the room.",
				metric.scope(), num(before), unit, num(after), unit, change > 0 ? "+" : "", num(change), unit, seconds, num(changes.scale()), unit,
				changes.n(), abs);
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("method", "robust z-score of the change between consecutive readings");
		d.put("previous", before);
		d.put("value", after);
		d.put("change", change);
		d.put("seconds", seconds);
		d.put("typicalChange", changes.median());
		d.put("spread", changes.scale());
		d.put("score", Math.round(z * 100) / 100.0);
		d.put("pairs", changes.n());
		d.put("warnAt", cfg.suddenWarnZ());
		d.put("criticalAt", cfg.suddenCriticalZ());
		if (metric == Metric.GAS) {
			d.put("note", GAS_NOTE);
		}
		return Optional.of(new Finding(AnomalyKind.SUDDEN_CHANGE, metric.scope(), severity,
				metric == Metric.GAS ? explanation + " " + GAS_NOTE : explanation, d, current.at()));
	}

	/**
	 * Motion at a time the learned household pattern says is normally quiet: in the training history this hour of the
	 * week had motion in at most {@code quietHourMaxRate} of at least {@code quietHourMinSupport} slots, and the trained
	 * model also gives a low probability. Both are required, so neither thin evidence nor the model alone can raise it.
	 */
	public static Optional<Finding> unusualActivity(java.time.ZonedDateTime local, Instant at, double modelProbability, Double observedRate,
			int support, MlProperties.Anomaly cfg) {
		if (observedRate == null || support < cfg.quietHourMinSupport() || observedRate > cfg.quietHourMaxRate()
				|| modelProbability > cfg.quietHourMaxProbability()) {
			return Optional.empty();
		}
		int motionSlots = (int) Math.round(observedRate * support);
		String when = local.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " "
				+ String.format(Locale.ROOT, "%02d:%02d", local.getHour(), local.getMinute());
		String explanation = String.format(Locale.ROOT,
				"Motion at %s (%s), a time that is normally quiet: in the learned history this device saw motion in %d of %d slots at this "
						+ "hour on this weekday (%.0f%%), and the occupancy model expected a %.0f%% chance. If nobody should be there, check it.",
				when, local.getZone().getId(), motionSlots, support, observedRate * 100, modelProbability * 100);
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("method", "learned hour-of-week pattern and occupancy model");
		d.put("localTime", when);
		d.put("observedRate", observedRate);
		d.put("slotsAtThisHour", support);
		d.put("modelProbability", modelProbability);
		d.put("quietAtMost", cfg.quietHourMaxRate());
		return Optional.of(new Finding(AnomalyKind.UNUSUAL_ACTIVITY, "motion", AnomalySeverity.WARNING, explanation, d, at));
	}

	/** A value outside what the sensor can really produce (DHT22 temperature range), or an ADC reading pinned at its maximum. */
	public static Optional<Finding> plausibility(Metric metric, SensorSample sample, MlProperties.Anomaly cfg) {
		Double value = metric.of(sample);
		if (value == null) {
			return Optional.empty();
		}
		if (metric == Metric.TEMPERATURE && (value < cfg.temperatureMin() || value > cfg.temperatureMax())) {
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("value", value);
			d.put("usableMin", cfg.temperatureMin());
			d.put("usableMax", cfg.temperatureMax());
			return Optional.of(new Finding(AnomalyKind.OUT_OF_RANGE, metric.scope(), AnomalySeverity.CRITICAL,
					String.format(Locale.ROOT, "temperature %s °C is outside the range the DHT22 can measure (%s to %s °C): most likely a sensor or wiring fault.",
							num(value), num(cfg.temperatureMin()), num(cfg.temperatureMax())), d, sample.at()));
		}
		if (metric == Metric.GAS && value >= cfg.gasSaturationRaw()) {
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("value", value);
			d.put("adcMaximum", cfg.gasSaturationRaw());
			d.put("note", GAS_NOTE);
			return Optional.of(new Finding(AnomalyKind.OUT_OF_RANGE, metric.scope(), AnomalySeverity.WARNING,
					"gasRaw is at the ADC maximum (" + cfg.gasSaturationRaw() + "): the sensor output is saturated or disconnected, or the gas level is extreme. Check the sensor. "
							+ GAS_NOTE, d, sample.at()));
		}
		return Optional.empty();
	}

	/** A reading the backend refused to store because it was outside the accepted range (reported by the ingest path). */
	public static Finding rejectedReading(String scope, double value, Instant at) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("value", value);
		d.put("handling", "message rejected, value not stored");
		return new Finding(AnomalyKind.OUT_OF_RANGE, scope, AnomalySeverity.WARNING,
				scope + " " + num(value) + " is outside the accepted range, so the whole message was rejected and not stored. The sensor may be faulty.",
				d, at);
	}

	/**
	 * The same exact value in each of the latest N readings. Readings must be newest first; missing values are
	 * skipped, never counted as repeats.
	 */
	public static Optional<Finding> stuck(Metric metric, List<SensorSample> newestFirst, MlProperties.Anomaly cfg) {
		int needed = metric == Metric.GAS ? cfg.stuckCountGas() : cfg.stuckCountTempHum();
		double first = 0;
		int run = 0;
		Instant newest = null;
		Instant oldest = null;
		for (SensorSample s : newestFirst) {
			Double v = metric.of(s);
			if (v == null) {
				continue;
			}
			if (run == 0) {
				first = v;
				newest = s.at();
			} else if (Double.compare(v, first) != 0) {
				break;
			}
			run++;
			oldest = s.at();
			if (run >= needed) {
				break;
			}
		}
		if (run < needed) {
			return Optional.empty();
		}
		long minutes = Math.max(1, Duration.between(oldest, newest).toMinutes());
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("value", first);
		d.put("identicalReadings", run);
		d.put("minutes", minutes);
		AnomalySeverity severity = metric == Metric.GAS ? AnomalySeverity.WARNING : AnomalySeverity.INFO;
		return Optional.of(new Finding(AnomalyKind.STUCK_VALUE, metric.scope(), severity,
				String.format(Locale.ROOT, "%s has reported exactly %s%s in each of the last %d readings (about %d minutes). A working sensor normally varies slightly; it may be frozen. "
						+ "(If the room really is that constant this can be ignored.)", metric.scope(), num(first), unit(metric), run, minutes) + (metric == Metric.GAS ? " " + GAS_NOTE : ""),
				d, newest));
	}

	/** The latest N messages all lack this sensor's value although the device is still reporting. Newest first. */
	public static Optional<Finding> missing(Metric metric, List<SensorSample> newestFirst, MlProperties.Anomaly cfg) {
		int needed = metric == Metric.GAS ? cfg.missingCountGas() : cfg.missingCountTempHum();
		if (newestFirst.size() < needed) {
			return Optional.empty();
		}
		for (int i = 0; i < needed; i++) {
			if (metric.of(newestFirst.get(i)) != null) {
				return Optional.empty();
			}
		}
		Instant newest = newestFirst.get(0).at();
		long minutes = Math.max(1, Duration.between(newestFirst.get(needed - 1).at(), newest).toMinutes());
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("missingMessages", needed);
		d.put("minutes", minutes);
		return Optional.of(new Finding(AnomalyKind.MISSING_DATA, metric.scope(), AnomalySeverity.WARNING,
				String.format(Locale.ROOT, "No %s value in the last %d messages (about %d minutes) although the device is still reporting. The sensor may be failing, disconnected%s.",
						metric.scope(), needed, minutes, metric == Metric.GAS ? ", or the MQ-2 may not have finished warming up" : ""), d, newest));
	}

	/** The device has not reported for too long. */
	public static Optional<Finding> stale(Instant latest, Instant now, MlProperties.Anomaly cfg) {
		Duration age = Duration.between(latest, now);
		if (age.compareTo(Duration.ofMinutes(cfg.staleMinutes())) < 0 || age.compareTo(Duration.ofDays(cfg.staleGiveUpDays())) > 0) {
			return Optional.empty();
		}
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("lastReadingAt", latest.toString());
		d.put("silentMinutes", age.toMinutes());
		return Optional.of(new Finding(AnomalyKind.STALE_DATA, "device", AnomalySeverity.WARNING,
				"No data from this device for " + age.toMinutes() + " minutes (last reading " + latest
						+ "). It may be powered off, out of Wi-Fi range, or disconnected from the broker.", d, latest));
	}

	private static String unit(Metric m) {
		return m.unit().equals("raw") ? "" : " " + m.unit();
	}

	private static String num(double v) {
		return v == Math.rint(v) && Math.abs(v) < 1e9 ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
	}
}
