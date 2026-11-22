package com.smarthome.ml;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.smarthome.ml.SyntheticGenerator.Reading;
import com.smarthome.ml.SyntheticGenerator.Scenario;
import com.smarthome.ml.SyntheticGenerator.ScenarioWindow;

/**
 * Trains, tunes and evaluates the hybrid anomaly detector (statistical rules + Isolation Forest) on a labelled
 * SYNTHETIC dataset, and reports real numbers.
 *
 * <p>Protocol, all chronological and never shuffled:
 * <ol>
 *   <li><b>Train</b> (first 70 %): normal minutes only (the generator puts no anomalies there). The feature
 *       preprocessing and the forests are fitted here and nowhere else.</li>
 *   <li><b>Validation</b> (next 15 %, after a 1-hour embargo): labelled anomalies of every type. Every combination of
 *       forest size, subsample, threshold quantile and consecutive-minute count is scored here; the one with the best
 *       F1 of the combined detector wins (ties: fewer false alarms per hour, then the smaller forest).</li>
 *   <li><b>Test</b> (last 15 %, after another embargo): different anomaly instances (other sensors, other magnitudes),
 *       scored once with the chosen settings.</li>
 * </ol>
 *
 * <p>Scoring is per minute. A minute inside a labelled window is positive. The {@link #GRACE} minutes after a window
 * are left out of the confusion matrix (a condition that has just ended may still be reported, and a silent device can
 * only be noticed afterwards) but an alarm there still counts as detecting that window. False alarms per hour counts
 * alarm episodes (alarm minutes with gaps of up to 2 minutes merged) that start in a normal minute, per normal hour.
 * Detection latency is the time from a window's start to the first alarm in the window or its grace period.
 *
 * <p>Three detectors are reported: RULES (the existing statistical and rule checks, exactly as the live scanner runs
 * them), ISOLATION_FOREST alone, and HYBRID = RULES or ISOLATION_FOREST. The forest can only add alarms; it never
 * removes a rule finding, so the hybrid's recall can never be below the rules'.
 */
public final class AnomalyBenchmark {

	public static final Duration GRACE = SyntheticAnomalyEvaluator.GRACE;
	public static final String METHOD = "Chronological 70/15/15 split with 1-hour embargoes. Preprocessing and forests fitted on normal training "
			+ "minutes only; settings chosen by the best combined-detector F1 on validation; test scored once. Per-minute labels from the "
			+ "generator; " + GRACE.toMinutes() + " minutes after each window excluded from the confusion matrix. Rules replayed exactly as the "
			+ "live scanner applies them.";

	public enum Detector { RULES, ISOLATION_FOREST, HYBRID }

	public record Confusion(long truePositives, long falsePositives, long falseNegatives, long trueNegatives) {
	}

	public record WindowResult(String scenario, String description, Instant from, Instant to, int minutes, boolean detected, Long latencyMinutes,
			double minuteRecall) {
	}

	public record TypeResult(String scenario, int windows, int detected, double minuteRecall, Double meanLatencyMinutes) {
	}

	public record DetectorResult(String detector, Confusion confusion, Double precision, Double recall, Double f1, int falseAlarms,
			double normalHours, double falseAlarmsPerHour, int windowsDetected, int windows, Double meanLatencyMinutes,
			Double medianLatencyMinutes, List<TypeResult> byType, List<WindowResult> windowResults) {
	}

	public record Candidate(int trees, int subsample, double quantile, int consecutive, double threshold, Double validationF1,
			double validationFalseAlarmsPerHour) {
	}

	public record SplitInfo(Instant trainFrom, Instant trainTo, int trainFrames, Instant validationFrom, Instant validationTo, int validationMinutes,
			Instant testFrom, Instant testTo, int testMinutes, long embargoMinutes) {
	}

	public record Report(String source, String notice, String method, String generatorVersion, long datasetSeed, long forestSeed,
			SplitInfo split, List<String> featureNames, Candidate chosen, int candidatesTried, List<Candidate> topCandidates,
			List<DetectorResult> validation, List<DetectorResult> test, long trainingMillis) {
	}

	public record Outcome(AnomalyModel model, Report report) {
	}

