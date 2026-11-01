package com.smarthome.ml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/** The regression itself, on small synthetic problems whose right answer is known. */
class LogisticRegressionTest {

	/** One feature; the label is 1 when the feature is above 0, with some label noise. */
	private static double[][] oneFeature(int n, long seed, int[] labelsOut, double noise) {
		Random r = new Random(seed);
		double[][] x = new double[n][1];
		for (int i = 0; i < n; i++) {
			x[i][0] = r.nextGaussian() * 2;
			boolean positive = x[i][0] + r.nextGaussian() * noise > 0;
			labelsOut[i] = positive ? 1 : 0;
		}
		return x;
	}

	@Test
	void learnsThatLargerFeatureValuesMeanPositive() {
		int[] y = new int[500];
		double[][] x = oneFeature(500, 1, y, 0.7);
		var fit = LogisticRegression.fit(x, y, 0.1, 50);
		assertTrue(fit.converged());
		assertTrue(fit.weights()[1] > 1.0, "slope should be clearly positive, was " + fit.weights()[1]);
		assertTrue(Math.abs(fit.weights()[0]) < 0.5, "intercept should be near zero");
		double low = LogisticRegression.probability(fit.weights(), new double[] { -3 });
		double mid = LogisticRegression.probability(fit.weights(), new double[] { 0 });
		double high = LogisticRegression.probability(fit.weights(), new double[] { 3 });
		assertTrue(low < 0.1 && high > 0.9);
		assertTrue(low < mid && mid < high);
		assertTrue(mid > 0.3 && mid < 0.7);
	}

	@Test
	void probabilitiesAlwaysStayBetweenZeroAndOne() {
		int[] y = new int[200];
		double[][] x = oneFeature(200, 2, y, 0.5);
		var fit = LogisticRegression.fit(x, y, 1.0, 50);
		for (double v : new double[] { -1e6, -50, 0, 50, 1e6 }) {
			double p = LogisticRegression.probability(fit.weights(), new double[] { v });
			assertTrue(p >= 0 && p <= 1 && !Double.isNaN(p), "p=" + p + " for " + v);
		}
	}

	@Test
	void perfectlySeparableDataStillGivesFiniteWeightsThanksToTheRidgePenalty() {
		double[][] x = new double[40][1];
		int[] y = new int[40];
		for (int i = 0; i < 40; i++) {
			x[i][0] = i < 20 ? -1 - i * 0.1 : 1 + i * 0.1;
			y[i] = i < 20 ? 0 : 1;
		}
		var fit = LogisticRegression.fit(x, y, 1.0, 100);
		for (double w : fit.weights()) {
			assertTrue(Double.isFinite(w) && Math.abs(w) < 100, "weight " + w);
		}
		// the ridge penalty deliberately keeps it from becoming certain
		double p = LogisticRegression.probability(fit.weights(), new double[] { 2 });
		assertTrue(p > 0.75 && p < 1.0, "p=" + p);
		assertTrue(LogisticRegression.probability(fit.weights(), new double[] { -2 }) < 0.25);
	}

	@Test
	void sameDataGivesIdenticalWeightsEveryTime() {
		int[] y = new int[300];
		double[][] x = oneFeature(300, 3, y, 1.0);
		assertArrayEquals(LogisticRegression.fit(x, y, 1.0, 50).weights(), LogisticRegression.fit(x, y, 1.0, 50).weights());
	}

	@Test
	void strongerRegularisationShrinksTheWeights() {
		int[] y = new int[300];
		double[][] x = oneFeature(300, 4, y, 0.6);
		double weak = Math.abs(LogisticRegression.fit(x, y, 0.01, 50).weights()[1]);
		double strong = Math.abs(LogisticRegression.fit(x, y, 50.0, 50).weights()[1]);
		assertTrue(strong < weak, "strong=" + strong + " weak=" + weak);
	}

	@Test
	void withNoUsefulFeatureItFallsBackToTheBaseRate() {
		Random r = new Random(5);
		double[][] x = new double[1000][1];
		int[] y = new int[1000];
		int positives = 0;
		for (int i = 0; i < 1000; i++) {
			x[i][0] = 0;   // carries no information
			y[i] = r.nextDouble() < 0.3 ? 1 : 0;
			positives += y[i];
		}
		var fit = LogisticRegression.fit(x, y, 1.0, 50);
		assertEquals(positives / 1000.0, LogisticRegression.probability(fit.weights(), new double[] { 0 }), 0.01);
	}

	@Test
	void solvesALinearSystemAndLeavesItsInputsAlone() {
		double[][] a = { { 2, 1 }, { 1, 3 } };
		double[] b = { 5, 10 };
		double[] x = LogisticRegression.solve(a, b);
		assertEquals(1.0, x[0], 1e-12);
		assertEquals(3.0, x[1], 1e-12);
		assertEquals(2, a[0][0]);
		assertEquals(5, b[0]);
		assertThrows(IllegalStateException.class, () -> LogisticRegression.solve(new double[][] { { 1, 2 }, { 2, 4 } }, new double[] { 1, 2 }));
	}

	@Test
	void refusesNonsenseInput() {
		assertThrows(IllegalArgumentException.class, () -> LogisticRegression.fit(new double[0][0], new int[0], 1.0, 10));
		assertThrows(IllegalArgumentException.class, () -> LogisticRegression.fit(new double[][] { { 1 } }, new int[] { 1, 0 }, 1.0, 10));
	}
}
