package com.smarthome.predict;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import com.smarthome.ml.LogisticRegression;

/**
 * One fitted binary classifier: logistic regression, random forest or gradient boosting, in plain Java with sample weights.
 * A record so that a trained model can be stored as JSON and loaded for inference without retraining.
 */
public record Learner(Algorithm algorithm, double[] weights, List<Tree> trees, double base) {

	public enum Algorithm { LOGISTIC_REGRESSION, RANDOM_FOREST, GRADIENT_BOOSTING }

	private static final int BINS = 24;
	private static final int FOREST_TREES = 40, FOREST_DEPTH = 8, FOREST_MIN_LEAF = 20;
	private static final int BOOST_TREES = 60, BOOST_DEPTH = 3, BOOST_MIN_LEAF = 25;
	private static final double BOOST_RATE = 0.1;

	/** Flat binary tree: node i is a leaf when {@code feature[i] < 0}. */
	public record Tree(int[] feature, double[] threshold, int[] left, int[] right, double[] value) {
		double predict(double[] x) {
			int i = 0;
			while (feature[i] >= 0) {
				i = x[feature[i]] <= threshold[i] ? left[i] : right[i];
			}
			return value[i];
		}
	}

	public double probability(double[] x) {
		return switch (algorithm) {
			case LOGISTIC_REGRESSION -> LogisticRegression.probability(weights, x);
			case RANDOM_FOREST -> clamp(trees.stream().mapToDouble(t -> t.predict(x)).average().orElse(base));
			case GRADIENT_BOOSTING -> {
				double z = base;
				for (Tree t : trees) {
					z += BOOST_RATE * t.predict(x);
				}
				yield clamp(sigmoid(z));
			}
		};
	}

	static Learner fit(Algorithm algorithm, double[][] x, int[] y, double[] w, long seed) {
		return switch (algorithm) {
			case LOGISTIC_REGRESSION -> new Learner(algorithm, LogisticRegression.fit(x, y, w, 1.0, 40).weights(), List.of(), 0);
			case RANDOM_FOREST -> forest(x, y, w, new Random(seed));
			case GRADIENT_BOOSTING -> boosted(x, y, w);
		};
	}

	private static Learner forest(double[][] x, int[] y, double[] w, Random random) {
		Grower grower = new Grower(x);
		double[] target = Arrays.stream(y).asDoubleStream().toArray();
		int perSplit = Math.max(1, (int) Math.round(Math.sqrt(x[0].length) * 1.5));
		List<Tree> trees = new ArrayList<>();
		for (int t = 0; t < FOREST_TREES; t++) {
			double[] weight = new double[x.length]; // bootstrap sample expressed as weights
			for (int i = 0; i < x.length; i++) {
				int j = random.nextInt(x.length);
				weight[j] += w[j];
			}
			// With hessian == weight, the leaf value sum(w*y)/sum(w) is the weighted share of positives.
			double[] gradient = new double[x.length];
			for (int i = 0; i < x.length; i++) {
				gradient[i] = weight[i] * target[i];
			}
			trees.add(grower.grow(gradient, weight, FOREST_DEPTH, FOREST_MIN_LEAF, perSplit, random, 0));
		}
		return new Learner(Algorithm.RANDOM_FOREST, new double[0], trees, 0.5);
	}

	private static Learner boosted(double[][] x, int[] y, double[] w) {
		Grower grower = new Grower(x);
		double positive = 0, total = 0;
		for (int i = 0; i < y.length; i++) {
			positive += w[i] * y[i];
			total += w[i];
		}
		double rate = clamp(positive / total);
		double base = Math.log(rate / (1 - rate));
		double[] z = new double[x.length];
		Arrays.fill(z, base);
		List<Tree> trees = new ArrayList<>();
		for (int t = 0; t < BOOST_TREES; t++) {
			double[] gradient = new double[x.length], hessian = new double[x.length];
			for (int i = 0; i < x.length; i++) {
				double p = sigmoid(z[i]);
				gradient[i] = w[i] * (y[i] - p);
				hessian[i] = w[i] * Math.max(p * (1 - p), 1e-6);
			}
			Tree tree = grower.grow(gradient, hessian, BOOST_DEPTH, BOOST_MIN_LEAF, x[0].length, null, 1.0);
			trees.add(tree);
			for (int i = 0; i < x.length; i++) {
				z[i] += BOOST_RATE * tree.predict(x[i]);
			}
		}
		return new Learner(Algorithm.GRADIENT_BOOSTING, new double[0], trees, base);
	}

	/** Histogram tree growth maximising G^2/(H+lambda); leaf value G/(H+lambda). */
	private static final class Grower {
		private final double[][] x;
		private final double[][] cuts;
		private final byte[][] bin;

