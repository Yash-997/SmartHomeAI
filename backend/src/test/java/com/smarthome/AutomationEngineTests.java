package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.smarthome.automation.AutomationExecution;
import com.smarthome.automation.AutomationRule;
import com.smarthome.automation.ConditionLogic;
import com.smarthome.automation.ExecutionOutcome;
import com.smarthome.automation.Operator;
import com.smarthome.automation.RuleCondition;
import com.smarthome.automation.Sensor;
import com.smarthome.automation.TriggerType;
import com.smarthome.command.CommandStatus;
import com.smarthome.command.DeviceCommand;
import com.smarthome.command.DeviceReportedState;

import tools.jackson.databind.JsonNode;

class AutomationEngineTests extends AutomationTestBase {

	@AfterEach
	void restore() {
		publisher.setDown(false);
	}

	private long count(UUID ruleId, ExecutionOutcome outcome) {
		return history(ruleId).stream().filter(e -> e.getOutcome() == outcome).count();
	}

	/** A rule row written straight to the database, bypassing the API validation (as if it had been tampered with). */
	private UUID tamperedRule(Paired owner, UUID target, String action, String value, int priority) {
		AutomationRule r = new AutomationRule(UUID.randomUUID(), owner.userId(), Instant.now());
		r.apply("Tampered", true, owner.uuid(), target, TriggerType.TELEMETRY, null, EnumSet.noneOf(DayOfWeek.class), null,
				ConditionLogic.AND, List.of(new RuleCondition(Sensor.TEMPERATURE, Operator.GT, 1.0, null)), action, value, priority, 10, Instant.now());
		return rules.saveAndFlush(r).getId();
	}

	// ---- acting and auditing ---------------------------------------------------------------------

	@Test
	void aMatchingReadingSendsACommandThroughTheCommandServiceAndIsFullyAudited() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID rid = simpleRule(p, "Hot room", "temperature", ">", "30", "set_pir_led", "on", 10, 10);
		Instant now = Instant.now();

		engine.onTelemetry(event(p, now, 31.5, 50.0, true, 1200), now);

		assertEquals(1, published(p));
		JsonNode wire = mapper.readTree(publisher.publishedTo(p.deviceId()).get(0).json());
		assertEquals("set_pir_led", wire.get("action").asString());
		assertEquals("on", wire.get("value").asString());

		AutomationExecution e = latest(rid);
		assertEquals(ExecutionOutcome.EXECUTED, e.getOutcome());
		assertNotNull(e.getCommandId());
		assertTrue(e.getTriggerValues().contains("31.5") && e.getTriggerValues().contains("1200"), e.getTriggerValues());
		assertTrue(e.getEvaluation().contains("\"result\":\"TRUE\"") && e.getEvaluation().contains("\"operator\":\">\""), e.getEvaluation());
		assertEquals("set_pir_led", e.getAction());
		assertEquals(p.uuid(), e.getDeviceUuid());

		DeviceCommand cmd = commands.findById(e.getCommandId()).orElseThrow();
		assertEquals("automation:Hot room", cmd.getOrigin());
		assertEquals(p.userId(), cmd.getRequestedBy(), "acts with the rule owner's identity");
		assertEquals(CommandStatus.SENT, cmd.getStatus());
		assertTrue(rule(rid).getLastExecutedAt() != null);

