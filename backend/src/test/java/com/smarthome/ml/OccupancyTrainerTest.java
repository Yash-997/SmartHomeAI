package com.smarthome.ml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.smarthome.ml.OccupancyTrainer.Result;
import com.smarthome.ml.OccupancyTrainer.Status;

/**
 * Trains on SIMULATED histories (see {@link SyntheticOccupancy}). These tests prove the machinery learns a pattern that
 * is really in the data, evaluates it honestly, and refuses to pretend when there is not enough data. They say nothing
 * about how well the model would do on a real home.
 */
class OccupancyTrainerTest {

	private static final MlProperties.Occupancy CFG = MlProperties.defaults().occupancy();
	private static final ZoneId UTC = ZoneId.of("UTC");
	/** A Monday, 00:00 UTC. */
	private static final Instant MONDAY = Instant.parse("2027-03-01T00:00:00Z");

	private static ZonedDateTime at(String isoLocal, ZoneId zone) {
		return LocalDateTime.parse(isoLocal).atZone(zone);
	}

	@Test
	void learnsTheRoutineAndBeatsTheBaselineOnUnseenRecentData() {
		Result r = OccupancyTrainer.train(SyntheticOccupancy.routine(MONDAY, 21, UTC, 42), UTC, CFG);

		assertEquals(Status.READY, r.status(), r.message());
		assertNotNull(r.model());
		assertNotNull(r.metrics());
		// honest evaluation: scored on the most recent 20% of slots, better than always guessing the average
		assertEquals(Math.round(r.counts().labeledBuckets() * 0.2), r.testBuckets());
		assertEquals(r.counts().labeledBuckets(), r.trainBuckets() + r.testBuckets());
		assertTrue(r.metrics().logLoss() < r.metrics().baselineLogLoss(), "model " + r.metrics().logLoss() + " vs baseline " + r.metrics().baselineLogLoss());
		assertTrue(r.metrics().accuracy() > r.metrics().baselineAccuracy(), "accuracy " + r.metrics().accuracy() + " vs " + r.metrics().baselineAccuracy());
		assertTrue(r.metrics().auc() > 0.85, "auc " + r.metrics().auc());

		OccupancyModel m = r.model();
		double weekdayMorning = m.probability(at("2027-05-03T08:00:00", UTC));   // Monday
		double weekdayMidday = m.probability(at("2027-05-03T12:00:00", UTC));
		double weekdayNight = m.probability(at("2027-05-03T03:00:00", UTC));
		double weekdayEvening = m.probability(at("2027-05-03T19:00:00", UTC));
		double saturdayMidday = m.probability(at("2027-05-01T12:00:00", UTC));
		assertTrue(weekdayMorning > 0.6, "weekday morning " + weekdayMorning);
		assertTrue(weekdayEvening > 0.6, "weekday evening " + weekdayEvening);
		assertTrue(weekdayMidday < 0.35, "weekday midday (out at work) " + weekdayMidday);
		assertTrue(weekdayNight < 0.2, "night " + weekdayNight);
		assertTrue(saturdayMidday > weekdayMidday + 0.3, "weekends differ from weekdays: " + saturdayMidday + " vs " + weekdayMidday);
	}

	@Test
	void trainingIsDeterministic() {
		var data = SyntheticOccupancy.routine(MONDAY, 14, UTC, 7);
		Result a = OccupancyTrainer.train(data, UTC, CFG);
		Result b = OccupancyTrainer.train(data, UTC, CFG);
		assertArrayEquals(a.model().weights(), b.model().weights());
		assertEquals(a.metrics(), b.metrics());
	}

	@Test
	void storedModelRemembersHowMuchEvidenceEachHourHas() {
		Result r = OccupancyTrainer.train(SyntheticOccupancy.routine(MONDAY, 14, UTC, 3), UTC, CFG);
		OccupancyModel m = r.model();
		ZonedDateTime mondayEight = at("2027-05-03T08:00:00", UTC);
		assertEquals(8, m.support(mondayEight), "2 Mondays x 4 quarter-hour slots in that hour");
		assertNotNull(m.observedRate(mondayEight));
		assertTrue(m.observedRate(mondayEight) > 0.5);
		assertEquals(14 * 96, r.counts().labeledBuckets());
		assertEquals(sum(m.cellN()), r.counts().labeledBuckets());
	}

	private static int sum(int[] a) {
		int s = 0;
		for (int v : a) s += v;
		return s;
	}

	// ---- being honest when there is not enough data ----------------------------------------------