		Grower(double[][] x) {
			this.x = x;
			int d = x[0].length;
			cuts = new double[d][];
			bin = new byte[d][x.length];
			for (int f = 0; f < d; f++) {
				double[] sorted = new double[x.length];
				for (int i = 0; i < x.length; i++) {
					sorted[i] = x[i][f];
				}
				Arrays.sort(sorted);
				double[] c = new double[BINS - 1];
				int n = 0;
				for (int q = 1; q < BINS; q++) {
					double v = sorted[q * (x.length - 1) / BINS];
					if (v < sorted[x.length - 1] && (n == 0 || v > c[n - 1])) {
						c[n++] = v;
					}
				}
				// A rare flag can sit entirely above every quantile; still allow splitting it from the minimum.
				if (n == 0 && sorted[0] < sorted[x.length - 1]) {
					c[n++] = sorted[0];
				}
				cuts[f] = Arrays.copyOf(c, n);
				for (int i = 0; i < x.length; i++) {
					int at = Arrays.binarySearch(cuts[f], x[i][f]);
					bin[f][i] = (byte) (at >= 0 ? at : -at - 1);
				}
			}
		}

		Tree grow(double[] g, double[] h, int depth, int minLeaf, int perSplit, Random random, double lambda) {
			List<double[]> nodes = new ArrayList<>(); // {feature, threshold, left, right, value}
			int[] rows = new int[x.length];
			int n = 0;
			for (int i = 0; i < x.length; i++) {
				if (h[i] > 0) {
					rows[n++] = i;
				}
			}
			split(nodes, Arrays.copyOf(rows, n), g, h, depth, minLeaf, perSplit, random, lambda);
			int size = nodes.size();
			Tree t = new Tree(new int[size], new double[size], new int[size], new int[size], new double[size]);
			for (int i = 0; i < size; i++) {
				double[] node = nodes.get(i);
				t.feature()[i] = (int) node[0];
				t.threshold()[i] = node[1];
				t.left()[i] = (int) node[2];
				t.right()[i] = (int) node[3];
				t.value()[i] = node[4];
			}
			return t;
		}

		private int split(List<double[]> nodes, int[] rows, double[] g, double[] h, int depth, int minLeaf, int perSplit,
				Random random, double lambda) {
			double sg = 0, sh = 0;
			for (int i : rows) {
				sg += g[i];
				sh += h[i];
			}
			int index = nodes.size();
			double[] node = { -1, 0, 0, 0, sg / (sh + lambda + 1e-12) };
			nodes.add(node);
			if (depth == 0 || rows.length < 2 * minLeaf) {
				return index;
			}
			int d = x[0].length;
			int bestFeature = -1, bestBin = -1;
			double bestGain = sg * sg / (sh + lambda + 1e-12) + 1e-9;
			for (int k = 0; k < perSplit; k++) {
				int f = random == null ? k : random.nextInt(d);
				int m = cuts[f].length;
				if (m == 0) {
					continue;
				}
				double[] bg = new double[m + 1], bh = new double[m + 1];
				int[] count = new int[m + 1];
				for (int i : rows) {
					int b = bin[f][i];
					bg[b] += g[i];
					bh[b] += h[i];
					count[b]++;
				}
				double lg = 0, lh = 0;
				int left = 0;
				for (int b = 0; b < m; b++) {
					lg += bg[b];
					lh += bh[b];
					left += count[b];
					if (left < minLeaf || rows.length - left < minLeaf) {
						continue;
					}
					double gain = lg * lg / (lh + lambda + 1e-12) + (sg - lg) * (sg - lg) / (sh - lh + lambda + 1e-12);
					if (gain > bestGain) {
						bestGain = gain;
						bestFeature = f;
						bestBin = b;
					}
				}
			}
			if (bestFeature < 0) {
				return index;
			}
			int f = bestFeature, cut = bestBin;
			int[] low = Arrays.stream(rows).filter(i -> bin[f][i] <= cut).toArray();
			int[] high = Arrays.stream(rows).filter(i -> bin[f][i] > cut).toArray();
			node[0] = f;
			node[1] = cuts[f][cut];
			node[2] = split(nodes, low, g, h, depth - 1, minLeaf, perSplit, random, lambda);
			node[3] = split(nodes, high, g, h, depth - 1, minLeaf, perSplit, random, lambda);
			return index;
		}
	}

	private static double sigmoid(double z) {
		return 1 / (1 + Math.exp(-z));
	}

	private static double clamp(double p) {
		return Math.max(0.001, Math.min(0.999, p));
	}
}
