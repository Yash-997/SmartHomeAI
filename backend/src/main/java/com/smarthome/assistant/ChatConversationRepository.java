package com.smarthome.assistant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, UUID> {

	Optional<ChatConversation> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

	List<ChatConversation> findByOwnerUserIdOrderByUpdatedAtDesc(UUID ownerUserId, Pageable page);

	long countByOwnerUserId(UUID ownerUserId);
}