	/** A period's per-minute view: labels, rule alarms and forest scores (NaN where the minute has no frame). */
	private record Timeline(Instant from, Scenario[] label, boolean[] grace, boolean[] rule, List<ScenarioWindow> windows) {
		int minutes() {
			return label.length;
		}
	}

	private AnomalyBenchmark() {
	}

	/** @param occupancy the occupancy model trained on this dataset (for the unusual-motion rule), or null */
	public static Outcome run(SyntheticGenerator.Dataset ds, OccupancyModel occupancy, MlProperties props) {
		long started = System.nanoTime();
		MlProperties.Forest cfg = props.forest();
		Instant trainEnd = ds.start().plusSeconds((long) (ds.days() * 86_400L * AnomalyModelTrainer.TRAIN_FRACTION));
		Instant valStart = SyntheticGenerator.validationStart(ds.start(), ds.days());
		Instant valEnd = SyntheticGenerator.validationEnd(ds.start(), ds.days());
		Instant testStart = SyntheticGenerator.testStart(ds.start(), ds.days());
		Instant testEnd = ds.end();

		List<MinuteFrame> frames = MinuteFrame.resample(points(ds));
		List<MinuteFrame> trainFrames = frames.stream()
				.filter(f -> f.at().isBefore(trainEnd) && SyntheticGenerator.scenarioAt(ds.scenarios(), f.at()) == Scenario.NORMAL).toList();
		AnomalyFeatures.Preprocessing pre = AnomalyFeatures.fit(trainFrames, ds.zone(), cfg.windowMinutes(), props.anomaly());
		List<AnomalyFeatures.Row> trainRows = AnomalyFeatures.compute(trainFrames, pre);
		Map<Long, AnomalyFeatures.Row> byMinute = new HashMap<>();
		for (AnomalyFeatures.Row r : AnomalyFeatures.compute(frames, pre)) {
			byMinute.put(r.frame().at().getEpochSecond() / 60, r);
		}
		Timeline val = timeline(ds, occupancy, props, valStart, valEnd);
		Timeline test = timeline(ds, occupancy, props, testStart, testEnd);
		double[][] x = AnomalyModelTrainer.matrix(trainRows);

		// ---- tuning on validation ----
		List<Candidate> candidates = new ArrayList<>();
		Candidate best = null;
		double bestF1 = -1, bestFa = Double.MAX_VALUE;
		Map<String, List<AnomalyModel.Subspace>> forests = new HashMap<>();
		Map<String, double[]> sortedTrainScores = new HashMap<>();
		for (int trees : cfg.treeCandidates()) {
			for (int subsample : cfg.subsampleCandidates()) {
				List<AnomalyModel.Subspace> forest = AnomalyModel.fit(x, trees, subsample, cfg.seed());
				String key = trees + "/" + subsample;
				forests.put(key, forest);
				double[] ts = AnomalyModelTrainer.scores(forest, trainRows);
				Arrays.sort(ts);
				sortedTrainScores.put(key, ts);
				double[] vs = scores(val, byMinute, forest);
				for (double q : cfg.quantileCandidates()) {
					double thr = AnomalyModelTrainer.quantile(ts, q);
					for (int k : cfg.consecutiveCandidates()) {
						DetectorResult hybrid = evaluate(Detector.HYBRID, val, or(val.rule(), forestAlarms(vs, thr, k)));
						double f1 = hybrid.f1() == null ? 0 : hybrid.f1();
						Candidate c = new Candidate(trees, subsample, q, k, AnomalyModelTrainer.round(thr), hybrid.f1(), hybrid.falseAlarmsPerHour());
						candidates.add(c);
						if (f1 > bestF1 + 1e-12 || (Math.abs(f1 - bestF1) <= 1e-12 && hybrid.falseAlarmsPerHour() < bestFa)) {
							best = c;
							bestF1 = f1;
							bestFa = hybrid.falseAlarmsPerHour();
						}
					}
				}
			}
		}
		String key = best.trees() + "/" + best.subsample();
		List<AnomalyModel.Subspace> forest = forests.get(key);
		AnomalyModel model = AnomalyModelTrainer.assemble(pre, forest, sortedTrainScores.get(key), best.quantile(), best.consecutive(), "SYNTHETIC",
				trainFrames.get(0).at(), trainFrames.get(trainFrames.size() - 1).at(), trainFrames.size());

		// ---- final scores ----
		List<DetectorResult> valResults = all(val, scores(val, byMinute, forest), model);
		List<DetectorResult> testResults = all(test, scores(test, byMinute, forest), model);
		List<Candidate> top = candidates.stream()
				.sorted(Comparator.comparingDouble((Candidate c) -> c.validationF1() == null ? 0 : -c.validationF1())
						.thenComparingDouble(Candidate::validationFalseAlarmsPerHour))
				.limit(10).toList();
		SplitInfo split = new SplitInfo(trainFrames.get(0).at(), trainEnd, trainFrames.size(), valStart, valEnd, val.minutes(), testStart, testEnd,
				test.minutes(), AnomalyModelTrainer.EMBARGO.toMinutes());
		Report report = new Report(SyntheticDtos.SOURCE, SyntheticDtos.NOTICE, METHOD, SyntheticGenerator.VERSION, ds.seed(), cfg.seed(), split,
				AnomalyFeatures.NAMES, best, candidates.size(), top, valResults, testResults, (System.nanoTime() - started) / 1_000_000);
		return new Outcome(model, report);
	}

