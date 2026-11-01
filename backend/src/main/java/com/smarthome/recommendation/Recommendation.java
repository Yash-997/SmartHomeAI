package com.smarthome.recommendation;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A proposed change with the evidence behind it. It does nothing until its owner approves it. */
@Entity
@Table(name = "recommendations")
public class Recommendation {

	public static final String CREATE_RULE = "CREATE_RULE";
	public static final String DISABLE_RULE = "DISABLE_RULE";
	public static final String PENDING = "PENDING";
	public static final String APPLIED = "APPLIED";
	public static final String DISMISSED = "DISMISSED";
	public static final String STALE = "STALE";

	@Id
	private UUID id;

	@Column(name = "owner_user_id", nullable = false, updatable = false)
	private UUID ownerUserId;

	@Column(name = "device_uuid", updatable = false)
	private UUID deviceUuid;

	@Column(name = "action_type", nullable = false, length = 12, updatable = false)
	private String actionType;

	@Column(nullable = false, length = 120, updatable = false)
	private String fingerprint;

	@Column(nullable = false, length = 160, updatable = false)
	private String title;

	@Column(nullable = false, length = 1000, updatable = false)
	private String rationale;

	@Column(nullable = false, length = 2000, updatable = false)
	private String evidence;

	@Column(nullable = false, length = 4000, updatable = false)
	private String payload;

	@Column(nullable = false, length = 10)
	private String status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(name = "created_rule_id")
	private UUID createdRuleId;

	protected Recommendation() {
	}

	public Recommendation(UUID ownerUserId, UUID deviceUuid, String actionType, String fingerprint, String title, String rationale,
			String evidence, String payload, Instant now) {
		this.id = UUID.randomUUID();
		this.ownerUserId = ownerUserId;
		this.deviceUuid = deviceUuid;
		this.actionType = actionType;
		this.fingerprint = fingerprint;
		this.title = title;
		this.rationale = rationale;
		this.evidence = evidence;
		this.payload = payload;
		this.status = PENDING;
		this.createdAt = now;
	}

	public void decide(String newStatus, Instant now, UUID ruleId) {
		this.status = newStatus;
		this.decidedAt = now;
		this.createdRuleId = ruleId;
	}

	public void markStale(Instant now) {
		this.status = STALE;
		this.decidedAt = now;
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

	public String getActionType() {
		return actionType;
	}

	public String getFingerprint() {
		return fingerprint;
	}

	public String getTitle() {
		return title;
	}

	public String getRationale() {
		return rationale;
	}

	public String getEvidence() {
		return evidence;
	}

	public String getPayload() {
		return payload;
	}

	public String getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getDecidedAt() {
		return decidedAt;
	}

	public UUID getCreatedRuleId() {
		return createdRuleId;
	}
}
