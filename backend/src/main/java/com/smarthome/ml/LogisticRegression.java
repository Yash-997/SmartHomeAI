package com.smarthome.ml;

/**
 * Binary logistic regression with L2 regularisation, fitted by Newton's method (iteratively reweighted least squares).
 *
 * <p>Why this and not gradient descent: the problem is convex, so Newton's method reaches the single best answer in
 * a handful of iterations with no learning rate to tune, and gives exactly the same result every run. That makes the
 * model reproducible and testable. A backtracking line search guards against overshooting. The ridge penalty keeps
 * the weights finite even if the history is perfectly separable or some days are missing (unseen features stay at 0).
 *
 * <p>weights[0] is the intercept (never penalised); weights[j + 1] belongs to feature j.
 */
public final class LogisticRegression {

	public record Fit(double[] weights, int iterations, boolean converged, double penalisedLogLikelihood) {
	}

	private LogisticRegression() {
	}

	public static Fit fit(double[][] x, int[] y, double lambda, int maxIterations) {
		if (x.length == 0 || x.length != y.length) {
			throw new IllegalArgumentException("need one label per row and at least one row");
		}
		int d = x[0].length;
		int k = d + 1;
		double[] w = new double[k];
		double current = penalisedLogLikelihood(x, y, w, lambda);
		boolean converged = false;
		int iterations = 0;
		for (int it = 1; it <= maxIterations; it++) {
			iterations = it;
			double[] g = new double[k];
			double[][] h = new double[k][k];
			for (int i = 0; i < x.length; i++) {
				double p = sigmoid(linear(w, x[i]));
				double r = y[i] - p;
				double s = Math.max(p * (1 - p), 1e-9);
				g[0] += r;
				h[0][0] += s;
				for (int j = 0; j < d; j++) {
					double xj = x[i][j];
					if (xj == 0) {
						continue;
					}
					g[j + 1] += r * xj;
					h[0][j + 1] += s * xj;
					h[j + 1][0] += s * xj;
					for (int m = 0; m < d; m++) {
						double xm = x[i][m];
						if (xm != 0) {
							h[j + 1][m + 1] += s * xj * xm;
						}
					}
				}
			}
			for (int j = 1; j < k; j++) {
				g[j] -= lambda * w[j];
				h[j][j] += lambda;
			}
			double[] step = solve(h, g);

			// Backtracking line search on the penalised log-likelihood (which Newton's step should increase).
			double scale = 1.0;
			double[] candidate = null;
			double candidateValue = current;
			for (int tries = 0; tries < 30; tries++) {
				double[] c = new double[k];
				for (int j = 0; j < k; j++) {
					c[j] = w[j] + scale * step[j];
				}
				double v = penalisedLogLikelihood(x, y, c, lambda);
				if (v >= current - 1e-12) {
					candidate = c;
					candidateValue = v;
					break;
				}
				scale /= 2;
			}
			if (candidate == null) {
				converged = true;   // cannot improve any further
				break;
			}
			double largest = 0;
			for (int j = 0; j < k; j++) {
				largest = Math.max(largest, Math.abs(candidate[j] - w[j]));
			}
			w = candidate;
			current = candidateValue;
			if (largest < 1e-7) {
				converged = true;
				break;
			}
		}
		return new Fit(w, iterations, converged, current);
	}

	public static double probability(double[] weights, double[] x) {
		return sigmoid(linear(weights, x));
	}

	private static double linear(double[] w, double[] x) {
		double z = w[0];
		for (int j = 0; j < x.length; j++) {
			z += w[j + 1] * x[j];
		}
		return z;
	}

	static double sigmoid(double z) {
		if (z >= 0) {
			double e = Math.exp(-z);
			return 1 / (1 + e);
		}
		double e = Math.exp(z);
		return e / (1 + e);
	}

	/** sum(y*z - log(1+e^z)) - lambda/2 * sum(w_j^2, j >= 1), computed without overflow. */
	static double penalisedLogLikelihood(double[][] x, int[] y, double[] w, double lambda) {
		double ll = 0;
		for (int i = 0; i < x.length; i++) {
			double z = linear(w, x[i]);
			double log1pExp = z > 0 ? z + Math.log1p(Math.exp(-z)) : Math.log1p(Math.exp(z));
			ll += y[i] * z - log1pExp;
		}
		double penalty = 0;
		for (int j = 1; j < w.length; j++) {
			penalty += w[j] * w[j];
		}
		return ll - 0.5 * lambda * penalty;
	}

	/** Solves A x = b by Gaussian elimination with partial pivoting. A and b are left untouched. */
	static double[] solve(double[][] a, double[] b) {
		int n = b.length;
		double[][] m = new double[n][n + 1];
		for (int i = 0; i < n; i++) {
			System.arraycopy(a[i], 0, m[i], 0, n);
			m[i][n] = b[i];
		}
		for (int col = 0; col < n; col++) {
			int pivot = col;
			for (int r = col + 1; r < n; r++) {
				if (Math.abs(m[r][col]) > Math.abs(m[pivot][col])) {
					pivot = r;
				}
			}
			if (Math.abs(m[pivot][col]) < 1e-12) {
				throw new IllegalStateException("singular system while fitting the model");
			}
			double[] tmp = m[col];
			m[col] = m[pivot];
			m[pivot] = tmp;
			for (int r = col + 1; r < n; r++) {
				double f = m[r][col] / m[col][col];
				if (f == 0) {
					continue;
				}
				for (int c = col; c <= n; c++) {
					m[r][c] -= f * m[col][c];
				}
			}
		}
		double[] x = new double[n];
		for (int i = n - 1; i >= 0; i--) {
			double s = m[i][n];
			for (int j = i + 1; j < n; j++) {
				s -= m[i][j] * x[j];
			}
			x[i] = s / m[i][i];
		}
		return x;
	}
}
