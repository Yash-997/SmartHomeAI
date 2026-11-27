package com.smarthome.predict;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import com.smarthome.ml.ModelMetrics;
import com.smarthome.ml.OccupancyFeatures;
import com.smarthome.predict.Learner.Algorithm;

/**
 * Presence ("is the resident home now?") and arrival ("if away, back within H minutes?") classifiers. For each of the five
 * targets the algorithm with the lowest validation log loss is chosen. Training and inference are separate: a trained model
 * is just {@link Stored} plus the history it learned from. It only predicts and has no access to any device.
 */
public final class PredictiveModel {

	public static final int[] HORIZONS = { 15, 30, 60, 120 };
	/** A day this many days older than the newest counts half as much, in training and in historical frequencies. */
	public static final double HALF_LIFE_DAYS = 14;
	private static final int STEP = 5;
	private static final int FULL_SUPPORT_DAYS = 8;
	private static final double PRIOR = 3;
	public static final double LIKELY_OCCUPIED = 0.7;
	public static final double LIKELY_EMPTY = 0.3;
	public static final int MIN_DAYS = 14;
	private static final long SEED = 7;

	public enum State { LIKELY_OCCUPIED, LIKELY_EMPTY, UNCERTAIN }

	/** {@code at} is the moment the prediction is for; the decision engine refuses old ones. */
	public record Presence(double probability, State state, double confidence, LocalDateTime at) {
	}

	public record Horizon(int minutes, double probability, double confidence) {
	}

	/**
	 * Quartiles of past return times later than "now" ({@code null} when history has none), from {@code supportDays} of
	 * {@code comparableDays} past days of the same kind (weekday or weekend).
	 */
	public record Arrival(List<Horizon> horizons, Integer windowFrom, Integer windowTo, Integer median, int supportDays,
			int comparableDays) {
		public Horizon at(int minutes) {
			return horizons.stream().filter(h -> h.minutes() == minutes).findFirst().orElseThrow();
		}
	}

	/** Everything needed for inference besides the history. */
	public record Stored(Learner presence, List<Learner> arrival, Evaluation evaluation, int trainingRows) {
	}

	private final List<DayTrace> history;
	private Stored stored;

	private PredictiveModel(List<DayTrace> history) {
		this.history = List.copyOf(history);
	}

	public static PredictiveModel restore(List<DayTrace> history, Stored stored) {
		PredictiveModel m = new PredictiveModel(history);
		m.stored = stored;
		return m;
	}

	/** The same learned classifiers over a different recent history (what-if adaptation without retraining). */
	public PredictiveModel withHistory(List<DayTrace> other) {
		return restore(other, stored);
	}

	/** Selects algorithms on a chronological validation split, scores them on a later test split, then refits on all days. */
	public static PredictiveModel train(List<DayTrace> days) {
		if (days.size() < MIN_DAYS || days.stream().anyMatch(d -> d.present() == null)) {
			throw new IllegalArgumentException(
					"Cold start: training needs at least " + MIN_DAYS + " days with occupancy labels, got " + days.size());
		}
		int trainEnd = (int) Math.round(days.size() * 0.6), validationEnd = (int) Math.round(days.size() * 0.8);
		List<DayTrace> validationDays = days.subList(trainEnd, validationEnd);
		List<DayTrace> testDays = days.subList(validationEnd, days.size());
		PredictiveModel early = new PredictiveModel(days.subList(0, trainEnd));
		PredictiveModel beforeTest = new PredictiveModel(days.subList(0, validationEnd));
		PredictiveModel all = new PredictiveModel(days);

		List<Outcome> outcomes = IntStream.range(0, HORIZONS.length + 1).parallel()
				.mapToObj(i -> new Target(i == 0 ? 0 : HORIZONS[i - 1]).run(early, validationDays, beforeTest, testDays, all))
				.toList();

		beforeTest.stored = new Stored(outcomes.get(0).tested(), outcomes.stream().skip(1).map(Outcome::tested).toList(), null, 0);
		ArrivalTimes times = arrivalTimes(beforeTest, testDays);
		Evaluation evaluation = new Evaluation("SYNTHETIC DATA EVALUATION - this is not real-world accuracy", trainEnd,
				validationDays.size(), testDays.size(),
				"Chronological split by day: train " + trainEnd + ", validation " + validationDays.size()
						+ " (picks the algorithm), test " + testDays.size() + " (scored once). Sampled every " + STEP
						+ " minutes; minutes without data are skipped.",
				"Baseline: recency-weighted historical share for the same quarter hour on the same kind of day, with no model.",
				outcomes.stream().map(Outcome::comparison).toList(), times.error(), times.coverage(), times.days());
		all.stored = new Stored(outcomes.get(0).learner(), outcomes.stream().skip(1).map(Outcome::learner).toList(), evaluation,
				outcomes.get(0).rows());
		return all;
	}

