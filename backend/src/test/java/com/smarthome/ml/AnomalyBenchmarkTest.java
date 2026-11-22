package com.smarthome.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.smarthome.ml.AnomalyBenchmark.DetectorResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * Trains, tunes and evaluates the hybrid anomaly detector on SYNTHETIC data, end to end without Spring, and writes the
 * report to target/ml-reports (anomaly-model.json, anomaly-evaluation.json, anomaly-evaluation.md).
 */
class AnomalyBenchmarkTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final LocalDate FIRST_DAY = LocalDate.of(2026, 10, 12);
	private static AnomalyBenchmark.Outcome outcome;

	@BeforeAll
	static void run() throws Exception {
		outcome = AnomalyBenchmarkCli.run(Path.of("target", "ml-reports"), 42, 30, FIRST_DAY, IST, MlProperties.defaults());
		System.out.println(AnomalyBenchmarkCli.markdown(outcome.report()));
	}

	private static DetectorResult test(AnomalyBenchmark.Detector d) {
		return outcome.report().test().get(d.ordinal());
	}

	@Test
	void theSplitIsChronologicalWithEmbargoesAndTrainsOnNormalMinutesOnly() {
		AnomalyBenchmark.SplitInfo s = outcome.report().split();
		assertTrue(s.trainTo().plus(AnomalyModelTrainer.EMBARGO).compareTo(s.validationFrom()) <= 0);
		assertTrue(s.validationTo().plus(AnomalyModelTrainer.EMBARGO).compareTo(s.testFrom()) <= 0);
		assertTrue(outcome.model().trainedTo().isBefore(s.trainTo()), "the model saw nothing after the training part");
		Instant start = FIRST_DAY.atStartOfDay(IST).toInstant();
		SyntheticGenerator.Dataset ds = SyntheticGenerator.generate(start, 30, IST, 42);
		assertTrue(ds.scenarios().stream().allMatch(w -> !w.from().isBefore(s.validationFrom())), "no labelled anomaly in training");
		assertTrue(s.trainFrames() > 25_000, "about 21 days of minutes: " + s.trainFrames());
	}

	@Test
	void everyMetricIsReportedForEveryDetector() {
		assertEquals(3, outcome.report().test().size());
		for (DetectorResult d : outcome.report().test()) {
			var c = d.confusion();
			long counted = c.truePositives() + c.falsePositives() + c.falseNegatives() + c.trueNegatives();
			long left = outcome.report().split().testMinutes() - counted;
			assertTrue(left > 0 && left <= 9 * AnomalyBenchmark.GRACE.toMinutes(), "only the grace minutes after the 9 windows are left out: " + left);
			assertEquals(c.truePositives() + c.falseNegatives(), d.windowResults().stream().mapToInt(AnomalyBenchmark.WindowResult::minutes).sum(),
					"positives are exactly the labelled minutes");
			assertEquals(9, d.byType().size(), d.detector() + ": every anomaly type");
			assertEquals(9, d.windows());
			assertTrue(d.normalHours() > 80, "false alarms are measured over days of normal data");
		}
	}

	@Test
	void theForestOnlyAddsAlarmsAndTheCombinationFindsEveryTestAnomaly() {
		DetectorResult rules = test(AnomalyBenchmark.Detector.RULES);
		DetectorResult forest = test(AnomalyBenchmark.Detector.ISOLATION_FOREST);
		DetectorResult hybrid = test(AnomalyBenchmark.Detector.HYBRID);
		assertTrue(hybrid.confusion().truePositives() >= rules.confusion().truePositives());
		assertTrue(hybrid.confusion().truePositives() >= forest.confusion().truePositives());
		assertEquals(9, hybrid.windowsDetected(), "all 9 test anomalies, which differ from the validation ones");
		assertNotNull(forest.precision());
		assertTrue(forest.precision() > 0.5, "when the forest alone raises an alarm it is usually right: " + forest.precision());
		assertTrue(hybrid.falseAlarmsPerHour() < 0.5, "false alarms per hour " + hybrid.falseAlarmsPerHour());
		assertTrue(hybrid.f1() > 0.5, "F1 " + hybrid.f1());
	}

	@Test
	void trainingIsReproducibleAndTheStoredModelScoresIdentically() throws Exception {
		AnomalyBenchmark.Outcome again = AnomalyBenchmark.run(
				SyntheticGenerator.generate(FIRST_DAY.atStartOfDay(IST).toInstant(), 30, IST, 42),
				SyntheticTrainer.train(SyntheticGenerator.generate(FIRST_DAY.atStartOfDay(IST).toInstant(), 30, IST, 42), MlProperties.defaults().occupancy())
						.model(), MlProperties.defaults());
		assertEquals(outcome.report().chosen(), again.report().chosen(), "same data and seed: same settings");
		assertEquals(outcome.report().test(), again.report().test(), "and the same numbers");
		AnomalyModel stored = JsonMapper.builder().build().readValue(Files.readString(Path.of("target", "ml-reports", "anomaly-model.json")),
				AnomalyModel.class);
		Random r = new Random(5);
		for (int i = 0; i < 200; i++) {
			double[] x = new double[AnomalyFeatures.SIZE];
			for (int j = 0; j < x.length; j++) {
				x[j] = r.nextGaussian() * 3;
			}
			assertEquals(outcome.model().score(x), stored.score(x), 0.0);
		}
	}

	// ---- real telemetry: no labels ----------------------------------------------------------------

	/** SIMULATED stand-in for real telemetry (no labels): 5 normal days at one frame per minute. */
	private static List<MinuteFrame> unlabelledDays(int days) {
		Random r = new Random(9);
		List<MinuteFrame> f = new ArrayList<>();
		Instant t0 = Instant.parse("2026-10-01T00:00:00Z");
		for (int m = 0; m < days * 1440; m++) {
			double hour = (m % 1440) / 60.0;
			double t = Math.round((25 + 2 * Math.sin(2 * Math.PI * hour / 24) + r.nextGaussian() * 0.1) * 10) / 10.0;
			double h = Math.round((65 - 1.4 * (t - 25) + r.nextGaussian() * 0.5) * 10) / 10.0;
			f.add(new MinuteFrame(t0.plusSeconds(60L * m), t, h, (double) Math.round(300 + 1.5 * (h - 60) + r.nextGaussian() * 6), false, 2));
		}
		return f;
	}

	@Test
	void onRealTelemetryTheThresholdKeepsWithinTheAlarmBudgetAndNoAccuracyIsClaimed() {
		MlProperties props = MlProperties.defaults();
		AnomalyModelTrainer.Result r = AnomalyModelTrainer.trainUnlabelled(unlabelledDays(5), ZoneId.of("UTC"), props, t -> false);
		assertEquals(AnomalyModelTrainer.Status.READY, r.status(), r.message());
		assertTrue(r.report().validationAlarmsPerDay() <= props.forest().maxFalseAlarmsPerDay());
		assertTrue(r.report().note().contains("cannot be measured"), "no precision or recall is invented without labels");
		assertEquals("REAL", r.model().dataSource());
		assertTrue(r.report().trainTo().isBefore(r.report().validationFrom()) && r.report().validationTo().isBefore(r.report().testFrom()));
	}

	@Test
	void tooLittleRealHistoryTrainsNothing() {
		AnomalyModelTrainer.Result r = AnomalyModelTrainer.trainUnlabelled(unlabelledDays(1), ZoneId.of("UTC"), MlProperties.defaults(), t -> false);
		assertEquals(AnomalyModelTrainer.Status.INSUFFICIENT_DATA, r.status());
		assertNull(r.model());
	}

	@Test
	void minutesInsideRecordedAnomaliesAreLeftOutOfTraining() {
		List<MinuteFrame> days = unlabelledDays(5);
		Instant from = days.get(1440).at(), to = days.get(1440 + 600).at();
		AnomalyModelTrainer.Result r = AnomalyModelTrainer.trainUnlabelled(days, ZoneId.of("UTC"), MlProperties.defaults(),
				t -> !t.isBefore(from) && t.isBefore(to));
		assertEquals(600, r.report().excludedFrames());
	}
}
