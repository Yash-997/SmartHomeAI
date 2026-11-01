package com.smarthome.automation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.smarthome.automation.ConditionEvaluator.ConditionResult;
import com.smarthome.automation.ConditionEvaluator.Evaluation;
import com.smarthome.automation.ConditionEvaluator.Reading;
import com.smarthome.automation.ConditionEvaluator.Tri;
import com.smarthome.command.CommandActions;
import com.smarthome.command.CommandDtos.CommandResponse;
import com.smarthome.command.CommandDtos.CreateCommandRequest;
import com.smarthome.command.CommandExceptions.DeviceOfflineException;
import com.smarthome.command.CommandExceptions.InvalidCommandException;
import com.smarthome.command.CommandExceptions.TooManyCommandsException;
import com.smarthome.command.CommandService;
import com.smarthome.command.DeviceReportedState;
import com.smarthome.command.DeviceReportedStateRepository;
import com.smarthome.device.Device;
import com.smarthome.device.DeviceConnection;
import com.smarthome.device.DeviceExceptions.DeviceNotFoundException;
import com.smarthome.device.DeviceRepository;
import com.smarthome.device.DeviceService;
import com.smarthome.device.DeviceStatus;
import com.smarthome.mqtt.BrokerAccess.BrokerUnavailableException;
import com.smarthome.telemetry.TelemetryReading;
import com.smarthome.telemetry.TelemetryRepository;
import com.smarthome.telemetry.TelemetryStoredEvent;

import tools.jackson.databind.ObjectMapper;

/**
 * Evaluates rules and acts on them.
 *
 * <p>The safety properties, in one place:
 * <ul>
 *   <li>It can only ask for what a person could ask for: every action goes through {@link CommandService}, the single
 *       place that publishes commands, with the rule owner's identity. So ownership, the online check, the allow-list
 *       and the "gas LED can never be switched off" rule apply to automations exactly as to manual commands.
 *       A second guard here refuses unsafe actions even if a rule row was tampered with.</li>
 *   <li>Missing or stale readings never count as zero or as "false": the rule is skipped and the reason recorded.</li>
 *   <li>A rule that throws never affects the others; every rule is evaluated and executed in its own try/catch.</li>
 *   <li>Cooldowns are claimed atomically in the database, so a rule cannot run twice inside its cooldown even with
 *       concurrent evaluations. A per-rule lock stops overlapping executions of the same rule, a per-device rate limit
 *       and a per-event action cap stop runaway behaviour, and evaluation is triggered only by incoming telemetry
 *       (never by command results or state reports), so an action cannot re-trigger itself.</li>
 *   <li>Competing rules for the same output are resolved deterministically: highest priority, then oldest rule, then
 *       lowest id.</li>
 * </ul>
 */
@Service
public class AutomationEngine {

	private static final Logger log = LoggerFactory.getLogger(AutomationEngine.class);
	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
	private static final int LOCK_STRIPES = 64;

	/** A rule whose conditions held, waiting for conflict resolution and execution. */
	record Candidate(AutomationRule rule, String triggerJson, String evaluationJson) {
	}

	private final AutomationRuleRepository rules;
	private final DeviceRepository devices;
	private final DeviceService deviceService;
	private final CommandService commandService;
	private final TelemetryRepository telemetry;
	private final DeviceReportedStateRepository states;
	private final AutomationExecutionRepository executions;
	private final AutomationStore store;
	private final AutomationProperties props;
	private final ObjectMapper mapper;
	private final Clock clock;
	private final Duration staleAfter;
	private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

	public AutomationEngine(AutomationRuleRepository rules, DeviceRepository devices, DeviceService deviceService,
			CommandService commandService, TelemetryRepository telemetry, DeviceReportedStateRepository states,
			AutomationExecutionRepository executions, AutomationStore store, AutomationProperties props, ObjectMapper mapper,
			Clock clock, @Value("${app.telemetry.stale-after-seconds:90}") long staleAfterSeconds) {
		this.rules = rules;
		this.devices = devices;
		this.deviceService = deviceService;
		this.commandService = commandService;
		this.telemetry = telemetry;
		this.states = states;
		this.executions = executions;
		this.store = store;
		this.props = props;
		this.mapper = mapper;
		this.clock = clock;
		this.staleAfter = Duration.ofSeconds(staleAfterSeconds);
		for (int i = 0; i < LOCK_STRIPES; i++) {
			locks[i] = new ReentrantLock();
		}
	}

