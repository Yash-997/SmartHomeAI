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
 * Nine kinds of labelled anomaly are injected (spike, sudden drop, gradual drift, frozen sensor, missing values,
 * disconnection, gas saturation, abnormal gas, unusual motion): one of each in the validation period and one of each in
 * the test period of the chronological split (see {@link SyntheticTrainer}), never in the training period, so models
 * learn from normal data only. The test instances hit different sensors or magnitudes than the validation ones, so a
 * threshold tuned on validation is tested on anomalies it has not seen. The injected effects are deterministic and use
 * no random numbers, so every normal minute is exactly what synth-1 produced for the same seed.
 */
public final class SyntheticGenerator {

	public static final String VERSION = "synth-2";

	/** NORMAL, or the labelled anomaly scenario a minute belongs to. */
	public enum Scenario {
		NORMAL, FROZEN_SENSOR, GAS_SATURATION, MISSING_VALUES, DISCONNECTION, UNUSUAL_MOTION,
		/** A short jump of one sensor (heat source next to the DHT22, a bad read burst). */
		SPIKE,
		/** A sudden step down that persists for a while (a window opened, a sensor fault). */
		SUDDEN_DROP,
		/** A slowly growing offset (sensor calibration drift): each minute looks almost normal. */
		GRADUAL_DRIFT,
		/** MQ-2 reading well above its normal level but below the gas alert threshold (smoke, a gas leak starting). */
		ABNORMAL_GAS
	}

	/** Which labelled set a scenario belongs to. */
	public enum LabelSet { VALIDATION, TEST }

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

	/** What an injected scenario does to the readings: an offset of {@code amplitude} on one sensor, as a step or a ramp. */
	private record Effect(Metric metric, double amplitude, boolean ramp) {
	}

