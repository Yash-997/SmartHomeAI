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
import org.springframework.transaction.annotation.Transactional;

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

	/** Anomalies still going on at or after {@code since} (to leave their minutes out of anomaly-model training). */
	List<MlAnomaly> findByDeviceUuidAndLastSeenAtAfter(UUID deviceUuid, Instant since);

	long countByDeviceUuidAndStatusInAndSeverity(UUID deviceUuid, Collection<AnomalyStatus> statuses, AnomalySeverity severity);

	@Modifying
	@Query("delete from MlAnomaly a where a.deviceUuid = :device")
	int deleteAllForDevice(@Param("device") UUID device);

	// ---- email delivery (only these write the email columns) ----

	/** Marks the anomaly PENDING if no email was handled for it yet. Returns 0 if it already was: never a second email. */
	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("update MlAnomaly a set a.emailStatus = 'PENDING', a.emailAt = :at where a.id = :id and a.emailStatus is null")
	int claimEmail(@Param("id") UUID id, @Param("at") Instant at);

	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("update MlAnomaly a set a.emailStatus = :status, a.emailAt = :at, a.emailError = :error where a.id = :id")
	int recordEmail(@Param("id") UUID id, @Param("status") String status, @Param("at") Instant at, @Param("error") String error);

	/** Was the same problem (device, kind, sensor) emailed successfully since {@code since}? Drives the cooldown. */
	boolean existsByDeviceUuidAndKindAndScopeAndEmailStatusAndEmailAtAfterAndIdNot(UUID deviceUuid, AnomalyKind kind, String scope,
			String emailStatus, Instant since, UUID id);

	/** Anomalies of these devices that have an email status, newest first (the owner's email history). */
	List<MlAnomaly> findByDeviceUuidInAndEmailStatusIsNotNullOrderByDetectedAtDesc(Collection<UUID> devices, Pageable page);
}
