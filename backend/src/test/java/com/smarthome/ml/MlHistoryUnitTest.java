package com.smarthome.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Pure-Java tests of the new feature extraction, model version 2, and the two new anomaly rules. Histories here are
 * SIMULATED test fixtures; production training only ever reads stored telemetry.
 */
class MlHistoryUnitTest {

	private static final MlProperties PROPS = MlProperties.defaults();
	private static final MlProperties.Anomaly CFG = PROPS.anomaly();
	private static final Instant T0 = Instant.parse("2027-05-03T00:00:00Z");

	// ---- motion-frequency feature ----------------------------------------------------------------

	@Test
	void frequencyIsSmoothedTowardsTheOverallRateAndClamped() {
		double base = 0.3;
		double logitBase = Math.log(base / (1 - base));
		assertEquals(logitBase, OccupancyFeatures.frequency(0, 0, base), 1e-9, "no evidence: just the overall rate");
		assertTrue(OccupancyFeatures.frequency(40, 40, base) > OccupancyFeatures.frequency(4, 4, base), "more evidence moves further");
		assertEquals(Math.log(0.98 / 0.02), OccupancyFeatures.frequency(100_000, 100_000, base), 1e-9, "clamped at 98 %");
		assertEquals(Math.log(0.02 / 0.98), OccupancyFeatures.frequency(100_000, 0, base), 1e-9, "clamped at 2 %");
	}

	@Test
	void versionTwoModelsUseTheFrequencyFeatureAndVersionOneModelsStillWork() {
		List<MotionSample> data = SyntheticOccupancy.routine(T0, 14, ZoneId.of("UTC"), 7);
		OccupancyTrainer.Result r = OccupancyTrainer.train(data, ZoneId.of("UTC"), PROPS.occupancy());
		assertEquals(OccupancyTrainer.Status.READY, r.status(), r.message());
		OccupancyModel v2 = r.model();
		assertEquals(2, v2.version());
		assertEquals(OccupancyFeatures.DIM_V2 + 1, v2.weights().length, "intercept + time features + frequency");
		ZonedDateTime monday3 = ZonedDateTime.parse("2027-05-24T03:30:00Z");
		ZonedDateTime monday8 = ZonedDateTime.parse("2027-05-24T08:30:00Z");
		assertEquals(OccupancyFeatures.DIM_V2, v2.features(monday3).length);
		assertTrue(v2.probability(monday3) < 0.2 && v2.probability(monday8) > 0.6, "learned quiet night and busy morning");

		double[] w1 = new double[OccupancyFeatures.DIM + 1];
		w1[0] = -1;
		OccupancyModel v1 = new OccupancyModel(1, 15, "UTC", w1, new int[168], new int[168], 0.3);
		assertEquals(OccupancyFeatures.DIM, v1.features(monday3).length, "a stored v1 model keeps its own layout");
		assertEquals(1 / (1 + Math.exp(1)), v1.probability(monday3), 1e-12);
	}

	@Test
	void evaluationStaysHonestWithTheFrequencyFeature() {
		// Pure noise: motion is random at every hour. A leaking frequency feature would fake a pattern here.
		List<MotionSample> noise = SyntheticOccupancy.generate(T0, 14, ZoneId.of("UTC"), 3, 15, 10, (d, h) -> 0.4);
		OccupancyTrainer.Result r = OccupancyTrainer.train(noise, ZoneId.of("UTC"), PROPS.occupancy());
		assertEquals(OccupancyTrainer.Status.LOW_QUALITY, r.status(), "random motion must not look learnable: " + r.message());
		assertTrue(r.metrics().auc() < 0.6, "AUC near chance on unseen slots, was " + r.metrics().auc());
	}

	// ---- sudden change ---------------------------------------------------------------------------

	private static List<SensorSample> steadyTemperature(int n, long stepSeconds) {
		Random random = new Random(5);
		List<SensorSample> out = new ArrayList<>();
		double t = 24;
		for (int i = 0; i < n; i++) {
			t += random.nextGaussian() * 0.1;
			out.add(new SensorSample(T0.plusSeconds(i * stepSeconds), t, null, null));
		}
		return out;
	}

