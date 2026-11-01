package com.smarthome.automation;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.ZoneId;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.smarthome.automation.AutomationDtos.ConditionRequest;
import com.smarthome.automation.AutomationDtos.RuleRequest;
import com.smarthome.automation.AutomationExceptions.InvalidRuleException;
import com.smarthome.command.CommandActions;

import tools.jackson.databind.JsonNode;

/**
 * Turns a request into a checked, normalised rule definition or rejects it with field-level messages.
 * It only ever accepts values from fixed lists and bounded numbers, so a rule can never carry code.
 */
@Component
public class AutomationValidator {

	/** A fully validated rule definition, ready to be stored. */
	public record Validated(String name, boolean enabled, UUID deviceId, UUID targetDeviceId, TriggerType trigger,
			LocalTime time, Set<DayOfWeek> days, String timeZone, ConditionLogic logic, List<RuleCondition> conditions,
			String action, String actionValue, int priority, int cooldownSeconds) {
	}

	private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
	private static final Map<String, DayOfWeek> DAYS = Map.of(
			"MON", DayOfWeek.MONDAY, "TUE", DayOfWeek.TUESDAY, "WED", DayOfWeek.WEDNESDAY, "THU", DayOfWeek.THURSDAY,
			"FRI", DayOfWeek.FRIDAY, "SAT", DayOfWeek.SATURDAY, "SUN", DayOfWeek.SUNDAY);

	private final AutomationProperties props;

	public AutomationValidator(AutomationProperties props) {
		this.props = props;
	}

