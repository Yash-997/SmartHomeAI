package com.smarthome.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.smarthome.automation.ConditionEvaluator.Evaluation;
import com.smarthome.automation.ConditionEvaluator.Reading;
import com.smarthome.automation.ConditionEvaluator.Tri;

/** Pure logic: no Spring, no database. */
class ConditionEvaluatorTest {

	private static RuleCondition num(Sensor s, Operator op, double v) {
		return new RuleCondition(s, op, v, null);
	}

	private static RuleCondition bool(Operator op, boolean v) {
		return new RuleCondition(Sensor.MOTION, op, null, v);
	}

	private static Tri eval(RuleCondition c, Reading r) {
		return ConditionEvaluator.evaluate(ConditionLogic.AND, List.of(c), r).result();
	}

	private static final Reading R = new Reading(30.0, 55.0, true, 1500);

	@Test
	void everyNumericOperatorOnTemperature() {
		assertEquals(Tri.TRUE, eval(num(Sensor.TEMPERATURE, Operator.GT, 29.9), R));
		assertEquals(Tri.FALSE, eval(num(Sensor.TEMPERATURE, Operator.GT, 30.0), R));
		assertEquals(Tri.TRUE, eval(num(Sensor.TEMPERATURE, Operator.GTE, 30.0), R));
		assertEquals(Tri.FALSE, eval(num(Sensor.TEMPERATURE, Operator.GTE, 30.1), R));
		assertEquals(Tri.TRUE, eval(num(Sensor.TEMPERATURE, Operator.LT, 30.1), R));
		assertEquals(Tri.FALSE, eval(num(Sensor.TEMPERATURE, Operator.LT, 30.0), R));
		assertEquals(Tri.TRUE, eval(num(Sensor.TEMPERATURE, Operator.LTE, 30.0), R));
		assertEquals(Tri.FALSE, eval(num(Sensor.TEMPERATURE, Operator.LTE, 29.9), R));
		assertEquals(Tri.TRUE, eval(num(Sensor.TEMPERATURE, Operator.EQ, 30.0), R));
		assertEquals(Tri.FALSE, eval(num(Sensor.TEMPERATURE, Operator.EQ, 30.5), R));
		assertEquals(Tri.TRUE, eval(num(Sensor.TEMPERATURE, Operator.NEQ, 30.5), R));
		assertEquals(Tri.FALSE, eval(num(Sensor.TEMPERATURE, Operator.NEQ, 30.0), R));
	}

	@Test
	void humidityAndGasUseTheirOwnReadings() {
		assertEquals(Tri.TRUE, eval(num(Sensor.HUMIDITY, Operator.GTE, 55), R));
		assertEquals(Tri.TRUE, eval(num(Sensor.GAS, Operator.GT, 1499), R));
		assertEquals(Tri.FALSE, eval(num(Sensor.GAS, Operator.GT, 1500), R));
		assertEquals(Tri.TRUE, eval(num(Sensor.GAS, Operator.EQ, 1500), R));
	}

	@Test
	void motionIsABooleanComparison() {
		assertEquals(Tri.TRUE, eval(bool(Operator.EQ, true), R));
		assertEquals(Tri.FALSE, eval(bool(Operator.EQ, false), R));
		assertEquals(Tri.TRUE, eval(bool(Operator.NEQ, false), R));
		Reading still = new Reading(30.0, 55.0, false, 1500);
		assertEquals(Tri.TRUE, eval(bool(Operator.EQ, false), still));
		assertEquals(Tri.FALSE, eval(bool(Operator.EQ, true), still));
	}