	private record Placed(ScenarioWindow window, Effect effect) {
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
		List<Placed> placed = layout(start, days, zone);
		List<ScenarioWindow> scenarios = placed.stream().map(Placed::window).toList();
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
			Placed active = placedAt(placed, at);
			Scenario scenario = active == null ? Scenario.NORMAL : active.window().scenario();
			if (scenario != Scenario.FROZEN_SENSOR) {
				frozenTemp = null;                               // each frozen window freezes at its own first value
				frozenHum = null;
			}

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
				case SPIKE, SUDDEN_DROP, GRADUAL_DRIFT, ABNORMAL_GAS -> {
					Effect e = active.effect();
					double share = 1;
					if (e.ramp()) {                              // grows linearly from 0 to the full amplitude
						long total = Duration.between(active.window().from(), active.window().to()).toMinutes();
						share = (Duration.between(active.window().from(), at).toMinutes() + 1) / (double) total;
					}
					double offset = e.amplitude() * share;
					switch (e.metric()) {
						case TEMPERATURE -> t = t == null ? null : round1(t + offset);
						case HUMIDITY -> h = h == null ? null : round1(clamp(h + offset, 0, 100));
						case GAS -> g = g == null ? null : (int) Math.round(clamp(g + offset, 0, 4095));
					}
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

	// ---- labelled anomaly scenarios (validation and test periods only, never training) --------

	/** Start of the validation period: after the training part and the embargo (same split as {@link SyntheticTrainer}). */
	public static Instant validationStart(Instant start, int days) {
		return start.plusSeconds((long) (days * 86_400L * SyntheticTrainer.TRAIN_FRACTION)).plus(SyntheticTrainer.EMBARGO);
	}

	public static Instant validationEnd(Instant start, int days) {
		return start.plusSeconds((long) (days * 86_400L * (SyntheticTrainer.TRAIN_FRACTION + SyntheticTrainer.VALIDATION_FRACTION)));
	}

	public static Instant testStart(Instant start, int days) {
		return validationEnd(start, days).plus(SyntheticTrainer.EMBARGO);
	}

	/** Which set a window belongs to, by where it starts. */
	public static LabelSet setOf(Dataset ds, ScenarioWindow w) {
		return w.from().isBefore(validationEnd(ds.start(), ds.days())) ? LabelSet.VALIDATION : LabelSet.TEST;
	}

	/**
	 * Two days of scenarios at the start of each period: the first two local days that start inside it. The rest of
	 * each period stays normal, which is where false alarms are counted. Windows are hours apart, so the condition
	 * checks (stuck, missing) and their grace periods never run into the next scenario.
	 */
	private static List<Placed> layout(Instant start, int days, ZoneId zone) {
		List<Placed> s = new ArrayList<>();
		LocalDate v = firstDayStartingAt(validationStart(start, days), zone);
		LocalDate t = firstDayStartingAt(testStart(start, days), zone);
		if (!v.plusDays(2).atStartOfDay(zone).toInstant().isAfter(validationEnd(start, days))
				&& !t.plusDays(2).atStartOfDay(zone).toInstant().isAfter(start.plus(Duration.ofDays(days)))) {
			s.addAll(set(v, zone, "validation",
					new Effect(Metric.TEMPERATURE, 6.0, false), "temperature jumps by +6 °C for 8 minutes",
					new Effect(Metric.HUMIDITY, -20.0, false), "humidity drops by 20 points for 30 minutes",
					new Effect(Metric.TEMPERATURE, 4.0, true), "temperature reading drifts upward to +4 °C over 6 hours (calibration drift)",
					new Effect(Metric.GAS, 450, true), "MQ-2 rises to about +450 raw above normal over 25 minutes, still below the gas alert threshold"));
			s.addAll(set(t, zone, "test",
					new Effect(Metric.HUMIDITY, 18.0, false), "humidity jumps by +18 points for 8 minutes",
					new Effect(Metric.TEMPERATURE, -5.0, false), "temperature drops by 5 °C for 30 minutes",
					new Effect(Metric.HUMIDITY, 15.0, true), "humidity reading drifts upward to +15 points over 6 hours (calibration drift)",
					new Effect(Metric.GAS, 350, true), "MQ-2 rises to about +350 raw above normal over 25 minutes, still below the gas alert threshold"));
		}
		s.sort((a, b) -> a.window().from().compareTo(b.window().from()));
		return s;
	}

	private static LocalDate firstDayStartingAt(Instant at, ZoneId zone) {
		LocalDate d = at.atZone(zone).toLocalDate();
		return d.atStartOfDay(zone).toInstant().isBefore(at) ? d.plusDays(1) : d;
	}

	private static List<Placed> set(LocalDate a, ZoneId zone, String set, Effect spike, String spikeText, Effect drop, String dropText,
			Effect drift, String driftText, Effect gas, String gasText) {
		LocalDate b = a.plusDays(1);
		List<Placed> s = new ArrayList<>();
		// Unusual motion needs a weekday night (the routine is quiet at 03:15 then).
		boolean aWeekday = a.getDayOfWeek() != DayOfWeek.SATURDAY && a.getDayOfWeek() != DayOfWeek.SUNDAY;
		LocalDate motionDay = aWeekday ? a : b;
		boolean weekdayNight = motionDay.getDayOfWeek() != DayOfWeek.SATURDAY && motionDay.getDayOfWeek() != DayOfWeek.SUNDAY;
		s.add(placed(Scenario.UNUSUAL_MOTION, motionDay, 3, 15, 15, zone, null,
				"repeated motion at 03:15 on a " + (weekdayNight ? "weekday" : "weekend") + " night, normally quiet (" + set + ")"));
		s.add(placed(Scenario.SPIKE, a, 7, 0, 8, zone, spike, spikeText + " (" + set + ")"));
		s.add(placed(Scenario.FROZEN_SENSOR, a, 9, 0, 120, zone, null, "DHT22 temperature and humidity frozen at one value for 2 hours (" + set + ")"));
		s.add(placed(Scenario.GAS_SATURATION, a, 13, 0, 15, zone, null, "MQ-2 ADC pinned at 4095 for 15 minutes (" + set + ")"));
		s.add(placed(Scenario.GRADUAL_DRIFT, a, 15, 0, 360, zone, drift, driftText + " (" + set + ")"));
		s.add(placed(Scenario.DISCONNECTION, b, 0, 30, 50, zone, null, "device offline for 50 minutes (" + set + ")"));
		s.add(placed(Scenario.MISSING_VALUES, b, 7, 30, 40, zone, null,
				"device keeps reporting but sends no temperature, humidity or gas for 40 minutes (" + set + ")"));
		s.add(placed(Scenario.SUDDEN_DROP, b, 12, 0, 30, zone, drop, dropText + " (" + set + ")"));
		s.add(placed(Scenario.ABNORMAL_GAS, b, 18, 0, 25, zone, gas, gasText + " (" + set + ")"));
		return s;
	}

	private static Placed placed(Scenario sc, LocalDate day, int hour, int minute, int minutes, ZoneId zone, Effect effect, String text) {
		Instant from = day.atTime(hour, minute).atZone(zone).toInstant();
		return new Placed(new ScenarioWindow(sc, from, from.plus(Duration.ofMinutes(minutes)), text), effect);
	}

	private static Placed placedAt(List<Placed> placed, Instant at) {
		for (Placed p : placed) {
			if (!at.isBefore(p.window().from()) && at.isBefore(p.window().to())) {
				return p;
			}
		}
		return null;
	}

	/** The labelled scenario a minute belongs to (NORMAL outside every window). */
	public static Scenario scenarioAt(List<ScenarioWindow> windows, Instant at) {
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
