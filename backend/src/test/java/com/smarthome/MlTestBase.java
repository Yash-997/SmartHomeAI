package com.smarthome;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import com.smarthome.ml.AnomalyScanner;
import com.smarthome.ml.AnomalyService;
import com.smarthome.ml.MlAnomalyRepository;
import com.smarthome.ml.MlDispatcher;
import com.smarthome.ml.MlModelRepository;
import com.smarthome.ml.MotionSample;
import com.smarthome.ml.OccupancyService;
import com.smarthome.ml.SyntheticOccupancy;
import com.smarthome.telemetry.TelemetryReading;

/**
 * Helpers for the ML integration tests. Everything seeded here is SIMULATED data written straight into the test
 * database; none of it comes from a real device.
 */
abstract class MlTestBase extends AutomationTestBase {

	private static final AtomicLong SEQ = new AtomicLong(7_000_000);

	@Autowired MlModelRepository mlModels;
	@Autowired MlAnomalyRepository mlAnomalies;
	@Autowired AnomalyScanner scanner;
	@Autowired AnomalyService anomalyService;
	@Autowired MlDispatcher mlDispatcher;
	@Autowired OccupancyService occupancy;
	@Autowired PlatformTransactionManager transactions;

	TelemetryReading reading(Paired p, Instant at, Double temp, Double hum, Boolean motion, Integer gas) {
		long seq = SEQ.incrementAndGet();
		return new TelemetryReading(p.uuid(), at, null, seq, temp, hum, motion, gas, Long.toHexString(seq) + "-" + Long.toHexString(at.toEpochMilli()));
	}

	void save(List<TelemetryReading> rows) {
		for (int i = 0; i < rows.size(); i += 2000) {
			telemetryRepo.saveAll(rows.subList(i, Math.min(rows.size(), i + 2000)));
		}
	}

	/**
	 * SIMULATED occupancy history: a weekday/weekend routine, {@code days} long, ending at {@code end}. Each 15-minute
	 * slot has 10 readings (the minimum a slot needs to be used).
	 */
	void seedMotionHistory(Paired p, Instant end, int days, long seed) {
		Instant start = end.minus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
		List<MotionSample> samples = SyntheticOccupancy.generate(start, days, ZoneId.of("UTC"), seed, 15, 10, SyntheticOccupancy.ROUTINE);
		List<TelemetryReading> rows = new ArrayList<>(samples.size());
		for (MotionSample s : samples) {
			rows.add(reading(p, s.at(), null, null, s.motion(), null));
		}
		save(rows);
	}

	/**
	 * SIMULATED "normal" sensor history: {@code count} readings ten minutes apart, the newest {@code endsMinutesAgo}
	 * minutes before {@code now}. Temperature around 22 C, humidity around 50 %, gas around 1500 raw.
	 */
	void seedNormalHistory(Paired p, Instant now, int count, int endsMinutesAgo) {
		Random r = new Random(99);
		List<TelemetryReading> rows = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			Instant at = now.minus(endsMinutesAgo, ChronoUnit.MINUTES).minusSeconds(i * 600L);
			rows.add(reading(p, at, 22 + r.nextGaussian() * 0.3, 50 + r.nextGaussian(), false, 1500 + (int) (r.nextGaussian() * 40)));
		}
		save(rows);
	}
}