	public Stored stored() {
		return stored;
	}

	public List<DayTrace> history() {
		return history;
	}

	public Presence presence(DayTrace today, int minute) {
		double p = stored.presence().probability(presenceFeatures(today, minute));
		State state = p >= LIKELY_OCCUPIED ? State.LIKELY_OCCUPIED : p <= LIKELY_EMPTY ? State.LIKELY_EMPTY : State.UNCERTAIN;
		return new Presence(p, state, Math.max(p, 1 - p), today.date().atTime(LocalTime.ofSecondOfDay(minute * 60L)));
	}

	/** Meaningful only while the resident is predicted to be away. */
	public Arrival arrival(DayTrace today, int minute) {
		List<Integer> later = new ArrayList<>();
		List<DayTrace> comparable = comparable(today);
		for (DayTrace d : comparable) {
			int back = d.returnMinute();
			if (back > minute) {
				later.add(back);
			}
		}
		later.sort(null);
		double support = Math.min(1.0, later.size() / (double) FULL_SUPPORT_DAYS);
		List<Horizon> out = new ArrayList<>();
		double floor = 0;
		for (int h = 0; h < HORIZONS.length; h++) {
			double p = stored.arrival().get(h).probability(arrivalFeatures(today, minute, HORIZONS[h]));
			floor = Math.max(floor, p); // returning within 30 min cannot be less likely than within 15
			out.add(new Horizon(HORIZONS[h], floor, Math.max(floor, 1 - floor) * support));
		}
		boolean any = !later.isEmpty();
		return new Arrival(out, any ? quantile(later, 0.25) : null, any ? quantile(later, 0.75) : null,
				any ? quantile(later, 0.5) : null, later.size(), comparable.size());
	}

	/** Share of past days of this kind with motion in each hour, for the activity chart. */
	public double[] hourlyMotionRate(boolean weekend) {
		double[] rate = new double[24];
		List<DayTrace> days = history.stream().filter(d -> d.weekend() == weekend).toList();
		for (int h = 0; h < 24 && !days.isEmpty(); h++) {
			int hour = h;
			rate[h] = days.stream().filter(d -> d.motionCount(hour * 60 + 59, 60) > 0).count() / (double) days.size();
		}
		return rate;
	}

	/** Share of minutes the resident was home in each hour (synthetic ground truth). */
	public double[] hourlyPresenceRate(boolean weekend) {
		double[] rate = new double[24];
		List<DayTrace> days = history.stream().filter(d -> d.weekend() == weekend).toList();
		for (int h = 0; h < 24 && !days.isEmpty(); h++) {
			int home = 0;
			for (DayTrace d : days) {
				for (int m = h * 60; m < h * 60 + 60; m++) {
					home += d.present()[m] ? 1 : 0;
				}
			}
			rate[h] = home / (60.0 * days.size());
		}
		return rate;
	}

	// ---- features -------------------------------------------------------------------------------------------------

	private List<DayTrace> comparable(DayTrace day) {
		return history.stream().filter(d -> d.weekend() == day.weekend() && !d.date().equals(day.date())).toList();
	}

	private double weight(DayTrace d) {
		long age = ChronoUnit.DAYS.between(d.date(), history.get(history.size() - 1).date());
		return Math.min(1, Math.pow(0.5, age / HALF_LIFE_DAYS));
	}

	/** Recency-weighted, smoothed share of comparable days on which {@code test} held. The day itself is left out. */
	private double historicalShare(DayTrace day, Predicate<DayTrace> eligible, Predicate<DayTrace> test) {
		double n = 0, hits = 0;
		for (DayTrace d : comparable(day)) {
			if (eligible.test(d)) {
				double w = weight(d);
				n += w;
				if (test.test(d)) {
					hits += w;
				}
			}
		}
		return (hits + PRIOR * 0.5) / (n + PRIOR);
	}

	private double presenceShare(DayTrace day, int minute) {
		int slot = minute / 15 * 15;
		return historicalShare(day, d -> d.seen(slot), d -> d.present()[slot]);
	}

	private double arrivalShare(DayTrace day, int minute, int horizon) {
		int slot = minute / 15 * 15;
		return historicalShare(day, d -> !d.present()[slot], d -> returnsWithin(d, slot, horizon));
	}

