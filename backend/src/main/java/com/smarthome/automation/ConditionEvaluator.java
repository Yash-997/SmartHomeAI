package com.smarthome.automation;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a rule's conditions against one set of readings. No I/O, no clock, no expressions: just comparisons of
 * known sensors with numbers or booleans.
 *
 * <p>Evaluation is three-valued. A reading that is missing (the sensor failed, is still warming up, or the data is too
 * old) makes the comparison UNKNOWN, never FALSE and above all never "0". A rule only acts when its result is TRUE.
 * <pre>
 * AND: any FALSE -> FALSE; otherwise any UNKNOWN -> UNKNOWN; otherwise TRUE
 * OR : any TRUE  -> TRUE ; otherwise any UNKNOWN -> UNKNOWN; otherwise FALSE
 * </pre>
 * So "temperature &lt; 5 OR motion == true" is TRUE when motion is true even if temperature is missing, while
 * "temperature &lt; 5 AND motion == true" with a missing temperature is never TRUE.
 */
public final class ConditionEvaluator {

	public enum Tri {
		TRUE, FALSE, UNKNOWN
	}

	/** The readings a rule is evaluated against. A null field means "no reading". */
	public record Reading(Double temperature, Double humidity, Boolean motion, Integer gas) {

		Object valueOf(Sensor s) {
			return switch (s) {
				case TEMPERATURE -> temperature;
				case HUMIDITY -> humidity;
				case MOTION -> motion;
				case GAS -> gas;
			};
		}
	}

	public record ConditionResult(Sensor sensor, Operator operator, Object expected, Object actual, Tri result) {
	}

	public record Evaluation(Tri result, List<ConditionResult> details) {
	}

	/** Two numbers closer than this are equal (so 0.1 + 0.2 == 0.3 style surprises don't matter). */
	static final double EPSILON = 1e-9;

	private ConditionEvaluator() {
	}

	public static Evaluation evaluate(ConditionLogic logic, List<RuleCondition> conditions, Reading reading) {
		List<ConditionResult> details = new ArrayList<>();
		boolean anyTrue = false;
		boolean anyFalse = false;
		boolean anyUnknown = false;
		for (RuleCondition c : conditions) {
			Object actual = reading.valueOf(c.getSensor());
			Tri r = evaluate(c, actual);
			Object expected = c.getSensor().isBoolean() ? c.getBoolValue() : c.getNumberValue();
			details.add(new ConditionResult(c.getSensor(), c.getOperator(), expected, actual, r));
			anyTrue |= r == Tri.TRUE;
			anyFalse |= r == Tri.FALSE;
			anyUnknown |= r == Tri.UNKNOWN;
		}
		if (conditions.isEmpty()) {
			return new Evaluation(Tri.TRUE, details);   // no conditions = unconditional (scheduled rules)
		}
		Tri result;
		if (logic == ConditionLogic.AND) {
			result = anyFalse ? Tri.FALSE : anyUnknown ? Tri.UNKNOWN : Tri.TRUE;
		} else {
			result = anyTrue ? Tri.TRUE : anyUnknown ? Tri.UNKNOWN : Tri.FALSE;
		}
		return new Evaluation(result, details);
	}

	static Tri evaluate(RuleCondition c, Object actual) {
		if (actual == null) {
			return Tri.UNKNOWN;
		}
		if (c.getSensor().isBoolean()) {
			if (c.getBoolValue() == null || !(actual instanceof Boolean b)) {
				return Tri.UNKNOWN;
			}
			boolean same = b.equals(c.getBoolValue());
			return switch (c.getOperator()) {
				case EQ -> of(same);
				case NEQ -> of(!same);
				default -> Tri.UNKNOWN;   // ordering a boolean makes no sense; validation prevents this
			};
		}
		if (c.getNumberValue() == null || !(actual instanceof Number n)) {
			return Tri.UNKNOWN;
		}
		double a = n.doubleValue();
		double e = c.getNumberValue();
		if (Double.isNaN(a) || Double.isInfinite(a)) {
			return Tri.UNKNOWN;
		}
		boolean equal = Math.abs(a - e) < EPSILON;
		return of(switch (c.getOperator()) {
			case GT -> a > e && !equal;
			case GTE -> a > e || equal;
			case LT -> a < e && !equal;
			case LTE -> a < e || equal;
			case EQ -> equal;
			case NEQ -> !equal;
		});
	}

	private static Tri of(boolean b) {
		return b ? Tri.TRUE : Tri.FALSE;
	}
}
