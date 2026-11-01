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

/** The current trained model (or the record of why none could be trained) for one device. */
@Entity
@Table(name = "ml_models")
public class MlModel {

	public static final String OCCUPANCY = "OCCUPANCY";

	@Id
	private UUID id;

	@Column(name = "device_uuid", nullable = false)
	private UUID deviceUuid;

	@Column(name = "model_type", nullable = false, length = 20)
	private String modelType;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private OccupancyTrainer.Status status;

	@Column(length = 400)
	private String reason;

	@Column(name = "time_zone", nullable = false, length = 50)
	private String timeZone;

	@Column(name = "bucket_minutes", nullable = false)
	private int bucketMinutes;

	@Column(name = "trained_at", nullable = false)
	private Instant trainedAt;

	@Column(name = "data_from")
	private Instant dataFrom;

	@Column(name = "data_to")
	private Instant dataTo;

	@Column(nullable = false)
	private int samples;

	@Column(name = "labeled_buckets", nullable = false)
	private int labeledBuckets;

	@Column(name = "positive_buckets", nullable = false)
	private int positiveBuckets;

	@Column(name = "distinct_days", nullable = false)
	private int distinctDays;

	@Column(name = "model_json", length = 8000)
	private String modelJson;

	@Column(name = "metrics_json", length = 2000)
	private String metricsJson;

	protected MlModel() {
	}

	public MlModel(UUID deviceUuid) {
		this.id = UUID.randomUUID();
		this.deviceUuid = deviceUuid;
		this.modelType = OCCUPANCY;
	}

	/** Replaces everything with the outcome of a training run. */
	public void record(OccupancyTrainer.Result r, String zone, int bucketMinutes, String modelJson, String metricsJson, Instant now) {
		this.status = r.status();
		this.reason = r.message() == null ? null : r.message().substring(0, Math.min(400, r.message().length()));
		this.timeZone = zone;
		this.bucketMinutes = bucketMinutes;
		this.trainedAt = now;
		this.dataFrom = r.counts().dataFrom();
		this.dataTo = r.counts().dataTo();
		this.samples = r.counts().samples();
		this.labeledBuckets = r.counts().labeledBuckets();
		this.positiveBuckets = r.counts().positiveBuckets();
		this.distinctDays = r.counts().distinctDays();
		this.modelJson = modelJson;
		this.metricsJson = metricsJson;
	}

	public boolean hasModel() {
		return modelJson != null;
	}

	public UUID getId() { return id; }
	public UUID getDeviceUuid() { return deviceUuid; }
	public OccupancyTrainer.Status getStatus() { return status; }
	public String getReason() { return reason; }
	public String getTimeZone() { return timeZone; }
	public int getBucketMinutes() { return bucketMinutes; }
	public Instant getTrainedAt() { return trainedAt; }
	public Instant getDataFrom() { return dataFrom; }
	public Instant getDataTo() { return dataTo; }
	public int getSamples() { return samples; }
	public int getLabeledBuckets() { return labeledBuckets; }
	public int getPositiveBuckets() { return positiveBuckets; }
	public int getDistinctDays() { return distinctDays; }
	public String getModelJson() { return modelJson; }
	public String getMetricsJson() { return metricsJson; }
}
