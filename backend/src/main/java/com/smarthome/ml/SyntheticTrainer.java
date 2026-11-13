package com.smarthome.ml;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.smarthome.ml.ModelMetrics.Metrics;
import com.smarthome.ml.OccupancyDataset.Bucket;
import com.smarthome.ml.SyntheticGenerator.Reading;
import com.smarthome.ml.SyntheticGenerator.Scenario;

/**
 * Trains the existing occupancy model (same features, same logistic regression) on a SYNTHETIC dataset with a strict
 * chronological train / validation / test split:
 * <ul>
 *   <li>70 % / 15 % / 15 % of the time span, in that order, never shuffled.</li>
 *   <li>A one-hour embargo after each boundary: slots that close to the previous part are dropped, so neighbouring
 *       15-minute windows cannot leak information across the split.</li>
 *   <li>The regularisation strength is chosen on validation only. The final model is refitted on train + validation
 *       and scored once on the test part, which nothing else has touched. Hour-of-week frequencies come from train (+
 *       validation for the final model), never from test.</li>
 *   <li>Slots inside the labelled anomaly scenarios are left out of occupancy training and scoring (they are test data
 *       for anomaly detection).</li>
 * </ul>
 * The target is the same as for real data: did the PIR see motion in the slot. Because the generator also knows the true
 * occupancy, the result additionally reports how well that motion forecast agrees with true occupancy (a sleeping
 * household is occupied but quiet, so this is expected to be lower; it is reported, not tuned).
 */
public final class SyntheticTrainer {

	static final double[] LAMBDAS = { 0.1, 1.0, 10.0 };
	static final Duration EMBARGO = Duration.ofHours(1);
	static final double TRAIN_FRACTION = 0.70;
	static final double VALIDATION_FRACTION = 0.15;

	public record Split(int slots, int withMotion, Instant from, Instant to) {
	}

	/** Agreement of the motion forecast with the generator's true occupancy, on the test slots. */
	public record OccupancyAgreement(int slots, double accuracy, double majorityBaseline) {
	}

	public record Result(OccupancyTrainer.Status status, String message, OccupancyModel model, double lambda, Map<String, Double> validationLogLoss,
			Split train, Split validation, Split test, int embargoDropped, int scenarioSlotsExcluded, Metrics metrics,
			OccupancyAgreement agreement, OccupancyTrainer.Counts counts) {
	}

	private SyntheticTrainer() {
	}

