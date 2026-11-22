package com.smarthome.ml;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Feature engineering for the Isolation Forest. Turns minute frames into one feature vector per frame, using only that
 * frame and the frames before it (never later ones), so training, evaluation and live scoring compute exactly the same
 * thing and nothing leaks from the future.
 *
 * <p>For each of temperature, humidity and gas:
 * <ul>
 *   <li>{@code hourZ}: distance from the median for this local hour of the day, in robust standard deviations (median
 *       and MAD learned from the training data, with the neighbouring hours, like {@link Baselines}).</li>
 *   <li>{@code meanDev}: distance from the rolling mean of the previous readings in the window.</li>
 *   <li>{@code rollStd}: rolling standard deviation in the window (a stuck sensor has none, an erratic one a lot).</li>
 *   <li>{@code rateOfChange}: change per minute since the previous value, against the usual minute-to-minute change.</li>
 *   <li>{@code madZ}: distance from the rolling median of the window, in units of the rolling MAD.</li>
 *   <li>{@code stuckRun}: log of how many consecutive values were exactly identical (stale values).</li>
 *   <li>{@code missingShare}: share of frames in the window without this sensor's value.</li>
 * </ul>
 * Plus: minutes since the previous frame (a silent device), motion now and its rate in the window, local time of day and
 * day of week (as sine/cosine, so 23:59 is next to 00:00), and two sensor relationships learned from training data:
 * humidity against temperature (indoor humidity falls as the room warms) and gas against humidity (the MQ-2 reading
 * rises with humidity). A drifting or faulty sensor breaks these relationships even when its own value looks ordinary.
 *
 * <p>A missing value contributes 0 ("typical") to its value features; its absence is carried by {@code missingShare},
 * so it is never mistaken for a reading of zero.
 */
public final class AnomalyFeatures {

	public static final String FEATURE_SET = "if-v1: per sensor hourZ, meanDev, rollStd, rateOfChange, madZ, stuckRun, missingShare; "
			+ "gap, motion, time of day/week, humidity~temperature and gas~humidity residuals";

	static final Metric[] METRICS = Metric.values();
	static final String[] PER_METRIC = { "hourZ", "meanDev", "rollStd", "rateOfChange", "madZ", "stuckRun", "missingShare" };
	static final String[] DEVICE = { "device.gapMinutes", "motion.now", "motion.rate", "time.hourSin", "time.hourCos", "time.dowSin",
			"time.dowCos", "rel.humidityVsTemperature", "rel.gasVsHumidity" };
	public static final List<String> NAMES = names();
	public static final int SIZE = NAMES.size();

	private static final int HOUR_Z = 0, STUCK = 5;
	private static final int GAP = METRICS.length * PER_METRIC.length;
	private static final int REL_HT = GAP + 7, REL_GH = GAP + 8;
	/** Hourly baselines need at least this many values (hour and neighbours); otherwise the overall figure is used. */
	private static final int MIN_HOUR_VALUES = 30;
	private static final double CLIP = 1000;

	/**
	 * Everything learned from the training frames that the features need, stored with the model so live scoring
	 * reproduces training exactly. Arrays are indexed by {@link Metric#ordinal()}.
	 */
	public record Preprocessing(int windowMinutes, String timeZone, double[][] hourMedian, double[][] hourScale, double[] median,
			double[] scale, double[] deltaScale, Relation humidityVsTemperature, Relation gasVsHumidity, double[][] routineScale, boolean dayOfWeek,
			double[] featureCenter, double[] featureScale) {

		/** The range that is normal for this sensor at this local hour: hourly median +/- 3 robust standard deviations. */
		public double[] expectedRange(Metric m, Instant at) {
			int h = at.atZone(ZoneId.of(timeZone)).getHour();
			double med = hourMedian[m.ordinal()][h];
			double s = hourScale[m.ordinal()][h];
			return new double[] { med - 3 * s, med + 3 * s };
		}
	}

	/** y ~ intercept + slope * x, fitted by least squares on training frames; scale is the robust spread of the residuals. */
	public record Relation(double intercept, double slope, double scale) {
		double residual(double x, double y) {
			return (y - (intercept + slope * x)) / scale;
		}
	}

	/** The features of one frame, with the frame they belong to. */
	public record Row(MinuteFrame frame, double[] x) {
	}

	private AnomalyFeatures() {
	}

	private static List<String> names() {
		List<String> n = new ArrayList<>();
		for (Metric m : METRICS) {
			for (String f : PER_METRIC) {
				n.add(m.scope() + "." + f);
			}
		}
		n.addAll(Arrays.asList(DEVICE));
		return List.copyOf(n);
	}

