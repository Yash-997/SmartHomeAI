package com.smarthome.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.smarthome.predict.LightingDecisionEngine.Decision;
import com.smarthome.predict.LightingDecisionEngine.Inputs;
import com.smarthome.predict.LightingDecisionEngine.Light;
import com.smarthome.predict.LightingDecisionEngine.Override;
import com.smarthome.predict.LightingDecisionEngine.Settings;
import com.smarthome.predict.LightingDecisionEngine.Type;
import com.smarthome.predict.PredictiveModel.Arrival;
import com.smarthome.predict.PredictiveModel.Horizon;
import com.smarthome.predict.PredictiveModel.Presence;
import com.smarthome.predict.PredictiveModel.State;

class LightingDecisionEngineTests {

	private static final LocalDateTime EVENING = LocalDateTime.of(2026, 12, 2, 18, 30);
	private static final LocalDateTime NIGHT = LocalDateTime.of(2026, 12, 2, 2, 0);
	private static final Light AUTO = new Light("auto", false);
	private static final Light FORCED_OFF = new Light("off", false);
	private static final Presence AWAY = new Presence(0.05, State.LIKELY_EMPTY, 0.95, null);
	private static final Presence HOME = new Presence(0.95, State.LIKELY_OCCUPIED, 0.95, null);

	private static Arrival arrival(double p, double confidence) {
		return new Arrival(List.of(new Horizon(15, p, confidence), new Horizon(30, p, confidence), new Horizon(60, p, confidence),
				new Horizon(120, p, confidence)), 1130, 1150, 1140, 20, 22);
	}

	private static Decision decide(LocalDateTime now, Presence presence, Arrival arrival, Integer since, Light light,
			Override override, LocalDateTime lastCommand) {
		return LightingDecisionEngine.decide(
				new Inputs(now, presence, arrival, since, true, true, light, override, lastCommand, null), Settings.DEFAULT);
	}

	@Test
	void nightWindowCrossesMidnight() {
		Settings s = Settings.DEFAULT;
		assertTrue(s.isNight(LocalTime.of(22, 0)));
		assertTrue(s.isNight(LocalTime.of(23, 59)));
		assertTrue(s.isNight(LocalTime.MIDNIGHT));
		assertTrue(s.isNight(LocalTime.of(5, 59)));
		assertFalse(s.isNight(LocalTime.of(6, 0)));
		assertFalse(s.isNight(LocalTime.of(21, 59)));
		Settings sameDay = new Settings(true, LocalTime.of(1, 0), LocalTime.of(5, 0), true, 0.75, 0.35, 0.65, 0.6, 60, 60, 2, 45, 180);
		assertTrue(sameDay.isNight(LocalTime.of(2, 0)));
		assertFalse(sameDay.isNight(LocalTime.of(23, 0)));
	}

	@Test
	void motionAtNightIsSuppressedAndTheLedForcedOff() {
		Decision d = decide(NIGHT, HOME, null, 0, AUTO, Override.NONE, null);
		assertEquals(Type.SUPPRESSED_NIGHT_MODE, d.type());
		assertEquals("off", d.command());
		assertEquals("Motion detected, but lighting suppressed because Night Mode is active.", d.reason());
		assertNull(decide(NIGHT, HOME, null, 0, FORCED_OFF, Override.NONE, null).command(), "no duplicate off");
	}

	@Test
	void disabledNightModeAllowsMotionLighting() {
		Settings off = new Settings(false, LocalTime.of(22, 0), LocalTime.of(6, 0), true, 0.75, 0.35, 0.65, 0.6, 60, 60, 2, 45, 180);
		Decision d = LightingDecisionEngine.decide(new Inputs(NIGHT, HOME, null, 0, true, true, AUTO, Override.NONE, null, null), off);
		assertEquals(Type.LIGHT_ON, d.type());
	}

	@Test
	void manualOverrideWinsDuringNightAndSaysSo() {
		Decision d = decide(NIGHT, HOME, null, 0, FORCED_OFF, Override.ON, NIGHT);
		assertEquals(Type.LIGHT_ON, d.type());
		assertTrue(d.reason().contains("Manual override") && d.reason().contains("suppression"));
		assertEquals(Type.NO_ACTION, decide(NIGHT, HOME, null, 0, new Light("on", true), Override.ON, null).type());
	}

	@Test
	void preArrivalNeedsConfidence() {
		assertEquals(Type.LIGHT_ON, decide(EVENING, AWAY, arrival(0.85, 0.85), null, AUTO, Override.NONE, null).type());
		Decision low = decide(EVENING, AWAY, arrival(0.85, 0.2), null, AUTO, Override.NONE, null);
		assertEquals(Type.SUPPRESSED_LOW_CONFIDENCE, low.type());
		assertNull(low.command());
		assertEquals(Type.NO_ACTION, decide(EVENING, AWAY, arrival(0.2, 0.8), null, AUTO, Override.NONE, null).type());
	}

	@Test
	void cooldownAndDuplicatesSendNothing() {
		Decision cooling = decide(EVENING, AWAY, arrival(0.9, 0.9), null, AUTO, Override.NONE, EVENING.minusSeconds(30));
		assertNull(cooling.command());
		assertTrue(cooling.reason().contains("cooldown"));
		Decision duplicate = decide(EVENING, AWAY, arrival(0.9, 0.9), null, new Light("on", true), Override.NONE, null);
		assertNull(duplicate.command());
		assertTrue(duplicate.reason().contains("duplicate"));
	}

