package com.smarthome.ml;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;

/**
 * SIMULATED motion history for tests. It exists only in test code and is never used by the application: the
 * application trains exclusively on readings a device actually sent.
 *
 * <p>The default routine: someone is home early morning and evening on weekdays, out during working hours, asleep at
 * night, and home most of the day at weekends. Whether a slot has motion is drawn from a seeded random generator
 * according to that probability, so the data is noisy but fully reproducible.
 */
public final class SyntheticOccupancy {

	/** Probability of motion in a slot, by local weekday/weekend and hour. */
	public static final BiFunction<DayOfWeek, Integer, Double> ROUTINE = (dow, hour) -> {
		boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
		if (hour >= 23 || hour < 7) {
			return 0.03;
		}
		if (weekend) {
			return 0.75;
		}
		if (hour < 9) {
			return 0.85;
		}
		if (hour < 17) {
			return 0.08;
		}
		return 0.85;
	};

	private SyntheticOccupancy() {
	}

	/** Every slot has {@code readingsPerSlot} readings, evenly spaced. */
	public static List<MotionSample> generate(Instant start, int days, ZoneId zone, long seed, int slotMinutes, int readingsPerSlot,
			BiFunction<DayOfWeek, Integer, Double> probability) {
		Random random = new Random(seed);
		List<MotionSample> out = new ArrayList<>();
		long width = slotMinutes * 60L;
		long slots = days * 24L * 60 / slotMinutes;
		for (long s = 0; s < slots; s++) {
			Instant slotStart = start.plusSeconds(s * width);
			ZonedDateTime mid = slotStart.plusSeconds(width / 2).atZone(zone);
			boolean occupied = random.nextDouble() < probability.apply(mid.getDayOfWeek(), mid.getHour());
			long step = width / readingsPerSlot;
			boolean motionGiven = false;
			for (int k = 0; k < readingsPerSlot; k++) {
				// In an occupied slot motion appears in roughly a third of the readings (at least once).
				boolean motion = occupied && (random.nextDouble() < 0.35 || (k == readingsPerSlot - 1 && !motionGiven));
				motionGiven |= motion;
				out.add(new MotionSample(slotStart.plusSeconds(k * step + 1), motion));
			}
		}
		return out;
	}

	public static List<MotionSample> routine(Instant start, int days, ZoneId zone, long seed) {
		return generate(start, days, zone, seed, 15, 12, ROUTINE);
	}
}
