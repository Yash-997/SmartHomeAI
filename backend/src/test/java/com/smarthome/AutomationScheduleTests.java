package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.smarthome.automation.AutomationRule;
import com.smarthome.automation.ConditionLogic;
import com.smarthome.automation.ExecutionOutcome;
import com.smarthome.automation.TriggerType;
import com.smarthome.telemetry.TelemetryReading;

/**
 * Scheduled rules. They run the engine at fixed, chosen instants (never the wall clock), so the time-zone and
 * daylight-saving cases are exact and repeatable.
 */
class AutomationScheduleTests extends AutomationTestBase {

	private static String schedule(String time, String days, String zone) {
		return "{\"type\":\"SCHEDULE\",\"time\":\"" + time + "\"" + (days == null ? "" : ",\"days\":" + days) + ",\"timeZone\":\"" + zone + "\"}";
	}

	private UUID scheduled(Paired p, String name, String trigger, List<String> conditions, String action, String value) throws Exception {
		return createRule(p, ruleJson(name, p.uuid(), null, trigger, "AND", conditions, action, value, 10, 10));
	}

	private long executed(UUID id) {
		return history(id).stream().filter(e -> e.getOutcome() == ExecutionOutcome.EXECUTED).count();
	}

	// ---- when it fires ---------------------------------------------------------------------------

	@Test
	void aScheduleFiresAtItsLocalTimeInItsOwnTimeZoneExactlyOncePerDay() throws Exception {
		Paired p = onlineDevice(newUserToken());
		// 22:30 in India (UTC+05:30, no DST) on Tuesday 2027-06-15 is 17:00 UTC
		UUID rid = scheduled(p, "Night light", schedule("22:30", null, "Asia/Kolkata"), List.of(), "set_pir_led", "off");
		Instant due = Instant.parse("2027-06-15T17:00:00Z");
		markOnlineAt(p, due);

		engine.runDueSchedules(due.minusSeconds(1));
		assertEquals(0, published(p), "not yet due");
		assertTrue(history(rid).isEmpty());

		engine.runDueSchedules(due);
		assertEquals(1, published(p));
		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(rid));
		assertTrue(latest(rid).getTriggerValues().contains("schedule"));

		finishAllCommands(p);
		engine.runDueSchedules(due.plusSeconds(30));
		engine.runDueSchedules(due.plusSeconds(120));
		assertEquals(1, published(p), "never twice on the same day");
		assertEquals(LocalDate.of(2027, 6, 15), rule(rid).getLastScheduledOn());

