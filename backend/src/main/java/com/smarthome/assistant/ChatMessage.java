package com.smarthome.assistant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_messages")
public class ChatMessage {

	@Id
	private UUID id;

	@Column(name = "conversation_id", nullable = false, updatable = false)
	private UUID conversationId;

	/** "user" or "assistant". */
	@Column(nullable = false, length = 10, updatable = false)
	private String role;

	@Column(nullable = false, length = 8000, updatable = false)
	private String content;

	@Column(name = "answer_status", length = 24, updatable = false)
	private String answerStatus;

	@Column(name = "sources_json", length = 8000, updatable = false)
	private String sourcesJson;

	@Column(name = "warnings_json", length = 2000, updatable = false)
	private String warningsJson;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ChatMessage() {
	}

	public ChatMessage(UUID id, UUID conversationId, String role, String content, String answerStatus, String sourcesJson,
			String warningsJson, Instant createdAt) {
		this.id = id;
		this.conversationId = conversationId;
		this.role = role;
		this.content = content;
		this.answerStatus = answerStatus;
		this.sourcesJson = sourcesJson;
		this.warningsJson = warningsJson;
		this.createdAt = createdAt;
	}

	public UUID getId() {
		return id;
	}

	public UUID getConversationId() {
		return conversationId;
	}

	public String getRole() {
		return role;
	}

	public String getContent() {
		return content;
	}

	public String getAnswerStatus() {
		return answerStatus;
	}

	public String getSourcesJson() {
		return sourcesJson;
	}

	public String getWarningsJson() {
		return warningsJson;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