	private static List<DetectorResult> all(Timeline t, double[] scores, AnomalyModel m) {
		boolean[] f = forestAlarms(scores, m.threshold(), m.consecutive());
		return List.of(evaluate(Detector.RULES, t, t.rule()), evaluate(Detector.ISOLATION_FOREST, t, f), evaluate(Detector.HYBRID, t, or(t.rule(), f)));
	}

	private static List<TelemetryPoint> points(SyntheticGenerator.Dataset ds) {
		List<TelemetryPoint> p = new ArrayList<>(ds.readings().size());
		for (Reading r : ds.readings()) {
			p.add(new TelemetryPoint(r.at(), r.temperature(), r.humidity(), r.gasRaw(), r.motion()));
		}
		return p;
	}

	// ---- per-minute view of a period ---------------------------------------------------------------

	private static Timeline timeline(SyntheticGenerator.Dataset ds, OccupancyModel occupancy, MlProperties props, Instant from, Instant to) {
		SyntheticAnomalyEvaluator.RuleTrace trace = SyntheticAnomalyEvaluator.trace(ds, occupancy, props, from, to);
		int n = trace.minutes().size();
		Scenario[] label = new Scenario[n];
		boolean[] grace = new boolean[n];
		boolean[] rule = new boolean[n];
		for (int i = 0; i < n; i++) {
			Instant t = from.plusSeconds(60L * i);
			label[i] = SyntheticGenerator.scenarioAt(ds.scenarios(), t);
			if (label[i] == Scenario.NORMAL) {
				for (ScenarioWindow w : ds.scenarios()) {
					if (!t.isBefore(w.to()) && t.isBefore(w.to().plus(GRACE))) {
						grace[i] = true;
					}
				}
			}
			rule[i] = !trace.minutes().get(i).isEmpty();
		}
		List<ScenarioWindow> windows = ds.scenarios().stream().filter(w -> !w.from().isBefore(from) && w.from().isBefore(to)).toList();
		return new Timeline(from, label, grace, rule, windows);
	}

	private static double[] scores(Timeline t, Map<Long, AnomalyFeatures.Row> byMinute, List<AnomalyModel.Subspace> forest) {
		double[] s = new double[t.minutes()];
		long first = t.from().getEpochSecond() / 60;
		for (int i = 0; i < s.length; i++) {
			AnomalyFeatures.Row r = byMinute.get(first + i);
			s[i] = r == null ? Double.NaN : AnomalyModel.score(forest, r.x());
		}
		return s;
	}

	/** Score at or above the threshold for k consecutive minutes; a minute without a frame breaks the run. */
	static boolean[] forestAlarms(double[] scores, double threshold, int k) {
		boolean[] a = new boolean[scores.length];
		int run = 0;
		for (int i = 0; i < a.length; i++) {
			run = !Double.isNaN(scores[i]) && scores[i] >= threshold ? run + 1 : 0;
			a[i] = run >= k;
		}
		return a;
	}

	private static boolean[] or(boolean[] a, boolean[] b) {
		boolean[] c = new boolean[a.length];
		for (int i = 0; i < a.length; i++) {
			c[i] = a[i] || b[i];
		}
		return c;
	}

	// ---- metrics -----------------------------------------------------------------------------------

