package com.smarthome.device;

/** Lifecycle of a device record. Whether it is currently reachable is a separate question: DeviceConnection. */
public enum DeviceStatus {
	/** Paired to an owner and holding a valid credential. Says nothing about connectivity (see DeviceConnection). */
	ACTIVE,
	/** Ownership removed and credential revoked. The device must be paired again with a fresh code. */
	DISOWNED
}
