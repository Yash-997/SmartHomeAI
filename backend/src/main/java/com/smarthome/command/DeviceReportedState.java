package com.smarthome.command;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** What the device itself last said about its outputs. One row per device, replaced on every report. */
@Entity
@Table(name = "device_reported_state")
public class DeviceReportedState {

	@Id
	@Column(name = "device_uuid")
	private UUID deviceUuid;

	@Column(name = "pir_led_mode", nullable = false, length = 10)
	private String pirLedMode;

	@Column(name = "pir_led_on", nullable = false)
	private boolean pirLedOn;

	@Column(name = "gas_led_mode", nullable = false, length = 10)
	private String gasLedMode;

	@Column(name = "gas_led_on", nullable = false)
	private boolean gasLedOn;

	@Column(name = "gas_warning", nullable = false)
	private boolean gasWarning;

	@Column(name = "last_command_id")
	private UUID lastCommandId;

	/** Server clock at receipt (the device's clock is not trusted for freshness). */
	@Column(name = "reported_at", nullable = false)
	private Instant reportedAt;

	protected DeviceReportedState() {
	}

	public DeviceReportedState(UUID deviceUuid) {
		this.deviceUuid = deviceUuid;
	}

	public void update(String pirLedMode, boolean pirLedOn, String gasLedMode, boolean gasLedOn, boolean gasWarning,
			UUID lastCommandId, Instant reportedAt) {
		this.pirLedMode = pirLedMode;
		this.pirLedOn = pirLedOn;
		this.gasLedMode = gasLedMode;
		this.gasLedOn = gasLedOn;
		this.gasWarning = gasWarning;
		this.lastCommandId = lastCommandId;
		this.reportedAt = reportedAt;
	}

	public UUID getDeviceUuid() { return deviceUuid; }
	public String getPirLedMode() { return pirLedMode; }
	public boolean isPirLedOn() { return pirLedOn; }
	public String getGasLedMode() { return gasLedMode; }
	public boolean isGasLedOn() { return gasLedOn; }
	public boolean isGasWarning() { return gasWarning; }
	public UUID getLastCommandId() { return lastCommandId; }
	public Instant getReportedAt() { return reportedAt; }
}