	/**
	 * Feature groups, one Isolation Forest each. A forest over all 30 features dilutes an anomaly that shows in one
	 * feature (a frozen humidity sensor is 1 dimension of 30, rarely picked for a split); a forest per sensor sees it as 1
	 * of 9. Each sensor group holds the sensor's own features and the relationships it takes part in; the device group
	 * holds the reporting gap, motion and the time of day and week, so motion at an unusual time is a corner it isolates.
	 */
	public record Group(String name, int[] features) {
	}

	public static final List<Group> GROUPS = groups();

	private static List<Group> groups() {
		List<Group> g = new ArrayList<>();
		for (Metric m : METRICS) {
			List<Integer> f = new ArrayList<>();
			for (int j = 0; j < PER_METRIC.length; j++) {
				f.add(m.ordinal() * PER_METRIC.length + j);
			}
			if (m != Metric.GAS) {
				f.add(REL_HT);                                   // humidity ~ temperature
			}
			if (m != Metric.TEMPERATURE) {
				f.add(REL_GH);                                   // gas ~ humidity
			}
			g.add(new Group(m.scope(), f.stream().mapToInt(Integer::intValue).toArray()));
		}
		g.add(new Group("device", new int[] { GAP, GAP + 1, GAP + 2, GAP + 3, GAP + 4, GAP + 5, GAP + 6 }));
		return List.copyOf(g);
	}

	/** A copy of x where every feature outside the group is set to its training median, so explanations stay in the group. */
	static double[] only(double[] x, int[] features, Preprocessing p) {
		double[] o = p.featureCenter().clone();
		for (int f : features) {
			o[f] = x[f];
		}
		return o;
	}

	static double[] project(double[] x, int[] features) {
		double[] p = new double[features.length];
		for (int i = 0; i < features.length; i++) {
			p[i] = x[features[i]];
		}
		return p;
	}

	/** The sensor a feature describes, or null for time features (time alone is never "the anomaly"). */
	static String owner(int feature) {
		if (feature < GAP) {
			return METRICS[feature / PER_METRIC.length].scope();
		}
		String name = NAMES.get(feature);
		if (name.startsWith("device.")) {
			return "device";
		}
		if (name.startsWith("motion.")) {
			return "motion";
		}
		return null;
	}

	// ---- learning the preprocessing -------------------------------------------------------------

