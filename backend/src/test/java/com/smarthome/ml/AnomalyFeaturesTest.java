package com.smarthome.ml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** Minute resampling and feature engineering, without Spring. SIMULATED readings only. */
class AnomalyFeaturesTest {

	private static final ZoneId UTC = ZoneId.of("UTC");
	private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
	private static final MlProperties.Anomaly CFG = MlProperties.defaults().anomaly();

	/** Three SIMULATED days, one frame per minute: a daily temperature cycle, humidity following it, gas following humidity. */
	private static List<MinuteFrame> normalDays(int days, long seed) {
		Random r = new Random(seed);
		List<MinuteFrame> f = new ArrayList<>();
		for (int m = 0; m < days * 1440; m++) {
			double hour = (m % 1440) / 60.0;
			double t = Math.round((25 + 2 * Math.sin(2 * Math.PI * hour / 24) + r.nextGaussian() * 0.1) * 10) / 10.0;
			double h = Math.round((65 - 1.4 * (t - 25) + r.nextGaussian() * 0.5) * 10) / 10.0;
			double g = Math.round(300 + 1.5 * (h - 60) + r.nextGaussian() * 6);
			f.add(new MinuteFrame(T0.plusSeconds(60L * m), t, h, g, r.nextDouble() < 0.1, 2));
		}
		return f;
	}

	private static int idx(String name) {
		return AnomalyFeatures.NAMES.indexOf(name);
	}

	@Test
	void resamplingAveragesAMinuteAndKeepsMissingAsMissing() {
		List<TelemetryPoint> p = List.of(
				new TelemetryPoint(T0.plusSeconds(5), 24.0, null, 300, false),
				new TelemetryPoint(T0.plusSeconds(35), 26.0, null, null, true),
				new TelemetryPoint(T0.plusSeconds(185), null, 60.0, 310, false));
		List<MinuteFrame> f = MinuteFrame.resample(p);
		assertEquals(2, f.size(), "minute 0 and minute 3; minutes without messages have no frame");
		assertEquals(25.0, f.get(0).temperature());
		assertEquals(null, f.get(0).humidity(), "no humidity in that minute: null, never 0");
		assertEquals(300.0, f.get(0).gas());
		assertEquals(Boolean.TRUE, f.get(0).motion(), "motion if any message saw it");
		assertEquals(2, f.get(0).messages());
	}

	@Test
	void featuresOfAMinuteNeverDependOnLaterMinutes() {
		List<MinuteFrame> frames = normalDays(3, 1);
		AnomalyFeatures.Preprocessing p = AnomalyFeatures.fit(frames, UTC, 30, CFG);
		List<AnomalyFeatures.Row> full = AnomalyFeatures.compute(frames, p);
		List<AnomalyFeatures.Row> prefix = AnomalyFeatures.compute(frames.subList(0, 2000), p);
		for (int i = 0; i < prefix.size(); i++) {
			assertArrayEquals(full.get(i).x(), prefix.get(i).x(), 0.0, "row " + i + " changed when later minutes were added");
		}
		assertEquals(AnomalyFeatures.SIZE, full.get(0).x().length);
		assertEquals(30, AnomalyFeatures.SIZE);
	}

	@Test
	void aMissingValueIsAbsenceNotZero() {
		List<MinuteFrame> frames = new ArrayList<>(normalDays(3, 2));
		AnomalyFeatures.Preprocessing p = AnomalyFeatures.fit(frames, UTC, 30, CFG);
		MinuteFrame last = frames.get(frames.size() - 1);
		frames.add(new MinuteFrame(last.at().plusSeconds(60), null, last.humidity(), last.gas(), false, 2));
		double[] x = AnomalyFeatures.compute(frames, p).get(frames.size() - 1).x();
		assertEquals(0.0, x[idx("temperature.hourZ")], "no value: the value features stay neutral");
		assertTrue(x[idx("temperature.missingShare")] > 0, "and the absence is a feature of its own");
		assertEquals(0.0, x[idx("rel.humidityVsTemperature")], "a relationship needs both values");
	}

