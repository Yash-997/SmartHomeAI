package com.smarthome.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Statistical and rule-based anomaly detection, on small hand-built data. */
class AnomalyDetectorsTest {

	private static final MlProperties.Anomaly CFG = MlProperties.defaults().anomaly();
	private static final Instant T0 = Instant.parse("2027-03-01T12:00:00Z");

	private static SensorSample s(int minutesAfterT0, Double temp, Double hum, Integer gas) {
		return new SensorSample(T0.plusSeconds(minutesAfterT0 * 60L), temp, hum, gas);
	}

	/** Readings newest first (as the repository returns them), one per minute, ending at T0. */
	private static List<SensorSample> newestFirst(int count, java.util.function.IntFunction<SensorSample> make) {
		List<SensorSample> out = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			out.add(make.apply(i));   // i = how many minutes before T0
		}
		return out;
	}

	private static SensorSample ago(int minutes, Double temp, Double hum, Integer gas) {
		return new SensorSample(T0.minusSeconds(minutes * 60L), temp, hum, gas);
	}

	// ---- robust statistics --------------------------------------------------------------------

	@Test
	void medianAndMadIgnoreExtremeValues() {
		double[] v = { 20, 21, 22, 23, 1000 };   // one wild reading
		assertEquals(22.0, RobustStats.median(v));
		assertEquals(1.0, RobustStats.mad(v, 22.0));   // |dev| = 2,1,0,1,978 -> median 1
		assertEquals(2.5, RobustStats.median(new double[] { 1, 2, 3, 4 }));
		assertEquals(1.4826, RobustStats.scale(1.0, 0.1), 1e-12);
		assertEquals(0.3, RobustStats.scale(0.0, 0.3), "a flat history uses the floor, not zero");
		assertEquals(-2.0, RobustStats.score(20, 22, 1.0));
	}

	// ---- baseline: what is normal -------------------------------------------------------------

	private static List<SensorSample> history(int n, long seed, double mean, double spread) {
		Random r = new Random(seed);
		List<SensorSample> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(new SensorSample(T0.minusSeconds((long) i * 600), mean + r.nextGaussian() * spread, 50 + r.nextGaussian(), 1500 + (int) (r.nextGaussian() * 40)));
		}
		return out;
	}

	@Test
	void tooLittleHistoryMeansNoBaselineAndNoStatisticalJudgement() {
		Baselines b = Baselines.build(history(150, 1, 22, 0.3), CFG);   // below the 200 minimum
		assertNull(b.lookup(Metric.TEMPERATURE, T0));
		assertTrue(AnomalyDetectors.outlier(Metric.TEMPERATURE, s(0, 90.0, null, null), b.lookup(Metric.TEMPERATURE, T0), CFG).isEmpty(),
				"with no idea what normal is, stay silent instead of guessing");
	}

	@Test
	void missingReadingsAreIgnoredWhenLearningNormalNotCountedAsZero() {
		List<SensorSample> h = new ArrayList<>();
		Random r = new Random(2);
		for (int i = 0; i < 600; i++) {
			// half the messages have no temperature at all
			Double temp = i % 2 == 0 ? 22 + r.nextGaussian() * 0.3 : null;
			h.add(new SensorSample(T0.minusSeconds(i * 600L), temp, 50.0, 1500));
		}
		Baselines b = Baselines.build(h, CFG);
		Baselines.Stat st = b.lookup(Metric.TEMPERATURE, T0);
		assertNotNull(st);
		assertTrue(st.n() > 0 && st.n() <= 300, "only real readings count, at most 300 of the 600 messages: " + st.n());
		assertEquals(22.0, st.median(), 0.2, "if gaps were zeros the median would be dragged towards 0");
	}

	@Test
	void anHourlyBaselineAvoidsFlaggingTheNormalDailySwing() {
		// 18 C at night, 26 C in the afternoon, for 10 days
		Random r = new Random(3);
		List<SensorSample> h = new ArrayList<>();
		Instant start = Instant.parse("2027-02-20T00:00:00Z");
		for (int i = 0; i < 10 * 24 * 6; i++) {
			Instant t = start.plusSeconds(i * 600L);
			int hour = t.atZone(java.time.ZoneOffset.UTC).getHour();
			double base = 18 + 8 * Math.max(0, Math.sin(Math.PI * (hour - 6) / 12.0));
			h.add(new SensorSample(t, base + r.nextGaussian() * 0.3, 50.0, 1500));
		}
		Baselines b = Baselines.build(h, CFG);
		Instant afternoon = Instant.parse("2027-03-02T12:00:00Z");   // the daily peak
		Instant night = Instant.parse("2027-03-02T03:00:00Z");
		Baselines.Stat pm = b.lookup(Metric.TEMPERATURE, afternoon);
		assertTrue(pm.hourSpecific());
		assertTrue(pm.median() > 24, "midday normal is warm: " + pm.median());
		assertTrue(b.lookup(Metric.TEMPERATURE, night).median() < 20, "night normal is cool");

		SensorSample warmAfternoon = new SensorSample(afternoon, 26.0, 50.0, 1500);
		SensorSample warmAtNight = new SensorSample(night, 26.0, 50.0, 1500);
		assertTrue(AnomalyDetectors.outlier(Metric.TEMPERATURE, warmAfternoon, pm, CFG).isEmpty(), "26 C at the midday peak is normal");
		var flagged = AnomalyDetectors.outlier(Metric.TEMPERATURE, warmAtNight, b.lookup(Metric.TEMPERATURE, night), CFG);
		assertTrue(flagged.isPresent(), "the same 26 C at 03:00 is unusual");
		assertTrue(flagged.get().explanation().contains("this time of day"), flagged.get().explanation());
	}

	// ---- outliers ------------------------------------------------------------------------------

	private static Baselines.Stat stat(double median, double scale) {
		return new Baselines.Stat(median, scale, 1000, false);
	}

	@Test
	void severityFollowsTheRobustScore() {
		var base = stat(22, 0.5);
		assertTrue(AnomalyDetectors.outlier(Metric.TEMPERATURE, s(0, 23.9, null, null), base, CFG).isEmpty(), "z=3.8 is below the warning level");
		var warn = AnomalyDetectors.outlier(Metric.TEMPERATURE, s(0, 24.1, null, null), base, CFG).orElseThrow();   // z=4.2
		assertEquals(AnomalySeverity.WARNING, warn.severity());
		var crit = AnomalyDetectors.outlier(Metric.TEMPERATURE, s(0, 25.2, null, null), base, CFG).orElseThrow();   // z=6.4
		assertEquals(AnomalySeverity.CRITICAL, crit.severity());
		assertEquals(AnomalyKind.STATISTICAL_OUTLIER, crit.kind());
		assertEquals("temperature", crit.scope());
	}

	@Test
	void theExplanationSaysWhatWasSeenWhatWasNormalAndWhy() {
		var f = AnomalyDetectors.outlier(Metric.TEMPERATURE, s(0, 15.0, null, null), stat(22, 0.5), CFG).orElseThrow();
		String e = f.explanation();
		assertTrue(e.contains("15 °C") && e.contains("22 °C") && e.contains("below") && e.contains("robust standard deviations"), e);
		assertEquals(15.0, (double) f.details().get("value"));
		assertEquals(22.0, (double) f.details().get("median"));
		assertEquals(-14.0, (double) f.details().get("score"));
		assertEquals(1000, f.details().get("baselineSamples"));
		assertEquals(f.readingAt(), T0);
	}

	@Test
	void aFlatHistoryUsesTheSpreadFloorAndDoesNotDivideByZero() {
		// a perfectly constant history has MAD 0; the floor (0.3 C) stands in
		List<SensorSample> flat = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			flat.add(new SensorSample(T0.minusSeconds(i * 600L), 22.0, 50.0, 1500));
		}
		Baselines.Stat st = Baselines.build(flat, CFG).lookup(Metric.TEMPERATURE, T0);
		assertEquals(0.3, st.scale(), 1e-12);
		assertTrue(AnomalyDetectors.outlier(Metric.TEMPERATURE, s(0, 22.1, null, null), st, CFG).isEmpty());
		var f = AnomalyDetectors.outlier(Metric.TEMPERATURE, s(0, 30.0, null, null), st, CFG).orElseThrow();
		assertEquals(AnomalySeverity.CRITICAL, f.severity());
		assertTrue(Double.isFinite((double) f.details().get("score")));
	}

	@Test
	void aMissingValueIsNeverJudgedAsAnOutlier() {
		var base = stat(22, 0.5);
		// if null were treated as 0 this would be a screaming outlier
		assertTrue(AnomalyDetectors.outlier(Metric.TEMPERATURE, s(0, null, 50.0, 1500), base, CFG).isEmpty());
		assertTrue(AnomalyDetectors.outlier(Metric.HUMIDITY, s(0, 22.0, null, 1500), stat(50, 2), CFG).isEmpty());
		assertTrue(AnomalyDetectors.outlier(Metric.GAS, s(0, 22.0, 50.0, null), stat(1500, 50), CFG).isEmpty());
	}

	@Test
	void gasFindingsCarryTheAdvisoryNote() {
		var f = AnomalyDetectors.outlier(Metric.GAS, s(0, null, null, 3100), stat(1500, 100), CFG).orElseThrow();
		assertTrue(f.explanation().contains("gas warning works independently"), f.explanation());
		assertEquals(AnomalySeverity.CRITICAL, f.severity());
		assertEquals("gasRaw", f.scope());
	}

	// ---- plausibility --------------------------------------------------------------------------

	@Test
	void temperaturesOutsideWhatADht22CanMeasureAreCritical() {
		assertEquals(AnomalySeverity.CRITICAL, AnomalyDetectors.plausibility(Metric.TEMPERATURE, s(0, 95.0, null, null), CFG).orElseThrow().severity());
		assertEquals(AnomalyKind.OUT_OF_RANGE, AnomalyDetectors.plausibility(Metric.TEMPERATURE, s(0, -45.0, null, null), CFG).orElseThrow().kind());
		assertTrue(AnomalyDetectors.plausibility(Metric.TEMPERATURE, s(0, 80.0, null, null), CFG).isEmpty(), "80 is the edge of the range");
		assertTrue(AnomalyDetectors.plausibility(Metric.TEMPERATURE, s(0, 25.0, null, null), CFG).isEmpty());
		assertTrue(AnomalyDetectors.plausibility(Metric.TEMPERATURE, s(0, null, null, null), CFG).isEmpty(), "missing is not out of range");
	}

	@Test
	void anAdcPinnedAtItsMaximumIsFlaggedButOnlyAdvisory() {
		var f = AnomalyDetectors.plausibility(Metric.GAS, s(0, null, null, 4095), CFG).orElseThrow();
		assertEquals(AnomalySeverity.WARNING, f.severity());
		assertTrue(f.explanation().contains("saturated") && f.explanation().contains("independently"), f.explanation());
		assertTrue(AnomalyDetectors.plausibility(Metric.GAS, s(0, null, null, 4094), CFG).isEmpty());
	}

	@Test
	void aRejectedReadingIsDescribed() {
		var f = AnomalyDetectors.rejectedReading("temperature", 900.0, T0);
		assertEquals(AnomalyKind.OUT_OF_RANGE, f.kind());
		assertTrue(f.explanation().contains("900") && f.explanation().contains("rejected"), f.explanation());
	}

	// ---- stuck ---------------------------------------------------------------------------------

	@Test
	void identicalReadingsOverAWindowLookStuckButOneChangeBreaksTheRun() {
		List<SensorSample> stuck = newestFirst(60, i -> ago(i, 23.4, 45.0, 1500 + i));
		var f = AnomalyDetectors.stuck(Metric.TEMPERATURE, stuck, CFG).orElseThrow();
		assertEquals(AnomalyKind.STUCK_VALUE, f.kind());
		assertEquals(AnomalySeverity.INFO, f.severity());
		assertTrue(f.explanation().contains("23.4") && f.explanation().contains("60 readings"), f.explanation());
		// humidity in the same data does vary? no, it is constant too
		assertTrue(AnomalyDetectors.stuck(Metric.HUMIDITY, stuck, CFG).isPresent());
		// gas varies with i, so not stuck
		assertTrue(AnomalyDetectors.stuck(Metric.GAS, stuck, CFG).isEmpty());

		List<SensorSample> almost = newestFirst(60, i -> ago(i, i == 30 ? 23.5 : 23.4, 45.0, 1500));
		assertTrue(AnomalyDetectors.stuck(Metric.TEMPERATURE, almost, CFG).isEmpty(), "one different value in the window breaks the run");
		assertTrue(AnomalyDetectors.stuck(Metric.TEMPERATURE, newestFirst(59, i -> ago(i, 23.4, 45.0, 1500)), CFG).isEmpty(), "59 is not enough");
	}

	@Test
	void missingReadingsInsideAStretchDoNotCountAsRepeats() {
		// 59 identical values interleaved with 40 gaps: only 59 real readings, short of the 60 needed
		List<SensorSample> mixed = new ArrayList<>();
		int real = 0;
		for (int i = 0; real < 59; i++) {
			boolean gap = i % 3 == 1;
			mixed.add(ago(i, gap ? null : 23.4, 45.0, 1500));
			if (!gap) real++;
		}
		assertTrue(AnomalyDetectors.stuck(Metric.TEMPERATURE, mixed, CFG).isEmpty());
	}

	@Test
	void aStuckGasReadingIsAWarningAndNeedsFewerRepeats() {
		var f = AnomalyDetectors.stuck(Metric.GAS, newestFirst(20, i -> ago(i, 22.0 + i * 0.1, 50.0, 0)), CFG).orElseThrow();
		assertEquals(AnomalySeverity.WARNING, f.severity());
		assertTrue(f.explanation().contains("independently"));
		assertTrue(AnomalyDetectors.stuck(Metric.GAS, newestFirst(19, i -> ago(i, 22.0, 50.0, 0)), CFG).isEmpty());
	}

	// ---- missing -------------------------------------------------------------------------------

	@Test
	void aSensorThatStopsReportingWhileTheDeviceKeepsTalkingIsMissingData() {
		List<SensorSample> noTemp = newestFirst(10, i -> ago(i, null, 45.0 + i, 1500));
		var f = AnomalyDetectors.missing(Metric.TEMPERATURE, noTemp, CFG).orElseThrow();
		assertEquals(AnomalyKind.MISSING_DATA, f.kind());
		assertEquals(AnomalySeverity.WARNING, f.severity());
		assertTrue(f.explanation().contains("No temperature value in the last 10 messages"), f.explanation());
		assertTrue(AnomalyDetectors.missing(Metric.HUMIDITY, noTemp, CFG).isEmpty(), "humidity is present");
		assertTrue(AnomalyDetectors.missing(Metric.TEMPERATURE, newestFirst(9, i -> ago(i, null, 45.0, 1500)), CFG).isEmpty(), "9 is not enough");
		List<SensorSample> oneValue = newestFirst(10, i -> ago(i, i == 4 ? 22.0 : null, 45.0, 1500));
		assertTrue(AnomalyDetectors.missing(Metric.TEMPERATURE, oneValue, CFG).isEmpty(), "a single real reading ends the gap");
	}

	@Test
	void gasMissingNeedsLongerBecauseTheSensorWarmsUpAfterBoot() {
		assertTrue(AnomalyDetectors.missing(Metric.GAS, newestFirst(19, i -> ago(i, 22.0, 45.0, null)), CFG).isEmpty());
		var f = AnomalyDetectors.missing(Metric.GAS, newestFirst(20, i -> ago(i, 22.0, 45.0, null)), CFG).orElseThrow();
		assertTrue(f.explanation().contains("warming up"), f.explanation());
	}

	// ---- stale ---------------------------------------------------------------------------------

	@Test
	void aSilentDeviceIsStaleOnlyBetweenTheThresholdAndGivingUp() {
		assertTrue(AnomalyDetectors.stale(T0.minusSeconds(9 * 60), T0, CFG).isEmpty());
		var f = AnomalyDetectors.stale(T0.minusSeconds(11 * 60), T0, CFG).orElseThrow();
		assertEquals(AnomalyKind.STALE_DATA, f.kind());
		assertEquals("device", f.scope());
		assertTrue(f.explanation().contains("11 minutes"), f.explanation());
		assertFalse(AnomalyDetectors.stale(T0.minusSeconds(8 * 86400L), T0, CFG).isPresent(), "after a week it is considered gone");
		assertEquals(Optional.empty(), AnomalyDetectors.stale(T0, T0, CFG));
	}
}
