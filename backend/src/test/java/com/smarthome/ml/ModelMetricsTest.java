package com.smarthome.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.smarthome.ml.ModelMetrics.Metrics;

/** Metrics checked against values worked out by hand. */
class ModelMetricsTest {

	@Test
	void classificationMetricsMatchHandCalculation() {
		// predictions at threshold 0.5: [1,1,0,0,1,0]; truth: [1,0,0,1,1,0] -> TP=2 FP=1 FN=1 TN=2
		double[] p = { 0.9, 0.8, 0.2, 0.3, 0.7, 0.1 };
		int[] y = { 1, 0, 0, 1, 1, 0 };
		Metrics m = ModelMetrics.evaluate(p, y, 0.5, 0.5);
		assertEquals(6, m.n());
		assertEquals(3, m.positives());
		assertEquals(4 / 6.0, m.accuracy(), 1e-12);
		assertEquals(2 / 3.0, m.precision(), 1e-12);
		assertEquals(2 / 3.0, m.recall(), 1e-12);
		assertEquals(2 / 3.0, m.f1(), 1e-12);
		double expectedBrier = (0.01 + 0.64 + 0.04 + 0.49 + 0.09 + 0.01) / 6;
		assertEquals(expectedBrier, m.brier(), 1e-12);
		double expectedLogLoss = -(Math.log(0.9) + Math.log(0.2) + Math.log(0.8) + Math.log(0.3) + Math.log(0.7) + Math.log(0.9)) / 6;
		assertEquals(expectedLogLoss, m.logLoss(), 1e-12);
	}

	@Test
	void aucIsOneForPerfectRankingZeroForReversedAndHalfForTies() {
		assertEquals(1.0, ModelMetrics.auc(new double[] { 0.1, 0.2, 0.8, 0.9 }, new int[] { 0, 0, 1, 1 }), 1e-12);
		assertEquals(0.0, ModelMetrics.auc(new double[] { 0.9, 0.8, 0.2, 0.1 }, new int[] { 0, 0, 1, 1 }), 1e-12);
		assertEquals(0.5, ModelMetrics.auc(new double[] { 0.5, 0.5, 0.5, 0.5 }, new int[] { 0, 1, 0, 1 }), 1e-12);
		// 4 positive/negative pairs: (0.8>0.3) (0.8>0.6) (0.4<0.6 no) (0.4>0.3) -> 3/4... positives 0.8,0.4 negatives 0.3,0.6
		assertEquals(0.75, ModelMetrics.auc(new double[] { 0.8, 0.4, 0.3, 0.6 }, new int[] { 1, 1, 0, 0 }), 1e-12);
	}

	@Test
	void undefinedMetricsAreNullNeverInvented() {
		// only one class in the test set
		Metrics oneClass = ModelMetrics.evaluate(new double[] { 0.9, 0.8, 0.7 }, new int[] { 1, 1, 1 }, 0.5, 0.5);
		assertNull(oneClass.auc());
		// nothing predicted positive: precision is undefined, recall is 0, F1 undefined
		Metrics nonePredicted = ModelMetrics.evaluate(new double[] { 0.1, 0.2, 0.3 }, new int[] { 1, 0, 1 }, 0.5, 0.5);
		assertNull(nonePredicted.precision());
		assertEquals(0.0, nonePredicted.recall());
		assertNull(nonePredicted.f1());
		// no positives in the test set: recall is undefined
		Metrics noPositives = ModelMetrics.evaluate(new double[] { 0.1, 0.9 }, new int[] { 0, 0 }, 0.5, 0.5);
		assertNull(noPositives.recall());
		assertNotNull(noPositives.accuracy());
	}

	@Test
	void baselineIsAlwaysGuessingTheTrainingAverage() {
		int[] y = { 1, 1, 1, 0 };
		double[] p = { 0.9, 0.9, 0.9, 0.1 };
		// training set was mostly positive -> baseline predicts "occupied" for everything
		Metrics m = ModelMetrics.evaluate(p, y, 0.5, 0.75);
		assertEquals(0.75, m.baselineAccuracy(), 1e-12);
		double expected = -(3 * Math.log(0.75) + Math.log(0.25)) / 4;
		assertEquals(expected, m.baselineLogLoss(), 1e-12);
		assertTrue(m.logLoss() < m.baselineLogLoss(), "a good model beats the baseline");
		// training set mostly negative -> baseline predicts "empty"
		assertEquals(0.25, ModelMetrics.evaluate(p, y, 0.5, 0.25).baselineAccuracy(), 1e-12);
	}

	@Test
	void extremeProbabilitiesDoNotProduceInfiniteLoss() {
		Metrics m = ModelMetrics.evaluate(new double[] { 0.0, 1.0 }, new int[] { 1, 0 }, 0.5, 0.5);
		assertTrue(Double.isFinite(m.logLoss()));
		assertTrue(m.logLoss() > 10, "confidently wrong should be heavily penalised");
	}

	@Test
	void refusesEmptyOrMismatchedInput() {
		assertThrows(IllegalArgumentException.class, () -> ModelMetrics.evaluate(new double[0], new int[0], 0.5, 0.5));
		assertThrows(IllegalArgumentException.class, () -> ModelMetrics.evaluate(new double[] { 0.5 }, new int[] { 1, 0 }, 0.5, 0.5));
	}
}
