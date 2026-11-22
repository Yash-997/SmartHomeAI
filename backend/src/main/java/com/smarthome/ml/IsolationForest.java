package com.smarthome.ml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Isolation Forest (Liu, Ting and Zhou, "Isolation Forest", ICDM 2008), written in plain Java like the occupancy
 * model's {@link LogisticRegression}: no ML library, no native code, and a model that is plain data (it is stored as
 * JSON with the rest of the anomaly model).
 *
 * <p>Each tree is grown on a random subsample by picking a random feature and a random split value between that
 * feature's minimum and maximum, until a point is isolated or the height limit {@code ceil(log2(subsample))} is reached.
 * Unusual points are isolated in few splits, so their average path length is short. The score is
 * {@code 2^(-E[h(x)] / c(subsample))}: close to 1 for points that are easy to isolate (anomalies), around 0.5 or below
 * for ordinary ones.
 *
 * <p>Training is sequential and every random choice comes from one {@link SplittableRandom} per tree, seeded from the
 * forest seed and the tree index, so the same data and seed always give exactly the same forest. Split values are
 * rounded to {@code float} when the tree is built, so a forest scores identically before and after being stored.
 */
public final class IsolationForest {

	/** Euler-Mascheroni constant, for the harmonic-number approximation in {@link #averagePathLength(int)}. */
	private static final double EULER = 0.5772156649015329;

	/**
	 * One tree in pre-order. {@code feature[i] < 0} marks a leaf, whose {@code value[i]} is the number of training points
	 * that reached it; otherwise {@code value[i]} is the split value, the left child is {@code i + 1} and the right child
	 * is {@code right[i]}.
	 */
	public record Tree(int[] feature, float[] value, int[] right) {
	}

	/** The stored forest. {@code subsample} is the per-tree sample size actually used (at most the training size). */
	public record Model(int features, int subsample, long seed, List<Tree> trees) {

		/** Anomaly score in (0, 1]. Higher means more unusual. */
		public double score(double[] x) {
			if (x.length != features) {
				throw new IllegalArgumentException("expected " + features + " features, got " + x.length);
			}
			double total = 0;
			for (Tree t : trees) {
				total += pathLength(t, x);
			}
			double mean = total / trees.size();
			return Math.pow(2, -mean / averagePathLength(subsample));
		}
	}

	private IsolationForest() {
	}

	/**
	 * Grows {@code trees} isolation trees on {@code data} (rows are observations). Rows are not modified.
	 *
	 * @param subsample points per tree (256 in the paper); capped at the number of rows
	 */
	public static Model fit(double[][] data, int trees, int subsample, long seed) {
		if (data.length < 2) {
			throw new IllegalArgumentException("at least two observations are needed");
		}
		if (trees < 1 || subsample < 2) {
			throw new IllegalArgumentException("trees >= 1 and subsample >= 2");
		}
		int d = data[0].length;
		for (double[] row : data) {
			if (row.length != d) {
				throw new IllegalArgumentException("all rows must have the same number of features");
			}
			for (double v : row) {
				if (!Double.isFinite(v)) {
					throw new IllegalArgumentException("features must be finite numbers (impute missing values first)");
				}
			}
		}
		int psi = Math.min(subsample, data.length);
		int heightLimit = (int) Math.ceil(Math.log(psi) / Math.log(2));
		List<Tree> grown = new ArrayList<>(trees);
		for (int t = 0; t < trees; t++) {
			// A distinct, reproducible stream per tree: same seed and index, same tree.
			SplittableRandom rnd = new SplittableRandom(seed * 0x9E3779B97F4A7C15L + t);
			int[] sample = sampleWithoutReplacement(data.length, psi, rnd);
			Builder b = new Builder();
			b.grow(data, sample, 0, sample.length, 0, heightLimit, rnd);
			grown.add(b.toTree());
		}
		return new Model(d, psi, seed, List.copyOf(grown));
	}