	@Test
	void consecutiveChangesAreLearnedOnlyWithEnoughCloseTogetherPairs() {
		assertNotNull(Baselines.build(steadyTemperature(400, 30), CFG).delta(Metric.TEMPERATURE));
		assertNull(Baselines.build(steadyTemperature(100, 30), CFG).delta(Metric.TEMPERATURE), "too few pairs: no judgement");
		assertNull(Baselines.build(steadyTemperature(400, 3600), CFG).delta(Metric.TEMPERATURE), "readings an hour apart are not consecutive");
		assertNull(Baselines.build(steadyTemperature(400, 30), CFG).delta(Metric.HUMIDITY), "a sensor with no values has no baseline");
	}

	@Test
	void aSuddenJumpIsReportedWithTheNumbersBehindIt() {
		Baselines.Stat changes = Baselines.build(steadyTemperature(400, 30), CFG).delta(Metric.TEMPERATURE);
		SensorSample before = new SensorSample(T0, 24.0, null, null);
		SensorSample after = new SensorSample(T0.plusSeconds(30), 31.5, null, null);
		Finding f = AnomalyDetectors.suddenChange(Metric.TEMPERATURE, before, after, changes, CFG).orElseThrow();
		assertEquals(AnomalyKind.SUDDEN_CHANGE, f.kind());
		assertEquals(AnomalySeverity.CRITICAL, f.severity());
		assertTrue(f.explanation().contains("from 24 °C to 31.5 °C") && f.explanation().contains("30 s apart"), f.explanation());
		assertEquals(7.5, (Double) f.details().get("change"), 1e-9);

		assertTrue(AnomalyDetectors.suddenChange(Metric.TEMPERATURE, before, new SensorSample(T0.plusSeconds(30), 24.1, null, null), changes, CFG)
				.isEmpty(), "a normal change is not reported");
		assertTrue(AnomalyDetectors.suddenChange(Metric.TEMPERATURE, before, new SensorSample(T0.plusSeconds(3600), 31.5, null, null), changes, CFG)
				.isEmpty(), "after a gap a big change is expected");
		assertTrue(AnomalyDetectors.suddenChange(Metric.TEMPERATURE, before, after, null, CFG).isEmpty(), "no baseline: no judgement");
		assertTrue(AnomalyDetectors.suddenChange(Metric.TEMPERATURE, new SensorSample(T0, null, null, null), after, changes, CFG).isEmpty(),
				"a missing reading is never treated as zero");
	}

	// ---- unusual activity ------------------------------------------------------------------------

	@Test
	void motionInANormallyQuietHourNeedsBothTheHistoryAndTheModelToAgree() {
		ZonedDateTime night = ZonedDateTime.parse("2027-05-24T03:20:00+05:30[Asia/Kolkata]");
		Optional<Finding> f = AnomalyDetectors.unusualActivity(night, night.toInstant(), 0.04, 1 / 12.0, 12, CFG);
		assertTrue(f.isPresent());
		assertEquals(AnomalyKind.UNUSUAL_ACTIVITY, f.get().kind());
		assertTrue(f.get().explanation().contains("Mon 03:20 (Asia/Kolkata)") && f.get().explanation().contains("1 of 12"), f.get().explanation());

		assertTrue(AnomalyDetectors.unusualActivity(night, night.toInstant(), 0.04, 0.0, 3, CFG).isEmpty(), "too little evidence for that hour");
		assertTrue(AnomalyDetectors.unusualActivity(night, night.toInstant(), 0.04, 0.5, 12, CFG).isEmpty(), "the hour is often busy");
		assertTrue(AnomalyDetectors.unusualActivity(night, night.toInstant(), 0.40, 0.0, 12, CFG).isEmpty(), "the model expects motion");
		assertTrue(AnomalyDetectors.unusualActivity(night, night.toInstant(), 0.04, null, 0, CFG).isEmpty(), "never seen: no judgement");
	}

	@Test
	void theFeatureSetDescriptionFitsItsColumn() {
		assertTrue(OccupancyFeatures.FEATURE_SET.length() <= 120);
		assertTrue(Arrays.stream(AnomalyKind.values()).allMatch(k -> k.name().length() <= 20), "ml_anomalies.kind is VARCHAR(20)");
	}
}
