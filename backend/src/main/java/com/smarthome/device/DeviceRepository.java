package com.smarthome.device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

	Optional<Device> findByDeviceId(String deviceId);

	/** Ownership check built into the query: another user's device is simply "not found". */
	Optional<Device> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

	List<Device> findByOwnerUserIdOrderByRegisteredAtDesc(UUID ownerUserId);

	List<Device> findByStatus(DeviceStatus status);
}
