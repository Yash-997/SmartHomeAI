package com.smarthome.mqtt;

/**
 * Controls which devices may use the MQTT broker. The broker is told about a device when it is paired and
 * forgets it (and drops its live connection) when it is disowned.
 */
public interface BrokerAccess {

	/** Lets {@code deviceId} connect with {@code credential} and publish only under its own topics. Idempotent. */
	void provisionDevice(String deviceId, String credential);

	/** Disconnects the device and removes its account. Safe to call for a device the broker doesn't know. */
	void revokeDevice(String deviceId);

	/** The broker could not be reached or refused the request; the operation did NOT happen. */
	class BrokerUnavailableException extends RuntimeException {
		public BrokerUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}

		public BrokerUnavailableException(String message) {
			super(message);
		}
	}
}
