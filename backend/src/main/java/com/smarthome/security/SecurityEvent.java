package com.smarthome.security;

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

/** One entry of the security history. Written only by the security code, read only by its owner. */
@Entity
@Table(name = "security_events")
public class SecurityEvent {

	public enum Kind { MODE_CHANGED, SETTINGS_CHANGED, MOTION_ALERT, DEVICE_OFFLINE, ACTION_TAKEN, ACTION_FAILED }

	public enum Severity { INFO, WARNING, CRITICAL }

	@Id
	private UUID id;

	@Column(name = "owner_user_id", nullable = false, updatable = false)
	private UUID ownerUserId;

	@Column(name = "device_uuid", updatable = false)
	private UUID deviceUuid;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20, updatable = false)
	private Kind kind;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 10, updatable = false)
	private Severity severity;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 8, updatable = false)
	private SecurityMode mode;

	@Column(nullable = false, length = 300, updatable = false)
	private String message;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "acknowledged_at")
	private Instant acknowledgedAt;

	protected SecurityEvent() {
	}

	public SecurityEvent(UUID ownerUserId, UUID deviceUuid, Kind kind, Severity severity, SecurityMode mode, String message, Instant now) {
		this.id = UUID.randomUUID();
		this.ownerUserId = ownerUserId;
		this.deviceUuid = deviceUuid;
		this.kind = kind;
		this.severity = severity;
		this.mode = mode;
		this.message = message.length() > 300 ? message.substring(0, 300) : message;
		this.createdAt = now;
	}

	public void acknowledge(Instant now) {
		if (acknowledgedAt == null) {
			acknowledgedAt = now;
		}
	}

	public UUID getId() {
		return id;
	}

	public UUID getOwnerUserId() {
		return ownerUserId;
	}

	public UUID getDeviceUuid() {
		return deviceUuid;
	}

	public Kind getKind() {
		return kind;
	}

	public Severity getSeverity() {
		return severity;
	}

	public SecurityMode getMode() {
		return mode;
	}

	public String getMessage() {
		return message;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getAcknowledgedAt() {
		return acknowledgedAt;
	}
}
