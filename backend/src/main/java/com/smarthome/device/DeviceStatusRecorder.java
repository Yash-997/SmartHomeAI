package com.smarthome.device;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the connectivity history. Once a minute it compares each active device's connection (the same rule the
 * dashboard uses: {@link DeviceService#connectionOf}) with the last recorded state and adds a row when it changed. That
 * catches every way a device can go away: a "Last Will" offline message, a clean "offline" message, or simply falling
 * silent. The history lives in the database, so it survives restarts; after a restart the first sweep just continues
 * from the last stored state.
 */
@Component
public class DeviceStatusRecorder {

	private static final Logger log = LoggerFactory.getLogger(DeviceStatusRecorder.class);

	private final DeviceRepository devices;
	private final DeviceService deviceService;
	private final DeviceStatusEventRepository events;
	private final Clock clock;

	public DeviceStatusRecorder(DeviceRepository devices, DeviceService deviceService, DeviceStatusEventRepository events, Clock clock) {
		this.devices = devices;
		this.deviceService = deviceService;
		this.events = events;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${app.history.status-sweep-ms:60000}", initialDelayString = "${app.history.status-sweep-ms:60000}")
	public void sweep() {
		Instant now = clock.instant();
		for (Device d : devices.findByStatus(DeviceStatus.ACTIVE)) {
			try {
				record(d, now);
			} catch (RuntimeException e) {
				log.warn("Status history update failed for device {}: {}", d.getDeviceId(), e.getClass().getSimpleName());
			}
		}
	}

	/** Adds a row if the device's connection differs from the last recorded one. Returns the new row, if any. */
	public Optional<DeviceStatusEvent> record(Device d, Instant now) {
		DeviceConnection c = deviceService.connectionOf(d, now);
		if (c == DeviceConnection.NEVER_CONNECTED) {
			return Optional.empty();
		}
		String last = events.findFirstByDeviceUuidOrderByChangedAtDescIdDesc(d.getId()).map(DeviceStatusEvent::getState).orElse(null);
		if (c.name().equals(last)) {
			return Optional.empty();
		}
		String reason;
		if (c == DeviceConnection.ONLINE) {
			reason = "reported online";
		} else if (d.getReportedState() == ReportedState.OFFLINE) {
			reason = "reported offline (or its broker connection dropped)";
		} else {
			reason = "went silent (no message within the staleness window)";
		}
		return Optional.of(events.save(new DeviceStatusEvent(d.getId(), c, reason, now, d.getLastSeenAt())));
	}
}
