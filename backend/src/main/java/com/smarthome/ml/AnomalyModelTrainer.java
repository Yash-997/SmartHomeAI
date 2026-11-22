package com.smarthome.ml;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * Trains the Isolation Forest anomaly model. Shared by the labelled SYNTHETIC benchmark ({@link AnomalyBenchmark}) and
 * by training on a device's real telemetry, which has no labels.
 *
 * <p>Both use a chronological split of the minute frames, never shuffled: 70 % train, 15 % validation, 15 % test, with
 * a one-hour embargo after each boundary (the rolling-window features of the first minutes of a part would otherwise
 * reach back into the previous part). The forest and its preprocessing are fitted on the training part only, the
 * decision rule is chosen on validation only, and test is scored once at the end.
 *
 * <p>On real telemetry nothing says which minutes were anomalous, so precision and recall cannot be measured and are not
 * claimed. Instead the threshold is chosen to keep false alarms on validation within a budget (most real minutes are
 * normal), and the test part shows whether that holds on later data. Minutes inside already recorded anomalies (except
 * dismissed ones, which the owner marked as false positives) are left out of training, so the model learns mostly from
 * normal behaviour.
 */
public final class AnomalyModelTrainer {

	public enum Status { READY, LOW_QUALITY, INSUFFICIENT_DATA }

	/** Alarm rate of one candidate decision rule on unlabelled validation data. */
	public record UnlabelledCandidate(double quantile, int consecutive, double threshold, double validationAlarmsPerDay) {
	}

	public record UnlabelledReport(Instant trainFrom, Instant trainTo, int trainFrames, int excludedFrames, Instant validationFrom,
			Instant validationTo, int validationFrames, Instant testFrom, Instant testTo, int testFrames, int trees, int subsample, long seed,
			double quantile, int consecutive, double threshold, double criticalThreshold, double validationAlarmsPerDay, double testAlarmsPerDay,
			double testFlaggedMinuteShare, double alarmBudgetPerDay, List<UnlabelledCandidate> candidates, String note) {
	}

	public record Result(Status status, String message, AnomalyModel model, UnlabelledReport report, int frames, int distinctDays,
			Instant dataFrom, Instant dataTo) {
	}

	static final double TRAIN_FRACTION = SyntheticTrainer.TRAIN_FRACTION;
	static final double VALIDATION_FRACTION = SyntheticTrainer.VALIDATION_FRACTION;
	static final Duration EMBARGO = SyntheticTrainer.EMBARGO;
	static final String NO_LABELS = "Real telemetry has no ground-truth labels, so precision, recall and F1 cannot be measured on it. "
			+ "The figures are alarm rates: the decision rule was chosen on the validation part to stay within the alarm budget, "
			+ "and the test part (later data, never used for fitting or choosing) shows whether that holds.";

	private AnomalyModelTrainer() {
	}

	// ---- shared helpers ---------------------------------------------------------------------------

	static double[][] matrix(List<AnomalyFeatures.Row> rows) {
		double[][] x = new double[rows.size()][];
		for (int i = 0; i < x.length; i++) {
			x[i] = rows.get(i).x();
		}
		return x;
	}

	static double[] scores(List<AnomalyModel.Subspace> forest, List<AnomalyFeatures.Row> rows) {
		double[] s = new double[rows.size()];
		for (int i = 0; i < s.length; i++) {
			s[i] = AnomalyModel.score(forest, rows.get(i).x());
		}
		return s;
	}

	/** Linear-interpolated quantile of an ascending array. */
	static double quantile(double[] sorted, double q) {
		if (sorted.length == 0) {
			return 1;
		}
		double pos = q * (sorted.length - 1);
		int lo = (int) Math.floor(pos);
		int hi = Math.min(sorted.length - 1, lo + 1);
		return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
	}

	static AnomalyModel assemble(AnomalyFeatures.Preprocessing p, List<AnomalyModel.Subspace> forest, double[] sortedTrainScores, double q, int k,
			String source, Instant from, Instant to, int frames) {
		double threshold = quantile(sortedTrainScores, q);
		double critical = Math.max(sortedTrainScores.length == 0 ? 1 : sortedTrainScores[sortedTrainScores.length - 1], threshold + 0.01);
		return new AnomalyModel(AnomalyModel.CURRENT_VERSION, AnomalyFeatures.FEATURE_SET, AnomalyFeatures.NAMES, source, p, forest, q, threshold,
				critical, k, from, to, frames);
	}

