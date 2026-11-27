package com.smarthome.predict;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.smarthome.predict.LightingDecisionEngine.Decision;
import com.smarthome.predict.LightingDecisionEngine.Light;
import com.smarthome.predict.LightingDecisionEngine.Override;
import com.smarthome.predict.LightingDecisionEngine.Settings;

/**
 * One user's SIMULATION over the primary synthetic dataset and the model trained on it. The script of "today" (when the
 * simulated resident wakes, leaves, returns) is the ground truth that generates PIR events as the clock reaches them. The model
 * only ever sees events up to the current minute. The light here is virtual: nothing in this class can reach a device.
 */
public final class SimulationSession {

	private static final long SEED = 42;
	private static final int LOG_LIMIT = 40;

	public enum Scenario {
		NORMAL_WEEKDAY, PREDICTIVE_ARRIVAL, NIGHT_MODE, UNUSUAL_WAKE, DELAYED_RETURN, MANUAL_OVERRIDE, MISSED_ROUTINE
	}

	/** Today's behaviour. {@code leave < 0}: the resident stays home (missed routine). */
	public record Script(int wake, int leave, int back) {
	}

	public record LogEntry(int minute, String type, String command, String reason) {
	}

	public record Point(int minute, int motion, double predicted, boolean actual) {
	}

	/** Share of each hour on past weekdays and weekends: resident home (ground truth) and PIR motion. */
	public record Activity(double[] weekdayPresence, double[] weekendPresence, double[] weekdayMotion, double[] weekendMotion) {
	}

	public record View(String mode, String dataLabel, Scenario scenario, LocalDateTime now, int minute,
			Script today, int sustainDays, RoutineProfile learned, RoutineProfile learnedWeekend,
			RoutineProfile.Deviation deviation, PredictiveModel.Presence presence, PredictiveModel.Arrival arrival,
			boolean arrivalApplicable, boolean nightActive, Settings settings, Override override, Light light,
			Decision decision, String acknowledgement, List<Point> timeline, Activity activity, List<LogEntry> log,
			Instant lastTrainedAt, int modelVersion, PredictiveModelService.DatasetInfo dataset,
			LightingDecisionEngine.PreArrival preArrival) {
	}

	private final PredictiveModelService.Ready ready;
	private final ZoneId zone;
	private final LocalDate date;

	private Scenario scenario = Scenario.NORMAL_WEEKDAY;
	private Settings settings = Settings.DEFAULT;
	private Script script;
	private int sustainDays;
	private PredictiveModel model;
	private DayTrace today;

	private int minute;
	private Override override = Override.NONE;
	private Light light;
	private LocalDateTime lastCommandAt;
	private LightingDecisionEngine.PreArrival preArrival;
	private Decision decision;
	private PredictiveModel.Presence presence;
	private PredictiveModel.Arrival arrival;
	private final List<LogEntry> log = new ArrayList<>();

