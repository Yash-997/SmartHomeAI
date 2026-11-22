package com.smarthome.ml;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A trained anomaly model: one Isolation Forest per feature group ({@link AnomalyFeatures#GROUPS}), the preprocessing
 * they need, and the decision rule tuned for them. Stored as one JSON document, so live scoring uses exactly what was
 * trained and evaluated.
 *
 * <p>The score of a minute is the highest of its group scores. Decision rule: a minute is an alarm when its score is at
 * or above {@code threshold} for {@code consecutive} minutes in a row. {@code threshold} is the {@code quantile} of the
 * scores on the training data (all normal), so it is "more unusual than all but a sliver of normal minutes".
 * {@code criticalThreshold} is the highest training score: above it, the minute is more unusual than anything seen while
 * learning.
 */
public record AnomalyModel(String version, String featureSet, List<String> featureNames, String dataSource,
		AnomalyFeatures.Preprocessing preprocessing, List<Subspace> forests, double quantile, double threshold, double criticalThreshold,
		int consecutive, Instant trainedFrom, Instant trainedTo, int trainingFrames) {

	public static final String CURRENT_VERSION = "if-1";
	static final String GAS_NOTE = "Advisory only: the device's own gas warning and the backend gas alert work independently of this model.";

	/** One group's forest. {@code features} are indexes into the full feature vector. */
	public record Subspace(String group, int[] features, IsolationForest.Model forest) {
		double score(double[] x) {
			return forest.score(AnomalyFeatures.project(x, features));
		}
	}

	/** Grows one forest per feature group, each with its own seed derived from {@code seed}. */
	static List<Subspace> fit(double[][] x, int trees, int subsample, long seed) {
		List<Subspace> s = new ArrayList<>();
		for (int g = 0; g < AnomalyFeatures.GROUPS.size(); g++) {
			AnomalyFeatures.Group group = AnomalyFeatures.GROUPS.get(g);
			double[][] px = new double[x.length][];
			for (int i = 0; i < x.length; i++) {
				px[i] = AnomalyFeatures.project(x[i], group.features());
			}
			s.add(new Subspace(group.name(), group.features(), IsolationForest.fit(px, trees, subsample, seed + 1_000_003L * g)));
		}
		return List.copyOf(s);
	}

	static double score(List<Subspace> forests, double[] x) {
		double best = 0;
		for (Subspace s : forests) {
			best = Math.max(best, s.score(x));
		}
		return best;
	}

	public double score(double[] x) {
		return score(forests, x);
	}

	private Subspace strongest(double[] x) {
		Subspace best = forests.get(0);
		double bestScore = -1;
		for (Subspace s : forests) {
			double v = s.score(x);
			if (v > bestScore) {
				bestScore = v;
				best = s;
			}
		}
		return best;
	}

	/** Scores rows (oldest first) and returns a finding for the newest row if it closes a run of alarm minutes. */
	public Optional<Finding> judgeLatest(List<AnomalyFeatures.Row> rows) {
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		int run = 0;
		double peak = 0;
		AnomalyFeatures.Row prev = null;
		for (int i = rows.size() - 1; i >= 0 && run < consecutive; i--) {
			AnomalyFeatures.Row r = rows.get(i);
			if (prev != null && java.time.Duration.between(r.frame().at(), prev.frame().at()).toMinutes() > 1) {
				break;                                       // a gap breaks the run
			}
			double s = score(r.x());
			if (s < threshold) {
				break;
			}
			peak = Math.max(peak, s);
			run++;
			prev = r;
		}
		if (run < consecutive) {
			return Optional.empty();
		}
		return Optional.of(finding(rows.get(rows.size() - 1), score(rows.get(rows.size() - 1).x()), run));
	}

	/** Explains one alarm minute in plain words, with the numbers behind it. */
	Finding finding(AnomalyFeatures.Row row, double score, int run) {
		AnomalyFeatures.Preprocessing p = preprocessing;
		Subspace group = strongest(row.x());
		String sensor = "device".equals(group.group()) ? AnomalyFeatures.responsibleSensor(AnomalyFeatures.only(row.x(), group.features(), p), p)
				: group.group();
		List<AnomalyFeatures.Contribution> top = AnomalyFeatures.top(AnomalyFeatures.only(row.x(), group.features(), p), p, 3);
		Metric metric = metric(sensor);
		Double value = metric == null ? null : row.frame().of(metric);
		double[] range = metric == null ? null : p.expectedRange(metric, row.frame().at());
		AnomalySeverity severity = score >= criticalThreshold ? AnomalySeverity.CRITICAL : AnomalySeverity.WARNING;

		StringBuilder why = new StringBuilder();
		why.append(String.format(Locale.ROOT, "The Isolation Forest scored this minute %.2f (alarm at %.2f, %d minute%s in a row), unlike the %d normal minutes it learned from. ",
				score, threshold, run, run == 1 ? "" : "s", trainingFrames));
		if (metric != null && value != null) {
			why.append(String.format(Locale.ROOT, "Most unusual: %s %s%s, where %s%s to %s%s is normal at this time of day. ", metric.scope(), num(value), unit(metric),
					num(range[0]), unit(metric), num(range[1]), unit(metric)));
		} else if ("device".equals(sensor)) {
			why.append("Most unusual: the gap since the device's previous report. ");
		} else if ("motion".equals(sensor)) {
			why.append("Most unusual: motion at a time and rate that the learned pattern rarely shows. ");
		} else if (metric != null) {
			why.append("Most unusual: no ").append(metric.scope()).append(" value in recent messages. ");
		}
		why.append("Strongest signals: ");
		List<String> parts = new ArrayList<>();
		for (AnomalyFeatures.Contribution c : top) {
			parts.add(describe(c.feature()) + " (" + num(c.rarity()) + "x the normal spread)");
		}
		why.append(String.join(", ", parts)).append('.');
		if (Metric.GAS.scope().equals(sensor)) {
			why.append(' ').append(GAS_NOTE);
		}

		Map<String, Object> d = new LinkedHashMap<>();
		d.put("method", "Isolation Forest per sensor group (" + forests.size() + " groups x " + forests.get(0).forest().trees().size()
				+ " trees, subsample " + forests.get(0).forest().subsample() + ") over " + featureNames.size() + " engineered features");
		d.put("group", group.group());
		d.put("score", Math.round(score * 1000) / 1000.0);
		d.put("threshold", Math.round(threshold * 1000) / 1000.0);
		d.put("criticalThreshold", Math.round(criticalThreshold * 1000) / 1000.0);
		d.put("consecutiveMinutes", run);
		if (value != null) {
			d.put("value", Math.round(value * 100) / 100.0);
		}
		if (range != null) {
			d.put("expectedLow", Math.round(range[0] * 100) / 100.0);
			d.put("expectedHigh", Math.round(range[1] * 100) / 100.0);
		}
		d.put("topFeatures", top);
		d.put("modelVersion", version);
		if (Metric.GAS.scope().equals(sensor)) {
			d.put("note", GAS_NOTE);
		}
		String text = why.toString();
		return new Finding(AnomalyKind.MULTIVARIATE_OUTLIER, sensor, severity, text.length() > 600 ? text.substring(0, 597) + "..." : text, d,
				row.frame().at());
	}

	private static Metric metric(String scope) {
		for (Metric m : Metric.values()) {
			if (m.scope().equals(scope)) {
				return m;
			}
		}
		return null;
	}

	/** "humidity.rateOfChange" -> "humidity rate of change". */
	static String describe(String feature) {
		return switch (feature) {
			case "device.gapMinutes" -> "minutes since the previous report";
			case "motion.now" -> "motion now";
			case "motion.rate" -> "motion rate";
			case "rel.humidityVsTemperature" -> "humidity vs temperature relationship";
			case "rel.gasVsHumidity" -> "gas vs humidity relationship";
			default -> {
				int dot = feature.indexOf('.');
				String sensor = feature.substring(0, dot);
				String f = switch (feature.substring(dot + 1)) {
					case "hourZ" -> "vs this hour's normal";
					case "meanDev" -> "vs its recent mean";
					case "rollStd" -> "recent variability";
					case "rateOfChange" -> "rate of change";
					case "madZ" -> "vs its recent median";
					case "stuckRun" -> "identical repeats";
					case "missingShare" -> "missing values";
					default -> feature.substring(dot + 1);
				};
				yield sensor + " " + f;
			}
		};
	}

	private static String unit(Metric m) {
		return m.unit().equals("raw") ? "" : " " + m.unit();
	}

	private static String num(double v) {
		return String.format(Locale.ROOT, Math.abs(v) >= 100 ? "%.0f" : "%.1f", v);
	}
}
