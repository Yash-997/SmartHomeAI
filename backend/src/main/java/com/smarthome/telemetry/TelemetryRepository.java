package com.smarthome.telemetry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TelemetryRepository extends JpaRepository<TelemetryReading, Long> {

	/**
	 * Newest first. Messages that arrive within the same clock tick share a timestamp, so the record id (which only
	 * grows) breaks the tie: without it "newest" could be an older reading.
	 */
	@Query("select r from TelemetryReading r where r.deviceUuid = :deviceUuid order by r.receivedAt desc, r.id desc")
	List<TelemetryReading> findByDeviceUuidOrderByReceivedAtDesc(@Param("deviceUuid") UUID deviceUuid, Pageable page);

	default Optional<TelemetryReading> findFirstByDeviceUuidOrderByReceivedAtDesc(UUID deviceUuid) {
		return findByDeviceUuidOrderByReceivedAtDesc(deviceUuid, PageRequest.of(0, 1)).stream().findFirst();
	}

	boolean existsByDeviceUuidAndDedupeKeyAndReceivedAtAfter(UUID deviceUuid, String dedupeKey, Instant after);

	long countByDeviceUuid(UUID deviceUuid);

	/** Used when a device is disowned so a later owner can never see the previous owner's data. */
	@Modifying
	@Query("delete from TelemetryReading t where t.deviceUuid = :deviceUuid")
	int deleteAllForDevice(@Param("deviceUuid") UUID deviceUuid);
}