	/**
	 * Alarm flags per row for "score >= threshold for k consecutive minutes". A gap of more than a minute between frames
	 * breaks the run.
	 */
	static boolean[] alarms(List<AnomalyFeatures.Row> rows, double[] scores, double threshold, int k) {
		boolean[] a = new boolean[rows.size()];
		int run = 0;
		for (int i = 0; i < a.length; i++) {
			boolean contiguous = i > 0 && Duration.between(rows.get(i - 1).frame().at(), rows.get(i).frame().at()).toMinutes() <= 1;
			run = scores[i] >= threshold ? (contiguous ? run + 1 : 1) : 0;
			a[i] = run >= k;
		}
		return a;
	}

	/** Alarm episodes: runs of alarm frames, with gaps of up to 2 minutes merged. */
	static int episodes(List<AnomalyFeatures.Row> rows, boolean[] alarm) {
		int n = 0;
		Instant last = null;
		for (int i = 0; i < alarm.length; i++) {
			if (!alarm[i]) {
				continue;
			}
			Instant t = rows.get(i).frame().at();
			if (last == null || Duration.between(last, t).toMinutes() > 2) {
				n++;
			}
			last = t;
		}
		return n;
	}

	static List<AnomalyFeatures.Row> between(List<AnomalyFeatures.Row> rows, Instant from, Instant to) {
		return rows.stream().filter(r -> !r.frame().at().isBefore(from) && r.frame().at().isBefore(to)).toList();
	}

	static int distinctDays(List<MinuteFrame> frames, ZoneId zone) {
		return (int) frames.stream().map(f -> f.at().atZone(zone).toLocalDate()).distinct().count();
	}

	// ---- real telemetry (no labels) ---------------------------------------------------------------