	/** Historical motion pattern: how often there was motion around this time. */
	private double activityShare(DayTrace day, int minute) {
		int end = Math.min(DayTrace.MINUTES - 1, minute / 15 * 15 + 14);
		return historicalShare(day, d -> true, d -> d.motionCount(end, 30) > 0);
	}

	/** Hour harmonics, day of week and weekend flag, plus raw forms of each that trees can split on. */
	private static double[] timeFeatures(DayTrace day, int minute) {
		double[] harmonics = OccupancyFeatures.of(day.date().atTime(LocalTime.ofSecondOfDay(minute * 60L)).atZone(ZoneOffset.UTC));
		return append(harmonics, minute / 1440.0, day.date().getDayOfWeek().getValue() / 7.0, day.weekend() ? 1 : 0);
	}

	private double[] presenceFeatures(DayTrace day, int minute) {
		int since = day.sinceMotion(minute, 720);
		boolean waking = minute >= 7 * 60 && minute < 22 * 60;
		return append(timeFeatures(day, minute), logOdds(presenceShare(day, minute)), activityShare(day, minute),
				Math.log1p(day.motionCount(minute, 5)), Math.log1p(day.motionCount(minute, 15)),
				Math.log1p(day.motionCount(minute, 30)), Math.log1p(day.motionCount(minute, 180)) / 3,
				since <= 5 ? 1 : 0, Math.log1p(since) / 3, waking ? Math.log1p(since) / 3 : 0,
				DayTrace.change(day.temperature(), minute, 60), DayTrace.change(day.humidity(), minute, 60) / 5,
				DayTrace.change(day.gas(), minute, 180) / 100, day.missingShare(minute, 30));
	}

	private double[] arrivalFeatures(DayTrace day, int minute, int horizon) {
		return append(timeFeatures(day, minute), logOdds(arrivalShare(day, minute, horizon)),
				Math.log1p(day.sinceMotion(minute, 720)) / 3, day.motionCount(minute, 15) > 0 ? 1 : 0,
				Math.log1p(day.motionCount(minute, 720)) / 4, day.missingShare(minute, 30));
	}

	private static double logOdds(double share) {
		double c = Math.min(0.98, Math.max(0.02, share));
		return Math.log(c / (1 - c));
	}

	private static boolean returnsWithin(DayTrace day, int minute, int horizon) {
		for (int i = minute + 1; i <= Math.min(DayTrace.MINUTES - 1, minute + horizon); i++) {
			if (day.present()[i]) {
				return true;
			}
		}
		return false;
	}

	private static double[] append(double[] base, double... extra) {
		double[] x = Arrays.copyOf(base, base.length + extra.length);
		System.arraycopy(extra, 0, x, base.length, extra.length);
		return x;
	}

	static int quantile(List<Integer> sorted, double q) {
		return sorted.get((int) Math.round(q * (sorted.size() - 1)));
	}

	// ---- training and evaluation ----------------------------------------------------------------------------------

	/** {@code falsePositive} = predicted occupied/returning but was not; {@code falseNegative} = missed it. */
	public record Score(int n, int positives, int truePositive, int falsePositive, int falseNegative, int trueNegative,
			double accuracy, Double precision, Double recall, Double f1, Double auc, double logLoss, double brier) {
	}

	/** One probability bucket: the mean predicted probability against how often it really happened. */
	public record Calibration(double from, double to, int n, double meanPredicted, double observedRate) {
	}

	public record Comparison(String target, Algorithm chosen, Map<Algorithm, Double> validationLogLoss, Score model,
			Score baseline, List<Calibration> calibration) {
	}

	public record Evaluation(String data, int trainDays, int validationDays, int testDays, String method, String baselines,
			List<Comparison> results, Double arrivalMedianErrorMinutes, Double arrivalWindowCoverage, int arrivalDaysScored) {
	}

	private record Samples(double[][] x, int[] y, double[] w, double[] share) {
	}

	private record Outcome(Learner learner, Learner tested, Comparison comparison, int rows) {
	}

	/** {@code horizon == 0} is presence; otherwise arrival within that many minutes (only moments when away). */
	private record Target(int horizon) {

		Samples samples(PredictiveModel m, List<DayTrace> days) {
			List<double[]> x = new ArrayList<>();
			List<double[]> meta = new ArrayList<>(); // {label, weight, baseline share}
			for (DayTrace day : days) {
				for (int t = 0; t < DayTrace.MINUTES; t += STEP) {
					if (!day.seen(t) || (horizon > 0 && day.present()[t])) {
						continue;
					}
					x.add(horizon == 0 ? m.presenceFeatures(day, t) : m.arrivalFeatures(day, t, horizon));
					boolean label = horizon == 0 ? day.present()[t] : returnsWithin(day, t, horizon);
					meta.add(new double[] { label ? 1 : 0, m.weight(day),
							horizon == 0 ? m.presenceShare(day, t) : m.arrivalShare(day, t, horizon) });
				}
			}
			return new Samples(x.toArray(double[][]::new), meta.stream().mapToInt(r -> (int) r[0]).toArray(),
					meta.stream().mapToDouble(r -> r[1]).toArray(), meta.stream().mapToDouble(r -> r[2]).toArray());
		}

