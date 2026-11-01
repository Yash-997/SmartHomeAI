package com.smarthome.assistant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A rule the assistant proposed. It is only data until the owner approves it; approval creates the rule through the normal service. */
@Entity
@Table(name = "assistant_suggestions")
public class AssistantSuggestion {

	public static final String PENDING = "PENDING";
	public static final String APPROVED = "APPROVED";
	public static final String REJECTED = "REJECTED";
	public static final String EXPIRED = "EXPIRED";

	@Id
	private UUID id;

	@Column(name = "owner_user_id", nullable = false, updatable = false)
	private UUID ownerUserId;

	@Column(name = "conversation_id", updatable = false)
	private UUID conversationId;

	@Column(name = "message_id", updatable = false)
	private UUID messageId;

	@Column(nullable = false, length = 200, updatable = false)
	private String summary;

	@Column(length = 1000, updatable = false)
	private String rationale;

	@Column(name = "rule_json", nullable = false, length = 4000, updatable = false)
	private String ruleJson;

	@Column(nullable = false, length = 10)
	private String status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "decided_at")
	private Instant decidedAt;

	@Column(name = "created_rule_id")
	private UUID createdRuleId;

	protected AssistantSuggestion() {
	}

	public AssistantSuggestion(UUID id, UUID ownerUserId, UUID conversationId, UUID messageId, String summary, String rationale,
			String ruleJson, Instant now, Instant expiresAt) {
		this.id = id;
		this.ownerUserId = ownerUserId;
		this.conversationId = conversationId;
		this.messageId = messageId;
		this.summary = summary;
		this.rationale = rationale;
		this.ruleJson = ruleJson;
		this.status = PENDING;
		this.createdAt = now;
		this.expiresAt = expiresAt;
	}

	public void decide(String newStatus, Instant now, UUID ruleId) {
		this.status = newStatus;
		this.decidedAt = now;
		this.createdRuleId = ruleId;
	}

	public UUID getId() {
		return id;
	}

	public UUID getOwnerUserId() {
		return ownerUserId;
	}

	public UUID getConversationId() {
		return conversationId;
	}

	public UUID getMessageId() {
		return messageId;
	}

	public String getSummary() {
		return summary;
	}

	public String getRationale() {
		return rationale;
	}

	public String getRuleJson() {
		return ruleJson;
	}

	public String getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getDecidedAt() {
		return decidedAt;
	}

	public UUID getCreatedRuleId() {
		return createdRuleId;
	}
}