	/**
	 * @param frames   minute frames, oldest first
	 * @param excluded minutes to leave out of training (inside recorded anomalies)
	 */
	public static Result trainUnlabelled(List<MinuteFrame> frames, ZoneId zone, MlProperties props, Predicate<Instant> excluded) {
		MlProperties.Forest cfg = props.forest();
		int days = distinctDays(frames, zone);
		Instant first = frames.isEmpty() ? null : frames.get(0).at();
		Instant last = frames.isEmpty() ? null : frames.get(frames.size() - 1).at();
		if (frames.size() < cfg.minTrainingFrames() || days < cfg.minDistinctDays()) {
			return new Result(Status.INSUFFICIENT_DATA, String.format(java.util.Locale.ROOT,
					"Not enough history yet: %d minutes with readings over %d days; the anomaly model needs at least %d minutes over %d days.",
					frames.size(), days, cfg.minTrainingFrames(), cfg.minDistinctDays()), null, null, frames.size(), days, first, last);
		}
		long span = Duration.between(first, last).getSeconds();
		Instant trainEnd = first.plusSeconds((long) (span * TRAIN_FRACTION));
		Instant valStart = trainEnd.plus(EMBARGO);
		Instant valEnd = first.plusSeconds((long) (span * (TRAIN_FRACTION + VALIDATION_FRACTION)));
		Instant testStart = valEnd.plus(EMBARGO);
		Instant testEnd = last.plusSeconds(60);

		List<MinuteFrame> trainFrames = frames.stream().filter(f -> f.at().isBefore(trainEnd) && !excluded.test(f.at())).toList();
		int excludedCount = (int) frames.stream().filter(f -> f.at().isBefore(trainEnd) && excluded.test(f.at())).count();
		if (trainFrames.size() < Math.max(256, cfg.minTrainingFrames() / 2)) {
			return new Result(Status.INSUFFICIENT_DATA, "Too few normal minutes in the training part of the history (" + trainFrames.size() + ").",
					null, null, frames.size(), days, first, last);
		}
		AnomalyFeatures.Preprocessing pre = AnomalyFeatures.fit(trainFrames, zone, cfg.windowMinutes(), props.anomaly());
		List<AnomalyFeatures.Row> trainRows = AnomalyFeatures.compute(trainFrames, pre);
		List<AnomalyFeatures.Row> all = AnomalyFeatures.compute(frames, pre);
		List<AnomalyFeatures.Row> val = between(all, valStart, valEnd);
		List<AnomalyFeatures.Row> test = between(all, testStart, testEnd);
		List<AnomalyModel.Subspace> forest = AnomalyModel.fit(matrix(trainRows), cfg.trees(), cfg.subsample(), cfg.seed());
		double[] trainScores = scores(forest, trainRows);
		Arrays.sort(trainScores);
		double[] valScores = scores(forest, val);
		double valDays = Math.max(1.0 / 24, Duration.between(valStart, valEnd).getSeconds() / 86_400.0);

		List<UnlabelledCandidate> candidates = new ArrayList<>();
		UnlabelledCandidate chosen = null;
		List<Double> qs = cfg.quantileCandidates().stream().sorted().toList();
		List<Integer> ks = cfg.consecutiveCandidates().stream().sorted().toList();
		for (double q : qs) {                                    // most sensitive first: the first rule within budget wins
			for (int k : ks) {
				double thr = quantile(trainScores, q);
				double rate = episodes(val, alarms(val, valScores, thr, k)) / valDays;
				UnlabelledCandidate c = new UnlabelledCandidate(q, k, round(thr), round(rate));
				candidates.add(c);
				if (chosen == null && rate <= cfg.maxFalseAlarmsPerDay()) {
					chosen = c;
				}
			}
		}
		if (chosen == null) {
			chosen = candidates.get(candidates.size() - 1);        // nothing within budget: the least sensitive rule
		}
		AnomalyModel model = assemble(pre, forest, trainScores, chosen.quantile(), chosen.consecutive(), "REAL",
				trainFrames.get(0).at(), trainFrames.get(trainFrames.size() - 1).at(), trainFrames.size());
		double[] testScores = scores(forest, test);
		boolean[] testAlarms = alarms(test, testScores, model.threshold(), model.consecutive());
		double testDays = Math.max(1.0 / 24, Duration.between(testStart, testEnd).getSeconds() / 86_400.0);
		double testRate = episodes(test, testAlarms) / testDays;
		int flagged = 0;
		for (boolean b : testAlarms) {
			flagged += b ? 1 : 0;
		}
		UnlabelledReport report = new UnlabelledReport(trainFrames.get(0).at(), trainEnd, trainFrames.size(), excludedCount, valStart, valEnd,
				val.size(), testStart, testEnd, test.size(), cfg.trees(), forest.get(0).forest().subsample(), cfg.seed(), chosen.quantile(), chosen.consecutive(),
				round(model.threshold()), round(model.criticalThreshold()), chosen.validationAlarmsPerDay(), round(testRate),
				test.isEmpty() ? 0 : round(flagged / (double) test.size()), cfg.maxFalseAlarmsPerDay(), candidates, NO_LABELS);
		boolean withinBudget = testRate <= 3 * cfg.maxFalseAlarmsPerDay() && chosen.validationAlarmsPerDay() <= cfg.maxFalseAlarmsPerDay();
		String message = withinBudget
				? String.format(java.util.Locale.ROOT, "Trained on %d normal minutes of real telemetry. On later, unseen data it raised %.2f alarms per day "
						+ "(budget %.1f). Without labels this is an alarm rate, not an accuracy.", trainFrames.size(), testRate, cfg.maxFalseAlarmsPerDay())
				: String.format(java.util.Locale.ROOT, "Trained, but it would raise %.2f alarms per day on later data (budget %.1f), so it is not used "
						+ "for live detection. More, steadier history usually helps.", testRate, cfg.maxFalseAlarmsPerDay());
		return new Result(withinBudget ? Status.READY : Status.LOW_QUALITY, message, model, report, frames.size(), days, first, last);
	}

	static double round(double v) {
		return Math.round(v * 10_000) / 10_000.0;
	}
}
