package com.smarthome.assistant;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

	List<ChatMessage> findByConversationIdOrderByCreatedAtAscIdAsc(UUID conversationId, Pageable page);

	/** Newest first, for building the short history sent to the model. */
	List<ChatMessage> findByConversationIdOrderByCreatedAtDescIdDesc(UUID conversationId, Pageable page);
}