	private static DetectorResult evaluate(Detector d, Timeline t, boolean[] alarm) {
		long tp = 0, fp = 0, fn = 0, tn = 0, normalMinutes = 0;
		for (int i = 0; i < t.minutes(); i++) {
			if (t.grace()[i]) {
				continue;
			}
			boolean positive = t.label()[i] != Scenario.NORMAL;
			if (positive) {
				if (alarm[i]) {
					tp++;
				} else {
					fn++;
				}
			} else {
				normalMinutes++;
				if (alarm[i]) {
					fp++;
				} else {
					tn++;
				}
			}
		}
		// False alarm episodes: alarm runs (gaps <= 2 minutes merged) that start in a normal, non-grace minute.
		int falseAlarms = 0;
		int lastAlarm = Integer.MIN_VALUE / 2;
		for (int i = 0; i < t.minutes(); i++) {
			if (!alarm[i]) {
				continue;
			}
			if (i - lastAlarm > 3 && t.label()[i] == Scenario.NORMAL && !t.grace()[i]) {
				falseAlarms++;
			}
			lastAlarm = i;
		}
		List<WindowResult> windows = new ArrayList<>();
		long first = t.from().getEpochSecond() / 60;
		for (ScenarioWindow w : t.windows()) {
			int start = (int) (w.from().getEpochSecond() / 60 - first);
			int end = (int) (w.to().getEpochSecond() / 60 - first);
			int graceEnd = (int) Math.min(t.minutes(), w.to().plus(GRACE).getEpochSecond() / 60 - first);
			Long latency = null;
			for (int i = start; i < graceEnd; i++) {
				if (alarm[i]) {
					latency = (long) (i - start);
					break;
				}
			}
			int hit = 0, len = 0;
			for (int i = start; i < Math.min(end, t.minutes()); i++) {
				len++;
				hit += alarm[i] ? 1 : 0;
			}
			windows.add(new WindowResult(w.scenario().name(), w.description(), w.from(), w.to(), len, latency != null, latency,
					len == 0 ? 0 : round(hit / (double) len)));
		}
		Map<String, List<WindowResult>> byType = new LinkedHashMap<>();
		for (WindowResult w : windows) {
			byType.computeIfAbsent(w.scenario(), k -> new ArrayList<>()).add(w);
		}
		List<TypeResult> types = new ArrayList<>();
		for (Map.Entry<String, List<WindowResult>> e : byType.entrySet()) {
			List<WindowResult> ws = e.getValue();
			int detected = (int) ws.stream().filter(WindowResult::detected).count();
			int minutes = ws.stream().mapToInt(WindowResult::minutes).sum();
			double hits = ws.stream().mapToDouble(w -> w.minuteRecall() * w.minutes()).sum();
			Double latency = ws.stream().filter(WindowResult::detected).mapToLong(WindowResult::latencyMinutes).average().stream().boxed().findFirst()
					.orElse(null);
			types.add(new TypeResult(e.getKey(), ws.size(), detected, minutes == 0 ? 0 : round(hits / minutes), latency == null ? null : round(latency)));
		}
		long[] lat = windows.stream().filter(WindowResult::detected).mapToLong(WindowResult::latencyMinutes).sorted().toArray();
		Double mean = lat.length == 0 ? null : round(Arrays.stream(lat).average().orElse(0));
		Double median = lat.length == 0 ? null : (lat.length % 2 == 1 ? lat[lat.length / 2] : (lat[lat.length / 2 - 1] + lat[lat.length / 2]) / 2.0);
		Double precision = tp + fp == 0 ? null : round(tp / (double) (tp + fp));
		Double recall = tp + fn == 0 ? null : round(tp / (double) (tp + fn));
		Double f1 = precision == null || recall == null || precision + recall == 0 ? null : round(2 * precision * recall / (precision + recall));
		double hours = normalMinutes / 60.0;
		return new DetectorResult(d.name(), new Confusion(tp, fp, fn, tn), precision, recall, f1, falseAlarms, round(hours),
				hours == 0 ? 0 : round(falseAlarms / hours), (int) windows.stream().filter(WindowResult::detected).count(), windows.size(), mean, median,
				types, windows);
	}

	private static double round(double v) {
		return Math.round(v * 10_000) / 10_000.0;
	}
}