	@Test
	void aMissingReadingIsUnknownNeverZero() {
		Reading nothing = new Reading(null, null, null, null);
		// If a missing temperature were treated as 0, each of these would be TRUE. They must all be UNKNOWN.
		assertEquals(Tri.UNKNOWN, eval(num(Sensor.TEMPERATURE, Operator.LT, 5), nothing));
		assertEquals(Tri.UNKNOWN, eval(num(Sensor.TEMPERATURE, Operator.LTE, 0), nothing));
		assertEquals(Tri.UNKNOWN, eval(num(Sensor.TEMPERATURE, Operator.EQ, 0), nothing));
		assertEquals(Tri.UNKNOWN, eval(num(Sensor.HUMIDITY, Operator.NEQ, 100), nothing));
		assertEquals(Tri.UNKNOWN, eval(num(Sensor.GAS, Operator.LT, 100), nothing));
		assertEquals(Tri.UNKNOWN, eval(bool(Operator.EQ, false), nothing));   // missing motion is not "no motion"
		assertEquals(Tri.UNKNOWN, eval(bool(Operator.NEQ, true), nothing));
	}

	@Test
	void notANumberIsUnknown() {
		assertEquals(Tri.UNKNOWN, eval(num(Sensor.TEMPERATURE, Operator.LT, 5), new Reading(Double.NaN, null, null, null)));
		assertEquals(Tri.UNKNOWN, eval(num(Sensor.TEMPERATURE, Operator.GT, 5), new Reading(Double.POSITIVE_INFINITY, null, null, null)));
	}

	@Test
	void equalityToleratesFloatingPointNoise() {
		assertEquals(Tri.TRUE, eval(num(Sensor.TEMPERATURE, Operator.EQ, 0.3), new Reading(0.1 + 0.2, null, null, null)));
	}

	@Test
	void andOrTruthTableWithUnknown() {
		RuleCondition t = num(Sensor.TEMPERATURE, Operator.GT, 0);        // TRUE for R
		RuleCondition f = num(Sensor.TEMPERATURE, Operator.GT, 100);      // FALSE for R
		RuleCondition u = num(Sensor.GAS, Operator.GT, 0);                // UNKNOWN when gas is missing
		Reading noGas = new Reading(30.0, 55.0, true, null);

		assertEquals(Tri.TRUE, run(ConditionLogic.AND, noGas, t, t));
		assertEquals(Tri.FALSE, run(ConditionLogic.AND, noGas, t, f));
		assertEquals(Tri.FALSE, run(ConditionLogic.AND, noGas, f, u));    // a FALSE settles AND even with UNKNOWN
		assertEquals(Tri.UNKNOWN, run(ConditionLogic.AND, noGas, t, u));  // never TRUE with something unknown

		assertEquals(Tri.TRUE, run(ConditionLogic.OR, noGas, t, f));
		assertEquals(Tri.TRUE, run(ConditionLogic.OR, noGas, t, u));      // a TRUE settles OR even with UNKNOWN
		assertEquals(Tri.FALSE, run(ConditionLogic.OR, noGas, f, f));
		assertEquals(Tri.UNKNOWN, run(ConditionLogic.OR, noGas, f, u));   // could still have been true
	}

	@Test
	void noConditionsIsUnconditionalForScheduledRules() {
		assertEquals(Tri.TRUE, ConditionEvaluator.evaluate(ConditionLogic.AND, List.of(), new Reading(null, null, null, null)).result());
	}

	@Test
	void detailsReportEachConditionWithItsActualValue() {
		Evaluation ev = ConditionEvaluator.evaluate(ConditionLogic.AND,
				List.of(num(Sensor.TEMPERATURE, Operator.GT, 25), num(Sensor.GAS, Operator.LT, 10)), new Reading(31.5, null, null, null));
		assertEquals(2, ev.details().size());
		assertEquals(31.5, ev.details().get(0).actual());
		assertEquals(Tri.TRUE, ev.details().get(0).result());
		assertEquals(null, ev.details().get(1).actual());
		assertEquals(Tri.UNKNOWN, ev.details().get(1).result());
	}

	private static Tri run(ConditionLogic logic, Reading r, RuleCondition... cs) {
		return ConditionEvaluator.evaluate(logic, List.of(cs), r).result();
	}
}
