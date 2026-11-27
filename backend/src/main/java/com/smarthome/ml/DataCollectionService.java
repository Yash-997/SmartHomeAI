package com.smarthome.ml;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.smarthome.device.Device;
import com.smarthome.device.DeviceConnection;
import com.smarthome.device.DeviceService;
import com.smarthome.device.DeviceStatusEventRepository;
import com.smarthome.ml.MlDtos.ConnectivityResponse;
import com.smarthome.ml.MlDtos.StatusEventResponse;

/** A device's live connectivity history. Occupancy ML does not collect real readings; it uses the synthetic dataset. */
@Service
public class DataCollectionService {

	private final DeviceService deviceService;
	private final DeviceStatusEventRepository statusEvents;
	private final Clock clock;

	public DataCollectionService(DeviceService deviceService, DeviceStatusEventRepository statusEvents, Clock clock) {
		this.deviceService = deviceService;
		this.statusEvents = statusEvents;
		this.clock = clock;
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
}