	// =================================================================================================================
	// Telemetry-triggered rules
	// =================================================================================================================

	public void onTelemetry(TelemetryStoredEvent e) {
		onTelemetry(e, clock.instant());
	}

	/** Evaluates every enabled TELEMETRY rule of the device against the message that just arrived. */
	public void onTelemetry(TelemetryStoredEvent e, Instant now) {
		List<AutomationRule> candidates = rules.findByDeviceUuidAndTriggerTypeAndEnabledTrue(e.deviceUuid(), TriggerType.TELEMETRY);
		if (candidates.isEmpty()) {
			return;
		}
		Reading reading = new Reading(e.temperature(), e.humidity(), e.motion(), e.gasRaw());
		String trigger = triggerJson("telemetry", reading, e.receivedAt());
		List<Candidate> matched = new ArrayList<>();
		for (AutomationRule rule : candidates) {
			try {
				consider(rule, reading, trigger, now, matched);
			} catch (RuntimeException ex) {
				failSafely(rule, ex, trigger, now);
			}
		}
		resolveAndExecute(matched, now);
	}

	/** Evaluates one rule; adds it to {@code matched} if its conditions hold, records why not otherwise (when useful). */
	private void consider(AutomationRule rule, Reading reading, String triggerJson, Instant now, List<Candidate> matched) {
		Evaluation ev = ConditionEvaluator.evaluate(rule.getLogic(), rule.getConditions(), reading);
		String evalJson = evaluationJson(ev);
		switch (ev.result()) {
			case TRUE -> matched.add(new Candidate(rule, triggerJson, evalJson));
			case UNKNOWN -> record(rule, ExecutionOutcome.SKIPPED_NO_DATA, "No reading for: " + missing(ev), triggerJson, evalJson, null, now);
			case FALSE -> {
				// Not matching is the normal case for most readings; it is deliberately not logged.
			}
		}
	}

	// =================================================================================================================
	// Scheduled rules
	// =================================================================================================================

	public void runDueSchedules() {
		runDueSchedules(clock.instant());
	}

	/**
	 * Fires every SCHEDULE rule that is due at {@code now}. A rule is due once per local day, from its scheduled time
	 * until {@code scheduleCatchupSeconds} later. Later than that it is recorded as missed rather than run late.
	 * The local time is resolved in the rule's own time zone, so daylight-saving changes are handled by the JDK rules:
	 * a time that does not exist (spring forward) runs at the first valid moment after the gap, and a time that occurs
	 * twice (fall back) runs once.
	 */
	public void runDueSchedules(Instant now) {
		List<Candidate> matched = new ArrayList<>();
		for (AutomationRule rule : rules.findByTriggerTypeAndEnabledTrue(TriggerType.SCHEDULE)) {
			try {
				ZoneId zone = ZoneId.of(rule.getTimeZone());
				ZonedDateTime local = now.atZone(zone);
				LocalDate today = local.toLocalDate();
				if (!rule.days().contains(local.getDayOfWeek())) {
					continue;
				}
				Instant due = ZonedDateTime.of(today, rule.getScheduleTime(), zone).toInstant();
				if (now.isBefore(due) || today.equals(rule.getLastScheduledOn())) {
					continue;
				}
				if (!store.claimScheduledDay(rule.getId(), today)) {
					continue;   // another tick or instance already handled today
				}
				String scheduled = "{\"source\":\"schedule\",\"scheduledFor\":\"" + due + "\"}";
				if (now.isAfter(due.plusSeconds(props.scheduleCatchupSeconds()))) {
					record(rule, ExecutionOutcome.SKIPPED_MISSED,
							"Scheduled for " + rule.getScheduleTime().format(HHMM) + " " + rule.getTimeZone()
									+ " but the engine was not running in time", scheduled, null, null, now);
					continue;
				}
				considerScheduled(rule, scheduled, now, matched);
			} catch (RuntimeException ex) {
				failSafely(rule, ex, null, now);
			}
		}
		resolveAndExecute(matched, now);
	}

