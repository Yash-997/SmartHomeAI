package com.smarthome.ml;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A detected anomaly. The same ongoing problem keeps updating one row (occurrences) instead of adding many. */
@Entity
@Table(name = "ml_anomalies")
public class MlAnomaly {

	@Id
	private UUID id;

	@Column(name = "device_uuid", nullable = false, updatable = false)
	private UUID deviceUuid;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private AnomalyKind kind;

	@Column(nullable = false, length = 12)
	private String scope;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 10)
	private AnomalySeverity severity;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 15)
	private AnomalyStatus status;

	@Column(nullable = false, length = 600)
	private String explanation;

	@Column(length = 1500)
	private String details;

	@Column(name = "detected_at", nullable = false, updatable = false)
	private Instant detectedAt;

	@Column(name = "last_seen_at", nullable = false)
	private Instant lastSeenAt;

	@Column(nullable = false)
	private int occurrences;

	@Column(name = "reading_at")
	private Instant readingAt;

	@Column(name = "resolved_at")
	private Instant resolvedAt;

	@Column(name = "resolution_note", length = 200)
	private String resolutionNote;

	protected MlAnomaly() {
	}

	public MlAnomaly(UUID deviceUuid, AnomalyKind kind, String scope, AnomalySeverity severity, String explanation, String details,
			Instant readingAt, Instant now) {
		this.id = UUID.randomUUID();
		this.deviceUuid = deviceUuid;
		this.kind = kind;
		this.scope = scope;
		this.severity = severity;
		this.status = AnomalyStatus.OPEN;
		this.explanation = trim(explanation, 600);
		this.details = trim(details, 1500);
		this.detectedAt = now;
		this.lastSeenAt = now;
		this.occurrences = 1;
		this.readingAt = readingAt;
	}

	/** The same problem was seen again: keep the worst severity, refresh the text with the latest numbers. */
	public void seenAgain(AnomalySeverity severity, String explanation, String details, Instant readingAt, Instant now) {
		if (severity.ordinal() > this.severity.ordinal()) {
			this.severity = severity;
		}
		this.explanation = trim(explanation, 600);
		this.details = trim(details, 1500);
		this.readingAt = readingAt;
		this.lastSeenAt = now;
		this.occurrences++;
	}

	public void close(AnomalyStatus to, String note, Instant now) {
		this.status = to;
		this.resolvedAt = now;
		this.resolutionNote = note == null ? null : trim(note, 200);
	}

	public void acknowledge() {
		this.status = AnomalyStatus.ACKNOWLEDGED;
	}

	private static String trim(String s, int max) {
		return s == null || s.length() <= max ? s : s.substring(0, max);
	}

	public UUID getId() { return id; }
	public UUID getDeviceUuid() { return deviceUuid; }
	public AnomalyKind getKind() { return kind; }
	public String getScope() { return scope; }
	public AnomalySeverity getSeverity() { return severity; }
	public AnomalyStatus getStatus() { return status; }
	public String getExplanation() { return explanation; }
	public String getDetails() { return details; }
	public Instant getDetectedAt() { return detectedAt; }
	public Instant getLastSeenAt() { return lastSeenAt; }
	public int getOccurrences() { return occurrences; }
	public Instant getReadingAt() { return readingAt; }
	public Instant getResolvedAt() { return resolvedAt; }
	public String getResolutionNote() { return resolutionNote; }
}
