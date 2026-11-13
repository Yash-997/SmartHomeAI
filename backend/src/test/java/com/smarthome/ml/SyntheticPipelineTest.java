package com.smarthome.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.smarthome.ml.SyntheticGenerator.Dataset;
import com.smarthome.ml.SyntheticGenerator.Reading;
import com.smarthome.ml.SyntheticGenerator.Scenario;

/** The synthetic generator, the chronological trainer and the anomaly replay, without Spring or a database. */
class SyntheticPipelineTest {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final Instant START = LocalDate.of(2026, 10, 12).atStartOfDay(IST).toInstant();
	private static final MlProperties PROPS = MlProperties.defaults();
	private static final Dataset DS = SyntheticGenerator.generate(START, 30, IST, 42);

	// ---- generation ------------------------------------------------------------------------------

	@Test
	void thirtyDaysOfOneMinuteReadingsMinusTheOfflineMinutes() {
		assertEquals(30 * 1440 - DS.offlineMinutes(), DS.readings().size(), "one reading per online minute, nothing else");
		assertTrue(DS.offlineMinutes() >= 50, "includes the 50-minute disconnection scenario and random outages");
		assertEquals(START, DS.start());
		assertEquals(START.plus(Duration.ofDays(30)), DS.end());
		List<Reading> r = DS.readings();
		for (int i = 1; i < r.size(); i++) {
			long gap = Duration.between(r.get(i - 1).at(), r.get(i).at()).getSeconds();
			assertTrue(gap >= 60 && gap % 60 == 0, "strictly increasing whole minutes at " + r.get(i).at());
		}
		assertTrue(r.stream().allMatch(x -> x.at().getEpochSecond() % 60 == 0), "aligned to the minute");
	}

	@Test
	void gapsInTheReadingsAreExactlyTheRecordedOfflinePeriods() {
		int offlineFromEvents = 0;
		List<SyntheticGenerator.ConnectivityEvent> ev = DS.connectivity();
		for (int i = 0; i < ev.size(); i++) {
			if (ev.get(i).state().equals("OFFLINE")) {
				Instant back = i + 1 < ev.size() ? ev.get(i + 1).at() : DS.end();
				offlineFromEvents += (int) Duration.between(ev.get(i).at(), back).toMinutes();
			}
		}
		assertEquals(DS.offlineMinutes(), offlineFromEvents);
		assertTrue(ev.stream().filter(e -> e.state().equals("OFFLINE")).count() >= 4, "random outages plus the labelled disconnection");
	}

	@Test
	void generationIsDeterministicForASeedAndDifferentForAnother() {
		Dataset again = SyntheticGenerator.generate(START, 30, IST, 42);
		assertEquals(DS.readings(), again.readings());
		Dataset other = SyntheticGenerator.generate(START, 30, IST, 43);
		assertFalse(DS.readings().equals(other.readings()));
	}

	@Test
	void motionFollowsOccupancyAndTheRoutine() {
		long motionWhileEmpty = DS.readings().stream()
				.filter(x -> !x.occupied() && Boolean.TRUE.equals(x.motion()) && x.scenario() == Scenario.NORMAL).count();
		assertEquals(0, motionWhileEmpty, "nobody home, no motion (outside the labelled scenario)");
		// Weekday working hours are mostly UNOCCUPIED; weekday evenings mostly OCCUPIED; nights OCCUPIED but quiet.
		double weekdayNoon = share(x -> weekday(x) && hour(x) == 13, Reading::occupied);
		double weekdayEvening = share(x -> weekday(x) && hour(x) == 20, Reading::occupied);
		double nightMotion = share(x -> hour(x) == 3 && x.scenario() == Scenario.NORMAL, x -> Boolean.TRUE.equals(x.motion()));
		double eveningMotion = share(x -> weekday(x) && hour(x) == 20, x -> Boolean.TRUE.equals(x.motion()));
		assertTrue(weekdayNoon < 0.4, "weekday 13:00 occupied share " + weekdayNoon);
		assertTrue(weekdayEvening > 0.95, "weekday 20:00 occupied share " + weekdayEvening);
		assertTrue(nightMotion < 0.01 && eveningMotion > 0.2, "night " + nightMotion + " vs evening " + eveningMotion);
		assertTrue(share(x -> !weekday(x) && hour(x) == 9, Reading::occupied) > weekdayNoon, "weekends differ from weekdays");
	}

