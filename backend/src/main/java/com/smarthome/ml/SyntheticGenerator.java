package com.smarthome.ml;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates a SYNTHETIC smart-home dataset: one reading per minute for a simulated ESP32 with the same sensors as the real
 * one (DHT22, PIR, MQ-2 raw ADC). Pure and deterministic: the same seed and start always give the same data.
 *
 * <p>Every value follows one simulated household, so the sensors are correlated rather than independent noise:
 * <ul>
 *   <li><b>Routine</b>: each day gets a plan in local time (wake, leave, return, sleep, with day-to-day jitter). Weekdays
 *       have a working-hours absence (about one day in five is work-from-home); weekends start later and may include an
 *       outing. Each minute is ASLEEP, ACTIVE or AWAY. OCCUPIED means ASLEEP or ACTIVE.</li>
 *   <li><b>PIR motion</b>: a bursty two-state process while ACTIVE, very rare while ASLEEP, never while AWAY (except in the
 *       labelled UNUSUAL_MOTION scenario).</li>
 *   <li><b>Temperature</b> (DHT22, 0.1 C): an outdoor daily cycle and a slow monthly drift, followed indoors with a
 *       thermal lag, plus body heat when occupied and cooking.</li>
 *   <li><b>Humidity</b> (0.1 %): falls as it gets warmer; showers after waking and cooking raise it.</li>
 *   <li><b>MQ-2</b>: raw ADC counts (never ppm) around a drifting baseline, sensitive to humidity, raised by cooking (well
 *       below the 600 warning level), absent for the first minute after each reconnect (heater warm-up).</li>
 *   <li><b>Faults</b>: rare single DHT22 read failures (both values missing, as the real firmware reports them) and a few
 *       random Wi-Fi outages, during which no readings exist at all.</li>
 * </ul>
 * Five labelled anomaly scenarios are placed in the last days, which the training split keeps for testing.
 */
public final class SyntheticGenerator {

	public static final String VERSION = "synth-1";

	/** NORMAL, or the labelled anomaly scenario a minute belongs to. */
	public enum Scenario { NORMAL, FROZEN_SENSOR, GAS_SATURATION, MISSING_VALUES, DISCONNECTION, UNUSUAL_MOTION }

	enum State { ASLEEP, ACTIVE, AWAY }

	/** One generated reading. {@code occupied} is ground truth that real telemetry never has. */
	public record Reading(Instant at, Double temperature, Double humidity, Boolean motion, Integer gasRaw, boolean occupied, Scenario scenario) {
	}

	public record ConnectivityEvent(Instant at, String state, String reason) {
	}

	/** A labelled anomaly window, [from, to). */
	public record ScenarioWindow(Scenario scenario, Instant from, Instant to, String description) {
	}

	public record Dataset(Instant start, Instant end, ZoneId zone, long seed, int days, List<Reading> readings,
			List<ConnectivityEvent> connectivity, List<ScenarioWindow> scenarios, int offlineMinutes) {
	}

	/** One day's plan, in minutes after local midnight. */
	private record DayPlan(int wake, int leave, int back, int sleep, int cookMorning, int cookEvening, boolean home) {
	}

	private SyntheticGenerator() {
	}

