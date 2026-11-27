package com.smarthome.predict;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.smarthome.predict.PredictiveModel.Arrival;
import com.smarthome.predict.PredictiveModel.Presence;

/**
 * The only place a lighting decision is made. Pure: no device access and no clock of its own, so simulation and live mode
 * run exactly the same rules.
 *
 * <p>Priority: device offline, manual override, Night Mode, safe fallback (failed, expired or stale), actual motion,
 * pre-arrival timeout, prediction (with hysteresis).
 */
public final class LightingDecisionEngine {

	public enum Type { LIGHT_ON, LIGHT_OFF, NO_ACTION, SUPPRESSED_NIGHT_MODE, SUPPRESSED_LOW_CONFIDENCE }

	public enum Override { NONE, ON, OFF }

	public record Settings(boolean nightEnabled, LocalTime nightStart, LocalTime nightEnd, boolean predictiveEnabled,
			double onThreshold, double offThreshold, double arrivalThreshold, double confidenceThreshold, int preArrivalMinutes,
			int cooldownSeconds, int motionHoldMinutes, int preArrivalTimeoutMinutes, int predictionMaxAgeSeconds) {

		public static final Settings DEFAULT = new Settings(true, LocalTime.of(22, 0), LocalTime.of(6, 0), true,
				0.75, 0.35, 0.65, 0.6, 60, 60, 2, 45, 180);

		public Settings {
			if (nightStart == null || nightEnd == null) {
				throw new IllegalArgumentException("Night start and end are required");
			}
			if (!(offThreshold >= 0 && offThreshold < onThreshold && onThreshold <= 1)) {
				throw new IllegalArgumentException("The OFF threshold must be below the ON threshold (both 0 to 1)");
			}
			if (arrivalThreshold < 0 || arrivalThreshold > 1 || confidenceThreshold < 0 || confidenceThreshold > 1) {
				throw new IllegalArgumentException("Thresholds must be between 0 and 1");
			}
			if (java.util.Arrays.stream(PredictiveModel.HORIZONS).noneMatch(h -> h == preArrivalMinutes)) {
				throw new IllegalArgumentException("Pre-arrival horizon must be 15, 30, 60 or 120 minutes");
			}
			if (cooldownSeconds < 0 || motionHoldMinutes < 1) {
				throw new IllegalArgumentException("Cooldown cannot be negative and the motion hold must be at least 1 minute");
			}
			if (preArrivalTimeoutMinutes < 1 || predictionMaxAgeSeconds < 1) {
				throw new IllegalArgumentException("Pre-arrival timeout and prediction expiry must be positive");
			}
		}

		/** Start inclusive, end exclusive; a start after the end crosses midnight. Equal times mean an empty window. */
		public boolean isNight(LocalTime t) {
			if (!nightEnabled || nightStart.equals(nightEnd)) {
				return false;
			}
			return nightStart.isBefore(nightEnd) ? !t.isBefore(nightStart) && t.isBefore(nightEnd)
					: !t.isBefore(nightStart) || t.isBefore(nightEnd);
		}
	}

	/** What the output is doing now. {@code mode} is "auto", "on" or "off", as the ESP32 reports it. */
	public record Light(String mode, boolean on) {
	}

	/** {@code presence}/{@code arrival} are null when inference failed. {@code minutesSinceMotion} is null if none is known. */
	public record Inputs(LocalDateTime now, Presence presence, Arrival arrival, Integer minutesSinceMotion, boolean sensorFresh,
			boolean deviceOnline, Light light, Override override, LocalDateTime lastCommandAt, PreArrival preArrival) {
		public Inputs {
			preArrival = preArrival == null ? PreArrival.NONE : preArrival;
		}
	}

	/**
	 * Pre-arrival state kept by the caller. {@code since}: when the light was switched on for a predicted arrival that no motion
	 * has confirmed yet. {@code expired}: that wait timed out, so no new pre-arrival lighting until motion is seen.
	 */
	public record PreArrival(LocalDateTime since, boolean expired) {
		public static final PreArrival NONE = new PreArrival(null, false);

		/** The state after {@code decision}. */
		public PreArrival after(Inputs in, Decision decision, Settings s) {
			boolean motion = in.sensorFresh() && in.minutesSinceMotion() != null && in.minutesSinceMotion() < s.motionHoldMinutes();
			if (motion || in.override() != Override.NONE || s.isNight(in.now().toLocalTime())) {
				return NONE;
			}
			if (decision.cause() == Cause.PRE_ARRIVAL_TIMEOUT) {
				return new PreArrival(null, true);
			}
			if (decision.cause() == Cause.PRE_ARRIVAL && "on".equals(decision.command()) && since == null) {
				return new PreArrival(in.now(), false);
			}
			return this;
		}
	}

	/** Why a decision was taken, where the caller or the event history needs to tell cases apart. */
	public enum Cause { OTHER, PRE_ARRIVAL, PRE_ARRIVAL_TIMEOUT }

	/** {@code command} is the value to send ("on", "off" or "auto"), or null to send nothing. */
	public record Decision(Type type, String command, String reason, Cause cause) {
		public Decision(Type type, String command, String reason) {
			this(type, command, reason, Cause.OTHER);
		}
	}

	private LightingDecisionEngine() {
	}

