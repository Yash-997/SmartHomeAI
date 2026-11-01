package com.smarthome.assistant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_conversations")
public class ChatConversation {

	@Id
	private UUID id;

	@Column(name = "owner_user_id", nullable = false, updatable = false)
	private UUID ownerUserId;

	@Column(nullable = false, length = 80)
	private String title;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ChatConversation() {
	}

	public ChatConversation(UUID id, UUID ownerUserId, String title, Instant now) {
		this.id = id;
		this.ownerUserId = ownerUserId;
		this.title = title;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public void touch(Instant now) {
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public UUID getOwnerUserId() {
		return ownerUserId;
	}

	public String getTitle() {
		return title;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