		Outcome run(PredictiveModel early, List<DayTrace> validationDays, PredictiveModel beforeTest, List<DayTrace> testDays,
				PredictiveModel all) {
			String name = horizon == 0 ? "Occupancy now" : "Return within " + horizon + " min";
			Samples train = samples(early, early.history), validation = samples(early, validationDays);
			Samples development = samples(beforeTest, beforeTest.history), test = samples(beforeTest, testDays);
			Samples everything = samples(all, all.history);
			for (Samples s : List.of(train, development, everything)) {
				if (s.y().length == 0 || IntStream.of(s.y()).distinct().count() < 2) {
					throw new IllegalArgumentException("History is too one-sided to learn \"" + name + "\"");
				}
			}
			Map<Algorithm, Double> losses = new EnumMap<>(Algorithm.class);
			Algorithm best = Algorithm.LOGISTIC_REGRESSION;
			if (validation.y().length > 0) {
				for (Algorithm a : Algorithm.values()) {
					Learner l = Learner.fit(a, train.x(), train.y(), train.w(), SEED);
					losses.put(a, ModelMetrics.evaluate(probabilities(l, validation), validation.y(), 0.5, 0.5).logLoss());
					if (losses.get(a) < losses.getOrDefault(best, Double.MAX_VALUE)) {
						best = a;
					}
				}
			}
			Learner tested = Learner.fit(best, development.x(), development.y(), development.w(), SEED);
			double[] p = test.y().length == 0 ? null : probabilities(tested, test);
			Comparison comparison = new Comparison(name, best, losses, p == null ? null : score(p, test.y()),
					p == null ? null : score(test.share(), test.y()), p == null ? List.of() : calibration(p, test.y()));
			return new Outcome(Learner.fit(best, everything.x(), everything.y(), everything.w(), SEED), tested, comparison,
					everything.y().length);
		}
	}

	private static double[] probabilities(Learner l, Samples s) {
		return Arrays.stream(s.x()).mapToDouble(l::probability).toArray();
	}

	private static Score score(double[] p, int[] y) {
		int tp = 0, fp = 0, fn = 0, tn = 0;
		for (int i = 0; i < p.length; i++) {
			if (p[i] >= 0.5) {
				if (y[i] == 1) tp++; else fp++;
			} else {
				if (y[i] == 1) fn++; else tn++;
			}
		}
		ModelMetrics.Metrics m = ModelMetrics.evaluate(p, y, 0.5, 0.5);
		return new Score(m.n(), m.positives(), tp, fp, fn, tn, m.accuracy(), m.precision(), m.recall(), m.f1(), m.auc(),
				m.logLoss(), m.brier());
	}

	private static List<Calibration> calibration(double[] p, int[] y) {
		List<Calibration> out = new ArrayList<>();
		for (int b = 0; b < 5; b++) {
			double from = b / 5.0, to = (b + 1) / 5.0, predicted = 0, observed = 0;
			int n = 0;
			for (int i = 0; i < p.length; i++) {
				if (p[i] >= from && (p[i] < to || b == 4)) {
					n++;
					predicted += p[i];
					observed += y[i];
				}
			}
			if (n > 0) {
				out.add(new Calibration(from, to, n, predicted / n, observed / n));
			}
		}
		return out;
	}

	private record ArrivalTimes(Double error, Double coverage, int days) {
	}

	/** Arrival time judged at 16:00 on test days where the resident was away then and came back later. */
	private static ArrivalTimes arrivalTimes(PredictiveModel model, List<DayTrace> test) {
		double error = 0;
		int covered = 0, scored = 0;
		for (DayTrace d : test) {
			int back = d.returnMinute();
			if (back > 16 * 60 && !d.present()[16 * 60]) {
				Arrival a = model.arrival(d, 16 * 60);
				if (a.median() != null) {
					scored++;
					error += Math.abs(a.median() - back);
					if (back >= a.windowFrom() && back <= a.windowTo()) {
						covered++;
					}
				}
			}
		}
		return new ArrivalTimes(scored == 0 ? null : error / scored, scored == 0 ? null : covered / (double) scored, scored);
	}
}