		// the next day it runs again
		Instant nextDue = due.plus(1, ChronoUnit.DAYS);
		markOnlineAt(p, nextDue);
		engine.runDueSchedules(nextDue.plusSeconds(5));
		assertEquals(2, published(p));
	}

	@Test
	void theSameInstantMeansDifferentLocalTimesInDifferentZones() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID india = scheduled(p, "India 22:30", schedule("22:30", null, "Asia/Kolkata"), List.of(), "set_pir_led", "on");
		UUID utc = scheduled(p, "UTC 22:30", schedule("22:30", null, "UTC"), List.of(), "set_gas_led", "on");
		Instant t = Instant.parse("2027-06-15T22:30:00Z");   // 22:30 in UTC, but already 04:00 the next day in India
		markOnlineAt(p, t);

		engine.runDueSchedules(t);

		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(utc));
		assertTrue(history(india).isEmpty(), "in India it is 04:00 on the 16th; today's 22:30 passed long ago (and is outside the catch-up window), so nothing is recorded as late");
	}

	@Test
	void onlyTheSelectedDaysRun() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID mondays = scheduled(p, "Mondays", schedule("10:00", "[\"MON\"]", "UTC"), List.of(), "set_pir_led", "on");
		UUID tuesdays = scheduled(p, "Tuesdays", schedule("10:00", "[\"TUE\",\"WED\"]", "UTC"), List.of(), "set_gas_led", "on");
		Instant tuesday = Instant.parse("2027-06-15T10:00:00Z");   // a Tuesday
		assertEquals(DayOfWeek.TUESDAY, tuesday.atZone(ZoneId.of("UTC")).getDayOfWeek());
		markOnlineAt(p, tuesday);

		engine.runDueSchedules(tuesday);

		assertEquals(1, published(p));
		assertTrue(history(mondays).isEmpty());
		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(tuesdays));
	}

	@Test
	void aMissedWindowIsRecordedNotRunLateAndNotRepeated() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID rid = scheduled(p, "Morning", schedule("07:00", null, "UTC"), List.of(), "set_pir_led", "on");
		Instant due = Instant.parse("2027-06-15T07:00:00Z");
		Instant late = due.plusSeconds(20 * 60);   // the backend was down; window is 5 minutes
		markOnlineAt(p, late);

		engine.runDueSchedules(late);
		engine.runDueSchedules(late.plusSeconds(15));

		assertEquals(0, published(p));
		assertEquals(1, history(rid).size());
		assertEquals(ExecutionOutcome.SKIPPED_MISSED, latestOutcome(rid));
		assertTrue(latest(rid).getReason().contains("07:00"));

		// tomorrow is a fresh day
		Instant tomorrow = due.plus(1, ChronoUnit.DAYS);
		markOnlineAt(p, tomorrow);
		engine.runDueSchedules(tomorrow);
		assertEquals(1, published(p));
	}

	// ---- daylight saving -------------------------------------------------------------------------

	@Test
	void aLocalTimeThatDoesNotExistRunsOnceAtTheFirstValidMoment() throws Exception {
		Paired p = onlineDevice(newUserToken());
		// New York, 2027-03-14: clocks jump from 02:00 EST straight to 03:00 EDT, so 02:30 never happens.
		UUID rid = scheduled(p, "Gap", schedule("02:30", null, "America/New_York"), List.of(), "set_pir_led", "on");
		Instant gapEnds = Instant.parse("2027-03-14T07:00:00Z");   // 03:00 EDT
		Instant shifted = gapEnds.plus(30, ChronoUnit.MINUTES);       // 03:30 EDT: 02:30 pushed forward by the gap
		markOnlineAt(p, shifted);

		engine.runDueSchedules(gapEnds.minusSeconds(60));
		engine.runDueSchedules(shifted.minusSeconds(1));
		assertEquals(0, published(p));

		engine.runDueSchedules(shifted);
		assertEquals(1, published(p));
		finishAllCommands(p);
		engine.runDueSchedules(shifted.plusSeconds(60));
		assertEquals(1, published(p));
		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(rid));
	}

	@Test
	void aLocalTimeThatHappensTwiceRunsOnlyOnce() throws Exception {
		Paired p = onlineDevice(newUserToken());
		// New York, 2027-11-07: at 02:00 EDT clocks go back to 01:00 EST, so 01:30 happens twice.
		UUID rid = scheduled(p, "Overlap", schedule("01:30", null, "America/New_York"), List.of(), "set_pir_led", "on");
		Instant first = Instant.parse("2027-11-07T05:30:00Z");    // 01:30 EDT
		Instant second = Instant.parse("2027-11-07T06:30:00Z");   // 01:30 EST, an hour later
		markOnlineAt(p, second);

		engine.runDueSchedules(first);
		assertEquals(1, published(p));
		finishAllCommands(p);
		engine.runDueSchedules(second);
		engine.runDueSchedules(second.plusSeconds(30));
		assertEquals(1, published(p), "the repeated 01:30 does not fire again");
		assertEquals(1, executed(rid));
	}

	// ---- conditions on scheduled rules -----------------------------------------------------------

	private void storeReading(Paired p, Instant at, Double temp, Boolean motion) {
		telemetryRepo.saveAndFlush(new TelemetryReading(p.uuid(), at, null, SeqHolder.next(), temp, null, motion, null, UUID.randomUUID().toString().replace("-", "")));
	}

	/** Unique sequence numbers for hand-made readings. */
	private static final class SeqHolder {
		private static long n = 90000;

		static synchronized long next() {
			return n++;
		}
	}

	@Test
	void scheduledConditionsUseTheLatestFreshReading() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID rid = scheduled(p, "Only if warm", schedule("12:00", null, "UTC"), List.of(cond("temperature", ">", "25")), "set_pir_led", "on");
		Instant due = Instant.parse("2027-06-15T12:00:00Z");
		markOnlineAt(p, due);
		storeReading(p, due.minusSeconds(20), 18.0, null);

		engine.runDueSchedules(due);
		assertEquals(0, published(p));
		assertEquals(ExecutionOutcome.SKIPPED_CONDITIONS_FALSE, latestOutcome(rid));
		assertTrue(latest(rid).getEvaluation().contains("\"actual\":18.0"));

		// next day the room is warm
		Instant next = due.plus(1, ChronoUnit.DAYS);
		markOnlineAt(p, next);
		storeReading(p, next.minusSeconds(10), 29.0, null);
		engine.runDueSchedules(next);
		assertEquals(1, published(p));
		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(rid));
	}

	@Test
	void aScheduledRuleWithConditionsWontActOnOldOrMissingData() throws Exception {
		// no readings at all
		Paired none = pair(newUserToken());   // online below, but it has never sent a reading
		UUID noData = scheduled(none, "No data", schedule("12:00", null, "UTC"), List.of(cond("temperature", ">", "25")), "set_pir_led", "on");
		Instant due = Instant.parse("2027-06-15T12:00:00Z");
		markOnlineAt(none, due);
		engine.runDueSchedules(due);
		assertEquals(ExecutionOutcome.SKIPPED_NO_DATA, latestOutcome(noData));
		assertEquals(0, published(none));

		// a reading that is far too old (would be TRUE if it were trusted)
		Paired old = onlineDevice(newUserToken());
		UUID stale = scheduled(old, "Stale", schedule("12:00", null, "UTC"), List.of(cond("temperature", ">", "25")), "set_pir_led", "on");
		markOnlineAt(old, due);
		storeReading(old, due.minusSeconds(600), 40.0, null);
		engine.runDueSchedules(due);
		assertEquals(ExecutionOutcome.SKIPPED_STALE_DATA, latestOutcome(stale));
		assertTrue(latest(stale).getReason().contains("old"));
		assertEquals(0, published(old));

		// fresh reading but the sensor value is missing
		Paired gap = onlineDevice(newUserToken());
		UUID missing = scheduled(gap, "Missing", schedule("12:00", null, "UTC"), List.of(cond("temperature", "<", "5")), "set_pir_led", "on");
		markOnlineAt(gap, due);
		storeReading(gap, due.minusSeconds(5), null, true);
		engine.runDueSchedules(due);
		assertEquals(ExecutionOutcome.SKIPPED_NO_DATA, latestOutcome(missing));
		assertEquals(0, published(gap));
	}

	@Test
	void aScheduledRuleOnAnOfflineDeviceIsSkippedWithAReason() throws Exception {
		Paired p = pair(newUserToken());   // never connected
		UUID rid = scheduled(p, "Offline", schedule("12:00", null, "UTC"), List.of(), "set_pir_led", "on");
		Instant due = Instant.parse("2027-06-15T12:00:00Z");
		engine.runDueSchedules(due);
		assertEquals(0, published(p));
		assertEquals(ExecutionOutcome.SKIPPED_OFFLINE, latestOutcome(rid));
		assertNull(rule(rid).getLastExecutedAt());
	}

	// ---- isolation and priorities ----------------------------------------------------------------

	@Test
	void aScheduleWithACorruptTimeZoneCannotStopTheOthers() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID good = scheduled(p, "Good", schedule("12:00", null, "UTC"), List.of(), "set_gas_led", "on");

		AutomationRule bad = new AutomationRule(UUID.randomUUID(), p.userId(), Instant.now().minusSeconds(10));
		bad.apply("Corrupt", true, p.uuid(), p.uuid(), TriggerType.SCHEDULE, LocalTime.NOON, EnumSet.allOf(DayOfWeek.class), "Nowhere/Land",
				ConditionLogic.AND, List.of(), "set_pir_led", "on", 99, 10, Instant.now());
		UUID badId = rules.saveAndFlush(bad).getId();

		Instant due = Instant.parse("2027-06-15T12:00:00Z");
		markOnlineAt(p, due);
		engine.runDueSchedules(due);

		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(good));
		assertEquals(ExecutionOutcome.FAILED, latestOutcome(badId));
		assertEquals(1, published(p));
		rules.deleteById(badId);   // don't leave a permanently failing rule behind for the other tests
	}

	@Test
	void schedulesFollowTheSamePriorityRulesAsTelemetryRules() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID low = createRule(p, ruleJson("Lights off", p.uuid(), null, schedule("22:00", null, "UTC"), "AND", List.of(), "set_pir_led", "off", 10, 10));
		UUID high = createRule(p, ruleJson("Lights on", p.uuid(), null, schedule("22:00", null, "UTC"), "AND", List.of(), "set_pir_led", "on", 70, 10));
		Instant due = Instant.parse("2027-06-15T22:00:00Z");
		markOnlineAt(p, due);

		engine.runDueSchedules(due);

		assertEquals(1, published(p));
		assertTrue(publisher.publishedTo(p.deviceId()).get(0).json().contains("\"value\":\"on\""));
		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(high));
		assertEquals(ExecutionOutcome.SKIPPED_CONFLICT, latestOutcome(low));
	}

	@Test
	void disabledSchedulesDoNotRun() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID rid = createRule(p, ruleJson("Off", p.uuid(), null, schedule("12:00", null, "UTC"), "AND", List.of(), "set_pir_led", "on", 10, 10)
				.replace("\"trigger\"", "\"enabled\":false,\"trigger\""));
		Instant due = Instant.parse("2027-06-15T12:00:00Z");
		markOnlineAt(p, due);
		engine.runDueSchedules(due);
		assertEquals(0, published(p));
		assertTrue(history(rid).isEmpty());
	}
}
