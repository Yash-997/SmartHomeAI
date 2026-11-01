package com.smarthome.telemetry;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One accepted telemetry message. Any reading may be null: that sensor was missing or failed. */
@Entity
@Table(name = "telemetry_readings")
public class TelemetryReading {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "device_uuid", nullable = false)
	private UUID deviceUuid;

	/** Server clock when the message arrived. This, not the device's clock, drives freshness decisions. */
	@Column(name = "received_at", nullable = false)
	private Instant receivedAt;

	/** The device's own timestamp, if it sent a plausible one. Informational only. */
	@Column(name = "device_timestamp")
	private Instant deviceTimestamp;

	@Column(nullable = false)
	private long seq;

	@Column(name = "temperature_c")
	private Double temperatureC;

	@Column(name = "humidity_pct")
	private Double humidityPct;

	private Boolean motion;

	/** Raw MQ-2 ADC value. Not converted to ppm: that requires calibration we don't have. */
	@Column(name = "gas_raw")
	private Integer gasRaw;

	@Column(name = "dedupe_key", nullable = false, length = 64)
	private String dedupeKey;

	protected TelemetryReading() {
	}

	public TelemetryReading(UUID deviceUuid, Instant receivedAt, Instant deviceTimestamp, long seq,
			Double temperatureC, Double humidityPct, Boolean motion, Integer gasRaw, String dedupeKey) {
		this.deviceUuid = deviceUuid;
		this.receivedAt = receivedAt;
		this.deviceTimestamp = deviceTimestamp;
		this.seq = seq;
		this.temperatureC = temperatureC;
		this.humidityPct = humidityPct;
		this.motion = motion;
		this.gasRaw = gasRaw;
		this.dedupeKey = dedupeKey;
	}

	public Long getId() { return id; }
	public UUID getDeviceUuid() { return deviceUuid; }
	public Instant getReceivedAt() { return receivedAt; }
	public Instant getDeviceTimestamp() { return deviceTimestamp; }
	public long getSeq() { return seq; }
	public Double getTemperatureC() { return temperatureC; }
	public Double getHumidityPct() { return humidityPct; }
	public Boolean getMotion() { return motion; }
	public Integer getGasRaw() { return gasRaw; }
	public String getDedupeKey() { return dedupeKey; }
}