	/** {@code start} should be a local midnight in {@code zone}. */
	public static Dataset generate(Instant start, int days, ZoneId zone, long seed) {
		if (days < 7) {
			throw new IllegalArgumentException("At least 7 days are needed for a weekly routine");
		}
		Random rnd = new Random(seed);
		Instant end = start.plus(Duration.ofDays(days));
		LocalDate firstDay = start.atZone(zone).toLocalDate();

		List<DayPlan> plans = new ArrayList<>();
		for (int d = 0; d < days; d++) {
			plans.add(plan(firstDay.plusDays(d).getDayOfWeek(), rnd));
		}
		List<ScenarioWindow> scenarios = scenarios(firstDay, days, zone);
		List<long[]> outages = outages(start, days, rnd);   // random Wi-Fi outages [fromEpochMin, toEpochMin), early days only

		List<Reading> out = new ArrayList<>(days * 1440);
		List<ConnectivityEvent> events = new ArrayList<>();
		events.add(new ConnectivityEvent(start, "ONLINE", "simulated device powered on"));

		double indoor = 27.0;
		double humidityShower = 0;
		double gasDrift = 0;
		boolean lastMotion = false;
		boolean online = true;
		int minutesSinceReconnect = 0;
		int offline = 0;
		Double frozenTemp = null, frozenHum = null;
		long startMin = start.getEpochSecond() / 60;

		for (long m = 0; m < days * 1440L; m++) {
			Instant at = Instant.ofEpochSecond((startMin + m) * 60);
			ZonedDateTime local = at.atZone(zone);
			int day = (int) java.time.temporal.ChronoUnit.DAYS.between(firstDay, local.toLocalDate());
			if (day < 0 || day >= days) {
				day = Math.max(0, Math.min(days - 1, day));
			}
			int minuteOfDay = local.getHour() * 60 + local.getMinute();
			DayPlan p = plans.get(day);
			State state = state(p, plans.get(Math.max(0, day - 1)), minuteOfDay);
			Scenario scenario = scenarioAt(scenarios, at);

			// ---- connectivity ----
			boolean shouldBeOnline = scenario != Scenario.DISCONNECTION && !inOutage(outages, startMin + m);
			if (shouldBeOnline != online) {
				online = shouldBeOnline;
				events.add(new ConnectivityEvent(at, online ? "ONLINE" : "OFFLINE",
						online ? "simulated device reconnected" : scenario == Scenario.DISCONNECTION ? "labelled disconnection scenario" : "simulated Wi-Fi outage"));
				minutesSinceReconnect = 0;
			}

			// ---- environment (evolves whether or not the device is online) ----
			double hour = minuteOfDay / 60.0;
			double outdoor = 25 + 5 * Math.sin(2 * Math.PI * (hour - 9) / 24) + 1.5 * day / days;
			boolean cooking = state == State.ACTIVE && (within(minuteOfDay, p.cookMorning(), 20) || within(minuteOfDay, p.cookEvening(), 45));
			double target = 0.6 * outdoor + 0.4 * 27 + (state != State.AWAY ? 0.5 : 0) + (cooking ? 0.8 : 0);
			indoor += 0.04 * (target - indoor) + rnd.nextGaussian() * 0.03;
			if (state == State.ACTIVE && minuteOfDay >= p.wake() && minuteOfDay < p.wake() + 2) {
				humidityShower = 9;                              // shower after waking
			}
			humidityShower *= 0.97;
			double humidity = clamp(70 - 1.4 * (indoor - 26) + humidityShower + (cooking ? 5 : 0) + rnd.nextGaussian() * 0.4, 30, 95);
			gasDrift = 0.999 * gasDrift + rnd.nextGaussian() * 0.6;
			double gas = 205 + 12 * Math.sin(2 * Math.PI * hour / 24) + gasDrift + 1.5 * (humidity - 60) + (cooking ? 140 : 0)
					+ rnd.nextGaussian() * 6;

			// ---- motion ----
			boolean motion;
			if (state == State.ACTIVE) {
				motion = rnd.nextDouble() < (lastMotion ? 0.55 : 0.22);
			} else if (state == State.ASLEEP) {
				motion = rnd.nextDouble() < 0.002;
			} else {
				motion = false;
			}
			if (scenario == Scenario.UNUSUAL_MOTION) {
				motion = rnd.nextDouble() < 0.7;                 // movement at a time the household is normally asleep
			}
			lastMotion = motion;

			if (!online) {
				offline++;
				continue;                                        // no message while offline
			}
			minutesSinceReconnect++;

			Double t = round1(indoor);
			Double h = round1(humidity);
			Integer g = (int) Math.round(clamp(gas, 0, 4095));
			if (rnd.nextDouble() < 0.003) {
				t = null;                                        // a failed DHT22 read: both values missing
				h = null;
			}
			if (minutesSinceReconnect <= 1) {
				g = null;                                        // MQ-2 warm-up after (re)connecting: not published
			}
			switch (scenario) {
				case FROZEN_SENSOR -> {
					if (frozenTemp == null) {
						frozenTemp = t == null ? round1(indoor) : t;
						frozenHum = h == null ? round1(humidity) : h;
					}
					t = frozenTemp;
					h = frozenHum;
				}
				case GAS_SATURATION -> g = 4095;
				case MISSING_VALUES -> {
					t = null;
					h = null;
					g = null;
				}
				default -> {
				}
			}
			out.add(new Reading(at, t, h, motion, g, state != State.AWAY, scenario));
		}
		return new Dataset(start, end, zone, seed, days, out, events, scenarios, offline);
	}

	// ---- the household ---------------------------------------------------------------------------

