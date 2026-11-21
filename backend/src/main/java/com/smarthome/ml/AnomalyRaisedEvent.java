package com.smarthome.ml;

import java.util.UUID;

/**
 * Published by {@link AnomalyService} right after an anomaly row is saved: when a new anomaly is recorded, and when an
 * ongoing one gets worse (higher severity). Listeners (the anomaly email) must only queue work, never block.
 */
public record AnomalyRaisedEvent(UUID anomalyId, UUID deviceUuid, AnomalySeverity severity, boolean newAnomaly) {
}