	/** c(n): the average path length of an unsuccessful search in a binary search tree of n points. */
	static double averagePathLength(int n) {
		if (n <= 1) {
			return 0;
		}
		if (n == 2) {
			return 1;
		}
		double harmonic = Math.log(n - 1.0) + EULER;
		return 2 * harmonic - 2.0 * (n - 1) / n;
	}

	private static double pathLength(Tree t, double[] x) {
		int i = 0;
		int depth = 0;
		while (t.feature()[i] >= 0) {
			i = x[t.feature()[i]] < t.value()[i] ? i + 1 : t.right()[i];
			depth++;
		}
		// A leaf holding several points stands for a subtree that was not grown: add its expected depth.
		return depth + averagePathLength((int) t.value()[i]);
	}

	/** Partial Fisher-Yates: the first k of a shuffled 0..n-1. */
	private static int[] sampleWithoutReplacement(int n, int k, SplittableRandom rnd) {
		int[] idx = new int[n];
		for (int i = 0; i < n; i++) {
			idx[i] = i;
		}
		for (int i = 0; i < k; i++) {
			int j = i + rnd.nextInt(n - i);
			int tmp = idx[i];
			idx[i] = idx[j];
			idx[j] = tmp;
		}
		return Arrays.copyOf(idx, k);
	}

	/** Grows one tree into growable pre-order arrays. */
	private static final class Builder {
		private int[] feature = new int[64];
		private float[] value = new float[64];
		private int[] right = new int[64];
		private int size;

		private int add(int f, float v) {
			if (size == feature.length) {
				feature = Arrays.copyOf(feature, size * 2);
				value = Arrays.copyOf(value, size * 2);
				right = Arrays.copyOf(right, size * 2);
			}
			feature[size] = f;
			value[size] = v;
			right[size] = -1;
			return size++;
		}

		/** Grows the subtree for sample[from, to). */
		void grow(double[][] data, int[] sample, int from, int to, int depth, int heightLimit, SplittableRandom rnd) {
			int n = to - from;
			if (n <= 1 || depth >= heightLimit) {
				add(-1, n);
				return;
			}
			int d = data[0].length;
			// Pick a random feature among those that still vary in this node (a constant feature cannot split it).
			double[] min = new double[d];
			double[] max = new double[d];
			Arrays.fill(min, Double.POSITIVE_INFINITY);
			Arrays.fill(max, Double.NEGATIVE_INFINITY);
			for (int i = from; i < to; i++) {
				double[] row = data[sample[i]];
				for (int f = 0; f < d; f++) {
					min[f] = Math.min(min[f], row[f]);
					max[f] = Math.max(max[f], row[f]);
				}
			}
			int[] varying = new int[d];
			int count = 0;
			for (int f = 0; f < d; f++) {
				if ((float) min[f] < (float) max[f]) {
					varying[count++] = f;
				}
			}
			if (count == 0) {
				add(-1, n);                                  // identical points: cannot be separated
				return;
			}
			int f = varying[rnd.nextInt(count)];
			float split = (float) (min[f] + rnd.nextDouble() * (max[f] - min[f]));
			if (split <= (float) min[f]) {
				split = Math.nextUp((float) min[f]);          // keep at least one point on each side
			}
			// Partition: values below the split to the left.
			int mid = from;
			for (int i = from; i < to; i++) {
				if (data[sample[i]][f] < split) {
					int tmp = sample[i];
					sample[i] = sample[mid];
					sample[mid] = tmp;
					mid++;
				}
			}
			if (mid == from || mid == to) {
				add(-1, n);                                  // float rounding left one side empty: stop here
				return;
			}
			int node = add(f, split);
			grow(data, sample, from, mid, depth + 1, heightLimit, rnd);
			right[node] = size;
			grow(data, sample, mid, to, depth + 1, heightLimit, rnd);
		}

		Tree toTree() {
			return new Tree(Arrays.copyOf(feature, size), Arrays.copyOf(value, size), Arrays.copyOf(right, size));
		}
	}
}
