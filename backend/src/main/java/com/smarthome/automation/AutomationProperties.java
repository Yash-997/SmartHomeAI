package com.smarthome.automation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Tunables for the automation engine (prefix app.automation). Every value has a safe default. */
@ConfigurationProperties(prefix = "app.automation")
public record AutomationProperties(
		/** Shortest cooldown a rule may be given. Stops a rule from hammering a device. */
		@DefaultValue("5") int minCooldownSeconds,
		@DefaultValue("86400") int maxCooldownSeconds,
		@DefaultValue("60") int defaultCooldownSeconds,
		@DefaultValue("50") int maxRulesPerUser,
		@DefaultValue("10") int maxConditionsPerRule,
		/** At most this many automation commands per target device per minute (loop / runaway protection). */
		@DefaultValue("6") int maxCommandsPerDevicePerMinute,
		/** At most this many actions from a single telemetry message or schedule tick. */
		@DefaultValue("10") int maxActionsPerEvent,
		/** How often the scheduler looks for due schedules. */
		@DefaultValue("15000") long scheduleTickMs,
		/** A schedule more than this long overdue is recorded as missed instead of being run late. */
		@DefaultValue("300") int scheduleCatchupSeconds,
		/** History rows kept per rule; older ones are pruned. */
		@DefaultValue("200") int historyPerRule,
		/** An identical skip within this window is folded into the previous history row. */
		@DefaultValue("300") int historyCompactSeconds,
		/** Pending telemetry evaluations that may queue up before new ones are dropped. */
		@DefaultValue("1000") int queueCapacity) {
}
