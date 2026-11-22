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
	/** A demonstration model trained on a generated dataset. Never used for the device's real predictions. */
	public static final String OCCUPANCY_SYNTHETIC = "OCCUPANCY_SYNTH";
	/** The Isolation Forest anomaly model trained on the device's real telemetry. The only one used for live detection. */
	public static final String ANOMALY_IF = "ANOMALY_IF";
	/** The Isolation Forest trained on the device's SYNTHETIC dataset. Evaluation and demonstration only. */
	public static final String ANOMALY_IF_SYNTHETIC = "ANOMALY_IF_SYNTH";

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

	/** The full anomaly model (forests, preprocessing, threshold) as JSON; null for occupancy models. */
	@Column(name = "artifact_json")
	private String artifactJson;

	/** 0 until the first model; +1 every time a training run produces a new current model. */
	@Column(nullable = false)
	private int version;

	protected MlModel() {
	}

	public MlModel(UUID deviceUuid) {
		this(deviceUuid, OCCUPANCY);
	}

	public MlModel(UUID deviceUuid, String modelType) {
		this.id = UUID.randomUUID();
		this.deviceUuid = deviceUuid;
		this.modelType = modelType;
	}

	public String getModelType() { return modelType; }

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
		if (modelJson != null) {
			this.version++;
		}
	}

	/**
	 * Replaces everything with the outcome of an anomaly-model training run. For anomaly models a "bucket" is one minute:
	 * {@code labeledBuckets} holds the training minutes and {@code positiveBuckets} the minutes left out of training.
	 * {@code summaryJson} is a short description; {@code artifactJson} the model itself (null when none was trained).
	 */
	public void recordAnomalyModel(OccupancyTrainer.Status status, String message, String zone, Instant from, Instant to, int samples,
			int trainingMinutes, int excludedMinutes, int distinctDays, String summaryJson, String artifactJson, String metricsJson, Instant now) {
		this.status = status;
		this.reason = message == null ? null : message.substring(0, Math.min(400, message.length()));
		this.timeZone = zone;
		this.bucketMinutes = 1;
		this.trainedAt = now;
		this.dataFrom = from;
		this.dataTo = to;
		this.samples = samples;
		this.labeledBuckets = trainingMinutes;
		this.positiveBuckets = excludedMinutes;
		this.distinctDays = distinctDays;
		this.modelJson = artifactJson == null ? null : summaryJson;
		this.artifactJson = artifactJson;
		this.metricsJson = metricsJson;
		if (artifactJson != null) {
			this.version++;
		}
	}

	public String getArtifactJson() { return artifactJson; }

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
	public int getVersion() { return version; }
}
