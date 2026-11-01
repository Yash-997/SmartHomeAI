package com.smarthome.command;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request/response shapes for commands. The request deliberately contains no owner, device or authorization fields. */
public final class CommandDtos {

	private CommandDtos() {
	}

	public record CreateCommandRequest(@NotBlank @Size(max = 30) String action, @NotBlank @Size(max = 10) String value) {
	}

	public record CommandResponse(UUID id, String action, String value, CommandStatus status, Instant createdAt,
			Instant expiresAt, Instant sentAt, Instant completedAt, String detail, String origin) {

		public static CommandResponse from(DeviceCommand c) {
			return new CommandResponse(c.getId(), c.getAction(), c.getValue(), c.getStatus(), c.getCreatedAt(),
					c.getExpiresAt(), c.getSentAt(), c.getCompletedAt(), c.getDetail(), c.getOrigin());
		}
	}

	/** A controllable output and the values it accepts, so the UI never hard-codes them. */
	public record OutputResponse(String action, String label, List<String> values) {
	}

	public record LedState(String mode, boolean on) {
	}

	/** What the device itself reports (not what we asked for). {@code stale} = reported too long ago to be current. */
	public record ReportedStateResponse(LedState pirLed, LedState gasLed, boolean gasWarning, UUID lastCommandId,
			Instant reportedAt, long ageSeconds, boolean stale) {
	}
}