	@Test
	void identicalRepeatsAndSilenceAreVisible() {
		List<MinuteFrame> frames = new ArrayList<>(normalDays(3, 3));
		AnomalyFeatures.Preprocessing p = AnomalyFeatures.fit(frames, UTC, 30, CFG);
		Instant at = frames.get(frames.size() - 1).at();
		for (int i = 1; i <= 40; i++) {
			frames.add(new MinuteFrame(at.plusSeconds(60L * i), 25.0, 65.0, 300.0, false, 2));   // frozen
		}
		frames.add(new MinuteFrame(at.plusSeconds(60L * 100), 25.0, 64.0, 301.0, false, 2));    // after an hour of silence
		List<AnomalyFeatures.Row> rows = AnomalyFeatures.compute(frames, p);
		double[] frozen = rows.get(rows.size() - 2).x();
		assertEquals(Math.log1p(39), frozen[idx("humidity.stuckRun")], 1e-9, "40 identical humidity values");
		assertEquals(0.0, frozen[idx("humidity.rollStd")], 1e-9, "the whole 30-minute window is frozen");
		assertEquals(Math.log1p(59), rows.get(rows.size() - 1).x()[idx("device.gapMinutes")], 1e-9, "60 silent minutes");
	}

	@Test
	void theLearnedRelationshipsMatchTheData() {
		AnomalyFeatures.Preprocessing p = AnomalyFeatures.fit(normalDays(3, 4), UTC, 30, CFG);
		assertEquals(-1.4, p.humidityVsTemperature().slope(), 0.1, "humidity falls as it gets warmer");
		assertEquals(1.5, p.gasVsHumidity().slope(), 0.3, "the MQ-2 reading rises with humidity");
		for (double[] hours : p.routineScale()) {
			for (double s : hours) {
				assertTrue(s >= 1, "routine scaling only ever shrinks a jump that is usual at that hour");
			}
		}
		double[] range = p.expectedRange(Metric.TEMPERATURE, T0.plusSeconds(6 * 3600));
		assertTrue(range[0] < 27 && range[1] > 27, "06:00 is the warm peak of this simulated cycle");
	}

	@Test
	void dayOfWeekIsOnlyUsedOnceTrainingHasSeenEveryWeekday() {
		// Three days of history: the other four weekdays were never seen, so they must not look anomalous.
		AnomalyFeatures.Preprocessing short3 = AnomalyFeatures.fit(normalDays(3, 6), UTC, 30, CFG);
		assertTrue(!short3.dayOfWeek());
		List<MinuteFrame> later = normalDays(10, 6).subList(5 * 1440, 5 * 1440 + 60);
		for (AnomalyFeatures.Row r : AnomalyFeatures.compute(later, short3)) {
			assertEquals(0.0, r.x()[idx("time.dowSin")]);
			assertEquals(0.0, r.x()[idx("time.dowCos")]);
		}
		AnomalyFeatures.Preprocessing week = AnomalyFeatures.fit(normalDays(8, 6), UTC, 30, CFG);
		assertTrue(week.dayOfWeek(), "with every weekday in training, day of week is a feature");
		double[] x = AnomalyFeatures.compute(later, week).get(0).x();
		assertTrue(x[idx("time.dowSin")] != 0 || x[idx("time.dowCos")] != 0);
	}

	@Test
	void everySensorHasItsOwnForestGroup() {
		assertEquals(List.of("temperature", "humidity", "gasRaw", "device"), AnomalyFeatures.GROUPS.stream().map(AnomalyFeatures.Group::name).toList());
		int covered = AnomalyFeatures.GROUPS.stream().mapToInt(g -> g.features().length).sum();
		assertTrue(covered >= AnomalyFeatures.SIZE, "every feature is in at least one group");
	}
}
