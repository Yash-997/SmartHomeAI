package com.smarthome.ml;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.smarthome.device.Device;
import com.smarthome.device.DeviceConnection;
import com.smarthome.device.DeviceService;
import com.smarthome.device.DeviceStatusEventRepository;
import com.smarthome.ml.MlDtos.ConnectivityResponse;
import com.smarthome.ml.MlDtos.DataSummary;
import com.smarthome.ml.MlDtos.DatasetSnapshot;
import com.smarthome.ml.MlDtos.StatusEventResponse;

/**
 * Reports how much real history has been collected for a device, and what the training dataset built from it looks like
 * right now. It only reads stored telemetry (written by the normal MQTT ingest, append-only) and never creates any.
 */
@Service
public class DataCollectionService {

	/** Building the dataset reads up to the training window of readings, so it is reused for a few minutes. */
	static final Duration SNAPSHOT_TTL = Duration.ofMinutes(10);

	private final MlSampleRepository samples;
	private final DeviceService deviceService;
	private final DeviceStatusEventRepository statusEvents;
	private final MlProperties props;
	private final Clock clock;

	private record Cached(DatasetSnapshot snapshot, long motionReadings) {
	}

	private final Map<UUID, Cached> snapshots = new ConcurrentHashMap<>();

	public DataCollectionService(MlSampleRepository samples, DeviceService deviceService, DeviceStatusEventRepository statusEvents,
			MlProperties props, Clock clock) {
		this.samples = samples;
		this.deviceService = deviceService;
		this.statusEvents = statusEvents;
		this.props = props;
		this.clock = clock;
	}

	public DataSummary summary(UUID deviceUuid) {
		Instant now = clock.instant();
		long motion = samples.countMotionSamples(deviceUuid);
		return new DataSummary(samples.countAll(deviceUuid), samples.firstReadingAt(deviceUuid),
				samples.countAllAfter(deviceUuid, now.minus(24, ChronoUnit.HOURS)), motion, samples.firstMotionAt(deviceUuid),
				samples.lastMotionAt(deviceUuid), snapshot(deviceUuid, motion, now));
	}

	/**
	 * The dataset exactly as training would build it (same window, slot size, minimum readings per slot). Reused while
	 * fresh and while no motion reading has been added or removed.
	 */
	DatasetSnapshot snapshot(UUID deviceUuid, long motionReadings, Instant now) {
		Cached c = snapshots.get(deviceUuid);
		if (c != null && c.motionReadings() == motionReadings && c.snapshot().checkedAt().plus(SNAPSHOT_TTL).isAfter(now)) {
			return c.snapshot();
		}
		MlProperties.Occupancy cfg = props.occupancy();
		List<MotionSample> data = motionReadings == 0 ? List.of()
				: samples.findMotionSamples(deviceUuid, now.minus(cfg.windowDays(), ChronoUnit.DAYS), PageRequest.of(0, cfg.maxSamples()));
		OccupancyDataset.Built built = OccupancyDataset.build(data, props.zone(), cfg.bucketMinutes(), cfg.minReadingsPerBucket());
		int usable = built.buckets().size();
		int withMotion = built.positives();
		int without = usable - withMotion;
		boolean enough = usable >= cfg.minLabeledBuckets() && built.distinctDays() >= cfg.minDistinctDays()
				&& withMotion >= cfg.minClassBuckets() && without >= cfg.minClassBuckets();
		DatasetSnapshot s = new DatasetSnapshot(usable, withMotion, without, built.sparseBucketsDropped(), built.distinctDays(), enough, now);
		snapshots.put(deviceUuid, new Cached(s, motionReadings));
		return s;
	}

	/** Connectivity history (newest first) for a device the caller owns. */
	public ConnectivityResponse connectivity(UUID ownerId, UUID deviceUuid, int limit) {
		Device d = deviceService.requireOwned(ownerId, deviceUuid);
		Instant now = clock.instant();
		List<StatusEventResponse> events = statusEvents
				.findByDeviceUuidOrderByChangedAtDescIdDesc(d.getId(), PageRequest.of(0, Math.max(1, Math.min(limit, 200)))).stream()
				.map(e -> new StatusEventResponse(e.getState(), e.getReason(), e.getChangedAt(), e.getLastSeenAt())).toList();
		DeviceConnection current = deviceService.connectionOf(d, now);
		long outages = statusEvents.countByDeviceUuidAndStateAndChangedAtAfter(d.getId(), DeviceConnection.OFFLINE.name(), now.minus(7, ChronoUnit.DAYS));
		return new ConnectivityResponse(current.name(), outages, events);
	}

	/** Tests and disown: forget the cached snapshot. */
	public void forget(UUID deviceUuid) {
		snapshots.remove(deviceUuid);
	}
}
