package com.smarthome.ml;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.smarthome.ml.ModelMetrics.Metrics;
import com.smarthome.ml.OccupancyDataset.Bucket;

/**
 * Trains and evaluates the occupancy model. Pure computation: it receives samples and returns a result, so it can be
 * tested without a database, and it never invents anything. Not enough data gives {@code INSUFFICIENT_DATA} with the
 * exact reason, no model and no metrics.
 *
 * <p>Evaluation is honest about time: slots are sorted chronologically, the model is fitted on the earlier part and
 * scored on the most recent part it has never seen (a random split would leak the same day into both sides). The
 * metrics describe that held-out model. The model that is actually stored is then refitted on all slots, as is usual,
 * because it makes better use of the data; that second model has no separate evaluation.
 */
public final class OccupancyTrainer {

	public enum Status {
		READY, LOW_QUALITY, INSUFFICIENT_DATA
	}

	/** Facts about the data that was available, whether or not a model could be trained. */
	public record Counts(int samples, int labeledBuckets, int positiveBuckets, int sparseBucketsDropped, int distinctDays,
			Instant dataFrom, Instant dataTo) {
	}

	public record Result(Status status, String message, Counts counts, OccupancyModel model, Metrics metrics, int trainBuckets,
			int testBuckets) {
	}

	/** Below this AUC a model is treated as no better than chance for ranking. */
	static final double MIN_AUC = 0.6;
	static final int MAX_ITERATIONS = 50;

	private OccupancyTrainer() {
	}

	public static Result train(List<MotionSample> samples, ZoneId zone, MlProperties.Occupancy cfg) {
		OccupancyDataset.Built data = OccupancyDataset.build(samples, zone, cfg.bucketMinutes(), cfg.minReadingsPerBucket());
		List<Bucket> all = data.buckets();
		int n = all.size();
		int positives = data.positives();
		int negatives = n - positives;
		Counts counts = new Counts(data.samples(), n, positives, data.sparseBucketsDropped(), data.distinctDays(), data.first(), data.last());
		int slotsPerDay = 24 * 60 / cfg.bucketMinutes();

		String why = null;
		if (n == 0) {
			why = "There is no usable motion history yet (" + data.samples() + " motion readings, none forming a "
					+ cfg.bucketMinutes() + "-minute slot with at least " + cfg.minReadingsPerBucket() + " readings).";
		} else if (n < cfg.minLabeledBuckets()) {
			why = "Only " + n + " usable " + cfg.bucketMinutes() + "-minute slots so far; at least " + cfg.minLabeledBuckets()
					+ " are needed (about " + (int) Math.ceil(cfg.minLabeledBuckets() / (double) slotsPerDay)
					+ " days of continuous data). " + data.sparseBucketsDropped() + " slots were ignored for having too few readings.";
		} else if (data.distinctDays() < cfg.minDistinctDays()) {
			why = "The history covers only " + data.distinctDays() + " calendar day(s); at least " + cfg.minDistinctDays()
					+ " are needed to see a daily pattern.";
		} else if (positives < cfg.minClassBuckets() || negatives < cfg.minClassBuckets()) {
			why = "The history has " + positives + " slots with motion and " + negatives + " without; at least "
					+ cfg.minClassBuckets() + " of each are needed, otherwise there is nothing to learn a pattern from.";
		}
		if (why != null) {
			return new Result(Status.INSUFFICIENT_DATA, why, counts, null, null, 0, 0);
		}

		// Chronological split: earlier slots train the evaluation model, the latest slots test it.
		int nTest = Math.max(cfg.minTestBuckets(), (int) Math.round(n * cfg.testFraction()));
		int nTrain = n - nTest;
		List<Bucket> train = all.subList(0, nTrain);
		List<Bucket> test = all.subList(nTrain, n);
		long trainPositives = train.stream().filter(Bucket::occupied).count();
		long trainNegatives = nTrain - trainPositives;
		if (trainPositives < cfg.minTrainClassBuckets() || trainNegatives < cfg.minTrainClassBuckets()) {
			return new Result(Status.INSUFFICIENT_DATA,
					"The earlier part of the history used for training has " + trainPositives + " slots with motion and "
							+ trainNegatives + " without; at least " + cfg.minTrainClassBuckets()
							+ " of each are needed. Wait for more varied data.", counts, null, null, nTrain, nTest);
		}

		// The hour-of-week frequencies for evaluation come from the training part only, so the test slots stay unseen.
		Cells trainCells = Cells.of(train);
		LogisticRegression.Fit evalFit = LogisticRegression.fit(trainingMatrix(train, trainCells), labels(train), cfg.ridgeLambda(), MAX_ITERATIONS);
		double[] testProbabilities = new double[nTest];
		for (int i = 0; i < nTest; i++) {
			testProbabilities[i] = LogisticRegression.probability(evalFit.weights(), trainCells.features(test.get(i), false));
		}
		Metrics metrics = ModelMetrics.evaluate(testProbabilities, labels(test), cfg.decisionThreshold(), trainPositives / (double) nTrain);

		// The stored model uses every slot.
		Cells allCells = Cells.of(all);
		LogisticRegression.Fit finalFit = LogisticRegression.fit(trainingMatrix(all, allCells), labels(all), cfg.ridgeLambda(), MAX_ITERATIONS);
		OccupancyModel model = new OccupancyModel(OccupancyModel.CURRENT_VERSION, cfg.bucketMinutes(), zone.getId(),
				finalFit.weights(), allCells.n(), allCells.pos(), allCells.baseRate());

		boolean beatsBaseline = metrics.logLoss() < metrics.baselineLogLoss();
		boolean ranksWell = metrics.auc() == null || metrics.auc() >= MIN_AUC;
		if (beatsBaseline && ranksWell) {
			return new Result(Status.READY, "Trained on " + n + " slots (" + data.distinctDays() + " days). On the most recent "
					+ nTest + " slots, which it had not seen, it beat the 'always guess the average' baseline.", counts, model, metrics, nTrain, nTest);
		}
		return new Result(Status.LOW_QUALITY, "Trained on " + n + " slots, but on the most recent " + nTest
				+ " slots it was not clearly better than always guessing the average"
				+ (ranksWell ? "" : " and could not rank occupied above empty slots") + ". Treat its predictions as unreliable; "
				+ "more data or a more regular routine may help.", counts, model, metrics, nTrain, nTest);
	}