	@Test
	void noDataMeansNoModelAndNoNumbers() {
		Result r = OccupancyTrainer.train(List.of(), UTC, CFG);
		assertEquals(Status.INSUFFICIENT_DATA, r.status());
		assertNull(r.model());
		assertNull(r.metrics());
		assertEquals(0, r.counts().labeledBuckets());
		assertTrue(r.message().contains("no usable motion history"), r.message());
	}

	@Test
	void tooFewSlotsIsReportedWithTheExactShortfall() {
		Result r = OccupancyTrainer.train(SyntheticOccupancy.routine(MONDAY, 2, UTC, 1), UTC, CFG);   // 192 slots, 2 days
		assertEquals(Status.INSUFFICIENT_DATA, r.status());
		assertNull(r.model());
		assertNull(r.metrics(), "no accuracy is ever reported without a real evaluation");
		assertTrue(r.message().contains("192") && r.message().contains("200"), r.message());
	}

	@Test
	void tooFewDistinctDaysIsReported() {
		// 300 usable slots all on the same two calendar days is impossible, so use a short, dense burst instead:
		// 36 hours of 5-minute slots gives plenty of slots but only 2 calendar days.
		var dense = SyntheticOccupancy.generate(MONDAY, 2, UTC, 5, 5, 12, SyntheticOccupancy.ROUTINE);
		MlProperties.Occupancy cfg = MlProperties.with(java.util.Map.of("app.ml.occupancy.bucket-minutes", "5")).occupancy();
		Result r = OccupancyTrainer.train(dense, UTC, cfg);
		assertEquals(Status.INSUFFICIENT_DATA, r.status());
		assertTrue(r.message().contains("2 calendar day"), r.message());
	}

	@Test
	void aHistoryWithOnlyOneOutcomeCannotBeLearnedFrom() {
		var neverMotion = SyntheticOccupancy.generate(MONDAY, 10, UTC, 9, 15, 12, (d, h) -> 0.0);
		Result r = OccupancyTrainer.train(neverMotion, UTC, CFG);
		assertEquals(Status.INSUFFICIENT_DATA, r.status());
		assertTrue(r.message().contains("0 slots with motion"), r.message());
		assertNull(r.model());
		var alwaysMotion = SyntheticOccupancy.generate(MONDAY, 10, UTC, 9, 15, 12, (d, h) -> 1.0);
		assertEquals(Status.INSUFFICIENT_DATA, OccupancyTrainer.train(alwaysMotion, UTC, CFG).status());
	}

	@Test
	void slotsWithTooFewReadingsAreIgnoredNotTreatedAsEmpty() {
		// 5 readings per slot is below the minimum of 10: every slot is dropped, so nothing is learned.
		var sparse = SyntheticOccupancy.generate(MONDAY, 14, UTC, 11, 15, 5, SyntheticOccupancy.ROUTINE);
		Result r = OccupancyTrainer.train(sparse, UTC, CFG);
		assertEquals(Status.INSUFFICIENT_DATA, r.status());
		assertEquals(0, r.counts().labeledBuckets());
		assertEquals(14 * 96, r.counts().sparseBucketsDropped());
	}

	@Test
	void timeWithoutDataIsNeverCountedAsAnUnoccupiedPeriod() {
		var all = new ArrayList<>(SyntheticOccupancy.routine(MONDAY, 14, UTC, 21));
		int before = all.size();
		// the device was offline for the whole of day 5 (a Friday): remove every reading from that day
		Instant from = MONDAY.plusSeconds(4 * 86400L), to = from.plusSeconds(86400);
		all.removeIf(s -> !s.at().isBefore(from) && s.at().isBefore(to));
		assertTrue(all.size() < before);
		Result r = OccupancyTrainer.train(all, UTC, CFG);
		assertEquals(13 * 96, r.counts().labeledBuckets(), "the 96 missing slots are absent, not negatives");
		assertEquals(13, r.counts().distinctDays());
		int fridayWorkday = at("2027-05-07T12:00:00", UTC).getDayOfWeek().getValue();
		assertEquals(5, fridayWorkday);
		assertTrue(r.model().cellN()[4 * 24 + 12] <= 4, "only one Friday remains: at most 4 slots in that hour");
	}

	@Test
	void anAlmostConstantSignalIsFlaggedAsLowQualityRatherThanReportedAsGood() {
		// Motion unrelated to the time of day: nothing real to learn.
		var noise = SyntheticOccupancy.generate(MONDAY, 14, UTC, 77, 15, 12, (d, h) -> 0.5);
		Result r = OccupancyTrainer.train(noise, UTC, CFG);
		assertEquals(Status.LOW_QUALITY, r.status(), r.message());
		assertNotNull(r.model(), "a model exists, but it is flagged");
		assertTrue(r.message().contains("unreliable"), r.message());
	}

