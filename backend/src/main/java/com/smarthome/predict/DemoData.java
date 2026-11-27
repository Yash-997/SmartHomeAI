package com.smarthome.predict;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import com.smarthome.ml.SyntheticGenerator;

/**
 * The SYNTHETIC resident: an approximate routine with realistic day-to-day variation, fed into {@link SyntheticGenerator}
 * for sensors, noise and outages. None of this is real sensor data, and no prediction ever reads these routine settings.
 */
public final class DemoData {

	public static final String LABEL = "DATA SOURCE: SYNTHETIC DEMONSTRATION DATA";
	public static final int HISTORY_DAYS = 30;

	/** Typical weekday times, minutes after local midnight. */
	public record Routine(int wake, int leave, int back) {
		public static final Routine DEFAULT = new Routine(6 * 60, 9 * 60 + 30, 19 * 60);

		public Routine {
			if (wake < 180 || wake >= leave || leave + 90 >= back || back > 22 * 60) {
				throw new IllegalArgumentException(
						"Routine needs 03:00 <= wake < leave, return at least 90 minutes after leaving and no later than 22:00");
			}
		}
	}

	public enum Variation {
		NORMAL, WEEKEND_OUTING, WEEKEND_AT_HOME, EARLY_WAKE, LATE_WAKE, EARLY_DEPARTURE, DELAYED_DEPARTURE, EARLY_RETURN,
		DELAYED_RETURN, STAYED_HOME, MISSED_ROUTINE, UNUSUAL_ACTIVITY
	}

	public record PlannedDay(LocalDate date, Variation variation, SyntheticGenerator.RoutineDay times) {
	}

	public record Generated(SyntheticGenerator.Dataset data, List<PlannedDay> plan) {
	}

	private DemoData() {
	}

	/** {@value #HISTORY_DAYS} days ending the day before {@code today}. The same arguments always give the same data. */
	public static Generated generate(LocalDate today, Routine routine, long seed, ZoneId zone) {
		LocalDate first = today.minusDays(HISTORY_DAYS);
		List<PlannedDay> plan = new ArrayList<>();
		for (int i = 0; i < HISTORY_DAYS; i++) {
			plan.add(plan(first.plusDays(i), routine, new Random(seed * 1009 + i)));
		}
		Instant start = first.atStartOfDay(zone).toInstant();
		return new Generated(SyntheticGenerator.simulate(start, plan.stream().map(PlannedDay::times).toList(), zone, seed), plan);
	}

	static PlannedDay plan(LocalDate date, Routine routine, Random r) {
		boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
		int sleep = Math.min(1435, jitter(r, 23 * 60 + (weekend ? 20 : 0), 18));
		if (weekend) {
			int wake = jitter(r, routine.wake() + 90, 35);
			if (r.nextDouble() >= 0.55) {
				return day(date, Variation.WEEKEND_AT_HOME, wake, -1, -1, sleep);
			}
			int leave = jitter(r, 11 * 60, 55);
			return day(date, Variation.WEEKEND_OUTING, wake, leave, leave + jitter(r, 200, 55), sleep);
		}
		int wake = jitter(r, routine.wake(), 13);
		int leave = jitter(r, routine.leave(), 16);
		int back = jitter(r, routine.back(), 28);
		double roll = r.nextDouble();
		Variation v = Variation.NORMAL;
		if (roll < 0.05) {
			v = Variation.EARLY_WAKE;
			wake -= 55;
		} else if (roll < 0.11) {
			v = Variation.LATE_WAKE;
			wake += 95;
			leave = Math.max(leave, wake + 40);
		} else if (roll < 0.16) {
			v = Variation.EARLY_DEPARTURE;
			leave = Math.max(wake + 40, leave - 55);
		} else if (roll < 0.22) {
			v = Variation.DELAYED_DEPARTURE;
			leave += 65;
		} else if (roll < 0.28) {
			v = Variation.EARLY_RETURN;
			back -= 95;
		} else if (roll < 0.35) {
			v = Variation.DELAYED_RETURN;
			back += 75;
		} else if (roll < 0.40) {
			return day(date, Variation.STAYED_HOME, wake, -1, -1, sleep);
		} else if (roll < 0.44) {
			v = Variation.MISSED_ROUTINE; // only a short midday errand instead of the working day
			leave = jitter(r, 13 * 60, 50);
			back = leave + jitter(r, 110, 25);
		} else if (roll < 0.47) {
			return day(date, Variation.UNUSUAL_ACTIVITY, 4 * 60 + r.nextInt(40), leave, back, sleep);
		}
		return day(date, v, wake, leave, back, sleep);
	}

