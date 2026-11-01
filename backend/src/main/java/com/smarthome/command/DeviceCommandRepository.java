package com.smarthome.command;

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

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, UUID> {

	/** The device is part of the lookup, so one device can never act on another device's command. */
	Optional<DeviceCommand> findByIdAndDeviceUuid(UUID id, UUID deviceUuid);

	List<DeviceCommand> findByDeviceUuidOrderByCreatedAtDesc(UUID deviceUuid, Pageable page);

	List<DeviceCommand> findTop5ByDeviceUuidOrderByCreatedAtDesc(UUID deviceUuid);

	long countByDeviceUuidAndStatusInAndExpiresAtAfter(UUID deviceUuid, Collection<CommandStatus> statuses, Instant after);

	Optional<DeviceCommand> findFirstByDeviceUuidAndActionAndValueAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
			UUID deviceUuid, String action, String value, Collection<CommandStatus> statuses, Instant after);

	/** PENDING -> SENT, but only if nothing (e.g. a very fast ack) has moved it on already. */
	@Modifying
	@Query("update DeviceCommand c set c.status = :to, c.sentAt = :now where c.id = :id and c.status = :from")
	int markSent(@Param("id") UUID id, @Param("from") CommandStatus from, @Param("to") CommandStatus to, @Param("now") Instant now);

	/** Generic guarded transition: applies only while the command is still in one of the allowed source states. */
	@Modifying
	@Query("update DeviceCommand c set c.status = :to, c.completedAt = :now, c.detail = :detail "
			+ "where c.id = :id and c.deviceUuid = :device and c.status in :from")
	int transition(@Param("id") UUID id, @Param("device") UUID device, @Param("from") Collection<CommandStatus> from,
			@Param("to") CommandStatus to, @Param("now") Instant now, @Param("detail") String detail);

	/** Never sent and now too late. */
	@Modifying
	@Query("update DeviceCommand c set c.status = :to, c.completedAt = :now, c.detail = :detail "
			+ "where c.status = :from and c.expiresAt < :now")
	int expirePending(@Param("from") CommandStatus from, @Param("to") CommandStatus to, @Param("now") Instant now,
			@Param("detail") String detail);

	/** Sent, but no acknowledgement arrived by expiry plus grace: outcome genuinely unknown. */
	@Modifying
	@Query("update DeviceCommand c set c.status = :to, c.completedAt = :now, c.detail = :detail "
			+ "where c.status = :from and c.expiresAt < :cutoff")
	int markUnknown(@Param("from") CommandStatus from, @Param("to") CommandStatus to, @Param("now") Instant now,
			@Param("cutoff") Instant cutoff, @Param("detail") String detail);

	@Modifying
	@Query("delete from DeviceCommand c where c.deviceUuid = :device")
	int deleteAllForDevice(@Param("device") UUID device);
}
