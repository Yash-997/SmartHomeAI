package com.smarthome.ml;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MlAnomalyRepository extends JpaRepository<MlAnomaly, UUID> {

	/** The device is part of the lookup: a user can never reach another device's anomaly by id. */
	Optional<MlAnomaly> findByIdAndDeviceUuid(UUID id, UUID deviceUuid);

	List<MlAnomaly> findByDeviceUuidOrderByLastSeenAtDesc(UUID deviceUuid, Pageable page);

	List<MlAnomaly> findByDeviceUuidAndStatusInOrderByLastSeenAtDesc(UUID deviceUuid, Collection<AnomalyStatus> statuses, Pageable page);

	Optional<MlAnomaly> findFirstByDeviceUuidAndKindAndScopeAndStatusInOrderByLastSeenAtDesc(UUID deviceUuid, AnomalyKind kind,
			String scope, Collection<AnomalyStatus> statuses);

	Optional<MlAnomaly> findFirstByDeviceUuidAndKindAndScopeAndStatusOrderByResolvedAtDesc(UUID deviceUuid, AnomalyKind kind,
			String scope, AnomalyStatus status);

	List<MlAnomaly> findByDeviceUuidAndKindInAndStatusIn(UUID deviceUuid, Collection<AnomalyKind> kinds, Collection<AnomalyStatus> statuses);

	List<MlAnomaly> findByDeviceUuidAndStatusInAndLastSeenAtBefore(UUID deviceUuid, Collection<AnomalyStatus> statuses, Instant before);

	long countByDeviceUuidAndStatusIn(UUID deviceUuid, Collection<AnomalyStatus> statuses);

	long countByDeviceUuidAndStatusInAndSeverity(UUID deviceUuid, Collection<AnomalyStatus> statuses, AnomalySeverity severity);

	@Modifying
	@Query("delete from MlAnomaly a where a.deviceUuid = :device")
	int deleteAllForDevice(@Param("device") UUID device);
}
