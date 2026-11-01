package com.smarthome.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.smarthome.security.SecurityEvent.Kind;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, UUID> {

	Optional<SecurityEvent> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

	List<SecurityEvent> findByOwnerUserIdOrderByCreatedAtDescIdDesc(UUID ownerUserId, Pageable page);

	List<SecurityEvent> findByOwnerUserIdAndKindOrderByCreatedAtDescIdDesc(UUID ownerUserId, Kind kind, Pageable page);

	/** Alerts that need a human look: warnings and criticals not yet acknowledged. */
	@Query("select count(e) from SecurityEvent e where e.ownerUserId = :owner and e.acknowledgedAt is null and e.severity <> 'INFO'")
	long countUnacknowledgedAlerts(@Param("owner") UUID owner);

	long countByDeviceUuidAndKindAndCreatedAtAfter(UUID deviceUuid, Kind kind, Instant after);

	@Modifying
	@Query("update SecurityEvent e set e.acknowledgedAt = :now where e.ownerUserId = :owner and e.acknowledgedAt is null and e.severity <> 'INFO'")
	int acknowledgeAll(@Param("owner") UUID owner, @Param("now") Instant now);

	@Modifying
	@Query("delete from SecurityEvent e where e.deviceUuid = :device")
	int deleteAllForDevice(@Param("device") UUID device);
}
