package com.smarthome.automation;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/** One comparison, e.g. "temperature > 30". Exactly one of numberValue / boolValue is set, according to the sensor. */
@Embeddable
public class RuleCondition {

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "sensor", nullable = false, length = 12)
	private Sensor sensor;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "comparison_op", nullable = false, length = 3)
	private Operator operator;

	@Column(name = "number_value")
	private Double numberValue;

	@Column(name = "bool_value")
	private Boolean boolValue;

	protected RuleCondition() {
	}

	public RuleCondition(Sensor sensor, Operator operator, Double numberValue, Boolean boolValue) {
		this.sensor = sensor;
		this.operator = operator;
		this.numberValue = numberValue;
		this.boolValue = boolValue;
	}

	public Sensor getSensor() { return sensor; }
	public Operator getOperator() { return operator; }
	public Double getNumberValue() { return numberValue; }
	public Boolean getBoolValue() { return boolValue; }
}