	private static PlannedDay day(LocalDate date, Variation v, int wake, int leave, int back, int sleep) {
		return new PlannedDay(date, v, new SyntheticGenerator.RoutineDay(wake, leave, back, Math.max(sleep, back + 30)));
	}

	/** Groups per-minute readings into local days. Minutes without a message (device offline) are marked unobserved. */
	public static List<DayTrace> traces(List<SyntheticGenerator.Reading> readings, ZoneId zone) {
		List<DayTrace> days = new ArrayList<>();
		DayTrace current = null;
		int filled = 0;
		for (SyntheticGenerator.Reading reading : readings) {
			ZonedDateTime local = reading.at().atZone(zone);
			if (current == null || !current.date().equals(local.toLocalDate())) {
				if (current != null) {
					fill(current, filled, DayTrace.MINUTES);
				}
				current = blank(local.toLocalDate());
				days.add(current);
				filled = 0;
			}
			int m = local.getHour() * 60 + local.getMinute();
			fill(current, filled, m);
			filled = m + 1;
			current.observed()[m] = true;
			current.motion()[m] = Boolean.TRUE.equals(reading.motion());
			current.present()[m] = reading.occupied();
			current.temperature()[m] = value(reading.temperature());
			current.humidity()[m] = value(reading.humidity());
			current.gas()[m] = value(reading.gasRaw());
		}
		if (current != null) {
			fill(current, filled, DayTrace.MINUTES);
		}
		return days;
	}

	static DayTrace blank(LocalDate date) {
		float[][] sensors = new float[3][DayTrace.MINUTES];
		for (float[] s : sensors) {
			Arrays.fill(s, Float.NaN);
		}
		return new DayTrace(date, new boolean[DayTrace.MINUTES], new boolean[DayTrace.MINUTES], new boolean[DayTrace.MINUTES],
				sensors[0], sensors[1], sensors[2]);
	}

	/** Unobserved minutes keep the previous label (they are excluded from training and scoring anyway). */
	private static void fill(DayTrace d, int from, int to) {
		for (int m = from; m < to; m++) {
			d.present()[m] = m == 0 || d.present()[m - 1];
		}
	}

	private static float value(Number n) {
		return n == null ? Float.NaN : n.floatValue();
	}

	/** One scripted "today" for the demonstration: exactly these times, with the same sensor behaviour as the history. */
	static DayTrace scripted(LocalDate date, int wake, int leave, int back, long seed, ZoneId zone) {
		int sleep = Math.min(1435, Math.max(23 * 60, back + 45));
		var data = SyntheticGenerator.simulate(date.atStartOfDay(zone).toInstant(),
				List.of(new SyntheticGenerator.RoutineDay(wake, leave, back, sleep)), zone, seed);
		DayTrace d = traces(data.readings(), zone).get(0);
		d.motion()[wake] = true;
		if (leave >= 0) {
			d.motion()[leave - 1] = true;
			d.motion()[Math.min(back, DayTrace.MINUTES - 1)] = true;
		}
		return d;
	}

	private static int jitter(Random r, int mean, int sd) {
		double z = Math.max(-2.2, Math.min(2.2, r.nextGaussian()));
		return (int) Math.round(mean + z * sd);
	}
}