	/** Learns baselines and relationships from training frames (oldest first), then the spread of each feature. */
	public static Preprocessing fit(List<MinuteFrame> train, ZoneId zone, int windowMinutes, MlProperties.Anomaly cfg) {
		int k = METRICS.length;
		double[][] hourMedian = new double[k][24];
		double[][] hourScale = new double[k][24];
		double[] median = new double[k];
		double[] scale = new double[k];
		double[] deltaScale = new double[k];
		for (Metric m : METRICS) {
			int i = m.ordinal();
			double floor = m.minScale(cfg);
			List<Double> all = new ArrayList<>();
			List<List<Double>> byHour = new ArrayList<>();
			for (int h = 0; h < 24; h++) {
				byHour.add(new ArrayList<>());
			}
			List<Double> deltas = new ArrayList<>();
			MinuteFrame prev = null;
			for (MinuteFrame f : train) {
				Double v = f.of(m);
				if (v == null) {
					continue;
				}
				all.add(v);
				byHour.get(f.at().atZone(zone).getHour()).add(v);
				if (prev != null) {
					long minutes = Duration.between(prev.at(), f.at()).toMinutes();
					if (minutes >= 1 && minutes <= 5) {
						deltas.add((v - prev.of(m)) / minutes);
					}
				}
				prev = f;
			}
			if (all.isEmpty()) {
				// A sensor this device never reported: every value feature stays 0 and missingShare says it is absent.
				Arrays.fill(hourScale[i], floor);
				scale[i] = floor;
				deltaScale[i] = floor;
				continue;
			}
			double[] a = toArray(all);
			median[i] = RobustStats.median(a);
			scale[i] = RobustStats.scale(RobustStats.mad(a, median[i]), floor);
			for (int h = 0; h < 24; h++) {
				List<Double> w = new ArrayList<>(byHour.get(h));
				w.addAll(byHour.get((h + 23) % 24));
				w.addAll(byHour.get((h + 1) % 24));
				if (w.size() >= MIN_HOUR_VALUES) {
					double[] wa = toArray(w);
					hourMedian[i][h] = RobustStats.median(wa);
					hourScale[i][h] = RobustStats.scale(RobustStats.mad(wa, hourMedian[i][h]), floor);
				} else {
					hourMedian[i][h] = median[i];
					hourScale[i][h] = scale[i];
				}
			}
			// The usual minute-to-minute change is small; a tenth of the value floor keeps it from being zero.
			deltaScale[i] = deltas.isEmpty() ? floor : RobustStats.scale(RobustStats.mad(toArray(deltas), RobustStats.median(toArray(deltas))), floor / 10);
		}
		Relation ht = relation(train, Metric.TEMPERATURE, Metric.HUMIDITY, cfg.minScaleHumidity());
		Relation gh = relation(train, Metric.HUMIDITY, Metric.GAS, cfg.minScaleGas());
		// Day of week only means something once training has seen every weekday. With less history (a real device needs
		// only 3 days) an unseen weekday would look anomalous all day long, so the day-of-week features stay neutral (0).
		boolean weekdays = train.stream().map(f -> f.at().atZone(zone).getDayOfWeek()).distinct().count() == 7;
		double[][] unit = new double[SIZE][24];
		for (double[] r : unit) {
			Arrays.fill(r, 1);
		}
		// Routine scaling: a jump is judged against the jumps normally seen at that local hour. Showers and cooking make
		// big, sudden changes at routine times; without this they would look as unusual as a fault. Never amplifies.
		List<Row> raw = compute(train, new Preprocessing(windowMinutes, zone.getId(), hourMedian, hourScale, median, scale, deltaScale, ht, gh,
				unit, weekdays, new double[SIZE], filled(SIZE, 1)));
		double[][] routine = new double[SIZE][24];
		for (int f = 0; f < SIZE; f++) {
			Arrays.fill(routine[f], 1);
			if (!ROUTINE_SCALED[f]) {
				continue;
			}
			List<List<Double>> byHour = new ArrayList<>();
			for (int h = 0; h < 24; h++) {
				byHour.add(new ArrayList<>());
			}
			for (Row r : raw) {
				byHour.get(r.frame().at().atZone(zone).getHour()).add(Math.abs(r.x()[f]));
			}
			for (int h = 0; h < 24; h++) {
				List<Double> w = new ArrayList<>(byHour.get(h));
				w.addAll(byHour.get((h + 23) % 24));
				w.addAll(byHour.get((h + 1) % 24));
				if (w.size() >= MIN_HOUR_VALUES) {
					double[] a = toArray(w);
					Arrays.sort(a);
					routine[f][h] = Math.max(1, a[(int) Math.floor(ROUTINE_QUANTILE * (a.length - 1))]);
				}
			}
		}
		Preprocessing partial = new Preprocessing(windowMinutes, zone.getId(), hourMedian, hourScale, median, scale, deltaScale, ht, gh, routine, weekdays,
				new double[SIZE], filled(SIZE, 1));
		// Feature spreads (for explanations only: which feature is most unusual). Not used by the forest itself.
		List<Row> rows = compute(train, partial);
		double[] center = new double[SIZE];
		double[] spread = new double[SIZE];
		for (int f = 0; f < SIZE; f++) {
			double[] col = new double[rows.size()];
			for (int r = 0; r < rows.size(); r++) {
				col[r] = rows.get(r).x()[f];
			}
			if (col.length == 0) {
				spread[f] = 1;
				continue;
			}
			double c = RobustStats.median(col);
			double sd = Math.sqrt(Arrays.stream(col).map(v -> (v - c) * (v - c)).sum() / col.length);
			center[f] = c;
			spread[f] = Math.max(Math.max(RobustStats.MAD_TO_SIGMA * RobustStats.mad(col, c), 0.5 * sd), 1e-3);
		}
		return new Preprocessing(windowMinutes, zone.getId(), hourMedian, hourScale, median, scale, deltaScale, ht, gh, routine, weekdays, center, spread);
	}

	/** Change-type features (meanDev, rollStd, rateOfChange, madZ of each sensor, and the two relationships). */
	private static final boolean[] ROUTINE_SCALED = routineScaled();
	/** The jump size that 99 % of normal minutes at that hour stay within. */
	private static final double ROUTINE_QUANTILE = 0.99;

	private static boolean[] routineScaled() {
		boolean[] s = new boolean[SIZE];
		for (Metric m : METRICS) {
			int base = m.ordinal() * PER_METRIC.length;
			for (int j = 1; j <= 4; j++) {
				s[base + j] = true;
			}
		}
		s[REL_HT] = true;
		s[REL_GH] = true;
		return s;
	}

