package com.smarthome.command;

/**
 * Lifecycle of a remote command.
 *
 * <pre>
 * PENDING --publish ok--> SENT --device acks applied--> ACKNOWLEDGED
 *    |                      |---device acks rejected--> FAILED
 *    |                      |---device says too late--> EXPIRED
 *    |                      '---no ack by expiry+grace-> UNKNOWN  (may still become ACKNOWLEDGED/FAILED later)
 *    |--publish failed-----------------------------> FAILED
 *    '--expired before it was sent------------------> EXPIRED
 * </pre>
 *
 * UNKNOWN is deliberate honesty: the command was handed to the broker and may or may not have run.
 */
public enum CommandStatus {
	PENDING, SENT, ACKNOWLEDGED, FAILED, EXPIRED, UNKNOWN;

	public boolean isOpen() {
		return this == PENDING || this == SENT;
	}
}
