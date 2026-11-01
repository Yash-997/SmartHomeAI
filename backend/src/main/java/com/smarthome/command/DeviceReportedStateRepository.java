package com.smarthome.command;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceReportedStateRepository extends JpaRepository<DeviceReportedState, UUID> {

	@Modifying
	@Query("delete from DeviceReportedState s where s.deviceUuid = :device")
	int deleteForDevice(@Param("device") UUID device);
}
