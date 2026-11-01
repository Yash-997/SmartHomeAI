package com.smarthome.automation;

import java.util.Arrays;
import java.util.Optional;

/** The sensor readings a rule may look at. Gas is the raw MQ-2 ADC value (0-4095), never ppm. */
public enum Sensor {
	TEMPERATURE("temperature", false, -40, 125),
	HUMIDITY("humidity", false, 0, 100),
	MOTION("motion", true, 0, 1),
	GAS("gas", false, 0, 4095);

	private final String id;
	private final boolean bool;
	private final double min;
	private final double max;

	Sensor(String id, boolean bool, double min, double max) {
		this.id = id;
		this.bool = bool;
		this.min = min;
		this.max = max;
	}

	/** The name used in JSON. */
	public String id() { return id; }
	public boolean isBoolean() { return bool; }
	public double min() { return min; }
	public double max() { return max; }

	public static Optional<Sensor> fromId(String id) {
		return Arrays.stream(values()).filter(s -> s.id.equals(id)).findFirst();
	}
}
