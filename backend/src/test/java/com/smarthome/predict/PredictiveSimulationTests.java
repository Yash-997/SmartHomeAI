package com.smarthome.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.smarthome.predict.LightingDecisionEngine.Override;
import com.smarthome.predict.LightingDecisionEngine.Type;
import com.smarthome.predict.RoutineProfile.Kind;
import com.smarthome.predict.SimulationSession.Scenario;
import com.smarthome.predict.SimulationSession.View;

import tools.jackson.databind.ObjectMapper;

/** Behaviour of the data and models on SYNTHETIC data. Nothing here measures accuracy on real sensor data. */
class PredictiveSimulationTests {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);
	private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
	private static DemoData.Generated generated;
	private static PredictiveModelService.Ready ready;

	@BeforeAll
	static void trainOnce() {
		generated = DemoData.generate(TODAY, DemoData.Routine.DEFAULT, 42, ZONE);
		PredictiveModel model = PredictiveModel.train(DemoData.traces(generated.data().readings(), ZONE));
		var info = new PredictiveModelService.DatasetInfo(UUID.randomUUID(), "SYNTHETIC", "synthetic-home-01", 42,
				DemoData.HISTORY_DAYS, ZONE.getId(), generated.data().start(), generated.data().end(),
				generated.data().readings().size(), generated.data().offlineMinutes(), 0, 0, "primary-1");
		ready = new PredictiveModelService.Ready(info, model, 1, Instant.now());
	}

	private static SimulationSession session(Scenario s) {
		SimulationSession session = new SimulationSession(ready);
		session.select(s);
		return session;
	}

	@Test
	void theDatasetIsThirtyReproducibleDaysWithVariationGapsAndLabels() {
		var again = DemoData.generate(TODAY, DemoData.Routine.DEFAULT, 42, ZONE);
		assertEquals(generated.data().readings(), again.data().readings(), "same seed, same data");
		assertNotEquals(generated.data().readings(),
				DemoData.generate(TODAY, DemoData.Routine.DEFAULT, 43, ZONE).data().readings());
		assertEquals(DemoData.HISTORY_DAYS, generated.plan().size());
		assertTrue(generated.plan().stream().map(DemoData.PlannedDay::variation).distinct().count() >= 5, generated.plan().toString());
		assertTrue(generated.data().readings().size() < 30 * 1440, "disconnections leave gaps");
		assertTrue(generated.data().readings().stream().anyMatch(r -> r.temperature() == null), "some readings are missing");
		assertTrue(generated.data().readings().stream().anyMatch(r -> r.motion() && !r.occupied()), "PIR noise: motion is not presence");
		assertTrue(generated.data().readings().stream().anyMatch(r -> !r.motion() && r.occupied()));
	}

	@Test
	void trainingIsSplitChronologicallyAndEveryAlgorithmIsCompared() {
		var e = ready.model().stored().evaluation();
		assertEquals(30, e.trainDays() + e.validationDays() + e.testDays());
		assertTrue(e.data().contains("SYNTHETIC"));
		assertEquals(1 + PredictiveModel.HORIZONS.length, e.results().size());
		for (var c : e.results()) {
			assertEquals(Learner.Algorithm.values().length, c.validationLogLoss().size());
			assertEquals(Collections.min(c.validationLogLoss().values()), c.validationLogLoss().get(c.chosen()), 1e-12);
			var m = c.model();
			assertEquals(m.n(), m.truePositive() + m.falsePositive() + m.falseNegative() + m.trueNegative());
			assertTrue(!c.calibration().isEmpty());
			System.out.printf("EVAL %s | %s val=%s | model P=%.3f R=%.3f F1=%.3f AUC=%.3f brier=%.3f cm=[%d,%d,%d,%d] | base P=%s R=%s F1=%s AUC=%.3f brier=%.3f%n",
					c.target(), c.chosen(), c.validationLogLoss(), m.precision(), m.recall(), m.f1(), m.auc(), m.brier(),
					m.truePositive(), m.falsePositive(), m.falseNegative(), m.trueNegative(), c.baseline().precision(),
					c.baseline().recall(), c.baseline().f1(), c.baseline().auc(), c.baseline().brier());
			c.calibration().forEach(b -> System.out.printf("EVAL   calibration %s %.1f-%.1f n=%d predicted=%.2f observed=%.2f%n",
					c.target(), b.from(), b.to(), b.n(), b.meanPredicted(), b.observedRate()));
		}
		System.out.printf("EVAL arrival median error=%s coverage=%s days=%d%n", e.arrivalMedianErrorMinutes(),
				e.arrivalWindowCoverage(), e.arrivalDaysScored());
	}

	@Test
	void aSavedModelGivesTheSamePredictionsWithoutRetraining() {
		ObjectMapper mapper = new ObjectMapper();
		var restored = PredictiveModel.restore(ready.model().history(),
				mapper.readValue(mapper.writeValueAsString(ready.model().stored()), PredictiveModel.Stored.class));
		DayTrace day = ready.model().history().get(20);
		for (int m : new int[] { 120, 600, 1110 }) {
			assertEquals(ready.model().presence(day, m).probability(), restored.presence(day, m).probability(), 1e-12);
			assertArrayEquals(ready.model().arrival(day, m).horizons().toArray(), restored.arrival(day, m).horizons().toArray());
		}
	}

	@Test
	void occupancyFollowsTheDayAndTheRoutineIsLearnedFromMotion() {
		SimulationSession s = session(Scenario.NORMAL_WEEKDAY);
		View morning = s.view();
		assertTrue(morning.presence().probability() > 0.7);
		assertEquals(360, morning.learned().wake(), 25);
		assertEquals(570, morning.learned().leave(), 30);
		assertEquals("SIMULATION", morning.mode());
		assertTrue(morning.dataLabel().contains("SYNTHETIC"));
		s.setTime(14 * 60);
		assertEquals(PredictiveModel.State.LIKELY_EMPTY, s.view().presence().state());
	}

	@Test
	void arrivalProbabilityRisesTowardsTheUsualReturnAndHorizonsAreOrdered() {
		SimulationSession s = new SimulationSession(ready);
		s.setTime(13 * 60);
		double early = s.view().arrival().at(60).probability();
		View evening = session(Scenario.PREDICTIVE_ARRIVAL).view();
		var a = evening.arrival();
		assertTrue(a.at(60).probability() > early + 0.3, early + " -> " + a.at(60).probability());
		assertTrue(a.at(15).probability() <= a.at(30).probability() && a.at(60).probability() <= a.at(120).probability());
		assertTrue(a.windowFrom() > 18 * 60 + 30 && a.supportDays() <= a.comparableDays());
	}

	@Test
	void aDelayedReturnMovesTheWindowAndThePreArrivalLightEventuallyTimesOut() {
		SimulationSession s = session(Scenario.DELAYED_RETURN);
		View before = s.view();
		s.advance(40);
		View after = s.view();
		assertTrue(after.presence().state() != PredictiveModel.State.LIKELY_OCCUPIED, after.presence().toString());
		assertTrue(after.arrival().supportDays() < before.arrival().supportDays());
		s.advance(34); // 19:59, still not home
		s.advance(11); // 20:10, motion has appeared
		assertTrue(s.view().presence().probability() > 0.7);
	}

	@Test
	void aMissedRoutineTimesOutThePreArrivalLightAndRecordsIt() {
		SimulationSession s = new SimulationSession(ready);
		s.changeToday(360, 570, 22 * 60, 0); // very late: nobody home all evening
		s.setSettings(new LightingDecisionEngine.Settings(true, java.time.LocalTime.of(23, 30), java.time.LocalTime.of(6, 0), true,
				0.75, 0.35, 0.5, 0.2, 60, 60, 2, 20, 180));
		s.setTime(18 * 60);
		for (int i = 0; i < 3; i++) {
			s.advance(60);
		}
		View v = s.view();
		assertTrue(v.log().stream().anyMatch(l -> l.reason().startsWith("Pre-arrival timeout")), v.log().toString());
		assertTrue(v.preArrival().expired());
		assertEquals(false, v.light().on());
		View home = session(Scenario.MISSED_ROUTINE).view();
		assertEquals(-1, home.today().leave());
	}

	@Test
	void oneLateWakeUpIsIsolatedAndFiveAreSustainedWithoutRewritingTheRoutine() {
		SimulationSession s = new SimulationSession(ready);
		int usual = s.view().learned().wake();
		s.select(Scenario.UNUSUAL_WAKE);
		s.setTime(8 * 60 + 30);
		assertEquals(Kind.ISOLATED, s.view().deviation().kind());
		assertEquals(usual, s.view().learned().wake());
		s.changeToday(480, 570, 1140, 4);
		assertEquals(Kind.SUSTAINED, s.view().deviation().kind());
		assertEquals(usual, s.view().learned().wake(), 20);
		s.changeToday(480, 570, 1140, 15);
		assertTrue(s.view().learned().wake() > usual + 60, "a long sustained change is eventually learned");
	}

	@Test
	void nightMotionIsRecordedButSuppressedAndOverrideIsRespected() {
		SimulationSession s = session(Scenario.NIGHT_MODE);
		double before = s.view().presence().probability();
		s.triggerMotion();
		View v = s.view();
		assertEquals(Type.SUPPRESSED_NIGHT_MODE, v.decision().type());
		assertEquals(false, v.light().on());
		assertTrue(v.timeline().get(v.timeline().size() - 1).motion() >= 1);
		assertNotEquals(before, v.presence().probability(), "motion updates the prediction");
		s.setOverride(Override.ON);
		assertTrue(s.view().light().on());
		s.setOverride(Override.NONE);
		assertEquals(false, s.view().light().on());
	}

	@Test
	void missingSensorDataStillGivesABoundedPredictionAndNoInventedWindow() {
		DayTrace silent = DemoData.blank(TODAY);
		double p = ready.model().presence(silent, 14 * 60).probability();
		assertTrue(p >= 0 && p <= 1);
		assertNotNull(ready.model().arrival(silent, 23 * 60 + 30));
		assertNull(ready.model().arrival(silent, 23 * 60 + 30).median());
	}

	@Test
	void coldStartAndUnlabelledHistoryAreRefused() {
		List<DayTrace> history = ready.model().history();
		var cold = assertThrows(IllegalArgumentException.class, () -> PredictiveModel.train(history.subList(0, 10)));
		assertTrue(cold.getMessage().startsWith("Cold start"));
		DayTrace live = new DayTrace(TODAY, new boolean[DayTrace.MINUTES], null);
		assertThrows(IllegalArgumentException.class, () -> PredictiveModel.train(Collections.nCopies(20, live)));
		assertThrows(IllegalArgumentException.class, () -> new DemoData.Routine(600, 500, 900));
	}
}