	private void considerScheduled(AutomationRule rule, String scheduledJson, Instant now, List<Candidate> matched) {
		if (rule.getConditions().isEmpty()) {
			matched.add(new Candidate(rule, scheduledJson, "[]"));
			return;
		}
		TelemetryReading latest = telemetry.findFirstByDeviceUuidOrderByReceivedAtDesc(rule.getDeviceUuid()).orElse(null);
		if (latest == null) {
			record(rule, ExecutionOutcome.SKIPPED_NO_DATA, "The source device has not sent any readings yet", scheduledJson, null, null, now);
			return;
		}
		long age = Math.max(0, Duration.between(latest.getReceivedAt(), now).toSeconds());
		Reading reading = new Reading(latest.getTemperatureC(), latest.getHumidityPct(), latest.getMotion(), latest.getGasRaw());
		String trigger = triggerJson("latest-telemetry", reading, latest.getReceivedAt());
		if (age > staleAfter.toSeconds()) {
			record(rule, ExecutionOutcome.SKIPPED_STALE_DATA,
					"Latest reading is " + age + " s old (limit " + staleAfter.toSeconds() + " s)", trigger, null, null, now);
			return;
		}
		Evaluation ev = ConditionEvaluator.evaluate(rule.getLogic(), rule.getConditions(), reading);
		String evalJson = evaluationJson(ev);
		switch (ev.result()) {
			case TRUE -> matched.add(new Candidate(rule, trigger, evalJson));
			case UNKNOWN -> record(rule, ExecutionOutcome.SKIPPED_NO_DATA, "No reading for: " + missing(ev), trigger, evalJson, null, now);
			case FALSE -> record(rule, ExecutionOutcome.SKIPPED_CONDITIONS_FALSE, "Conditions were not met at the scheduled time", trigger, evalJson, null, now);
		}
	}

	// =================================================================================================================
	// Conflict resolution and execution
	// =================================================================================================================

	/**
	 * Rules that want the same output on the same device compete: highest priority wins, then the oldest rule, then the
	 * lowest id, so the same inputs always give the same winner. The others are recorded as conflicts. This is decided
	 * among all rules whose conditions hold, before cooldowns: a rule in cooldown still "owns" its output while its
	 * conditions hold, so a lower-priority rule cannot sneak past it.
	 */
	private void resolveAndExecute(List<Candidate> matched, Instant now) {
		if (matched.isEmpty()) {
			return;
		}
		Comparator<Candidate> order = Comparator
				.comparingInt((Candidate c) -> c.rule().getPriority()).reversed()
				.thenComparing(c -> c.rule().getCreatedAt())
				.thenComparing(c -> c.rule().getId());

		Map<String, List<Candidate>> byOutput = new LinkedHashMap<>();
		for (Candidate c : matched) {
			byOutput.computeIfAbsent(c.rule().getTargetDeviceUuid() + "|" + c.rule().getAction(), k -> new ArrayList<>()).add(c);
		}
		List<Candidate> winners = new ArrayList<>();
		for (List<Candidate> group : byOutput.values()) {
			group.sort(order);
			Candidate winner = group.get(0);
			winners.add(winner);
			for (Candidate loser : group.subList(1, group.size())) {
				record(loser.rule(), ExecutionOutcome.SKIPPED_CONFLICT,
						"Overridden by rule '" + winner.rule().getName() + "' (priority " + winner.rule().getPriority()
								+ " vs " + loser.rule().getPriority() + ")",
						loser.triggerJson(), loser.evaluationJson(), null, now);
			}
		}
		winners.sort(order);
		int acted = 0;
		for (Candidate w : winners) {
			if (acted++ >= props.maxActionsPerEvent()) {
				record(w.rule(), ExecutionOutcome.SKIPPED_RATE_LIMITED,
						"Too many automation actions from a single event (limit " + props.maxActionsPerEvent() + ")",
						w.triggerJson(), w.evaluationJson(), null, now);
				continue;
			}
			try {
				execute(w, now);
			} catch (RuntimeException ex) {
				failSafely(w.rule(), ex, w.triggerJson(), now);
			}
		}
	}

