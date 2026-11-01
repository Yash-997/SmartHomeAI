package com.smarthome.mqtt;

/** Delivers a command to one device's command topic. */
public interface CommandPublisher {

	/** State of the backend's link to the message broker, as shown on the system status view. */
	enum Link { CONNECTED, DISCONNECTED, DISABLED }

	/**
	 * Publishes {@code json} to {@code home/devices/<deviceId>/commands} (QoS 1, never retained: a retained command
	 * would be replayed to the device on every reconnect). Returns only once the broker has accepted it.
	 *
	 * @throws BrokerAccess.BrokerUnavailableException if it could not be handed to the broker
	 */
	void publish(String deviceId, String json);

	/** Whether commands can currently be delivered. Never blocks and never throws. */
	default Link link() {
		return Link.DISCONNECTED;
	}
}
