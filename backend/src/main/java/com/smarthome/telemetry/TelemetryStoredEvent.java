package com.smarthome.telemetry;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after a new, valid, non-duplicate telemetry message has been committed. The automation engine listens for
 * it. It carries exactly the values that arrived (null = that sensor sent nothing), so rules are evaluated against the
 * message that triggered them and a missing value can never be filled in with an older or default one.
 */
public record TelemetryStoredEvent(UUID deviceUuid, Instant receivedAt, Double temperature, Double humidity,
		Boolean motion, Integer gasRaw) {
}