	private void execute(Candidate c, Instant now) {
		AutomationRule stale = c.rule();
		ReentrantLock lock = locks[Math.floorMod(stale.getId().hashCode(), LOCK_STRIPES)];
		if (!lock.tryLock()) {
			record(stale, ExecutionOutcome.SKIPPED_BUSY, "This rule is already being executed", c.triggerJson(), c.evaluationJson(), null, now);
			return;
		}
		try {
			AutomationRule rule = rules.findById(stale.getId()).orElse(null);
			if (rule == null || !rule.isEnabled()) {
				return;   // deleted or switched off while we were working
			}
			String trigger = c.triggerJson();
			String eval = c.evaluationJson();

			// 1. Defence in depth: never act on anything the command allow-list would not accept, in particular never
			//    switch the gas warning LED off. (The validator and CommandService refuse this too.)
			if (!isSafe(rule)) {
				record(rule, ExecutionOutcome.SKIPPED_UNSAFE, "Refused: this action is not allowed for automation", trigger, eval, null, now);
				return;
			}

			// 2. The owner must still own both devices.
			Device source = devices.findById(rule.getDeviceUuid()).orElse(null);
			Device target = devices.findById(rule.getTargetDeviceUuid()).orElse(null);
			if (!ownedBy(source, rule.getOwnerUserId()) || !ownedBy(target, rule.getOwnerUserId())) {
				record(rule, ExecutionOutcome.SKIPPED_NOT_OWNED, "The rule owner no longer owns the device", trigger, eval, null, now);
				return;
			}

			// 3. Cooldown (cheap read; the authoritative atomic claim comes later).
			Instant last = rule.getLastExecutedAt();
			if (last != null && now.isBefore(last.plusSeconds(rule.getCooldownSeconds()))) {
				record(rule, ExecutionOutcome.SKIPPED_COOLDOWN,
						"Cooling down for " + rule.getCooldownSeconds() + " s after the last run", trigger, eval, null, now);
				return;
			}

			// 4. Only act on a device that is genuinely online right now.
			DeviceConnection connection = deviceService.connectionOf(target, now);
			if (connection != DeviceConnection.ONLINE) {
				record(rule, ExecutionOutcome.SKIPPED_OFFLINE, connection == DeviceConnection.NEVER_CONNECTED
						? "The target device has never connected" : "The target device is offline", trigger, eval, null, now);
				return;
			}

			// 5. Runaway protection per device.
			long recent = executions.countByDeviceUuidAndOutcomeAndCreatedAtAfter(target.getId(), ExecutionOutcome.EXECUTED, now.minusSeconds(60));
			if (recent >= props.maxCommandsPerDevicePerMinute()) {
				record(rule, ExecutionOutcome.SKIPPED_RATE_LIMITED,
						"Too many automation commands for this device in the last minute (limit " + props.maxCommandsPerDevicePerMinute() + ")",
						trigger, eval, null, now);
				return;
			}

			// 6. Nothing to do if the device already reports the requested mode (fresh report only).
			if (alreadySet(target, rule, now)) {
				record(rule, ExecutionOutcome.SKIPPED_ALREADY_SET, "The device already reports '" + rule.getActionValue() + "'", trigger, eval, null, now);
				return;
			}

			// 7. Claim the cooldown atomically, then hand the work to the one command service.
			if (!store.claimExecution(rule.getId(), now, rule.getCooldownSeconds())) {
				record(rule, ExecutionOutcome.SKIPPED_COOLDOWN, "Another evaluation just ran this rule", trigger, eval, null, now);
				return;
			}
			try {
				CommandResponse cmd = commandService.createFromAutomation(rule.getOwnerUserId(), rule.getTargetDeviceUuid(),
						new CreateCommandRequest(rule.getAction(), rule.getActionValue()), "automation:" + rule.getName());
				record(rule, ExecutionOutcome.EXECUTED, "Command " + cmd.status().name().toLowerCase() + " to the device", trigger, eval, cmd.id(), now);
			} catch (DeviceOfflineException ex) {
				give(rule, now, last);
				record(rule, ExecutionOutcome.SKIPPED_OFFLINE, "The target device went offline", trigger, eval, null, now);
			} catch (TooManyCommandsException ex) {
				give(rule, now, last);
				record(rule, ExecutionOutcome.SKIPPED_RATE_LIMITED, "The device already has too many commands waiting", trigger, eval, null, now);
			} catch (DeviceNotFoundException ex) {
				give(rule, now, last);
				record(rule, ExecutionOutcome.SKIPPED_NOT_OWNED, "The rule owner no longer owns the device", trigger, eval, null, now);
			} catch (InvalidCommandException ex) {
				give(rule, now, last);
				record(rule, ExecutionOutcome.FAILED, "The command was refused: " + String.join("; ", ex.fieldErrors().values()), trigger, eval, null, now);
			} catch (BrokerUnavailableException ex) {
				give(rule, now, last);
				record(rule, ExecutionOutcome.FAILED, "The command could not be delivered (message broker unavailable)", trigger, eval, null, now);
			} catch (RuntimeException ex) {
				give(rule, now, last);
				throw ex;
			}
		} finally {
			lock.unlock();
		}
	}

