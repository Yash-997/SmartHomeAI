package com.smarthome.automation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * Request and response shapes. Requests contain only plain, allow-listed fields: no owner, no ids of other users'
 * things, nothing executable. Owner and ownership checks always come from the JWT.
 */
public final class AutomationDtos {

	private AutomationDtos() {
	}

	/**
	 * type = TELEMETRY (run on each new reading) or SCHEDULE (run once at {@code time} local time on {@code days},
	 * in {@code timeZone}, an IANA name such as "Asia/Kolkata").
	 */
	public record TriggerRequest(@NotBlank String type, String time, List<String> days, String timeZone) {
	}

	/** sensor: temperature | humidity | motion | gas. operator: > >= < <= == != . value: a number, or true/false for motion. */
	public record ConditionRequest(@NotBlank String sensor, @NotBlank String operator, JsonNode value) {
	}

	/** action: set_pir_led (auto|on|off) or set_gas_led (auto|on). Anything else is refused. */
	public record ActionRequest(@NotBlank String action, @NotBlank String value) {
	}

	public record RuleRequest(
			@NotBlank @Size(max = 60) String name,
			@NotNull UUID deviceId,
			/** Defaults to deviceId. */
			UUID targetDeviceId,
			/** Defaults to true. */
			Boolean enabled,
			@NotNull @Valid TriggerRequest trigger,
			/** AND (default) or OR. */
			String logic,
			List<@Valid ConditionRequest> conditions,
			@NotNull @Valid ActionRequest action,
			/** 0-100, higher wins when rules compete for the same output. Default 10. */
			Integer priority,
			Integer cooldownSeconds) {
	}

	public record EnabledRequest(@NotNull Boolean enabled) {
	}

	public record TriggerResponse(String type, String time, List<String> days, String timeZone) {
	}

	public record ConditionResponse(String sensor, String operator, Object value) {
	}

	public record ActionResponse(String action, String value) {
	}

	public record RuleResponse(UUID id, String name, UUID deviceId, UUID targetDeviceId, boolean enabled,
			TriggerResponse trigger, String logic, List<ConditionResponse> conditions, ActionResponse action,
			int priority, int cooldownSeconds, Instant lastExecutedAt, Instant createdAt, Instant updatedAt) {
	}

	/**
	 * One audit entry. {@code triggerValues} and {@code evaluation} are JSON snapshots. {@code commandStatus} is the
	 * current state of the command this execution created (it keeps changing after the entry was written).
	 */
	public record ExecutionResponse(UUID id, UUID ruleId, String ruleName, UUID deviceId, String triggerType,
			ExecutionOutcome outcome, String reason, String action, String value, JsonNode triggerValues,
			JsonNode evaluation, UUID commandId, String commandStatus, Instant createdAt, Instant lastAt, int repeatCount) {
	}
}
