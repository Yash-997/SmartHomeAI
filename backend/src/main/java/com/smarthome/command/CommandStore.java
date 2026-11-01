package com.smarthome.command;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * All command state changes, each in its own short transaction. They are separate from CommandService on purpose:
 * the PENDING row must be COMMITTED before the message is published, otherwise a fast device acknowledgement could
 * arrive before the row is visible and be lost.
 *
 * Every transition is a guarded UPDATE ("only if still in state X"), so concurrent events (an ack racing the
 * "sent" marker, or the timeout sweeper) can never move a command backwards or overwrite a final result.
 */
@Component
public class CommandStore {

	private static final EnumSet<CommandStatus> UNFINISHED = EnumSet.of(CommandStatus.PENDING, CommandStatus.SENT, CommandStatus.UNKNOWN);

	private final DeviceCommandRepository commands;

	public CommandStore(DeviceCommandRepository commands) {
		this.commands = commands;
	}

	@Transactional
	public DeviceCommand createPending(UUID deviceUuid, UUID requestedBy, String action, String value, Instant now, Instant expiresAt) {
		return createPending(deviceUuid, requestedBy, action, value, now, expiresAt, null);
	}

	@Transactional
	public DeviceCommand createPending(UUID deviceUuid, UUID requestedBy, String action, String value, Instant now, Instant expiresAt,
			String origin) {
		return commands.saveAndFlush(new DeviceCommand(UUID.randomUUID(), deviceUuid, requestedBy, action, value, now, expiresAt, origin));
	}

	/** No-op if an acknowledgement already moved the command on. */
	@Transactional
	public void markSent(UUID id, Instant now) {
		commands.markSent(id, CommandStatus.PENDING, CommandStatus.SENT, now);
	}

	@Transactional
	public void markFailed(UUID id, UUID deviceUuid, String detail, Instant now) {
		commands.transition(id, deviceUuid, EnumSet.of(CommandStatus.PENDING, CommandStatus.SENT), CommandStatus.FAILED, now, detail);
	}

	/**
	 * Applies a result reported by the device. Works from PENDING/SENT, and from UNKNOWN so a late acknowledgement
	 * still resolves the doubt. Returns false if the command is unknown, belongs to another device, or is already final.
	 */
	@Transactional
	public boolean applyDeviceResult(UUID deviceUuid, UUID id, CommandStatus to, String detail, Instant now) {
		return commands.transition(id, deviceUuid, UNFINISHED, to, now, detail) == 1;
	}

	/** Expires never-sent commands and flags sent-but-unacknowledged ones as UNKNOWN. Returns how many changed. */
	@Transactional
	public int sweep(Instant now, Duration unknownGrace) {
		int expired = commands.expirePending(CommandStatus.PENDING, CommandStatus.EXPIRED, now, "expired before it could be sent");
		int unknown = commands.markUnknown(CommandStatus.SENT, CommandStatus.UNKNOWN, now, now.minus(unknownGrace),
				"no acknowledgement from the device; it may or may not have run");
		return expired + unknown;
	}
}