	@Test
	void sensorsAreCorrelatedWithTheHouseholdAndPhysicallyPlausible() {
		double tempHome = mean(x -> hour(x) == 18 && x.occupied(), Reading::temperature);
		double tempAway = mean(x -> hour(x) == 18 && !x.occupied(), Reading::temperature);
		assertTrue(tempHome > tempAway, "people and cooking warm the room: " + tempHome + " vs " + tempAway);
		double humMorning = mean(x -> weekday(x) && hour(x) == 7 && x.occupied(), Reading::humidity);
		double humAfternoon = mean(x -> weekday(x) && hour(x) == 15, Reading::humidity);
		assertTrue(humMorning > humAfternoon, "showers and cooler mornings: " + humMorning + " vs " + humAfternoon);
		double gasEvening = mean(x -> hour(x) == 20 && x.occupied() && x.scenario() == Scenario.NORMAL, x -> x.gasRaw() == null ? null : x.gasRaw().doubleValue());
		double gasNight = mean(x -> hour(x) == 3 && x.scenario() == Scenario.NORMAL, x -> x.gasRaw() == null ? null : x.gasRaw().doubleValue());
		assertTrue(gasEvening > gasNight + 15, "cooking (about 45 min of that hour) raises the MQ-2 reading: " + gasEvening + " vs " + gasNight);
		for (Reading x : DS.readings()) {
			if (x.scenario() == Scenario.NORMAL) {
				assertTrue(x.temperature() == null || (x.temperature() > 15 && x.temperature() < 40));
				assertTrue(x.humidity() == null || (x.humidity() >= 30 && x.humidity() <= 95));
				assertTrue(x.gasRaw() == null || x.gasRaw() < 600, "normal life never reaches the 600 warning level");
				assertTrue(x.temperature() == null || x.temperature() * 10 == Math.rint(x.temperature() * 10), "DHT22 resolution 0.1");
			}
		}
		long dhtFailures = DS.readings().stream().filter(x -> x.scenario() == Scenario.NORMAL && x.temperature() == null).count();
		assertTrue(dhtFailures > 20 && dhtFailures < 400, "rare DHT22 read failures: " + dhtFailures);
	}

	@Test
	void anomalyScenariosAreLabelledAndLookLikeTheFaultTheyRepresent() {
		assertEquals(5, DS.scenarios().size());
		List<Reading> frozen = in(Scenario.FROZEN_SENSOR);
		assertEquals(120, frozen.size());
		assertEquals(1, frozen.stream().map(Reading::temperature).distinct().count(), "one frozen temperature value");
		assertTrue(in(Scenario.GAS_SATURATION).stream().allMatch(x -> x.gasRaw() == 4095));
		assertTrue(in(Scenario.MISSING_VALUES).stream().allMatch(x -> x.temperature() == null && x.humidity() == null && x.gasRaw() == null));
		assertTrue(in(Scenario.DISCONNECTION).isEmpty(), "no readings at all while disconnected");
		assertTrue(in(Scenario.UNUSUAL_MOTION).stream().filter(x -> Boolean.TRUE.equals(x.motion())).count() >= 5);
		ZonedDateTime unusual = in(Scenario.UNUSUAL_MOTION).get(0).at().atZone(IST);
		assertEquals(3, unusual.getHour());
		assertTrue(unusual.getDayOfWeek().getValue() <= 5, "a weekday night");
		Instant testStart = START.plus(Duration.ofMillis((long) (Duration.ofDays(30).toMillis() * 0.85)));
		assertTrue(DS.scenarios().stream().allMatch(w -> !w.from().isBefore(testStart.minus(Duration.ofDays(1)))), "scenarios sit at the end");
	}

	// ---- training --------------------------------------------------------------------------------

	@Test
	void chronologicalSplitWithEmbargoAndNoLeakage() {
		SyntheticTrainer.Result r = SyntheticTrainer.train(DS, PROPS.occupancy());
		assertTrue(r.train().to().isBefore(r.validation().from()), "train ends before validation starts");
		assertTrue(!r.validation().from().isBefore(r.train().to().plus(SyntheticTrainer.EMBARGO)), "embargo after train");
		assertTrue(!r.test().from().isBefore(r.validation().to().plus(SyntheticTrainer.EMBARGO)), "embargo after validation");
		assertTrue(r.embargoDropped() >= 8, "slots near the boundaries are dropped, not reused");
		assertTrue(r.scenarioSlotsExcluded() > 0, "labelled anomaly slots stay out of occupancy training and scoring");
		// The stored model's hour-of-week counts contain exactly the train + validation slots: no test slot leaked in.
		int counted = java.util.Arrays.stream(r.model().cellN()).sum();
		assertEquals(r.train().slots() + r.validation().slots(), counted);
		assertTrue(r.test().slots() >= 300, "about 4.5 days of 15-minute test slots: " + r.test().slots());
	}

	@Test
	void theModelBeatsTheBaselineOnUnseenLaterDays() {
		SyntheticTrainer.Result r = SyntheticTrainer.train(DS, PROPS.occupancy());
		assertEquals(OccupancyTrainer.Status.READY, r.status(), r.message());
		assertTrue(r.metrics().logLoss() < r.metrics().baselineLogLoss());
		assertTrue(r.metrics().auc() > 0.8, "auc " + r.metrics().auc());
		assertTrue(r.validationLogLoss().size() == SyntheticTrainer.LAMBDAS.length, "lambda chosen on validation");
		assertNotNull(r.agreement());
	}

