package com.smarthome.assistant;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssistantSuggestionRepository extends JpaRepository<AssistantSuggestion, UUID> {

	Optional<AssistantSuggestion> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

	List<AssistantSuggestion> findByOwnerUserIdAndStatusOrderByCreatedAtDesc(UUID ownerUserId, String status, Pageable page);

	List<AssistantSuggestion> findByMessageIdIn(Collection<UUID> messageIds);

	long countByOwnerUserIdAndStatus(UUID ownerUserId, String status);

	@Modifying
	@Query("update AssistantSuggestion s set s.status = 'EXPIRED' where s.ownerUserId = :owner and s.status = 'PENDING' and s.expiresAt < :now")
	int expireOld(@Param("owner") UUID owner, @Param("now") java.time.Instant now);

	/** Atomically moves a PENDING, unexpired suggestion to {@code newStatus}; 0 rows means someone else decided it first (or it expired). */
	@Modifying
	@Query("update AssistantSuggestion s set s.status = :newStatus, s.decidedAt = :now where s.id = :id and s.ownerUserId = :owner "
			+ "and s.status = 'PENDING' and s.expiresAt >= :now")
	int claim(@Param("id") UUID id, @Param("owner") UUID owner, @Param("newStatus") String newStatus, @Param("now") java.time.Instant now);
}
