package com.smarthome.ml;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Tunables for the ML features (prefix {@code app.ml}). Every value has a safe default, so nothing needs to be set.
 * The thresholds are the "how much data is enough" rules: below them the system says so instead of guessing.
 */
@ConfigurationProperties(prefix = "app.ml")
public record MlProperties(@DefaultValue Occupancy occupancy, @DefaultValue Training training, @DefaultValue Anomaly anomaly) {

	/** Occupancy model: how history is turned into training examples, and how much is enough. */
	public record Occupancy(
			/** Width of a time slot. Must divide 60. */
			@DefaultValue("15") int bucketMinutes,
			/** A slot with fewer motion readings than this is ignored (never treated as "empty"). */
			@DefaultValue("10") int minReadingsPerBucket,
			/** Only the most recent N days of history are used. */
			@DefaultValue("28") int windowDays,
			/** Safety cap on rows loaded for one training run. */
			@DefaultValue("150000") int maxSamples,
			@DefaultValue("200") int minLabeledBuckets,
			@DefaultValue("3") int minDistinctDays,
			/** Both "occupied" and "unoccupied" slots are needed, at least this many of each. */
			@DefaultValue("20") int minClassBuckets,
			/** Each class must also appear at least this often in the training part of the split. */
			@DefaultValue("5") int minTrainClassBuckets,
			/** The most recent fraction of slots is held out for evaluation. */
			@DefaultValue("0.2") double testFraction,
			@DefaultValue("40") int minTestBuckets,
			/** L2 regularisation strength (the intercept is not penalised). */
			@DefaultValue("1.0") double ridgeLambda,
			@DefaultValue("0.5") double decisionThreshold,
			/** A prediction backed by fewer similar training slots than this is marked low-reliability. */
			@DefaultValue("4") int minSupport) {
	}

	/** When training happens. It is never triggered by individual telemetry messages. */
	public record Training(
			@DefaultValue("30") int minManualIntervalSeconds,
			@DefaultValue("true") boolean autoEnabled,
			@DefaultValue("3600000") long autoCheckMs,
			@DefaultValue("24") int retrainIntervalHours,
			/** How long to wait before trying again after "not enough data". */
			@DefaultValue("6") int insufficientRetryHours,
			/** A model is only retrained automatically when at least this many new readings arrived. */
			@DefaultValue("1500") int minNewSamplesForRetrain) {
	}

	/** Anomaly detection thresholds. */
	public record Anomaly(
			@DefaultValue("true") boolean scanEnabled,
			@DefaultValue("60000") long scanMs,
			/** The baseline ("normal") is learned from this many days of history... */
			@DefaultValue("7") int baselineDays,
			/** ...excluding the most recent minutes, so a problem cannot become its own baseline. */
			@DefaultValue("30") int baselineLagMinutes,
			@DefaultValue("30") int baselineRefreshMinutes,
			@DefaultValue("200") int minBaselineSamples,
			@DefaultValue("30") int minHourBaselineSamples,
			@DefaultValue("30000") int maxBaselineSamples,
			/** Robust z-score at which a reading becomes a WARNING / CRITICAL outlier. */
			@DefaultValue("4.0") double warnZ,
			@DefaultValue("6.0") double criticalZ,
			/** Smallest spread assumed for a perfectly flat history (avoids dividing by zero). */
			@DefaultValue("0.3") double minScaleTemperature,
			@DefaultValue("1.5") double minScaleHumidity,
			@DefaultValue("25") double minScaleGas,
			/** DHT22 usable range. Readings outside it are almost certainly a sensor fault. */
			@DefaultValue("-40") double temperatureMin,
			@DefaultValue("80") double temperatureMax,
			@DefaultValue("4095") int gasSaturationRaw,
			/** Identical consecutive readings that look "stuck". */
			@DefaultValue("60") int stuckCountTempHum,
			@DefaultValue("20") int stuckCountGas,
			/** Consecutive messages without a value for a sensor. */
			@DefaultValue("10") int missingCountTempHum,
			@DefaultValue("20") int missingCountGas,
			@DefaultValue("10") int staleMinutes,
			/** After this many days of silence no new "stale" alert is raised (the device is considered gone). */
			@DefaultValue("7") int staleGiveUpDays,
			/** Outlier-type anomalies that were not seen again for this long are closed automatically. */
			@DefaultValue("15") int autoResolveMinutes,
			/** A dismissed (false-positive) anomaly is not raised again for this long. */
			@DefaultValue("60") int suppressAfterDismissMinutes,
			@DefaultValue("10") int firstScanLookbackMinutes,
			@DefaultValue("60") int manualScanLookbackMinutes,
			@DefaultValue("10") int minManualScanIntervalSeconds,
			/** Readings fetched per scan for the stuck / missing checks. */
			@DefaultValue("120") int recentWindow,
			@DefaultValue("50") int maxNewReadingsPerScan) {
	}

	/** Defaults exactly as Spring would bind them with no configuration. Handy for unit tests. */
	public static MlProperties defaults() {
		return with(Map.of());
	}

	/** Defaults plus overrides given as {@code app.ml.occupancy.min-labeled-buckets} style keys. */
	public static MlProperties with(Map<String, String> overrides) {
		return new Binder(new MapConfigurationPropertySource(overrides)).bindOrCreate("app.ml", MlProperties.class);
	}
}
