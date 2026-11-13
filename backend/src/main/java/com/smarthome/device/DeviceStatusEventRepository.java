package com.smarthome.device;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceStatusEventRepository extends JpaRepository<DeviceStatusEvent, Long> {

	Optional<DeviceStatusEvent> findFirstByDeviceUuidOrderByChangedAtDescIdDesc(UUID deviceUuid);

	List<DeviceStatusEvent> findByDeviceUuidOrderByChangedAtDescIdDesc(UUID deviceUuid, Pageable page);

	long countByDeviceUuidAndStateAndChangedAtAfter(UUID deviceUuid, String state, Instant after);

	@Modifying
	@Query("delete from DeviceStatusEvent e where e.deviceUuid = :device")
	int deleteAllForDevice(@Param("device") UUID device);
}
