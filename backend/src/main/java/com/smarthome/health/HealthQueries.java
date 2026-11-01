package com.smarthome.health;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.smarthome.command.DeviceCommand;
import com.smarthome.telemetry.TelemetryReading;

/** Small read-only aggregates for the system status view. */
public interface HealthQueries extends Repository<TelemetryReading, Long> {

	@Query("select max(r.receivedAt) from TelemetryReading r where r.deviceUuid = :d and r.temperatureC is not null")
	Instant lastTemperatureAt(@Param("d") UUID device);

	@Query("select max(r.receivedAt) from TelemetryReading r where r.deviceUuid = :d and r.humidityPct is not null")
	Instant lastHumidityAt(@Param("d") UUID device);

	@Query("select max(r.receivedAt) from TelemetryReading r where r.deviceUuid = :d and r.gasRaw is not null")
	Instant lastGasAt(@Param("d") UUID device);

	@Query("select max(r.receivedAt) from TelemetryReading r where r.deviceUuid = :d and r.motion is not null")
	Instant lastMotionAt(@Param("d") UUID device);

	/** Commands this user's devices received in the window, grouped by status. */
	@Query("select c.status, count(c) from DeviceCommand c where c.requestedBy = :owner and c.createdAt >= :since group by c.status")
	List<Object[]> commandCounts(@Param("owner") UUID owner, @Param("since") Instant since);

	@Query("select c from DeviceCommand c where c.requestedBy = :owner and c.createdAt >= :since "
			+ "and c.status in (com.smarthome.command.CommandStatus.FAILED, com.smarthome.command.CommandStatus.EXPIRED, com.smarthome.command.CommandStatus.UNKNOWN) "
			+ "order by c.createdAt desc")
	List<DeviceCommand> recentProblems(@Param("owner") UUID owner, @Param("since") Instant since, org.springframework.data.domain.Pageable page);
}
