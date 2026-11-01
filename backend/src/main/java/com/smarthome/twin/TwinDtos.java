package com.smarthome.twin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.smarthome.command.CommandStatus;
import com.smarthome.device.DeviceConnection;

/** The digital twin: what we asked for, what the device says, and whether they agree. */
public final class TwinDtos {

	private TwinDtos() {
	}

	/**
	 * NO_DESIRED: nothing was ever confirmed, so there is nothing to compare. IN_SYNC: the device reports the last confirmed
	 * request. PENDING: a command is on its way. DRIFT: the device reports something else (rebooted, local override...).
	 * UNREPORTED: the device has not reported its state recently, so we cannot say. UNKNOWN: the last command's outcome
	 * is unknown (sent, never acknowledged).
	 */
	public enum SyncState { NO_DESIRED, IN_SYNC, PENDING, DRIFT, UNREPORTED, UNKNOWN }

	/**
	 * {@code desired} is the value of the newest ACKNOWLEDGED command (what the device confirmed it was told), with who asked
	 * ({@code desiredBy}: "user", "automation:rule", "security:away"...). {@code requested} is a command still in flight.
	 */
	public record OutputTwin(String action, String label, String desired, String desiredBy, Instant desiredAt, String requested,
			String reportedMode, Boolean reportedLit, Instant reportedAt, boolean reportedStale, SyncState sync, String note) {
	}

	public record PendingCommand(UUID id, String action, String value, CommandStatus status, Instant createdAt, Instant expiresAt, String origin) {
	}

	/**
	 * {@code gasWarningActive} is what the device last reported, nothing more: the warning itself is raised and kept by the
	 * firmware, not by this backend.
	 */
	public record TwinResponse(UUID deviceId, String name, DeviceConnection connection, Instant lastSeenAt, Long lastSeenAgeSeconds,
			Instant lastReadingAt, Long lastReadingAgeSeconds, boolean readingStale, Boolean gasWarningActive, List<OutputTwin> outputs,
			List<PendingCommand> pendingCommands, int problemCommands24h, String summary, Instant computedAt) {
	}
}
