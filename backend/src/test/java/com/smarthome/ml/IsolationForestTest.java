package com.smarthome.ml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The hand-written Isolation Forest, without Spring. */
class IsolationForestTest {

	private static double[][] gaussian(int n, int d, long seed) {
		Random r = new Random(seed);
		double[][] x = new double[n][d];
		for (double[] row : x) {
			for (int j = 0; j < d; j++) {
				row[j] = r.nextGaussian();
			}
		}
		return x;
	}

	@Test
	void anIsolatedPointScoresHigherThanTheBulk() {
		double[][] x = gaussian(2000, 4, 1);
		// A few scattered anomalies inside the data, as Isolation Forest assumes ("few and different").
		double[][] outliers = { { 8, 8, 8, 8 }, { -8, 7, -9, 8 }, { 9, -8, 8, -7 } };
		for (int i = 0; i < outliers.length; i++) {
			x[i * 500] = outliers[i];
		}
		IsolationForest.Model f = IsolationForest.fit(x, 100, 256, 42);
		double inlier = f.score(new double[] { 0, 0, 0, 0 });
		for (double[] o : outliers) {
			assertTrue(f.score(o) > 0.65, "anomaly " + f.score(o));
		}
		assertTrue(inlier < 0.5, "centre " + inlier);
		// An unseen point beyond the data scores at least like the most extreme training points (no extrapolation).
		assertTrue(f.score(new double[] { 6, 0, 0, 0 }) > inlier + 0.05);
	}

	@Test
	void theSameSeedGivesTheSameForestAndAnotherSeedDoesNot() {
		double[][] x = gaussian(1000, 3, 2);
		IsolationForest.Model a = IsolationForest.fit(x, 50, 128, 7);
		IsolationForest.Model b = IsolationForest.fit(x, 50, 128, 7);
		IsolationForest.Model c = IsolationForest.fit(x, 50, 128, 8);
		for (int t = 0; t < a.trees().size(); t++) {
			assertArrayEquals(a.trees().get(t).feature(), b.trees().get(t).feature());
			assertArrayEquals(a.trees().get(t).value(), b.trees().get(t).value());
		}
		double[] probe = { 1.5, -0.3, 2.2 };
		assertEquals(a.score(probe), b.score(probe), 0.0);
		assertNotEquals(a.score(probe), c.score(probe));
	}

	@Test
	void aStoredForestScoresExactlyLikeTheTrainedOne() {
		double[][] x = gaussian(800, 3, 3);
		IsolationForest.Model f = IsolationForest.fit(x, 30, 256, 11);
		ObjectMapper mapper = JsonMapper.builder().build();
		IsolationForest.Model back = mapper.readValue(mapper.writeValueAsString(f), IsolationForest.Model.class);
		for (double[] row : gaussian(50, 3, 4)) {
			assertEquals(f.score(row), back.score(row), 0.0, "split values are float at build time, so JSON is lossless");
		}
	}

	@Test
	void heightIsLimitedAndSubsampleIsCappedAtTheData() {
		IsolationForest.Model f = IsolationForest.fit(gaussian(100, 2, 5), 10, 256, 1);
		assertEquals(100, f.subsample(), "never more points per tree than rows");
		for (IsolationForest.Tree t : f.trees()) {
			assertTrue(t.feature().length <= 2 * 100 - 1);
		}
		assertEquals(1.0, IsolationForest.averagePathLength(2), 1e-12);
		assertEquals(0.0, IsolationForest.averagePathLength(1), 1e-12);
	}

	@Test
	void constantDataAndInvalidInputAreHandled() {
		double[][] flat = new double[50][2];
		IsolationForest.Model f = IsolationForest.fit(flat, 5, 32, 1);
		assertTrue(Double.isFinite(f.score(new double[] { 0, 0 })), "identical points: one leaf, still a score");
		assertThrows(IllegalArgumentException.class, () -> IsolationForest.fit(new double[][] { { Double.NaN }, { 1 } }, 5, 2, 1),
				"missing values must be imputed first, never passed as NaN");
		assertThrows(IllegalArgumentException.class, () -> f.score(new double[] { 1 }));
	}
}
