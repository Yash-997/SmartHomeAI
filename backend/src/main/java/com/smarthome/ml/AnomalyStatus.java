package com.smarthome.ml;

/**
 * Lifecycle of an anomaly. OPEN and ACKNOWLEDGED are "active" (the same problem keeps updating the same row).
 * RESOLVED and DISMISSED are set by the user; AUTO_RESOLVED when the system sees the condition has ended.
 */
public enum AnomalyStatus {
	OPEN, ACKNOWLEDGED, RESOLVED, DISMISSED, AUTO_RESOLVED;

	public boolean isActive() {
		return this == OPEN || this == ACKNOWLEDGED;
	}
}