	/** Per hour of the week: how many slots, and how many with motion. Shared with the synthetic trainer. */
	record Cells(int[] n, int[] pos, double baseRate) {

		static Cells of(List<Bucket> buckets) {
			int[] n = new int[168];
			int[] pos = new int[168];
			int positives = 0;
			for (Bucket b : buckets) {
				n[b.cell()]++;
				if (b.occupied()) {
					pos[b.cell()]++;
					positives++;
				}
			}
			return new Cells(n, pos, buckets.isEmpty() ? 0 : positives / (double) buckets.size());
		}

		/** Version-2 features of a slot. {@code leaveOut}: the slot is in these counts, so remove its own label first. */
		double[] features(Bucket b, boolean leaveOut) {
			int c = b.cell();
			int slots = n[c] - (leaveOut ? 1 : 0);
			int motion = pos[c] - (leaveOut && b.occupied() ? 1 : 0);
			return OccupancyFeatures.withFrequency(b.x(), OccupancyFeatures.frequency(slots, motion, baseRate));
		}
	}

	/** Training rows: each slot's frequency feature leaves its own label out (no target leakage). */
	static double[][] trainingMatrix(List<Bucket> b, Cells cells) {
		double[][] x = new double[b.size()][];
		for (int i = 0; i < x.length; i++) {
			x[i] = cells.features(b.get(i), true);
		}
		return x;
	}

	static int[] labels(List<Bucket> b) {
		int[] y = new int[b.size()];
		for (int i = 0; i < y.length; i++) {
			y[i] = b.get(i).occupied() ? 1 : 0;
		}
		return y;
	}
}
