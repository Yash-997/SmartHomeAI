package com.smarthome.ml;

import java.time.Instant;

/** One stored telemetry message with every sensor the anomaly model uses. Any value may be null (not sent). */
public record TelemetryPoint(Instant at, Double temperature, Double humidity, Integer gas, Boolean motion) {
}