	private static DayPlan plan(DayOfWeek dow, Random r) {
		boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
		if (weekend) {
			int wake = jitter(r, 8 * 60, 40);
			boolean outing = r.nextDouble() < 0.6;
			int leave = outing ? jitter(r, 11 * 60, 60) : -1;
			int back = outing ? leave + jitter(r, 180, 60) : -1;
			return new DayPlan(wake, leave, back, jitter(r, 23 * 60 + 30, 25), jitter(r, 9 * 60, 30), jitter(r, 19 * 60 + 30, 30), !outing);
		}
		int wake = jitter(r, 6 * 60 + 30, 20);
		boolean wfh = r.nextDouble() < 0.2;
		int leave = wfh ? -1 : jitter(r, 9 * 60, 20);
		int back = wfh ? -1 : jitter(r, 17 * 60 + 45, 30);
		return new DayPlan(wake, leave, back, jitter(r, 23 * 60, 25), wake + 20, jitter(r, 19 * 60 + 30, 25), wfh);
	}

	/** Before waking the household is still asleep from the previous night. */
	private static State state(DayPlan p, DayPlan previous, int minute) {
		if (minute < p.wake() || minute >= p.sleep()) {
			return State.ASLEEP;
		}
		if (p.leave() >= 0 && minute >= p.leave() && minute < p.back()) {
			return State.AWAY;
		}
		return State.ACTIVE;
	}

	// ---- labelled anomaly scenarios (all in the last days, i.e. in the test period) ------------

	private static List<ScenarioWindow> scenarios(LocalDate firstDay, int days, ZoneId zone) {
		List<ScenarioWindow> s = new ArrayList<>();
		s.add(window(Scenario.FROZEN_SENSOR, firstDay.plusDays(days - 5), 10, 0, 120, zone,
				"DHT22 temperature and humidity frozen at one value for 2 hours"));
		s.add(window(Scenario.GAS_SATURATION, firstDay.plusDays(days - 4), 14, 0, 15, zone, "MQ-2 ADC pinned at 4095 for 15 minutes"));
		s.add(window(Scenario.MISSING_VALUES, firstDay.plusDays(days - 3), 16, 0, 40, zone,
				"device keeps reporting but sends no temperature, humidity or gas for 40 minutes"));
		s.add(window(Scenario.DISCONNECTION, firstDay.plusDays(days - 2), 2, 0, 50, zone, "device offline for 50 minutes"));
		// Unusual motion on the last weekday night: someone moving at 03:15, when the household is normally asleep.
		LocalDate d = firstDay.plusDays(days - 1);
		while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
			d = d.minusDays(1);
		}
		s.add(window(Scenario.UNUSUAL_MOTION, d, 3, 15, 15, zone, "repeated motion at 03:15 on a weekday night, normally quiet"));
		return s;
	}

	private static ScenarioWindow window(Scenario sc, LocalDate day, int hour, int minute, int minutes, ZoneId zone, String text) {
		Instant from = day.atTime(hour, minute).atZone(zone).toInstant();
		return new ScenarioWindow(sc, from, from.plus(Duration.ofMinutes(minutes)), text);
	}

	private static Scenario scenarioAt(List<ScenarioWindow> windows, Instant at) {
		for (ScenarioWindow w : windows) {
			if (!at.isBefore(w.from()) && at.isBefore(w.to())) {
				return w.scenario();
			}
		}
		return Scenario.NORMAL;
	}

	/** Three random outages of 15-120 minutes in the first two thirds (they are ordinary life, not test scenarios). */
	private static List<long[]> outages(Instant start, int days, Random r) {
		List<long[]> o = new ArrayList<>();
		long startMin = start.getEpochSecond() / 60;
		int span = Math.max(1, (int) (days * 1440L * 2 / 3));
		for (int i = 0; i < 3; i++) {
			long from = startMin + 1440 + r.nextInt(span - 1440);
			o.add(new long[] { from, from + 15 + r.nextInt(106) });
		}
		return o;
	}

	private static boolean inOutage(List<long[]> outages, long minute) {
		for (long[] o : outages) {
			if (minute >= o[0] && minute < o[1]) {
				return true;
			}
		}
		return false;
	}

	// ---- helpers ---------------------------------------------------------------------------------

	private static int jitter(Random r, int minutes, int sd) {
		return (int) Math.round(minutes + r.nextGaussian() * sd);
	}

	private static boolean within(int minute, int start, int length) {
		return minute >= start && minute < start + length;
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static Double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
