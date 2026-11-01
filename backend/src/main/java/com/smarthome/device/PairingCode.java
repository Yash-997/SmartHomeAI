package com.smarthome.device;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A pairing code bound to the user who created it. Only its hash is stored. */
@Entity
@Table(name = "pairing_codes")
public class PairingCode {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "code_hash", nullable = false, unique = true, length = 64)
	private String codeHash;

	@Column(name = "device_name", nullable = false, length = 60)
	private String deviceName;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	protected PairingCode() {
	}

	public PairingCode(UUID userId, String codeHash, String deviceName, Instant createdAt, Instant expiresAt) {
		this.userId = userId;
		this.codeHash = codeHash;
		this.deviceName = deviceName;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public UUID getId() { return id; }
	public UUID getUserId() { return userId; }
	public String getCodeHash() { return codeHash; }
	public String getDeviceName() { return deviceName; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getExpiresAt() { return expiresAt; }
	public Instant getUsedAt() { return usedAt; }
}