	public static Result train(SyntheticGenerator.Dataset ds, MlProperties.Occupancy cfg) {
		long width = cfg.bucketMinutes() * 60L;
		List<MotionSample> samples = new ArrayList<>();
		Map<Long, int[]> truth = new HashMap<>();                // slot start -> {readings, occupied readings}
		Map<Long, Boolean> scenarioSlot = new HashMap<>();
		for (Reading r : ds.readings()) {
			long slot = Math.floorDiv(r.at().getEpochSecond(), width) * width;
			if (r.scenario() != Scenario.NORMAL) {
				scenarioSlot.put(slot, true);
			}
			if (r.motion() == null) {
				continue;
			}
			samples.add(new MotionSample(r.at(), r.motion()));
			int[] t = truth.computeIfAbsent(slot, k -> new int[2]);
			t[0]++;
			if (r.occupied()) {
				t[1]++;
			}
		}
		OccupancyDataset.Built built = OccupancyDataset.build(samples, ds.zone(), cfg.bucketMinutes(), cfg.minReadingsPerBucket());
		long span = Duration.between(ds.start(), ds.end()).getSeconds();
		long trainEnd = ds.start().getEpochSecond() + (long) (span * TRAIN_FRACTION);
		long valEnd = ds.start().getEpochSecond() + (long) (span * (TRAIN_FRACTION + VALIDATION_FRACTION));
		long embargo = EMBARGO.getSeconds();

		List<Bucket> train = new ArrayList<>(), val = new ArrayList<>(), test = new ArrayList<>();
		int dropped = 0, excluded = 0;
		for (Bucket b : built.buckets()) {
			long s = b.startEpoch();
			if (scenarioSlot.containsKey(s)) {
				excluded++;
			} else if (s + width <= trainEnd) {
				train.add(b);
			} else if (s >= trainEnd + embargo && s + width <= valEnd) {
				val.add(b);
			} else if (s >= valEnd + embargo) {
				test.add(b);
			} else {
				dropped++;                                       // straddles a boundary or sits in the embargo
			}
		}
		OccupancyTrainer.Counts counts = new OccupancyTrainer.Counts(built.samples(), built.buckets().size(), built.positives(),
				built.sparseBucketsDropped(), built.distinctDays(), built.first(), built.last());
		if (train.size() < cfg.minLabeledBuckets() || val.isEmpty() || test.isEmpty() || classes(train) < 2) {
			return new Result(OccupancyTrainer.Status.INSUFFICIENT_DATA, "The synthetic dataset is too small for a train/validation/test split.",
					null, 0, Map.of(), split(train, width), split(val, width), split(test, width), dropped, excluded, null, null, counts);
		}

		// Model selection on validation only.
		OccupancyTrainer.Cells trainCells = OccupancyTrainer.Cells.of(train);
		Map<String, Double> valLoss = new java.util.LinkedHashMap<>();
		double best = LAMBDAS[1];
		double bestLoss = Double.MAX_VALUE;
		for (double lambda : LAMBDAS) {
			LogisticRegression.Fit fit = LogisticRegression.fit(OccupancyTrainer.trainingMatrix(train, trainCells), OccupancyTrainer.labels(train),
					lambda, OccupancyTrainer.MAX_ITERATIONS);
			double[] p = new double[val.size()];
			for (int i = 0; i < p.length; i++) {
				p[i] = LogisticRegression.probability(fit.weights(), trainCells.features(val.get(i), false));
			}
			double loss = ModelMetrics.evaluate(p, OccupancyTrainer.labels(val), cfg.decisionThreshold(), trainCells.baseRate()).logLoss();
			valLoss.put(String.valueOf(lambda), loss);
			if (loss < bestLoss) {
				bestLoss = loss;
				best = lambda;
			}
		}

		// Final model: train + validation, scored once on the untouched test part.
		List<Bucket> fitSet = new ArrayList<>(train);
		fitSet.addAll(val);
		OccupancyTrainer.Cells cells = OccupancyTrainer.Cells.of(fitSet);
		LogisticRegression.Fit finalFit = LogisticRegression.fit(OccupancyTrainer.trainingMatrix(fitSet, cells), OccupancyTrainer.labels(fitSet),
				best, OccupancyTrainer.MAX_ITERATIONS);
		OccupancyModel model = new OccupancyModel(OccupancyModel.CURRENT_VERSION, cfg.bucketMinutes(), ds.zone().getId(), finalFit.weights(),
				cells.n(), cells.pos(), cells.baseRate());
		double[] testP = new double[test.size()];
		for (int i = 0; i < testP.length; i++) {
			testP[i] = LogisticRegression.probability(finalFit.weights(), cells.features(test.get(i), false));
		}
		Metrics metrics = ModelMetrics.evaluate(testP, OccupancyTrainer.labels(test), cfg.decisionThreshold(), cells.baseRate());

		// Secondary: agreement with true occupancy on the same test slots.
		int agree = 0, occupiedSlots = 0;
		for (int i = 0; i < test.size(); i++) {
			int[] t = truth.get(test.get(i).startEpoch());
			boolean occupied = t != null && t[1] * 2 > t[0];
			occupiedSlots += occupied ? 1 : 0;
			agree += (testP[i] >= cfg.decisionThreshold()) == occupied ? 1 : 0;
		}
		OccupancyAgreement agreement = new OccupancyAgreement(test.size(), agree / (double) test.size(),
				Math.max(occupiedSlots, test.size() - occupiedSlots) / (double) test.size());

		boolean beats = metrics.logLoss() < metrics.baselineLogLoss() && (metrics.auc() == null || metrics.auc() >= OccupancyTrainer.MIN_AUC);
		String message = beats
				? "Trained on SYNTHETIC data (" + fitSet.size() + " slots); on " + test.size()
						+ " later test slots it beat the 'always guess the average' baseline. This says the pipeline works, not how well it would predict a real home."
				: "Trained on SYNTHETIC data, but on the test slots it was not clearly better than the baseline.";
		return new Result(beats ? OccupancyTrainer.Status.READY : OccupancyTrainer.Status.LOW_QUALITY, message, model, best, valLoss,
				split(train, width), split(val, width), split(test, width), dropped, excluded, metrics, agreement, counts);
	}

	private static int classes(List<Bucket> b) {
		boolean pos = b.stream().anyMatch(Bucket::occupied);
		boolean neg = b.stream().anyMatch(x -> !x.occupied());
		return (pos ? 1 : 0) + (neg ? 1 : 0);
	}

	private static Split split(List<Bucket> b, long width) {
		if (b.isEmpty()) {
			return new Split(0, 0, null, null);
		}
		return new Split(b.size(), (int) b.stream().filter(Bucket::occupied).count(), Instant.ofEpochSecond(b.get(0).startEpoch()),
				Instant.ofEpochSecond(b.get(b.size() - 1).startEpoch() + width));
	}
}
