package com.smarthome.automation;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One audit-trail entry: a rule was evaluated and either acted or was deliberately held back (see outcome/reason). */
@Entity
@Table(name = "automation_executions")
public class AutomationExecution {

	@Id
	private UUID id;

	@Column(name = "rule_id", nullable = false)
	private UUID ruleId;

	@Column(name = "owner_user_id", nullable = false)
	private UUID ownerUserId;

	@Column(name = "device_uuid", nullable = false)
	private UUID deviceUuid;

	@Column(name = "rule_name", nullable = false, length = 60)
	private String ruleName;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "trigger_type", nullable = false, length = 10)
	private TriggerType triggerType;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 30)
	private ExecutionOutcome outcome;

	@Column(length = 200)
	private String reason;

	@Column(nullable = false, length = 30)
	private String action;

	@Column(name = "action_value", nullable = false, length = 10)
	private String actionValue;

	@Column(name = "trigger_values", length = 1000)
	private String triggerValues;

	@Column(length = 2000)
	private String evaluation;

	@Column(name = "command_id")
	private UUID commandId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "last_at", nullable = false)
	private Instant lastAt;

	@Column(name = "repeat_count", nullable = false)
	private int repeatCount;

	protected AutomationExecution() {
	}

	public AutomationExecution(AutomationRule rule, ExecutionOutcome outcome, String reason, String triggerValues,
			String evaluation, UUID commandId, Instant now) {
		this.id = UUID.randomUUID();
		this.ruleId = rule.getId();
		this.ownerUserId = rule.getOwnerUserId();
		this.deviceUuid = rule.getTargetDeviceUuid();
		this.ruleName = rule.getName();
		this.triggerType = rule.getTriggerType();
		this.outcome = outcome;
		this.reason = truncate(reason, 200);
		this.action = rule.getAction();
		this.actionValue = rule.getActionValue();
		this.triggerValues = truncate(triggerValues, 1000);
		this.evaluation = truncate(evaluation, 2000);
		this.commandId = commandId;
		this.createdAt = now;
		this.lastAt = now;
		this.repeatCount = 1;
	}

	/** The same thing happened again: fold it into this row. */
	public void repeat(String triggerValues, String evaluation, Instant now) {
		this.repeatCount++;
		this.lastAt = now;
		this.triggerValues = truncate(triggerValues, 1000);
		this.evaluation = truncate(evaluation, 2000);
	}

	private static String truncate(String s, int max) {
		return s == null || s.length() <= max ? s : s.substring(0, max);
	}

	public UUID getId() { return id; }
	public UUID getRuleId() { return ruleId; }
	public UUID getOwnerUserId() { return ownerUserId; }
	public UUID getDeviceUuid() { return deviceUuid; }
	public String getRuleName() { return ruleName; }
	public TriggerType getTriggerType() { return triggerType; }
	public ExecutionOutcome getOutcome() { return outcome; }
	public String getReason() { return reason; }
	public String getAction() { return action; }
	public String getActionValue() { return actionValue; }
	public String getTriggerValues() { return triggerValues; }
	public String getEvaluation() { return evaluation; }
	public UUID getCommandId() { return commandId; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getLastAt() { return lastAt; }
	public int getRepeatCount() { return repeatCount; }
}