	public static Decision decide(Inputs in, Settings s) {
		boolean night = s.isNight(in.now().toLocalTime());
		boolean motion = in.minutesSinceMotion() != null && in.minutesSinceMotion() < s.motionHoldMinutes() && in.sensorFresh();
		if (!in.deviceOnline()) {
			return new Decision(Type.NO_ACTION, null, "The device is offline, so no command is sent.");
		}
		if (in.override() != Override.NONE) {
			boolean on = in.override() == Override.ON;
			String reason = "Manual override " + in.override() + " is respected."
					+ (night ? " Night Mode suppression of automatic lighting remains active; the light is "
							+ (on ? "ON only because you switched it on." : "OFF.") : "");
			boolean satisfied = on ? in.light().on() : in.light().mode().equals("off");
			return satisfied ? new Decision(Type.NO_ACTION, null, reason)
					: new Decision(on ? Type.LIGHT_ON : Type.LIGHT_OFF, on ? "on" : "off", reason);
		}
		if (night) {
			String reason = motion ? "Motion detected, but lighting suppressed because Night Mode is active."
					: "Night Mode is active: automatic lighting is suppressed. Sensors keep recording.";
			// "off", not "auto": in auto the ESP32 itself lights this LED on motion.
			return new Decision(Type.SUPPRESSED_NIGHT_MODE, in.light().mode().equals("off") ? null : "off", reason);
		}
		boolean expired = in.presence() != null && in.presence().at() != null
				&& Duration.between(in.presence().at(), in.now()).getSeconds() > s.predictionMaxAgeSeconds();
		if (in.presence() == null || expired || !in.sensorFresh()) {
			String why = in.presence() == null ? "Prediction failed" : expired ? "The prediction has expired" : "Sensor data is stale or missing";
			return new Decision(Type.NO_ACTION, in.light().mode().equals("auto") ? null : "auto",
					why + ": safe fallback to the device's own automatic behaviour. No predictive lighting.");
		}
		if (motion) {
			return turnOn(in, s, "Actual PIR motion was detected.");
		}
		LocalDateTime since = in.preArrival().since();
		if (since != null && Duration.between(since, in.now()).toMinutes() >= s.preArrivalTimeoutMinutes()) {
			boolean released = in.light().mode().equals("auto");
			return new Decision(released ? Type.NO_ACTION : Type.LIGHT_OFF, released ? null : "auto",
					"Pre-arrival timeout: no motion within " + s.preArrivalTimeoutMinutes()
							+ " minutes of switching on for a predicted arrival. Returning to the normal state.",
					Cause.PRE_ARRIVAL_TIMEOUT);
		}
		if (!s.predictiveEnabled()) {
			return turnOff(in, s, "Predictive lighting is disabled and there is no recent motion.");
		}
		Presence p = in.presence();
		if (!in.preArrival().expired() && p.state() != PredictiveModel.State.LIKELY_OCCUPIED && in.arrival() != null) {
			var h = in.arrival().at(s.preArrivalMinutes());
			if (h.probability() >= s.arrivalThreshold()) {
				String numbers = percent(h.probability()) + " chance of return within " + h.minutes() + " min, confidence "
						+ percent(h.confidence());
				if (h.confidence() < s.confidenceThreshold()) {
					return new Decision(Type.SUPPRESSED_LOW_CONFIDENCE, null, (in.light().on()
							? "No new pre-arrival command (the light stays ON from an earlier decision): " : "Pre-arrival lighting suppressed: ") + numbers
							+ " is below the " + percent(s.confidenceThreshold()) + " confidence requirement.");
				}
				Decision on = turnOn(in, s, "Pre-arrival lighting: " + numbers + ".");
				return new Decision(on.type(), on.command(), on.reason(), Cause.PRE_ARRIVAL);
			}
		}
		if (p.probability() >= s.onThreshold()) {
			if (p.confidence() < s.confidenceThreshold()) {
				return new Decision(Type.SUPPRESSED_LOW_CONFIDENCE, null, "Occupancy confidence is below the requirement.");
			}
			return turnOn(in, s, "Occupancy probability " + percent(p.probability()) + " is at or above the ON threshold.");
		}
		if (p.probability() <= s.offThreshold()) {
			return turnOff(in, s, (in.preArrival().expired() ? "Pre-arrival lighting already timed out; waiting for real motion. " : "")
					+ "Occupancy probability " + percent(p.probability()) + " is at or below the OFF threshold and no confident return is expected.");
		}
		return new Decision(Type.NO_ACTION, null, "Occupancy " + percent(p.probability())
				+ " is between the OFF and ON thresholds (hysteresis): the light stays as it is.");
	}

	private static Decision turnOn(Inputs in, Settings s, String reason) {
		if (in.light().on() && !in.light().mode().equals("off")) {
			return new Decision(Type.NO_ACTION, null, reason + " The light is already ON, so no duplicate command is sent.");
		}
		return cooling(in, s) ? new Decision(Type.NO_ACTION, null, reason + " Waiting for the command cooldown.")
				: new Decision(Type.LIGHT_ON, "on", reason);
	}

	private static Decision turnOff(Inputs in, Settings s, String reason) {
		if (in.light().mode().equals("auto")) {
			return new Decision(Type.NO_ACTION, null, reason + " No override is active, so there is nothing to send.");
		}
		// Daytime "off" releases to auto so that real motion can still light the LED on the device itself.
		return cooling(in, s) ? new Decision(Type.NO_ACTION, null, reason + " Waiting for the command cooldown.")
				: new Decision(Type.LIGHT_OFF, "auto", reason);
	}

	private static boolean cooling(Inputs in, Settings s) {
		return in.lastCommandAt() != null && Duration.between(in.lastCommandAt(), in.now()).getSeconds() < s.cooldownSeconds();
	}

	private static String percent(double v) {
		return Math.round(v * 100) + "%";
	}
}