	public SimulationSession(PredictiveModelService.Ready ready) {
		this.ready = ready;
		this.zone = ZoneId.of(ready.dataset().timeZone());
		List<DayTrace> history = ready.model().history();
		// A Wednesday after the history, so the demonstration always shows a working day.
		this.date = history.get(history.size() - 1).date().plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY));
		select(Scenario.NORMAL_WEEKDAY);
	}

	public int modelVersion() {
		return ready.version();
	}

	public synchronized void select(Scenario next) {
		scenario = next;
		DemoData.Routine usual = DemoData.Routine.DEFAULT;
		int wake = next == Scenario.UNUSUAL_WAKE ? usual.wake() + 120 : usual.wake();
		int back = next == Scenario.DELAYED_RETURN ? usual.back() + 60 : usual.back();
		script = new Script(wake, next == Scenario.MISSED_ROUTINE ? -1 : usual.leave(), back);
		sustainDays = 0;
		override = Override.NONE;
		settings = Settings.DEFAULT;
		rebuild();
		jump(switch (next) {
			case NORMAL_WEEKDAY -> 8 * 60;
			case PREDICTIVE_ARRIVAL -> 18 * 60 + 30;
			case NIGHT_MODE, MANUAL_OVERRIDE -> 2 * 60;
			case UNUSUAL_WAKE -> usual.wake() + 90;
			case DELAYED_RETURN -> 18 * 60 + 45;
			case MISSED_ROUTINE -> 8 * 60 + 30;
		});
	}

	/**
	 * Changes today ({@code leave < 0} = stays home). With {@code sustain > 0} the previous weekdays are replaced, in this
	 * session only, by days like today: historical features and the learned routine adapt without retraining.
	 */
	public synchronized void changeToday(int wake, int leave, int back, int sustain) {
		if (leave >= 0) {
			new DemoData.Routine(wake, leave, back); // validity rules
		} else if (wake < 180 || wake > 22 * 60) {
			throw new IllegalArgumentException("Wake-up must be between 03:00 and 22:00");
		}
		if (sustain < 0 || sustain > 15) {
			throw new IllegalArgumentException("Sustained days must be 0 to 15");
		}
		script = new Script(wake, leave, back);
		sustainDays = sustain;
		rebuild();
		jump(minute);
	}

	public synchronized void setTime(int target) {
		if (target < 0 || target >= DayTrace.MINUTES) {
			throw new IllegalArgumentException("Time must be within the day");
		}
		jump(target);
	}

	/** Runs the clock forward one minute at a time, evaluating at every step, as the live loop would. */
	public synchronized void advance(int minutes) {
		if (minutes < 1 || minutes > 240) {
			throw new IllegalArgumentException("Advance by 1 to 240 minutes");
		}
		for (int i = 0; i < minutes && minute < DayTrace.MINUTES - 1; i++) {
			minute++;
			evaluate();
		}
	}

	/** A person moves in front of the simulated PIR now; that also makes them actually present until the scripted return. */
	public synchronized void triggerMotion() {
		today.motion()[minute] = true;
		for (int m = minute; m < DayTrace.MINUTES && !today.present()[m]; m++) {
			today.present()[m] = true;
		}
		evaluate();
	}

	public synchronized void setSettings(Settings next) {
		settings = next;
		evaluate();
	}

	public synchronized void setOverride(Override next) {
		boolean released = override != Override.NONE && next == Override.NONE;
		override = next;
		if (released) {
			light = new Light("auto", false);
			lastCommandAt = null;
		}
		preArrival = LightingDecisionEngine.PreArrival.NONE;
		evaluate();
	}

	public synchronized void reset() {
		select(scenario);
	}

	public synchronized View view() {
		List<Point> timeline = new ArrayList<>();
		for (int m = 0; m <= minute; m += 15) {
			int end = Math.min(minute, m + 14);
			timeline.add(new Point(m, today.motionCount(end, end - m + 1), model.presence(today, end).probability(),
					today.present()[end]));
		}
		List<DayTrace> history = model.history();
		RoutineProfile learned = RoutineProfile.learn(history, false);
		return new View("SIMULATION", DemoData.LABEL, scenario, date.atTime(LocalTime.ofSecondOfDay(minute * 60L)), minute,
				script, sustainDays, learned, RoutineProfile.learn(history, true),
				learned.wakeDeviation(history, today, minute), presence, arrival,
				presence != null && presence.state() != PredictiveModel.State.LIKELY_OCCUPIED, night(), settings, override, light,
				decision, "SIMULATED light: no command was sent to any hardware, so there is no device acknowledgement.", timeline,
				new Activity(model.hourlyPresenceRate(false), model.hourlyPresenceRate(true), model.hourlyMotionRate(false),
						model.hourlyMotionRate(true)),
				List.copyOf(log), ready.trainedAt(), ready.version(), ready.dataset(), preArrival);
	}

	private boolean night() {
		return settings.isNight(LocalTime.ofSecondOfDay(minute * 60L));
	}

	private void rebuild() {
		List<DayTrace> days = new ArrayList<>(ready.model().history());
		int replaced = 0;
		for (int i = days.size() - 1; i >= 0 && replaced < sustainDays; i--) {
			DayTrace d = days.get(i);
			if (!d.weekend()) {
				int shift = new Random(ready.dataset().seed() + i).nextInt(11) - 5;
				days.set(i, DemoData.scripted(d.date(), script.wake() + shift, script.leave(), script.back(), ready.dataset().seed() + i, zone));
				replaced++;
			}
		}
		model = sustainDays == 0 ? ready.model() : ready.model().withHistory(days);
		today = DemoData.scripted(date, script.wake(), script.leave(), script.back(), ready.dataset().seed(), zone);
	}

	private void jump(int target) {
		minute = target;
		light = new Light("auto", false);
		lastCommandAt = null;
		preArrival = LightingDecisionEngine.PreArrival.NONE;
		log.clear();
		decision = null;
		evaluate();
	}

	private void evaluate() {
		try {
			presence = model.presence(today, minute);
			arrival = model.arrival(today, minute);
		} catch (RuntimeException e) {
			presence = null;
			arrival = null;
		}
		int since = today.sinceMotion(minute, 720);
		LocalDateTime now = date.atTime(LocalTime.ofSecondOfDay(minute * 60L));
		var inputs = new LightingDecisionEngine.Inputs(now, presence, arrival, since >= 720 ? null : since, true, true, light,
				override, lastCommandAt, preArrival);
		Decision next = LightingDecisionEngine.decide(inputs, settings);
		preArrival = preArrival.after(inputs, next, settings);
		if (next.command() != null) {
			light = new Light(next.command(), next.command().equals("on"));
			lastCommandAt = now;
		}
		if (decision == null || decision.type() != next.type() || decision.cause() != next.cause() && next.cause() == LightingDecisionEngine.Cause.PRE_ARRIVAL_TIMEOUT || next.command() != null) {
			log.add(0, new LogEntry(minute, next.type().name(), next.command(), next.reason()));
			if (log.size() > LOG_LIMIT) {
				log.remove(log.size() - 1);
			}
		}
		decision = next;
	}
}
