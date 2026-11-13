package com.smarthome.device;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One change of a device's connectivity (ONLINE or OFFLINE). Rows are only ever added, never updated. */
@Entity
@Table(name = "device_status_events")
public class DeviceStatusEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "device_uuid", nullable = false, updatable = false)
	private UUID deviceUuid;

	@Column(nullable = false, length = 10, updatable = false)
	private String state;

	@Column(nullable = false, length = 60, updatable = false)
	private String reason;

	@Column(name = "changed_at", nullable = false, updatable = false)
	private Instant changedAt;

	@Column(name = "last_seen_at", updatable = false)
	private Instant lastSeenAt;

	protected DeviceStatusEvent() {
	}

	public DeviceStatusEvent(UUID deviceUuid, DeviceConnection state, String reason, Instant changedAt, Instant lastSeenAt) {
		this.deviceUuid = deviceUuid;
		this.state = state.name();
		this.reason = reason.length() > 60 ? reason.substring(0, 60) : reason;
		this.changedAt = changedAt;
		this.lastSeenAt = lastSeenAt;
	}

	public Long getId() { return id; }
	public UUID getDeviceUuid() { return deviceUuid; }
	public String getState() { return state; }
	public String getReason() { return reason; }
	public Instant getChangedAt() { return changedAt; }
	public Instant getLastSeenAt() { return lastSeenAt; }
}
