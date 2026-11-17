package com.smarthome.analytics;

import java.util.Locale;

/** The numeric sensors. Gas is the raw MQ-2 ADC value (0-4095), never ppm: there is no calibration to convert it. */
public enum Metric {
	TEMPERATURE("temperature", "°C", 1),
	HUMIDITY("humidity", "%", 1),
	GAS("gas", "raw", 0);

	private final String label;
	private final String unit;
	private final int decimals;

	Metric(String label, String unit, int decimals) {
		this.label = label;
		this.unit = unit;
		this.decimals = decimals;
	}

	public String label() {
		return label;
	}

	public String unit() {
		return unit;
	}

	public int decimals() {
		return decimals;
	}

	/** A value with its unit, rounded the same way everywhere ("23.4 °C", "1830 raw"). */
	public String format(Double v) {
		if (v == null) {
			return "no value";
		}
		return number(v) + (this == GAS ? " (raw)" : " " + unit);
	}

	public String number(double v) {
		return String.format(Locale.ROOT, "%." + decimals + "f", v);
	}

	/** Accepts "temperature", "temp", "humidity", "gas", "smoke", "mq2"... */
	public static Metric parse(String s) {
		String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
		return switch (t) {
			case "temperature", "temp" -> TEMPERATURE;
			case "humidity", "humid" -> HUMIDITY;
			case "gas", "gas_raw", "gasraw", "smoke", "mq2", "mq-2" -> GAS;
			default -> throw new IllegalArgumentException("Unknown metric '" + t.replaceAll("[^a-z0-9_-]", "?") + "'. Use temperature, humidity or gas");
		};
	}
}
