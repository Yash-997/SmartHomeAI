package com.smarthome.ml;

/** The analysed sensor values. {@code scope} is the name used in the API and in the database. */
public enum Metric {
	TEMPERATURE("temperature", "°C"),
	HUMIDITY("humidity", "%"),
	GAS("gasRaw", "raw");

	private final String scope;
	private final String unit;

	Metric(String scope, String unit) {
		this.scope = scope;
		this.unit = unit;
	}

	public String scope() { return scope; }
	public String unit() { return unit; }

	/** The value of this metric in a sample, or null if the sensor sent nothing. */
	public Double of(SensorSample s) {
		return switch (this) {
			case TEMPERATURE -> s.temperature();
			case HUMIDITY -> s.humidity();
			case GAS -> s.gas() == null ? null : s.gas().doubleValue();
		};
	}

	public double minScale(MlProperties.Anomaly cfg) {
		return switch (this) {
			case TEMPERATURE -> cfg.minScaleTemperature();
			case HUMIDITY -> cfg.minScaleHumidity();
			case GAS -> cfg.minScaleGas();
		};
	}
}
