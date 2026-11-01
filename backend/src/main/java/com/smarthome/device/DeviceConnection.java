package com.smarthome.device;

/** Derived connectivity shown to users. Never stored. */
public enum DeviceConnection {
	/** No live message has ever been received from this device. */
	NEVER_CONNECTED,
	/** Reported online AND a message was received recently. */
	ONLINE,
	/** Reported offline, or has gone silent for longer than the staleness threshold. */
	OFFLINE
}
