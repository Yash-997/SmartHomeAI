package com.smarthome.recommendation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendationRepository extends JpaRepository<Recommendation, UUID> {

	Optional<Recommendation> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

	List<Recommendation> findByOwnerUserIdAndStatusOrderByCreatedAtDesc(UUID ownerUserId, String status, Pageable page);

	/** Every earlier recommendation with this identity, newest first. */
	List<Recommendation> findByOwnerUserIdAndFingerprintOrderByCreatedAtDesc(UUID ownerUserId, String fingerprint);

	List<Recommendation> findByOwnerUserIdAndStatus(UUID ownerUserId, String status);

	/** Atomically PENDING -> {@code newStatus}; 0 rows means someone else decided it first (or it went stale). */
	@Modifying
	@Query("update Recommendation r set r.status = :newStatus, r.decidedAt = :now where r.id = :id and r.ownerUserId = :owner and r.status = 'PENDING'")
	int claim(@Param("id") UUID id, @Param("owner") UUID owner, @Param("newStatus") String newStatus, @Param("now") Instant now);
}
