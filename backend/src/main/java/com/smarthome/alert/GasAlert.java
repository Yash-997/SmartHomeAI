package com.smarthome.alert;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One gas/smoke incident and the delivery status of its two emails. Written only by {@link GasAlertService}. */
@Entity
@Table(name = "gas_alerts")
public class GasAlert {

	public enum State { ACTIVE, RECOVERED }

	public enum Delivery { PENDING, SENT, FAILED, SKIPPED }

	@Id
	private UUID id;

	@Column(name = "owner_user_id", nullable = false, updatable = false)
	private UUID ownerUserId;

	@Column(name = "device_uuid", nullable = false, updatable = false)
	private UUID deviceUuid;

	@Column(name = "device_name", nullable = false, updatable = false, length = 100)
	private String deviceName;

	@Column(nullable = false, updatable = false)
	private int threshold;

	@Column(name = "recovery_threshold", nullable = false, updatable = false)
	private int recoveryThreshold;

	@Column(name = "trigger_gas_raw", nullable = false, updatable = false)
	private int triggerGasRaw;

	@Column(name = "peak_gas_raw", nullable = false)
	private int peakGasRaw;

	@Column(name = "last_gas_raw", nullable = false)
	private int lastGasRaw;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 10)
	private State state;

	@Column(name = "started_at", nullable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "recovered_at")
	private Instant recoveredAt;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "alert_email_status", nullable = false, length = 8)
	private Delivery alertEmailStatus;

	@Column(name = "alert_email_sent_at")
	private Instant alertEmailSentAt;

	@Column(name = "alert_email_error", length = 200)
	private String alertEmailError;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "recovery_email_status", length = 8)
	private Delivery recoveryEmailStatus;

	@Column(name = "recovery_email_sent_at")
	private Instant recoveryEmailSentAt;

	@Column(name = "recovery_email_error", length = 200)
	private String recoveryEmailError;

	protected GasAlert() {
	}

	public GasAlert(UUID ownerUserId, UUID deviceUuid, String deviceName, int threshold, int recoveryThreshold, int gasRaw, Instant now) {
		this.id = UUID.randomUUID();
		this.ownerUserId = ownerUserId;
		this.deviceUuid = deviceUuid;
		this.deviceName = deviceName.length() > 100 ? deviceName.substring(0, 100) : deviceName;
		this.threshold = threshold;
		this.recoveryThreshold = recoveryThreshold;
		this.triggerGasRaw = gasRaw;
		this.peakGasRaw = gasRaw;
		this.lastGasRaw = gasRaw;
		this.state = State.ACTIVE;
		this.startedAt = now;
		this.alertEmailStatus = Delivery.PENDING;
	}

	public void observe(int gasRaw) {
		this.lastGasRaw = gasRaw;
		this.peakGasRaw = Math.max(peakGasRaw, gasRaw);
	}

	public void recover(Instant now) {
		this.state = State.RECOVERED;
		this.recoveredAt = now;
		this.recoveryEmailStatus = Delivery.PENDING;
	}

	public void alertDelivered(Delivery status, Instant now, String error) {
		this.alertEmailStatus = status;
		this.alertEmailSentAt = status == Delivery.SENT ? now : null;
		this.alertEmailError = error;
	}

	public void recoveryDelivered(Delivery status, Instant now, String error) {
		this.recoveryEmailStatus = status;
		this.recoveryEmailSentAt = status == Delivery.SENT ? now : null;
		this.recoveryEmailError = error;
	}

	public UUID getId() { return id; }
	public UUID getOwnerUserId() { return ownerUserId; }
	public UUID getDeviceUuid() { return deviceUuid; }
	public String getDeviceName() { return deviceName; }
	public int getThreshold() { return threshold; }
	public int getRecoveryThreshold() { return recoveryThreshold; }
	public int getTriggerGasRaw() { return triggerGasRaw; }
	public int getPeakGasRaw() { return peakGasRaw; }
	public int getLastGasRaw() { return lastGasRaw; }
	public State getState() { return state; }
	public Instant getStartedAt() { return startedAt; }
	public Instant getRecoveredAt() { return recoveredAt; }
	public Delivery getAlertEmailStatus() { return alertEmailStatus; }
	public Instant getAlertEmailSentAt() { return alertEmailSentAt; }
	public String getAlertEmailError() { return alertEmailError; }
	public Delivery getRecoveryEmailStatus() { return recoveryEmailStatus; }
	public Instant getRecoveryEmailSentAt() { return recoveryEmailSentAt; }
	public String getRecoveryEmailError() { return recoveryEmailError; }
}
