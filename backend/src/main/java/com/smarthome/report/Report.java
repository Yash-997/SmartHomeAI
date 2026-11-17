package com.smarthome.report;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A generated report: a frozen JSON snapshot of the numbers at generation time. Rows are never updated. */
@Entity
@Table(name = "reports")
public class Report {

	@Id
	private UUID id;

	@Column(name = "owner_user_id", nullable = false, updatable = false)
	private UUID ownerUserId;

	@Column(nullable = false, length = 10, updatable = false)
	private String kind;

	@Column(nullable = false, length = 160, updatable = false)
	private String title;

	@Column(name = "period_from", nullable = false, updatable = false)
	private Instant periodFrom;

	@Column(name = "period_to", nullable = false, updatable = false)
	private Instant periodTo;

	@Column(name = "time_zone", nullable = false, length = 50, updatable = false)
	private String timeZone;

	@Column(name = "device_uuid", updatable = false)
	private UUID deviceUuid;

	@Column(nullable = false, length = 2000, updatable = false)
	private String summary;

	@Column(name = "content_json", nullable = false, length = 1000000, updatable = false)
	private String contentJson;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Report() {
	}

	public Report(UUID id, UUID ownerUserId, String kind, String title, Instant periodFrom, Instant periodTo, String timeZone, UUID deviceUuid,
			String summary, String contentJson, Instant createdAt) {
		this.id = id;
		this.ownerUserId = ownerUserId;
		this.kind = kind;
		this.title = title;
		this.periodFrom = periodFrom;
		this.periodTo = periodTo;
		this.timeZone = timeZone;
		this.deviceUuid = deviceUuid;
		this.summary = summary;
		this.contentJson = contentJson;
		this.createdAt = createdAt;
	}

	public UUID getId() { return id; }
	public UUID getOwnerUserId() { return ownerUserId; }
	public String getKind() { return kind; }
	public String getTitle() { return title; }
	public Instant getPeriodFrom() { return periodFrom; }
	public Instant getPeriodTo() { return periodTo; }
	public String getTimeZone() { return timeZone; }
	public UUID getDeviceUuid() { return deviceUuid; }
	public String getSummary() { return summary; }
	public String getContentJson() { return contentJson; }
	public Instant getCreatedAt() { return createdAt; }
}
