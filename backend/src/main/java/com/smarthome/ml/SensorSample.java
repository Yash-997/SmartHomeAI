package com.smarthome.ml;

import java.time.Instant;

/** One stored reading of the three analysed sensors. A null value means "no reading", never zero. */
public record SensorSample(Instant at, Double temperature, Double humidity, Integer gas) {
}