	// ---- time zones and time handling -------------------------------------------------------------

	@Test
	void timeOfDayIsLearnedInTheChosenTimeZone() {
		ZoneId kolkata = ZoneId.of("Asia/Kolkata");
		// The routine happens on India's clock; the readings are stamped in UTC instants.
		var data = SyntheticOccupancy.routine(Instant.parse("2027-02-28T18:30:00Z"), 14, kolkata, 5);   // = Monday 00:00 IST
		Result r = OccupancyTrainer.train(data, kolkata, CFG);
		assertEquals(Status.READY, r.status(), r.message());
		assertEquals("Asia/Kolkata", r.model().timeZone());
		double morningIst = r.model().probability(at("2027-05-03T08:00:00", kolkata));
		double middayIst = r.model().probability(at("2027-05-03T12:00:00", kolkata));
		assertTrue(morningIst > 0.6 && middayIst < 0.35, "morning " + morningIst + " midday " + middayIst);
		// the same instant evaluated through the model's own zone gives the same answer
		Instant instant = at("2027-05-03T08:00:00", kolkata).toInstant();
		assertEquals(morningIst, r.model().probability(instant.atZone(kolkata)), 1e-12);
	}

	@Test
	void daylightSavingDoesNotBreakBucketingOrFeatures() {
		ZoneId newYork = ZoneId.of("America/New_York");
		// spans the 2027-03-14 spring-forward change
		var data = SyntheticOccupancy.routine(Instant.parse("2027-03-08T05:00:00Z"), 14, newYork, 13);
		Result r = OccupancyTrainer.train(data, newYork, CFG);
		assertTrue(r.status() == Status.READY || r.status() == Status.LOW_QUALITY, r.message());
		assertNotNull(r.model());
		for (double w : r.model().weights()) {
			assertTrue(Double.isFinite(w));
		}
	}

	@Test
	void bucketsMustDivideAnHour() {
		assertThrows(IllegalArgumentException.class, () -> OccupancyDataset.build(List.of(), UTC, 7, 10));
		assertThrows(IllegalArgumentException.class, () -> OccupancyDataset.build(List.of(), UTC, 0, 10));
	}

	@Test
	void datasetLabelsASlotOccupiedIfAnyReadingHadMotion() {
		List<MotionSample> s = new ArrayList<>();
		Instant slot = Instant.parse("2027-03-01T10:00:00Z");
		for (int i = 0; i < 12; i++) {
			s.add(new MotionSample(slot.plusSeconds(i * 60L), i == 7));          // one motion reading
		}
		Instant empty = Instant.parse("2027-03-01T10:15:00Z");
		for (int i = 0; i < 12; i++) {
			s.add(new MotionSample(empty.plusSeconds(i * 60L), false));
		}
		var built = OccupancyDataset.build(s, UTC, 15, 10);
		assertEquals(2, built.buckets().size());
		assertTrue(built.buckets().get(0).occupied());
		assertFalse(built.buckets().get(1).occupied());
		assertEquals(1, built.positives());
		assertEquals(slot.getEpochSecond(), built.buckets().get(0).startEpoch());
	}

	@Test
	void featuresAreCyclicAroundMidnightAndDifferByDay() {
		double[] a = OccupancyFeatures.of(at("2027-03-01T23:59:00", UTC));
		double[] b = OccupancyFeatures.of(at("2027-03-02T00:01:00", UTC));
		// time-of-day terms are nearly identical across midnight (sin flips sign by about 0.009) even though the day changed
		for (int i = 0; i < 2 * OccupancyFeatures.HARMONICS; i++) {
			assertEquals(a[i], b[i], 0.06);
		}
		assertEquals(OccupancyFeatures.DIM, a.length);
		// Monday is the reference weekday (no indicator), Tuesday sets the first one
		int firstWeekday = 2 * OccupancyFeatures.HARMONICS;
		assertEquals(0.0, a[firstWeekday]);
		assertEquals(1.0, b[firstWeekday]);
		assertEquals(0, OccupancyFeatures.cell(at("2027-03-01T00:30:00", UTC)));
		assertEquals(6 * 24 + 23, OccupancyFeatures.cell(at("2027-03-07T23:30:00", UTC)));
	}
}
