package com.smarthome.telemetry;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a registered device sent a message that was refused because one sensor value was outside the accepted
 * range. The message itself is not stored; this event lets the anomaly detector record that the sensor misbehaved.
 * {@code sensor} is the payload field name: temperature, humidity or gasRaw.
 */
public record SensorRejectedEvent(UUID deviceUuid, String sensor, double value, Instant at) {
}