		// the manual command list shows where it came from
		mvc.perform(get("/api/devices/" + p.uuid() + "/commands").header("Authorization", p.auth()))
				.andExpect(jsonPath("$[0].origin").value("automation:Hot room"));
	}

	@Test
	void aNonMatchingReadingDoesNothingAndLeavesNoNoise() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID rid = simpleRule(p, "Hot room", "temperature", ">", "30", "set_pir_led", "on", 10, 10);
		Instant now = Instant.now();
		engine.onTelemetry(event(p, now, 20.0, 50.0, false, 100), now);
		assertEquals(0, published(p));
		assertTrue(history(rid).isEmpty());
	}

	@Test
	void andNeedsEveryConditionAndOrNeedsOne() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID both = createRule(p, ruleJson("Both", p.uuid(), null, TELEMETRY, "AND",
				List.of(cond("temperature", ">", "30"), cond("motion", "==", "true")), "set_pir_led", "on", 10, 10));
		UUID either = createRule(p, ruleJson("Either", p.uuid(), null, TELEMETRY, "OR",
				List.of(cond("temperature", ">", "30"), cond("motion", "==", "true")), "set_gas_led", "on", 10, 10));
		Instant now = Instant.now();

		engine.onTelemetry(event(p, now, 35.0, null, false, null), now);   // hot but no motion
		var sent = publisher.publishedTo(p.deviceId());
		assertEquals(1, sent.size());
		assertTrue(sent.get(0).json().contains("set_gas_led"), "only the OR rule fired");
		assertTrue(history(both).isEmpty());

		finishAllCommands(p);
		engine.onTelemetry(event(p, now.plusSeconds(12), 35.0, null, true, null), now.plusSeconds(12));   // hot AND motion
		assertEquals(3, published(p), "now the AND rule fires, and the OR rule again after its cooldown");
		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(both));
		assertEquals(2, count(either, ExecutionOutcome.EXECUTED));
	}

	// ---- missing and stale data ------------------------------------------------------------------

	@Test
	void aMissingReadingIsNeverTreatedAsZero() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID cold = simpleRule(p, "Freezing", "temperature", "<", "5", "set_pir_led", "on", 10, 10);
		UUID quiet = simpleRule(p, "No motion", "motion", "==", "false", "set_gas_led", "on", 10, 10);
		Instant now = Instant.now();

		// DHT22 failed and the PIR value is absent: a zero/false default would wrongly trigger both rules
		engine.onTelemetry(event(p, now, null, null, null, null), now);

		assertEquals(0, published(p));
		AutomationExecution e = latest(cold);
		assertEquals(ExecutionOutcome.SKIPPED_NO_DATA, e.getOutcome());
		assertTrue(e.getReason().contains("temperature"), e.getReason());
		assertTrue(e.getEvaluation().contains("UNKNOWN"));
		assertEquals(ExecutionOutcome.SKIPPED_NO_DATA, latestOutcome(quiet));
		assertNull(rule(cold).getLastExecutedAt(), "a skipped run does not start a cooldown");
	}

	@Test
	void gasIsMissingWhileTheSensorWarmsUp() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID rid = simpleRule(p, "Clean air", "gas", "<", "500", "set_pir_led", "on", 10, 10);
		Instant now = Instant.now();
		engine.onTelemetry(event(p, now, 22.0, 50.0, false, null), now);   // gasRaw omitted during warm-up
		assertEquals(0, published(p));
		assertEquals(ExecutionOutcome.SKIPPED_NO_DATA, latestOutcome(rid));
		engine.onTelemetry(event(p, now.plusSeconds(12), 22.0, 50.0, false, 300), now.plusSeconds(12));   // real reading
		assertEquals(1, published(p));
	}

	@Test
	void anOrRuleStillFiresWhenTheOtherSensorIsMissing() throws Exception {
		Paired p = onlineDevice(newUserToken());
		createRule(p, ruleJson("Either", p.uuid(), null, TELEMETRY, "OR",
				List.of(cond("temperature", "<", "5"), cond("motion", "==", "true")), "set_pir_led", "on", 10, 10));
		Instant now = Instant.now();
		engine.onTelemetry(event(p, now, null, null, true, null), now);   // temperature unknown, motion true
		assertEquals(1, published(p));
	}

	// ---- cooldown and duplicates -----------------------------------------------------------------

	@Test
	void aRuleCannotFireAgainInsideItsCooldownAndRepeatsAreFoldedTogether() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID rid = simpleRule(p, "Hot room", "temperature", ">", "30", "set_pir_led", "on", 10, 10);
		Instant t0 = Instant.now();

		engine.onTelemetry(event(p, t0, 35.0, null, null, null), t0);
		engine.onTelemetry(event(p, t0.plusSeconds(3), 35.0, null, null, null), t0.plusSeconds(3));
		engine.onTelemetry(event(p, t0.plusSeconds(4), 36.0, null, null, null), t0.plusSeconds(4));
		assertEquals(1, published(p));
		assertEquals(1, count(rid, ExecutionOutcome.SKIPPED_COOLDOWN), "two identical skips share one row");
		AutomationExecution skip = history(rid).stream().filter(e -> e.getOutcome() == ExecutionOutcome.SKIPPED_COOLDOWN).findFirst().orElseThrow();
		assertEquals(2, skip.getRepeatCount());

		finishAllCommands(p);
		engine.onTelemetry(event(p, t0.plusSeconds(12), 35.0, null, null, null), t0.plusSeconds(12));
		assertEquals(2, published(p), "runs again once the cooldown has passed");
		assertEquals(2, count(rid, ExecutionOutcome.EXECUTED));
	}

	@Test
	void concurrentEvaluationsOfTheSameRuleExecuteItExactlyOnce() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID rid = simpleRule(p, "Hot room", "temperature", ">", "30", "set_pir_led", "on", 10, 60);
		Instant now = Instant.now();
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				engine.onTelemetry(event(p, now, 40.0, null, null, null), now);
				return null;
			}));
		}
		ready.await();
		go.countDown();
		for (var f : futures) {
			f.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();

		assertEquals(1, published(p), "one command, not eight");
		assertEquals(1, count(rid, ExecutionOutcome.EXECUTED));
	}

	@Test
	void aDeviceAlreadyInTheRequestedModeIsLeftAlone() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID rid = simpleRule(p, "Hot room", "temperature", ">", "30", "set_pir_led", "on", 10, 10);
		Instant now = Instant.now();
		DeviceReportedState s = new DeviceReportedState(p.uuid());
		s.update("on", true, "auto", false, false, null, now);
		states.saveAndFlush(s);

		engine.onTelemetry(event(p, now, 35.0, null, null, null), now);
		assertEquals(0, published(p));
		assertEquals(ExecutionOutcome.SKIPPED_ALREADY_SET, latestOutcome(rid));

		// a report that is too old proves nothing, so the rule acts
		s.update("on", true, "auto", false, false, null, now.minusSeconds(600));
		states.saveAndFlush(s);
		engine.onTelemetry(event(p, now.plusSeconds(1), 35.0, null, null, null), now.plusSeconds(1));
		assertEquals(1, published(p));
	}

	// ---- offline ---------------------------------------------------------------------------------

	@Test
	void anOfflineTargetIsSkippedWithAReasonAndKeepsTheCooldownFree() throws Exception {
		String token = newUserToken();
		Paired sensor = onlineDevice(token);
		Paired lamp = pair(token);   // paired but never connected
		UUID rid = createRule(sensor, ruleJson("Lamp follows heat", sensor.uuid(), lamp.uuid(), TELEMETRY, "AND",
				List.of(cond("temperature", ">", "30")), "set_pir_led", "on", 10, 10));
		Instant now = Instant.now();

		engine.onTelemetry(event(sensor, now, 35.0, null, null, null), now);
		assertEquals(0, published(lamp));
		AutomationExecution e = latest(rid);
		assertEquals(ExecutionOutcome.SKIPPED_OFFLINE, e.getOutcome());
		assertTrue(e.getReason().contains("never connected"), e.getReason());
		assertNull(rule(rid).getLastExecutedAt(), "nothing was run, so no cooldown is burned");

		// it goes offline later
		sendTelemetry(lamp, "{\"seq\":1,\"motion\":false}");
		markOffline(lamp);
		engine.onTelemetry(event(sensor, now.plusSeconds(1), 35.0, null, null, null), now.plusSeconds(1));
		assertEquals(0, published(lamp));
		assertTrue(latest(rid).getReason().contains("offline"));

		// and when it comes back, the very next reading acts immediately
		sendTelemetry(lamp, "{\"seq\":2,\"motion\":false}");
		engine.onTelemetry(event(sensor, now.plusSeconds(2), 35.0, null, null, null), now.plusSeconds(2));
		assertEquals(1, published(lamp));
		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(rid));
	}

	@Test
	void ifTheOwnerNoLongerOwnsTheTargetNothingIsSent() throws Exception {
		Paired a = onlineDevice(newUserToken());
		Paired stranger = onlineDevice(newUserToken());
		UUID rid = tamperedRule(a, stranger.uuid(), "set_pir_led", "on", 10);
		Instant now = Instant.now();
		engine.onTelemetry(event(a, now, 35.0, null, null, null), now);
		assertEquals(0, published(stranger));
		assertEquals(ExecutionOutcome.SKIPPED_NOT_OWNED, latestOutcome(rid));
	}

	// ---- gas warning safety ----------------------------------------------------------------------

	@Test
	void automationNeverSwitchesTheGasWarningLedOffEvenIfARuleRowIsTampered() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID rid = tamperedRule(p, p.uuid(), "set_gas_led", "off", 100);
		Instant now = Instant.now();

		engine.onTelemetry(event(p, now, 35.0, null, null, null), now);

		assertEquals(0, published(p), "no command at all");
		assertEquals(ExecutionOutcome.SKIPPED_UNSAFE, latestOutcome(rid));
		assertTrue(commands.findByDeviceUuidOrderByCreatedAtDesc(p.uuid(), org.springframework.data.domain.PageRequest.of(0, 10)).isEmpty());
	}

	@Test
	void forcingTheGasLedOnOrBackToAutoIsAllowed() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID on = simpleRule(p, "Gas high", "gas", ">", "2500", "set_gas_led", "on", 10, 10);
		UUID auto = simpleRule(p, "Gas low", "gas", "<", "1000", "set_gas_led", "auto", 10, 10);
		Instant now = Instant.now();
		engine.onTelemetry(event(p, now, 22.0, null, null, 3000), now);
		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(on));
		finishAllCommands(p);
		engine.onTelemetry(event(p, now.plusSeconds(12), 22.0, null, null, 400), now.plusSeconds(12));
		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(auto));
		assertTrue(publisher.publishedTo(p.deviceId()).stream().noneMatch(x -> x.json().contains("\"value\":\"off\"") && x.json().contains("set_gas_led")));
	}

	// ---- priorities and conflicts ----------------------------------------------------------------

	@Test
	void whenRulesCompeteForTheSameOutputThePriorityWinsAndTheLoserIsRecorded() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID low = simpleRule(p, "Low priority", "temperature", ">", "30", "set_pir_led", "off", 10, 10);
		UUID high = simpleRule(p, "High priority", "temperature", ">", "25", "set_pir_led", "on", 90, 10);
		Instant now = Instant.now();

		engine.onTelemetry(event(p, now, 35.0, null, null, null), now);

		var sent = publisher.publishedTo(p.deviceId());
		assertEquals(1, sent.size());
		assertTrue(sent.get(0).json().contains("\"value\":\"on\""), "the high-priority rule's value was sent");
		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(high));
		AutomationExecution lost = latest(low);
		assertEquals(ExecutionOutcome.SKIPPED_CONFLICT, lost.getOutcome());
		assertTrue(lost.getReason().contains("High priority"), lost.getReason());
		assertNull(rule(low).getLastExecutedAt());
	}

	@Test
	void equalPrioritiesAreBrokenDeterministicallyByAge() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID older = simpleRule(p, "Older", "temperature", ">", "30", "set_pir_led", "on", 50, 10);
		Thread.sleep(10);
		UUID newer = simpleRule(p, "Newer", "temperature", ">", "30", "set_pir_led", "off", 50, 10);
		Instant now = Instant.now();

		for (int round = 0; round < 3; round++) {
			Instant at = now.plusSeconds(12L * round);
			finishAllCommands(p);
			engine.onTelemetry(event(p, at, 35.0, null, null, null), at);
		}
		// the same rule wins every time
		assertEquals(3, count(older, ExecutionOutcome.EXECUTED));
		assertEquals(0, count(newer, ExecutionOutcome.EXECUTED));
		assertTrue(publisher.publishedTo(p.deviceId()).stream().allMatch(x -> x.json().contains("\"value\":\"on\"")));
	}

	@Test
	void aWinnerStillInCooldownKeepsItsOutputAgainstLowerPriorityRules() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID high = simpleRule(p, "Boss", "temperature", ">", "30", "set_pir_led", "on", 90, 30);
		UUID low = simpleRule(p, "Minion", "temperature", ">", "30", "set_pir_led", "off", 10, 10);
		Instant now = Instant.now();
		engine.onTelemetry(event(p, now, 35.0, null, null, null), now);
		assertEquals(1, published(p));

		finishAllCommands(p);
		engine.onTelemetry(event(p, now.plusSeconds(15), 35.0, null, null, null), now.plusSeconds(15));   // Boss is cooling down
		assertEquals(1, published(p), "the low-priority rule must not take over while the Boss's conditions hold");
		assertEquals(ExecutionOutcome.SKIPPED_COOLDOWN, latestOutcome(high));
		assertEquals(ExecutionOutcome.SKIPPED_CONFLICT, latestOutcome(low));
	}

	@Test
	void rulesForDifferentOutputsDoNotCompete() throws Exception {
		Paired p = onlineDevice(newUserToken());
		simpleRule(p, "Pir", "temperature", ">", "30", "set_pir_led", "on", 10, 10);
		simpleRule(p, "Gas", "temperature", ">", "30", "set_gas_led", "on", 10, 10);
		Instant now = Instant.now();
		engine.onTelemetry(event(p, now, 35.0, null, null, null), now);
		assertEquals(2, published(p));
	}

	// ---- failures and limits ---------------------------------------------------------------------

	@Test
	void aBrokenRuleDoesNotStopTheOthers() throws Exception {
		Paired mine = onlineDevice(newUserToken());
		Paired stranger = onlineDevice(newUserToken());
		UUID broken = tamperedRule(mine, stranger.uuid(), "set_pir_led", "on", 99);   // outranks the good rule
		UUID good = simpleRule(mine, "Good", "temperature", ">", "30", "set_gas_led", "on", 1, 10);
		Instant now = Instant.now();

		engine.onTelemetry(event(mine, now, 35.0, null, null, null), now);

		assertEquals(ExecutionOutcome.SKIPPED_NOT_OWNED, latestOutcome(broken));
		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(good));
		assertEquals(1, published(mine));
	}

	@Test
	void whenTheBrokerIsDownRulesFailVisiblyAndCanRunAgainAtOnce() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID a = simpleRule(p, "A", "temperature", ">", "30", "set_pir_led", "on", 10, 10);
		UUID b = simpleRule(p, "B", "temperature", ">", "30", "set_gas_led", "on", 10, 10);
		Instant now = Instant.now();

		publisher.setDown(true);
		engine.onTelemetry(event(p, now, 35.0, null, null, null), now);
		assertEquals(ExecutionOutcome.FAILED, latestOutcome(a));
		assertEquals(ExecutionOutcome.FAILED, latestOutcome(b), "one failure did not stop the other rule");
		assertTrue(latest(a).getReason().contains("broker"), latest(a).getReason());
		assertNull(rule(a).getLastExecutedAt(), "a failed run does not use up the cooldown");

		publisher.setDown(false);
		engine.onTelemetry(event(p, now.plusSeconds(1), 35.0, null, null, null), now.plusSeconds(1));
		assertEquals(2, published(p));
		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(a));
	}

	@Test
	void aRunawayRuleIsHeldBackByThePerDeviceRateLimit() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID rid = simpleRule(p, "Chatty", "temperature", ">", "30", "set_pir_led", "on", 10, 5);
		Instant t0 = Instant.now();
		for (int i = 0; i < 7; i++) {
			Instant at = t0.plusSeconds(6L * i);
			finishAllCommands(p);
			engine.onTelemetry(event(p, at, 35.0, null, null, null), at);
		}
		assertEquals(6, count(rid, ExecutionOutcome.EXECUTED), "at most 6 automation commands per device per minute");
		assertEquals(ExecutionOutcome.SKIPPED_RATE_LIMITED, latestOutcome(rid));
		assertEquals(6, published(p));
	}

	@Test
	void historyIsPrunedToTheConfiguredSize() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID rid = simpleRule(p, "Noisy", "temperature", ">", "30", "set_pir_led", "on", 10, 10);
		AutomationRule rule = rule(rid);
		for (int i = 0; i < 205; i++) {
			automationStore.record(rule, ExecutionOutcome.FAILED, "failure " + i, null, null, null, Instant.now().plusMillis(i));
		}
		assertEquals(200, executions.countByRuleId(rid));
	}

	// ---- the live telemetry path -----------------------------------------------------------------

	@Test
	void realTelemetryTriggersRulesOnlyOncePerNewValidMessage() throws Exception {
		Paired p = onlineDevice(newUserToken());
		Paired other = onlineDevice(newUserToken());
		UUID rid = simpleRule(p, "Hot room", "temperature", ">", "30", "set_pir_led", "on", 10, 10);

		sendTelemetry(p, "{\"seq\":7001,\"temperature\":33.0,\"motion\":false}");
		dispatcher.awaitIdle();
		assertEquals(1, published(p));
		assertEquals(ExecutionOutcome.EXECUTED, latestOutcome(rid));

		// QoS 1 redelivery of the same bytes: stored once, so it does not trigger anything again
		sendTelemetry(p, "{\"seq\":7001,\"temperature\":33.0,\"motion\":false}");
		// rejected payloads and other devices never reach this rule
		sendTelemetry(p, "{\"seq\":7002,\"temperature\":900}");
		sendTelemetry(other, "{\"seq\":7003,\"temperature\":99.0,\"motion\":false}");
		dispatcher.awaitIdle();
		assertEquals(1, published(p));
		assertEquals(1, history(rid).size());
		assertEquals(0, published(other));
	}

	@Test
	void aDisabledRuleIsNotEvaluated() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID rid = simpleRule(p, "Hot room", "temperature", ">", "30", "set_pir_led", "on", 10, 10);
		mvc.perform(patch("/api/automations/" + rid + "/enabled").header("Authorization", p.auth())
				.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andExpect(status().isOk());

		sendTelemetry(p, "{\"seq\":7101,\"temperature\":40.0,\"motion\":false}");
		dispatcher.awaitIdle();
		assertEquals(0, published(p));
		assertTrue(history(rid).isEmpty());
		assertFalse(rule(rid).isEnabled());
	}
}
