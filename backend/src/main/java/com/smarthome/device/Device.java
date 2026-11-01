package com.smarthome.device;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A physical device. {@code deviceId} is its hardware identity and is NOT a secret: knowing it
 * never grants ownership. Ownership is only granted by redeeming a pairing code (see PairingService).
 */
// Update only changed columns, so presence updates from MQTT and renames by the owner can't overwrite each other.
@DynamicUpdate
@Entity
@Table(name = "devices")
public class Device {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "device_id", nullable = false, unique = true, length = 64)
	private String deviceId;

	/** Null while disowned. */
	@Column(name = "owner_user_id")
	private UUID ownerUserId;

	@Column(nullable = false, length = 60)
	private String name;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private DeviceStatus status;

	@Column(name = "registered_at", nullable = false)
	private Instant registeredAt;

	@Column(name = "disowned_at")
	private Instant disownedAt;

	/** SHA-256 hex of the device credential; null when revoked. Never serialized to any API response. */
	@Column(name = "credential_hash", length = 64)
	private String credentialHash;

	/** When the backend last received a live (non-retained) message from the device. Null = never. */
	@Column(name = "last_seen_at")
	private Instant lastSeenAt;

	/** What the device last said about itself (its MQTT status message or Last Will). Null = never. */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "reported_state", length = 10)
	private ReportedState reportedState;

	protected Device() {
	}

	public Device(String deviceId, UUID ownerUserId, String name, Instant now, String credentialHash) {
		this.deviceId = deviceId;
		pair(ownerUserId, name, now, credentialHash);
	}

	/** (Re)activates the device for a new owner with a new credential. */
	public final void pair(UUID ownerUserId, String name, Instant now, String credentialHash) {
		this.ownerUserId = ownerUserId;
		this.name = name;
		this.status = DeviceStatus.ACTIVE;
		this.registeredAt = now;
		this.disownedAt = null;
		this.credentialHash = credentialHash;
		this.lastSeenAt = null;
		this.reportedState = null;
	}

	/** Removes the owner and revokes the credential so the device can no longer authenticate. */
	public void disown(Instant now) {
		this.ownerUserId = null;
		this.status = DeviceStatus.DISOWNED;
		this.disownedAt = now;
		this.credentialHash = null;
		this.lastSeenAt = null;
		this.reportedState = null;
	}

	/** Records proof of life (never moves last-seen backwards). */
	public void markSeen(Instant at, ReportedState state) {
		if (lastSeenAt == null || at.isAfter(lastSeenAt)) {
			lastSeenAt = at;
		}
		reportedState = state;
	}

	/** Records what the device reported without claiming it is alive right now (e.g. a retained or Last Will message). */
	public void reportState(ReportedState state) {
		reportedState = state;
	}

	public void rename(String name) {
		this.name = name;
	}

	public UUID getId() { return id; }
	public String getDeviceId() { return deviceId; }
	public UUID getOwnerUserId() { return ownerUserId; }
	public String getName() { return name; }
	public DeviceStatus getStatus() { return status; }
	public Instant getRegisteredAt() { return registeredAt; }
	public Instant getDisownedAt() { return disownedAt; }
	public String getCredentialHash() { return credentialHash; }
	public Instant getLastSeenAt() { return lastSeenAt; }
	public ReportedState getReportedState() { return reportedState; }
}
