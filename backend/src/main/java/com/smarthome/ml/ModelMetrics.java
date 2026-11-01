package com.smarthome.ml;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Evaluation of probability predictions against true labels, plus the score of a trivial baseline (always guessing
 * the training set's average) so that "good" always means "better than guessing".
 */
public final class ModelMetrics {

	/**
	 * Precision, recall, F1 and AUC are null when they are not defined (for example no positive predictions, or only one
	 * class in the test set). They are never filled in with a made-up number.
	 */
	public record Metrics(int n, int positives, double accuracy, Double precision, Double recall, Double f1, double logLoss,
			double brier, Double auc, double baselineAccuracy, double baselineLogLoss) {
	}

	private static final double EPS = 1e-15;

	private ModelMetrics() {
	}

	public static Metrics evaluate(double[] p, int[] y, double threshold, double trainBaseRate) {
		if (p.length == 0 || p.length != y.length) {
			throw new IllegalArgumentException("need predictions and labels of the same, non-zero length");
		}
		int n = p.length;
		int tp = 0, fp = 0, fn = 0, tn = 0, positives = 0;
		double logLoss = 0, brier = 0;
		for (int i = 0; i < n; i++) {
			boolean predicted = p[i] >= threshold;
			if (y[i] == 1) {
				positives++;
				if (predicted) tp++; else fn++;
			} else {
				if (predicted) fp++; else tn++;
			}
			double c = Math.min(1 - EPS, Math.max(EPS, p[i]));
			logLoss += -(y[i] * Math.log(c) + (1 - y[i]) * Math.log(1 - c));
			brier += (p[i] - y[i]) * (p[i] - y[i]);
		}
		double accuracy = (tp + tn) / (double) n;
		Double precision = tp + fp == 0 ? null : tp / (double) (tp + fp);
		Double recall = positives == 0 ? null : tp / (double) positives;
		Double f1 = (precision == null || recall == null || precision + recall == 0) ? null : 2 * precision * recall / (precision + recall);

		// Baseline: predict the training set's majority class, and its average rate as a constant probability.
		boolean majorityPositive = trainBaseRate >= 0.5;
		int baselineCorrect = majorityPositive ? positives : n - positives;
		double constant = Math.min(1 - 1e-6, Math.max(1e-6, trainBaseRate));
		double baselineLogLoss = 0;
		for (int i = 0; i < n; i++) {
			baselineLogLoss += -(y[i] * Math.log(constant) + (1 - y[i]) * Math.log(1 - constant));
		}
		return new Metrics(n, positives, accuracy, precision, recall, f1, logLoss / n, brier / n, auc(p, y),
				baselineCorrect / (double) n, baselineLogLoss / n);
	}

	/** Probability that a random positive is ranked above a random negative (ties count half). Null if one class is missing. */
	static Double auc(double[] p, int[] y) {
		int n = p.length;
		long pos = Arrays.stream(y).filter(v -> v == 1).count();
		long neg = n - pos;
		if (pos == 0 || neg == 0) {
			return null;
		}
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		Arrays.sort(order, Comparator.comparingDouble(i -> p[i]));
		double rankSumPositives = 0;
		int i = 0;
		while (i < n) {
			int j = i;
			while (j + 1 < n && p[order[j + 1]] == p[order[i]]) {
				j++;
			}
			double averageRank = (i + j) / 2.0 + 1;   // ranks are 1-based; tied scores share the average rank
			for (int k = i; k <= j; k++) {
				if (y[order[k]] == 1) {
					rankSumPositives += averageRank;
				}
			}
			i = j + 1;
		}
		return (rankSumPositives - pos * (pos + 1) / 2.0) / ((double) pos * neg);
	}
}
