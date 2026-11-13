package com.smarthome.ml;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import com.smarthome.telemetry.TelemetryReading;

/**
 * Read-only, projection queries over stored telemetry for the ML code. They select only the few columns needed
 * (not whole entities) and are always capped with a Pageable.
 */
public interface MlSampleRepository extends Repository<TelemetryReading, Long> {

	/** Newest first. Rows where the PIR value is missing are not returned. */
	@Query("select new com.smarthome.ml.MotionSample(r.receivedAt, r.motion) from TelemetryReading r "
			+ "where r.deviceUuid = :device and r.receivedAt >= :from and r.motion is not null order by r.receivedAt desc, r.id desc")
	List<MotionSample> findMotionSamples(@Param("device") UUID device, @Param("from") Instant from, Pageable page);

	/** Newest first, within [from, to). */
	@Query("select new com.smarthome.ml.SensorSample(r.receivedAt, r.temperatureC, r.humidityPct, r.gasRaw) from TelemetryReading r "
			+ "where r.deviceUuid = :device and r.receivedAt >= :from and r.receivedAt < :to order by r.receivedAt desc, r.id desc")
	List<SensorSample> findSensorSamples(@Param("device") UUID device, @Param("from") Instant from, @Param("to") Instant to, Pageable page);

	@Query("select count(r) from TelemetryReading r where r.deviceUuid = :device and r.motion is not null")
	long countMotionSamples(@Param("device") UUID device);

	@Query("select min(r.receivedAt) from TelemetryReading r where r.deviceUuid = :device and r.motion is not null")
	Instant firstMotionAt(@Param("device") UUID device);

	@Query("select max(r.receivedAt) from TelemetryReading r where r.deviceUuid = :device and r.motion is not null")
	Instant lastMotionAt(@Param("device") UUID device);

	@Query("select count(r) from TelemetryReading r where r.deviceUuid = :device and r.receivedAt > :after and r.motion is not null")
	long countMotionSamplesAfter(@Param("device") UUID device, @Param("after") Instant after);

	/** Every stored message of the device, whatever sensors it carried. */
	@Query("select count(r) from TelemetryReading r where r.deviceUuid = :device")
	long countAll(@Param("device") UUID device);

	@Query("select count(r) from TelemetryReading r where r.deviceUuid = :device and r.receivedAt > :after")
	long countAllAfter(@Param("device") UUID device, @Param("after") Instant after);

	@Query("select min(r.receivedAt) from TelemetryReading r where r.deviceUuid = :device")
	Instant firstReadingAt(@Param("device") UUID device);
}