	public Validated validate(RuleRequest req) {
		Map<String, String> errors = new LinkedHashMap<>();

		String name = req.name() == null ? "" : req.name().trim();
		if (name.isEmpty() || name.length() > 60) {
			errors.put("name", "Name must be 1-60 characters");
		}

		// ---- trigger ----
		TriggerType trigger = null;
		LocalTime time = null;
		Set<DayOfWeek> days = EnumSet.allOf(DayOfWeek.class);
		String zone = null;
		if (req.trigger() == null) {
			errors.put("trigger", "A trigger is required");
		} else {
			try {
				trigger = TriggerType.valueOf(req.trigger().type() == null ? "" : req.trigger().type());
			} catch (IllegalArgumentException e) {
				errors.put("trigger.type", "Must be TELEMETRY or SCHEDULE");
			}
			if (trigger == TriggerType.SCHEDULE) {
				String t = req.trigger().time();
				if (t == null || !TIME.matcher(t).matches()) {
					errors.put("trigger.time", "Use 24-hour HH:mm, for example 22:30");
				} else {
					time = LocalTime.parse(t);
				}
				if (req.trigger().days() != null && !req.trigger().days().isEmpty()) {
					days = EnumSet.noneOf(DayOfWeek.class);
					for (String d : req.trigger().days()) {
						DayOfWeek dow = d == null ? null : DAYS.get(d.toUpperCase(Locale.ROOT));
						if (dow == null) {
							errors.put("trigger.days", "Days must be MON, TUE, WED, THU, FRI, SAT or SUN");
							break;
						}
						days.add(dow);
					}
				}
				String tz = req.trigger().timeZone();
				if (tz == null || !ZoneId.getAvailableZoneIds().contains(tz)) {
					errors.put("trigger.timeZone", "Use an IANA time zone name such as Asia/Kolkata or UTC");
				} else {
					zone = tz;
				}
			}
		}

		// ---- conditions ----
		ConditionLogic logic = ConditionLogic.AND;
		if (req.logic() != null && !req.logic().isBlank()) {
			try {
				logic = ConditionLogic.valueOf(req.logic().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				errors.put("logic", "Must be AND or OR");
			}
		}
		List<ConditionRequest> reqConditions = req.conditions() == null ? List.of() : req.conditions();
		List<RuleCondition> conditions = new ArrayList<>();
		if (reqConditions.size() > props.maxConditionsPerRule()) {
			errors.put("conditions", "At most " + props.maxConditionsPerRule() + " conditions are allowed");
		}
		if (trigger == TriggerType.TELEMETRY && reqConditions.isEmpty()) {
			errors.put("conditions", "A rule that runs on sensor readings needs at least one condition");
		}
		for (int i = 0; i < Math.min(reqConditions.size(), props.maxConditionsPerRule()); i++) {
			RuleCondition c = condition(reqConditions.get(i), "conditions[" + i + "]", errors);
			if (c != null) {
				conditions.add(c);
			}
		}

		// ---- action ----
		String action = null;
		String actionValue = null;
		if (req.action() == null) {
			errors.put("action", "An action is required");
		} else {
			var output = CommandActions.find(req.action().action());
			if (output.isEmpty()) {
				errors.put("action.action", "Unsupported action");
			} else if (!output.get().values().contains(req.action().value())) {
				// The gas warning LED can never be switched off, by automation or by hand.
				errors.put("action.value", CommandActions.SET_GAS_LED.equals(req.action().action()) && "off".equals(req.action().value())
						? "Automation cannot turn off the gas warning LED: it must always light on a gas warning"
						: "Unsupported value (allowed: " + String.join(", ", output.get().values()) + ")");
			} else {
				action = req.action().action();
				actionValue = req.action().value();
			}
		}

		// ---- numbers ----
		int priority = req.priority() == null ? 10 : req.priority();
		if (priority < 0 || priority > 100) {
			errors.put("priority", "Priority must be between 0 and 100");
		}
		int cooldown = req.cooldownSeconds() == null ? props.defaultCooldownSeconds() : req.cooldownSeconds();
		if (cooldown < props.minCooldownSeconds() || cooldown > props.maxCooldownSeconds()) {
			errors.put("cooldownSeconds", "Cooldown must be between " + props.minCooldownSeconds() + " and " + props.maxCooldownSeconds() + " seconds");
		}

		if (!errors.isEmpty()) {
			throw new InvalidRuleException(errors);
		}
		UUID target = req.targetDeviceId() == null ? req.deviceId() : req.targetDeviceId();
		return new Validated(name, req.enabled() == null || req.enabled(), req.deviceId(), target, trigger, time, days, zone,
				logic, conditions, action, actionValue, priority, cooldown);
	}

	private RuleCondition condition(ConditionRequest c, String path, Map<String, String> errors) {
		Sensor sensor = Sensor.fromId(c.sensor() == null ? "" : c.sensor()).orElse(null);
		if (sensor == null) {
			errors.put(path + ".sensor", "Must be temperature, humidity, motion or gas");
		}
		Operator op = Operator.fromSymbol(c.operator() == null ? "" : c.operator()).orElse(null);
		if (op == null) {
			errors.put(path + ".operator", "Must be one of >, >=, <, <=, ==, !=");
		}
		if (sensor == null || op == null) {
			return null;
		}
		JsonNode v = c.value();
		if (sensor.isBoolean()) {
			if (!op.isEquality()) {
				errors.put(path + ".operator", "Motion can only be compared with == or !=");
				return null;
			}
			if (v == null || !v.isBoolean()) {
				errors.put(path + ".value", "Motion must be compared with true or false");
				return null;
			}
			return new RuleCondition(sensor, op, null, v.booleanValue());
		}
		if (v == null || !v.isNumber()) {
			errors.put(path + ".value", "Must be a number");
			return null;
		}
		double d = v.asDouble();
		if (Double.isNaN(d) || Double.isInfinite(d) || d < sensor.min() || d > sensor.max()) {
			errors.put(path + ".value", "Must be between " + trim(sensor.min()) + " and " + trim(sensor.max()) + " for " + sensor.id());
			return null;
		}
		if (sensor == Sensor.GAS && !v.isIntegralNumber()) {
			errors.put(path + ".value", "Gas is a raw ADC reading and must be a whole number");
			return null;
		}
		return new RuleCondition(sensor, op, d, null);
	}

	private static String trim(double d) {
		return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
	}
}
