package com.smarthome.automation;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

/** A user-defined automation rule. Plain data only: nothing here is ever executed as code. */
@Entity
@Table(name = "automation_rules")
public class AutomationRule {

	@Id
	private UUID id;

	@Column(name = "owner_user_id", nullable = false, updatable = false)
	private UUID ownerUserId;

	/** Device whose readings are evaluated. */
	@Column(name = "device_uuid", nullable = false)
	private UUID deviceUuid;

	/** Device that receives the action. */
	@Column(name = "target_device_uuid", nullable = false)
	private UUID targetDeviceUuid;

	@Column(nullable = false, length = 60)
	private String name;

	@Column(nullable = false)
	private boolean enabled;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "trigger_type", nullable = false, length = 10)
	private TriggerType triggerType;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "condition_logic", nullable = false, length = 3)
	private ConditionLogic logic;

	@Column(nullable = false, length = 30)
	private String action;

	@Column(name = "action_value", nullable = false, length = 10)
	private String actionValue;

	@Column(nullable = false)
	private int priority;

	@Column(name = "cooldown_seconds", nullable = false)
	private int cooldownSeconds;

	@Column(name = "schedule_time")
	private LocalTime scheduleTime;

	/** Comma separated three-letter day codes, e.g. "MON,TUE" (at most 27 characters for all seven). */
	@Column(name = "schedule_days", length = 40)
	private String scheduleDays;

	@Column(name = "time_zone", length = 50)
	private String timeZone;

	@Column(name = "last_executed_at")
	private Instant lastExecutedAt;

	@Column(name = "last_scheduled_on")
	private LocalDate lastScheduledOn;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "automation_conditions", joinColumns = @JoinColumn(name = "rule_id"))
	@OrderColumn(name = "cond_order")
	private List<RuleCondition> conditions = new ArrayList<>();

	protected AutomationRule() {
	}

	public AutomationRule(UUID id, UUID ownerUserId, Instant now) {
		this.id = id;
		this.ownerUserId = ownerUserId;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** Replaces everything the user can edit. Validation has already happened in AutomationValidator. */
	public void apply(String name, boolean enabled, UUID deviceUuid, UUID targetDeviceUuid, TriggerType triggerType,
			LocalTime scheduleTime, Set<DayOfWeek> days, String timeZone, ConditionLogic logic,
			List<RuleCondition> conditions, String action, String actionValue, int priority, int cooldownSeconds, Instant now) {
		this.name = name;
		this.enabled = enabled;
		this.deviceUuid = deviceUuid;
		this.targetDeviceUuid = targetDeviceUuid;
		this.triggerType = triggerType;
		this.scheduleTime = triggerType == TriggerType.SCHEDULE ? scheduleTime : null;
		this.scheduleDays = triggerType == TriggerType.SCHEDULE
				? days.stream().sorted().map(AutomationRule::dayCode).collect(Collectors.joining(","))
				: null;
		this.timeZone = triggerType == TriggerType.SCHEDULE ? timeZone : null;
		this.logic = logic;
		this.conditions.clear();
		this.conditions.addAll(conditions);
		this.action = action;
		this.actionValue = actionValue;
		this.priority = priority;
		this.cooldownSeconds = cooldownSeconds;
		this.updatedAt = now;
	}

	static String dayCode(DayOfWeek d) {
		return d.name().substring(0, 3);
	}

	public void setEnabled(boolean enabled, Instant now) {
		this.enabled = enabled;
		this.updatedAt = now;
	}

	public Set<DayOfWeek> days() {
		Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
		if (scheduleDays != null && !scheduleDays.isBlank()) {
			for (String d : scheduleDays.split(",")) {
				for (DayOfWeek candidate : DayOfWeek.values()) {
					if (dayCode(candidate).equals(d.trim())) {
						days.add(candidate);
					}
				}
			}
		}
		return days;
	}

	public UUID getId() { return id; }
	public UUID getOwnerUserId() { return ownerUserId; }
	public UUID getDeviceUuid() { return deviceUuid; }
	public UUID getTargetDeviceUuid() { return targetDeviceUuid; }
	public String getName() { return name; }
	public boolean isEnabled() { return enabled; }
	public TriggerType getTriggerType() { return triggerType; }
	public ConditionLogic getLogic() { return logic; }
	public String getAction() { return action; }
	public String getActionValue() { return actionValue; }
	public int getPriority() { return priority; }
	public int getCooldownSeconds() { return cooldownSeconds; }
	public LocalTime getScheduleTime() { return scheduleTime; }
	public String getScheduleDays() { return scheduleDays; }
	public String getTimeZone() { return timeZone; }
	public Instant getLastExecutedAt() { return lastExecutedAt; }
	public LocalDate getLastScheduledOn() { return lastScheduledOn; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
	public List<RuleCondition> getConditions() { return List.copyOf(conditions); }
}