	// =================================================================================================================
	// Helpers
	// =================================================================================================================

	private static boolean isSafe(AutomationRule rule) {
		var output = CommandActions.find(rule.getAction());
		if (output.isEmpty() || !output.get().values().contains(rule.getActionValue())) {
			return false;
		}
		return !(CommandActions.SET_GAS_LED.equals(rule.getAction()) && "off".equals(rule.getActionValue()));
	}

	private static boolean ownedBy(Device d, UUID owner) {
		return d != null && d.getStatus() == DeviceStatus.ACTIVE && owner.equals(d.getOwnerUserId());
	}

	private boolean alreadySet(Device target, AutomationRule rule, Instant now) {
		DeviceReportedState s = states.findById(target.getId()).orElse(null);
		if (s == null || Duration.between(s.getReportedAt(), now).compareTo(staleAfter) > 0) {
			return false;   // no fresh report: we can't know, so go ahead
		}
		String mode = CommandActions.SET_PIR_LED.equals(rule.getAction()) ? s.getPirLedMode() : s.getGasLedMode();
		return rule.getActionValue().equals(mode);
	}

	/** Returns the cooldown that was claimed for a run that did not actually happen. */
	private void give(AutomationRule rule, Instant claimed, Instant previous) {
		store.revertClaim(rule.getId(), claimed, previous);
	}

	private void failSafely(AutomationRule rule, RuntimeException ex, String triggerJson, Instant now) {
		log.warn("Automation rule {} failed: {}", rule.getId(), ex.getClass().getSimpleName());
		record(rule, ExecutionOutcome.FAILED, "Unexpected error while running the rule (" + ex.getClass().getSimpleName() + ")",
				triggerJson, null, null, now);
	}

	/** History writing must never be the reason a rule run fails. */
	private void record(AutomationRule rule, ExecutionOutcome outcome, String reason, String trigger, String eval, UUID commandId, Instant now) {
		try {
			store.record(rule, outcome, reason, trigger, eval, commandId, now);
		} catch (RuntimeException ex) {
			log.warn("Could not record automation history for rule {}: {}", rule.getId(), ex.getClass().getSimpleName());
		}
	}

	private String missing(Evaluation ev) {
		return ev.details().stream().filter(d -> d.result() == Tri.UNKNOWN).map(d -> d.sensor().id()).distinct().collect(Collectors.joining(", "));
	}

	private String triggerJson(String source, Reading r, Instant readingAt) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("source", source);
		m.put("temperature", r.temperature());
		m.put("humidity", r.humidity());
		m.put("motion", r.motion());
		m.put("gas", r.gas());
		m.put("readingAt", readingAt == null ? null : readingAt.toString());
		return mapper.writeValueAsString(m);
	}

	private String evaluationJson(Evaluation ev) {
		List<Map<String, Object>> list = new ArrayList<>();
		for (ConditionResult d : ev.details()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("sensor", d.sensor().id());
			m.put("operator", d.operator().symbol());
			m.put("expected", d.expected());
			m.put("actual", d.actual());
			m.put("result", d.result().name());
			list.add(m);
		}
		return mapper.writeValueAsString(list);
	}
}
