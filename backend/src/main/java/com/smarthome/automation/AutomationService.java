package com.smarthome.automation;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.automation.AutomationDtos.ActionResponse;
import com.smarthome.automation.AutomationDtos.ConditionResponse;
import com.smarthome.automation.AutomationDtos.ExecutionResponse;
import com.smarthome.automation.AutomationDtos.RuleRequest;
import com.smarthome.automation.AutomationDtos.RuleResponse;
import com.smarthome.automation.AutomationDtos.TriggerResponse;
import com.smarthome.automation.AutomationExceptions.InvalidRuleException;
import com.smarthome.automation.AutomationExceptions.RuleNotFoundException;
import com.smarthome.automation.AutomationValidator.Validated;
import com.smarthome.command.DeviceCommand;
import com.smarthome.command.DeviceCommandRepository;
import com.smarthome.device.DeviceService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Create/read/update/delete for rules. Every method takes the owner id from the JWT and re-checks, here in the service
 * layer, that the rule belongs to that user and that they own every device it mentions.
 */
@Service
public class AutomationService {

	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

	private final AutomationRuleRepository rules;
	private final AutomationExecutionRepository executions;
	private final DeviceCommandRepository commands;
	private final AutomationValidator validator;
	private final DeviceService deviceService;
	private final AutomationProperties props;
	private final ObjectMapper mapper;
	private final Clock clock;

	public AutomationService(AutomationRuleRepository rules, AutomationExecutionRepository executions, DeviceCommandRepository commands,
			AutomationValidator validator, DeviceService deviceService, AutomationProperties props, ObjectMapper mapper, Clock clock) {
		this.rules = rules;
		this.executions = executions;
		this.commands = commands;
		this.validator = validator;
		this.deviceService = deviceService;
		this.props = props;
		this.mapper = mapper;
		this.clock = clock;
	}

	@Transactional
	public RuleResponse create(UUID ownerId, RuleRequest req) {
		Validated v = validator.validate(req);
		requireDevices(ownerId, v);
		if (rules.countByOwnerUserId(ownerId) >= props.maxRulesPerUser()) {
			throw new InvalidRuleException("rules", "You can have at most " + props.maxRulesPerUser() + " automation rules");
		}
		Instant now = clock.instant();
		AutomationRule rule = new AutomationRule(UUID.randomUUID(), ownerId, now);
		apply(rule, v, now);
		return toResponse(rules.save(rule));
	}

	/** Runs every check {@link #create} would (fields, device ownership, rule limit) without saving anything. */
	@Transactional(readOnly = true)
	public void check(UUID ownerId, RuleRequest req) {
		Validated v = validator.validate(req);
		requireDevices(ownerId, v);
		if (rules.countByOwnerUserId(ownerId) >= props.maxRulesPerUser()) {
			throw new InvalidRuleException("rules", "You can have at most " + props.maxRulesPerUser() + " automation rules");
		}
	}

	@Transactional(readOnly = true)
	public List<RuleResponse> list(UUID ownerId) {
		return rules.findByOwnerUserIdOrderByCreatedAtAsc(ownerId).stream().map(this::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public RuleResponse get(UUID ownerId, UUID id) {
		return toResponse(owned(ownerId, id));
	}

	@Transactional
	public RuleResponse update(UUID ownerId, UUID id, RuleRequest req) {
		AutomationRule rule = owned(ownerId, id);
		Validated v = validator.validate(req);
		requireDevices(ownerId, v);
		apply(rule, v, clock.instant());
		return toResponse(rules.save(rule));
	}

	@Transactional
	public RuleResponse setEnabled(UUID ownerId, UUID id, boolean enabled) {
		AutomationRule rule = owned(ownerId, id);
		rule.setEnabled(enabled, clock.instant());
		return toResponse(rules.save(rule));
	}

	@Transactional
	public void delete(UUID ownerId, UUID id) {
		rules.delete(owned(ownerId, id));
	}

	@Transactional(readOnly = true)
	public List<ExecutionResponse> history(UUID ownerId, UUID ruleId, int limit) {
		owned(ownerId, ruleId);
		return toExecutions(executions.findByRuleIdOrderByLastAtDesc(ruleId, PageRequest.of(0, clamp(limit))));
	}

	@Transactional(readOnly = true)
	public List<ExecutionResponse> history(UUID ownerId, int limit) {
		return toExecutions(executions.findByOwnerUserIdOrderByLastAtDesc(ownerId, PageRequest.of(0, clamp(limit))));
	}

	// ---- helpers -------------------------------------------------------------------------------

	private AutomationRule owned(UUID ownerId, UUID id) {
		return rules.findByIdAndOwnerUserId(id, ownerId).orElseThrow(RuleNotFoundException::new);
	}

	/** The user must own every device the rule reads from or acts on; otherwise it is "device not found". */
	private void requireDevices(UUID ownerId, Validated v) {
		deviceService.requireOwned(ownerId, v.deviceId());
		deviceService.requireOwned(ownerId, v.targetDeviceId());
	}

	private void apply(AutomationRule rule, Validated v, Instant now) {
		rule.apply(v.name(), v.enabled(), v.deviceId(), v.targetDeviceId(), v.trigger(), v.time(), v.days(), v.timeZone(),
				v.logic(), v.conditions(), v.action(), v.actionValue(), v.priority(), v.cooldownSeconds(), now);
	}

	private static int clamp(int limit) {
		return Math.max(1, Math.min(limit, 200));
	}

	private RuleResponse toResponse(AutomationRule r) {
		TriggerResponse trigger = r.getTriggerType() == TriggerType.SCHEDULE
				? new TriggerResponse("SCHEDULE", r.getScheduleTime().format(HHMM),
						r.days().stream().sorted().map(d -> d.name().substring(0, 3)).toList(), r.getTimeZone())
				: new TriggerResponse("TELEMETRY", null, null, null);
		List<ConditionResponse> conditions = r.getConditions().stream().map(c -> new ConditionResponse(
				c.getSensor().id(), c.getOperator().symbol(),
				c.getSensor().isBoolean() ? c.getBoolValue()
						: c.getSensor() == Sensor.GAS ? (Object) c.getNumberValue().intValue() : c.getNumberValue())).toList();
		return new RuleResponse(r.getId(), r.getName(), r.getDeviceUuid(), r.getTargetDeviceUuid(), r.isEnabled(), trigger,
				r.getLogic().name(), conditions, new ActionResponse(r.getAction(), r.getActionValue()), r.getPriority(),
				r.getCooldownSeconds(), r.getLastExecutedAt(), r.getCreatedAt(), r.getUpdatedAt());
	}

	private List<ExecutionResponse> toExecutions(List<AutomationExecution> list) {
		Set<UUID> commandIds = list.stream().map(AutomationExecution::getCommandId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		Map<UUID, String> statuses = commands.findAllById(commandIds).stream()
				.collect(Collectors.toMap(DeviceCommand::getId, c -> c.getStatus().name(), (a, b) -> a));
		return list.stream().map(e -> new ExecutionResponse(e.getId(), e.getRuleId(), e.getRuleName(), e.getDeviceUuid(),
				e.getTriggerType().name(), e.getOutcome(), e.getReason(), e.getAction(), e.getActionValue(), json(e.getTriggerValues()),
				json(e.getEvaluation()), e.getCommandId(), e.getCommandId() == null ? null : statuses.get(e.getCommandId()),
				e.getCreatedAt(), e.getLastAt(), e.getRepeatCount())).toList();
	}

	private JsonNode json(String text) {
		if (text == null) {
			return null;
		}
		try {
			return mapper.readTree(text);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
