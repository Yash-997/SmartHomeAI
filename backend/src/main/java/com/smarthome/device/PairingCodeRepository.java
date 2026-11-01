package com.smarthome.device;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PairingCodeRepository extends JpaRepository<PairingCode, UUID> {

	Optional<PairingCode> findByCodeHash(String codeHash);

	void deleteByUserIdAndUsedAtIsNull(UUID userId);

	/**
	 * Atomically marks a code as used. Returns 1 only for the single caller that wins; unknown,
	 * expired and already-used codes (including a concurrent second redeem) return 0.
	 */
	@Modifying
	@Query("update PairingCode p set p.usedAt = :now where p.codeHash = :hash and p.usedAt is null and p.expiresAt > :now")
	int consume(@Param("hash") String hash, @Param("now") Instant now);
}