	@Test
	void hysteresisHoldsBetweenThresholdsAndReleasesBelow() {
		Presence middle = new Presence(0.5, State.UNCERTAIN, 0.5, null);
		Light on = new Light("on", true);
		assertNull(decide(EVENING, middle, arrival(0.1, 0.9), null, on, Override.NONE, null).command());
		Decision off = decide(EVENING, AWAY, arrival(0.1, 0.9), null, on, Override.NONE, null);
		assertEquals(Type.LIGHT_OFF, off.type());
		assertEquals("auto", off.command());
	}

	@Test
	void failedInferenceStaleSensorAndOfflineAreSafe() {
		Decision failed = decide(EVENING, null, null, 0, new Light("on", true), Override.NONE, null);
		assertEquals(Type.NO_ACTION, failed.type());
		assertEquals("auto", failed.command());
		Decision stale = LightingDecisionEngine.decide(
				new Inputs(EVENING, AWAY, arrival(0.9, 0.9), null, false, true, AUTO, Override.NONE, null, null), Settings.DEFAULT);
		assertEquals(Type.NO_ACTION, stale.type());
		assertNull(stale.command());
		Decision offline = LightingDecisionEngine.decide(
				new Inputs(EVENING, AWAY, arrival(0.9, 0.9), 0, true, false, AUTO, Override.ON, null, null), Settings.DEFAULT);
		assertEquals(Type.NO_ACTION, offline.type());
		assertNull(offline.command());
	}

	@Test
	void invalidThresholdsAreRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> new Settings(true, LocalTime.of(22, 0), LocalTime.of(6, 0), true, 0.3, 0.5, 0.65, 0.6, 60, 60, 2, 45, 180));
	}

	/** Runs one step the way both modes do: decide, then update the pre-arrival state. */
	private static Object[] step(LocalDateTime now, Integer since, Light light, Override override, LightingDecisionEngine.PreArrival state) {
		Inputs in = new Inputs(now, AWAY, arrival(0.9, 0.9), since, true, true, light, override, null, state);
		Decision d = LightingDecisionEngine.decide(in, Settings.DEFAULT);
		return new Object[] { d, state.after(in, d, Settings.DEFAULT) };
	}

	@Test
	void preArrivalLightTimesOutAndDoesNotComeBackUntilMotionIsSeen() {
		Object[] on = step(EVENING, null, AUTO, Override.NONE, LightingDecisionEngine.PreArrival.NONE);
		assertEquals(Type.LIGHT_ON, ((Decision) on[0]).type());
		var state = (LightingDecisionEngine.PreArrival) on[1];
		assertEquals(EVENING, state.since());
		Light lit = new Light("on", true);

		Object[] waiting = step(EVENING.plusMinutes(44), null, lit, Override.NONE, state);
		assertNull(((Decision) waiting[0]).command());
		assertEquals(EVENING, ((LightingDecisionEngine.PreArrival) waiting[1]).since(), "renewals do not restart the timer");

		Object[] timeout = step(EVENING.plusMinutes(45), null, lit, Override.NONE, state);
		assertEquals(Type.LIGHT_OFF, ((Decision) timeout[0]).type());
		assertEquals("auto", ((Decision) timeout[0]).command());
		assertEquals(LightingDecisionEngine.Cause.PRE_ARRIVAL_TIMEOUT, ((Decision) timeout[0]).cause());
		var expired = (LightingDecisionEngine.PreArrival) timeout[1];
		assertTrue(expired.expired());
		assertNull(((Decision) step(EVENING.plusMinutes(50), null, AUTO, Override.NONE, expired)[0]).command(),
				"the outdated prediction cannot switch it on again");
		assertEquals(LightingDecisionEngine.PreArrival.NONE, step(EVENING.plusMinutes(60), 0, AUTO, Override.NONE, expired)[1],
				"real motion resets the state");
	}

	@Test
	void theTimeoutRespectsManualOverrideAndNightMode() {
		var state = new LightingDecisionEngine.PreArrival(EVENING, false);
		Object[] manual = step(EVENING.plusMinutes(90), null, new Light("on", true), Override.ON, state);
		assertNull(((Decision) manual[0]).command());
		assertEquals(LightingDecisionEngine.PreArrival.NONE, manual[1]);
		Object[] night = step(EVENING.withHour(22), null, new Light("on", true), Override.NONE, state);
		assertEquals(Type.SUPPRESSED_NIGHT_MODE, ((Decision) night[0]).type());
		assertEquals("off", ((Decision) night[0]).command());
	}

	@Test
	void theNightModeTableHolds() {
		LocalDateTime day = EVENING.toLocalDate().atStartOfDay();
		assertEquals("on", decide(day.withHour(19), AWAY, arrival(0.9, 0.9), null, AUTO, Override.NONE, null).command());
		for (int hour : new int[] { 23, 2 }) {
			assertEquals("off", decide(day.withHour(hour), HOME, null, 0, AUTO, Override.NONE, null).command(), "hour " + hour);
		}
		assertEquals(Type.LIGHT_ON, decide(day.withHour(7), HOME, null, 0, AUTO, Override.NONE, null).type());
		assertEquals(Type.LIGHT_ON, decide(day.withHour(2), HOME, null, 0, FORCED_OFF, Override.ON, null).type());
		assertEquals(Type.LIGHT_ON, decide(day.withHour(6), HOME, null, 0, FORCED_OFF, Override.NONE, null).type(), "night over");
	}

	@Test
	void anExpiredPredictionIsNotActedOn() {
		Presence old = new Presence(0.05, State.LIKELY_EMPTY, 0.95, EVENING.minusMinutes(10));
		Decision d = decide(EVENING, old, arrival(0.9, 0.9), null, AUTO, Override.NONE, null);
		assertEquals(Type.NO_ACTION, d.type());
		assertNull(d.command());
		assertTrue(d.reason().contains("expired"));
	}
}