	private static Relation relation(List<MinuteFrame> frames, Metric xm, Metric ym, double floor) {
		double sx = 0, sy = 0, sxx = 0, sxy = 0;
		int n = 0;
		for (MinuteFrame f : frames) {
			Double x = f.of(xm), y = f.of(ym);
			if (x != null && y != null) {
				sx += x;
				sy += y;
				sxx += x * x;
				sxy += x * y;
				n++;
			}
		}
		if (n < 30) {
			return new Relation(0, 0, Double.POSITIVE_INFINITY);   // residual is then always 0
		}
		double varX = sxx - sx * sx / n;
		double slope = varX <= 1e-12 ? 0 : (sxy - sx * sy / n) / varX;
		double intercept = (sy - slope * sx) / n;
		double[] res = new double[n];
		int i = 0;
		for (MinuteFrame f : frames) {
			Double x = f.of(xm), y = f.of(ym);
			if (x != null && y != null) {
				res[i++] = y - (intercept + slope * x);
			}
		}
		double med = RobustStats.median(res);
		return new Relation(intercept, slope, RobustStats.scale(RobustStats.mad(res, med), floor / 3));
	}

	// ---- computing features -----------------------------------------------------------------------

	/** One row per frame, oldest first. Frames must be sorted oldest first. */
	public static List<Row> compute(List<MinuteFrame> frames, Preprocessing p) {
		ZoneId zone = ZoneId.of(p.timeZone());
		Duration window = Duration.ofMinutes(p.windowMinutes());
		Deque<MinuteFrame> win = new ArrayDeque<>();             // frames in (t - window, t), before the current one
		int k = METRICS.length;
		double[] runValue = new double[k];
		int[] run = new int[k];
		Instant[] runLast = new Instant[k];
		List<Row> out = new ArrayList<>(frames.size());
		MinuteFrame previous = null;
		for (MinuteFrame f : frames) {
			Instant t = f.at();
			while (!win.isEmpty() && !win.peekFirst().at().isAfter(t.minus(window))) {
				win.pollFirst();
			}
			double[] x = new double[SIZE];
			int h = t.atZone(zone).getHour();
			for (Metric m : METRICS) {
				int i = m.ordinal();
				int base = i * PER_METRIC.length;
				Double v = f.of(m);
				List<Double> prior = new ArrayList<>();
				MinuteFrame lastWith = null;
				int missing = v == null ? 1 : 0;
				for (MinuteFrame w : win) {
					Double wv = w.of(m);
					if (wv == null) {
						missing++;
					} else {
						prior.add(wv);
						lastWith = w;
					}
				}
				x[base + 6] = missing / (double) (win.size() + 1);
				// Stuck run: identical consecutive values, missing values skipped (as the STUCK_VALUE rule does);
				// a gap longer than the window starts a new run.
				if (v != null) {
					if (run[i] > 0 && Double.compare(v, runValue[i]) == 0 && Duration.between(runLast[i], t).compareTo(window) <= 0) {
						run[i]++;
					} else {
						run[i] = 1;
						runValue[i] = v;
					}
					runLast[i] = t;
				}
				if (v == null) {
					continue;                                    // value features stay 0
				}
				x[base + HOUR_Z] = (v - p.hourMedian()[i][h]) / p.hourScale()[i][h];
				x[base + STUCK] = Math.log1p(run[i] - 1.0);
				if (prior.size() >= 3) {
					double[] a = toArray(prior);
					double mean = Arrays.stream(a).average().orElse(v);
					x[base + 1] = (v - mean) / p.scale()[i];
					double[] withCurrent = Arrays.copyOf(a, a.length + 1);
					withCurrent[a.length] = v;
					double mu = Arrays.stream(withCurrent).average().orElse(v);
					x[base + 2] = Math.sqrt(Arrays.stream(withCurrent).map(z -> (z - mu) * (z - mu)).sum() / (withCurrent.length - 1)) / p.scale()[i];
					double med = RobustStats.median(a);
					double mad = RobustStats.mad(a, med);
					// Floor: a few times the usual minute-to-minute change, so a flat window cannot turn a 0.1 step into a huge score.
					x[base + 4] = (v - med) / Math.max(RobustStats.MAD_TO_SIGMA * mad, 3 * p.deltaScale()[i]);
				}
				if (lastWith != null) {
					long minutes = Math.max(1, Duration.between(lastWith.at(), t).toMinutes());
					x[base + 3] = (v - lastWith.of(m)) / minutes / p.deltaScale()[i];
				}
			}
			long gap = previous == null ? 0 : Math.max(0, Duration.between(previous.at(), t).toMinutes() - 1);
			x[GAP] = Math.log1p(gap);
			x[GAP + 1] = Boolean.TRUE.equals(f.motion()) ? 1 : 0;
			int withMotion = f.motion() == null ? 0 : 1, moving = Boolean.TRUE.equals(f.motion()) ? 1 : 0;
			for (MinuteFrame w : win) {
				if (w.motion() != null) {
					withMotion++;
					moving += w.motion() ? 1 : 0;
				}
			}
			x[GAP + 2] = withMotion == 0 ? 0 : moving / (double) withMotion;
			ZonedDateTime local = t.atZone(zone);
			double hour = local.getHour() + local.getMinute() / 60.0;
			double dow = local.getDayOfWeek().getValue() - 1 + hour / 24;
			x[GAP + 3] = Math.sin(2 * Math.PI * hour / 24);
			x[GAP + 4] = Math.cos(2 * Math.PI * hour / 24);
			x[GAP + 5] = p.dayOfWeek() ? Math.sin(2 * Math.PI * dow / 7) : 0;
			x[GAP + 6] = p.dayOfWeek() ? Math.cos(2 * Math.PI * dow / 7) : 0;
			if (f.temperature() != null && f.humidity() != null && Double.isFinite(p.humidityVsTemperature().scale())) {
				x[REL_HT] = p.humidityVsTemperature().residual(f.temperature(), f.humidity());
			}
			if (f.humidity() != null && f.gas() != null && Double.isFinite(p.gasVsHumidity().scale())) {
				x[REL_GH] = p.gasVsHumidity().residual(f.humidity(), f.gas());
			}
			for (int j = 0; j < SIZE; j++) {
				double v = x[j] / p.routineScale()[j][h];
				x[j] = Double.isFinite(v) ? Math.max(-CLIP, Math.min(CLIP, v)) : 0;
			}
			out.add(new Row(f, x));
			win.addLast(f);
			previous = f;
		}
		return out;
	}

