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

/**
 * One training run, kept as history (ml_models only holds the current model). Records what data was used, what came
 * out, the evaluation, and whether the result became the current model. Never updated after it is written.
 */
@Entity
@Table(name = "ml_training_runs")
public class MlTrainingRun {

	public enum Trigger { MANUAL, AUTO }

	/** Where the training data came from: the device's real telemetry, or a generated synthetic dataset. */
	public enum DataSource { REAL, SYNTHETIC }

	@Id
	private UUID id;

	@Column(name = "device_uuid", nullable = false, updatable = false)
	private UUID deviceUuid;

	@Column(name = "model_type", nullable = false, length = 20, updatable = false)
	private String modelType;

	@Column(updatable = false)
	private Integer version;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "trigger_kind", nullable = false, length = 10, updatable = false)
	private Trigger trigger;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20, updatable = false)
	private OccupancyTrainer.Status status;

	@Column(length = 400, updatable = false)
	private String reason;

	@Column(name = "became_current", nullable = false, updatable = false)
	private boolean becameCurrent;

	@Column(name = "time_zone", nullable = false, length = 50, updatable = false)
	private String timeZone;

	@Column(name = "feature_set", nullable = false, length = 120, updatable = false)
	private String featureSet;

	@Column(name = "trained_at", nullable = false, updatable = false)
	private Instant trainedAt;

	@Column(name = "duration_ms", nullable = false, updatable = false)
	private long durationMs;

	@Column(name = "data_from", updatable = false)
	private Instant dataFrom;

	@Column(name = "data_to", updatable = false)
	private Instant dataTo;

	@Column(nullable = false, updatable = false)
	private int samples;

	@Column(name = "labeled_buckets", nullable = false, updatable = false)
	private int labeledBuckets;

	@Column(name = "positive_buckets", nullable = false, updatable = false)
	private int positiveBuckets;

	@Column(name = "distinct_days", nullable = false, updatable = false)
	private int distinctDays;

	@Column(name = "metrics_json", length = 2000, updatable = false)
	private String metricsJson;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "data_source", nullable = false, length = 10, updatable = false)
	private DataSource dataSource = DataSource.REAL;

	@Column(name = "dataset_id", updatable = false)
	private UUID datasetId;

	protected MlTrainingRun() {
	}

	/** A run on the device's real telemetry. */
	public MlTrainingRun(UUID deviceUuid, Integer version, Trigger trigger, OccupancyTrainer.Result r, boolean becameCurrent, String zone,
			String featureSet, Instant trainedAt, long durationMs, String metricsJson) {
		this(deviceUuid, MlModel.OCCUPANCY, DataSource.REAL, null, version, trigger, r, becameCurrent, zone, featureSet, trainedAt, durationMs,
				metricsJson);
	}

	public MlTrainingRun(UUID deviceUuid, String modelType, DataSource dataSource, UUID datasetId, Integer version, Trigger trigger,
			OccupancyTrainer.Result r, boolean becameCurrent, String zone, String featureSet, Instant trainedAt, long durationMs, String metricsJson) {
		this.id = UUID.randomUUID();
		this.deviceUuid = deviceUuid;
		this.modelType = modelType;
		this.dataSource = dataSource;
		this.datasetId = datasetId;
		this.version = version;
		this.trigger = trigger;
		this.status = r.status();
		this.reason = r.message() == null ? null : r.message().substring(0, Math.min(400, r.message().length()));
		this.becameCurrent = becameCurrent;
		this.timeZone = zone;
		this.featureSet = featureSet;
		this.trainedAt = trainedAt;
		this.durationMs = durationMs;
		this.dataFrom = r.counts().dataFrom();
		this.dataTo = r.counts().dataTo();
		this.samples = r.counts().samples();
		this.labeledBuckets = r.counts().labeledBuckets();
		this.positiveBuckets = r.counts().positiveBuckets();
		this.distinctDays = r.counts().distinctDays();
		this.metricsJson = metricsJson;
	}

	public UUID getId() { return id; }
	public UUID getDeviceUuid() { return deviceUuid; }
	public Integer getVersion() { return version; }
	public Trigger getTrigger() { return trigger; }
	public OccupancyTrainer.Status getStatus() { return status; }
	public String getReason() { return reason; }
	public boolean isBecameCurrent() { return becameCurrent; }
	public String getTimeZone() { return timeZone; }
	public String getFeatureSet() { return featureSet; }
	public Instant getTrainedAt() { return trainedAt; }
	public long getDurationMs() { return durationMs; }
	public Instant getDataFrom() { return dataFrom; }
	public Instant getDataTo() { return dataTo; }
	public int getSamples() { return samples; }
	public int getLabeledBuckets() { return labeledBuckets; }
	public int getPositiveBuckets() { return positiveBuckets; }
	public int getDistinctDays() { return distinctDays; }
	public String getMetricsJson() { return metricsJson; }
	public DataSource getDataSource() { return dataSource; }
	public UUID getDatasetId() { return datasetId; }
	public String getModelType() { return modelType; }
}