	@Test
	void randomMotionCannotLookLearnable() {
		// Same timestamps, motion shuffled into pure noise: an honest pipeline must not report a good model.
		java.util.Random rnd = new java.util.Random(1);
		List<Reading> noisy = DS.readings().stream()
				.map(x -> new Reading(x.at(), x.temperature(), x.humidity(), rnd.nextDouble() < 0.12, x.gasRaw(), x.occupied(), x.scenario())).toList();
		Dataset noise = new Dataset(DS.start(), DS.end(), DS.zone(), 0, 30, noisy, DS.connectivity(), DS.scenarios(), DS.offlineMinutes());
		SyntheticTrainer.Result r = SyntheticTrainer.train(noise, PROPS.occupancy());
		assertEquals(OccupancyTrainer.Status.LOW_QUALITY, r.status(), "AUC " + r.metrics().auc());
	}

	// ---- anomaly replay --------------------------------------------------------------------------

	@Test
	void everyLabelledScenarioIsDetectedByTheRuleMeantForIt() {
		SyntheticTrainer.Result t = SyntheticTrainer.train(DS, PROPS.occupancy());
		SyntheticAnomalyEvaluator.Evaluation e = SyntheticAnomalyEvaluator.evaluate(DS, t.model(), PROPS);
		for (SyntheticAnomalyEvaluator.ScenarioResult s : e.scenarios()) {
			assertTrue(s.detected(), s.scenario() + " not detected");
			assertTrue(s.delayMinutes() <= 70, s.scenario() + " took " + s.delayMinutes() + " min");
		}
		assertTrue(e.normalHours() > 24, "false positives are measured over normal operation too");
	}

	@Test
	void withoutAModelUnusualMotionIsNotJudged() {
		SyntheticAnomalyEvaluator.Evaluation e = SyntheticAnomalyEvaluator.evaluate(DS, null, PROPS);
		var unusual = e.scenarios().stream().filter(s -> s.scenario() == Scenario.UNUSUAL_MOTION).findFirst().orElseThrow();
		assertFalse(unusual.detected());
		assertNull(unusual.delayMinutes());
	}

	/** Writes the numbers for the report (not an assertion test). */
	@Test
	void writeReport() throws Exception {
		SyntheticTrainer.Result t = SyntheticTrainer.train(DS, PROPS.occupancy());
		SyntheticAnomalyEvaluator.Evaluation e = SyntheticAnomalyEvaluator.evaluate(DS, t.model(), PROPS);
		var m = t.metrics();
		StringBuilder b = new StringBuilder();
		b.append(String.format(Locale.ROOT, "records=%d offlineMinutes=%d events=%d%n", DS.readings().size(), DS.offlineMinutes(), DS.connectivity().size()));
		b.append(String.format(Locale.ROOT, "train=%d (%d motion) val=%d (%d) test=%d (%d) embargoDropped=%d scenarioExcluded=%d lambda=%s valLoss=%s%n",
				t.train().slots(), t.train().withMotion(), t.validation().slots(), t.validation().withMotion(), t.test().slots(), t.test().withMotion(),
				t.embargoDropped(), t.scenarioSlotsExcluded(), t.lambda(), t.validationLogLoss()));
		b.append(String.format(Locale.ROOT, "status=%s acc=%.3f baseAcc=%.3f prec=%s rec=%s f1=%s auc=%s logloss=%.3f baseLogloss=%.3f brier=%.3f%n",
				t.status(), m.accuracy(), m.baselineAccuracy(), m.precision(), m.recall(), m.f1(), m.auc(), m.logLoss(), m.baselineLogLoss(), m.brier()));
		b.append(String.format(Locale.ROOT, "agreementWithTrueOccupancy=%.3f majority=%.3f slots=%d%n", t.agreement().accuracy(),
				t.agreement().majorityBaseline(), t.agreement().slots()));
		for (var s : e.scenarios()) {
			b.append(String.format(Locale.ROOT, "%s detected=%s delay=%s as=%s%n", s.scenario(), s.detected(), s.delayMinutes(), s.detectedAs()));
		}
		b.append(String.format(Locale.ROOT, "normalHours=%.1f falsePositives=%s total=%d%n", e.normalHours(), e.falsePositiveEpisodes(), e.totalFalsePositiveEpisodes()));
		Files.createDirectories(Path.of("target"));
		Files.writeString(Path.of("target", "synthetic-report.txt"), b.toString());
	}

	// ---- helpers ---------------------------------------------------------------------------------

	private static List<Reading> in(Scenario s) {
		return DS.readings().stream().filter(x -> x.scenario() == s).toList();
	}

	private static int hour(Reading x) {
		return x.at().atZone(IST).getHour();
	}

	private static boolean weekday(Reading x) {
		DayOfWeek d = x.at().atZone(IST).getDayOfWeek();
		return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
	}

	private static double share(java.util.function.Predicate<Reading> where, java.util.function.Predicate<Reading> what) {
		List<Reading> sel = DS.readings().stream().filter(where).toList();
		return sel.stream().filter(what).count() / (double) sel.size();
	}

	private static double mean(java.util.function.Predicate<Reading> where, java.util.function.Function<Reading, Double> value) {
		return DS.readings().stream().filter(where).map(value).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
	}
}