	// ---- explanation ------------------------------------------------------------------------------

	/** A feature's contribution to an explanation: how many training spreads it is from its training median. */
	public record Contribution(String feature, double value, double rarity) {
	}

	/** The most unusual features of a row, most unusual first (time-of-day features left out). */
	public static List<Contribution> top(double[] x, Preprocessing p, int n) {
		List<Contribution> c = new ArrayList<>();
		for (int f = 0; f < SIZE; f++) {
			if (NAMES.get(f).startsWith("time.")) {
				continue;
			}
			c.add(new Contribution(NAMES.get(f), round(x[f]), round(Math.abs(x[f] - p.featureCenter()[f]) / p.featureScale()[f])));
		}
		c.sort((a, b) -> Double.compare(b.rarity(), a.rarity()));
		return c.subList(0, Math.min(n, c.size()));
	}

	/**
	 * The sensor most responsible for the row: the owner of its most unusual feature. A relationship feature is
	 * credited to whichever of its two sensors is further from its own hourly normal.
	 */
	public static String responsibleSensor(double[] x, Preprocessing p) {
		int best = -1;
		double bestRarity = -1;
		for (int f = 0; f < SIZE; f++) {
			if (NAMES.get(f).startsWith("time.")) {
				continue;
			}
			double r = Math.abs(x[f] - p.featureCenter()[f]) / p.featureScale()[f];
			if (r > bestRarity) {
				bestRarity = r;
				best = f;
			}
		}
		if (best == REL_HT) {
			return Math.abs(x[hourZ(Metric.TEMPERATURE)]) > Math.abs(x[hourZ(Metric.HUMIDITY)]) ? Metric.TEMPERATURE.scope() : Metric.HUMIDITY.scope();
		}
		if (best == REL_GH) {
			return Math.abs(x[hourZ(Metric.HUMIDITY)]) > Math.abs(x[hourZ(Metric.GAS)]) ? Metric.HUMIDITY.scope() : Metric.GAS.scope();
		}
		String owner = best < 0 ? null : owner(best);
		return owner == null ? "device" : owner;
	}

	static int hourZ(Metric m) {
		return m.ordinal() * PER_METRIC.length + HOUR_Z;
	}

	private static double[] toArray(List<Double> v) {
		double[] a = new double[v.size()];
		for (int i = 0; i < a.length; i++) {
			a[i] = v.get(i);
		}
		return a;
	}

	private static double[] filled(int n, double v) {
		double[] a = new double[n];
		Arrays.fill(a, v);
		return a;
	}

	private static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}
}
