package com.smarthome.ml;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Request and response shapes for the ML endpoints. */
public final class MlDtos {

	private MlDtos() {
	}

	/** What the history contained. A "slot" is a short time window (default 15 minutes) with enough readings to be used. */
	public record CountsResponse(int motionReadings, int usableSlots, int slotsWithMotion, int slotsWithoutMotion,
			int sparseSlotsIgnored, int distinctDays, Instant dataFrom, Instant dataTo) {
	}

	/** The minimum data needed before a model is trained, so the UI can show progress. */
	public record RequirementsResponse(int slotMinutes, int minReadingsPerSlot, int minUsableSlots, int minDistinctDays,
			int minSlotsPerClass) {
	}

	/**
	 * Evaluation on the most recent slots, which the evaluation model did not see during fitting. Values that are not
	 * defined (for example precision with no positive predictions) are null, never invented. The baseline is the score
	 * of always guessing the training average.
	 */
	public record MetricsResponse(int trainSlots, int testSlots, int testSlotsWithMotion, double accuracy, Double precision,
			Double recall, Double f1, Double auc, double logLoss, double brier, double baselineAccuracy, double baselineLogLoss,
			String evaluation) {
	}

	/**
	 * The training dataset as it would be built right now from stored readings (same rules as training). Recomputed at
	 * most every few minutes; {@code checkedAt} says when.
	 */
	public record DatasetSnapshot(int usableSlots, int slotsWithMotion, int slotsWithoutMotion, int sparseSlotsIgnored, int distinctDays,
			boolean enoughForTraining, Instant checkedAt) {
	}

	/**
	 * What has been collected for this device. {@code totalRecords}: every stored telemetry message (all sensors);
	 * {@code collectionStartedAt}: the first one. {@code motionReadings}, {@code firstAt}, {@code lastAt}: messages that
	 * carried a motion value, which is what occupancy learning uses.
	 */
	public record DataSummary(long totalRecords, Instant collectionStartedAt, long recordsLast24h, long motionReadings, Instant firstAt,
			Instant lastAt, DatasetSnapshot dataset) {
	}

	/**
	 * state: NOT_TRAINED, INSUFFICIENT_DATA, READY or LOW_QUALITY. {@code collecting} is true while there is no usable
	 * model yet (the dashboard then says "Collecting historical data"). {@code message} always says in plain words what is
	 * going on. {@code metrics} is null unless a model was evaluated. {@code modelVersion} is 0 before the first model.
	 */
	public record OccupancyStatusResponse(String state, boolean predictionsAvailable, String message, Instant trainedAt,
			String timeZone, CountsResponse counts, RequirementsResponse requirements, MetricsResponse metrics, DataSummary data,
			String method, boolean collecting, int modelVersion, Instant lastSuccessfulTrainingAt, long trainingRuns, String featureSet,
			boolean autoTraining) {
	}

	/** One training run from the history. {@code version} is null when the run produced no model. */
	public record TrainingRunResponse(UUID id, Integer version, String trigger, String status, String reason, boolean becameCurrent,
			Instant trainedAt, long durationMs, String timeZone, String featureSet, int samples, int usableSlots, int slotsWithMotion,
			int distinctDays, Instant dataFrom, Instant dataTo, MetricsResponse metrics) {
	}

	/**
	 * One hour of the week in the learned pattern. dayOfWeek: 1 = Monday ... 7 = Sunday. {@code slots}: training slots in
	 * that hour; {@code observedRate}: share of them with motion (null with none); {@code modelProbability}: what the
	 * current model predicts for the middle of that hour.
	 */
	public record PatternCell(int dayOfWeek, int hour, int slots, Double observedRate, double modelProbability) {
	}

	public record PatternResponse(String timeZone, int modelVersion, String quality, Instant trainedAt, double overallRate,
			List<PatternCell> cells) {
	}

	/** One change in connectivity. state: ONLINE or OFFLINE. */
	public record StatusEventResponse(String state, String reason, Instant changedAt, Instant lastSeenAt) {
	}

	public record ConnectivityResponse(String currentState, long offlineEventsLast7Days, List<StatusEventResponse> events) {
	}

	/**
	 * probability: chance the PIR sees motion at that time. confidence: how decisive that is (the larger of p and 1-p),
	 * not a guarantee. support: how many training slots from the same weekday and hour stand behind it; reliable is
	 * false when that is too few, or when the model as a whole is LOW_QUALITY. observedRate: what actually happened in
	 * those slots (null if none).
	 */
	public record PredictionResponse(Instant at, String localTime, String dayOfWeek, int hour, double probability,
			boolean predictedOccupied, double confidence, int support, boolean reliable, Double observedRate, String quality) {
	}

	public record ForecastResponse(String timeZone, String quality, List<PredictionResponse> points) {
	}

	public record AnomalyResponse(UUID id, AnomalyKind kind, String scope, AnomalySeverity severity, AnomalyStatus status,
			String explanation, JsonNode details, Instant detectedAt, Instant lastSeenAt, int occurrences, Instant readingAt,
			Instant resolvedAt, String resolutionNote) {
	}

	public record AnomalySummary(long active, long critical, long warning, long info, Instant lastScanAt) {
	}

	public record MlStatusResponse(OccupancyStatusResponse occupancy, AnomalySummary anomalies, String notice) {
	}

	/** status: ACKNOWLEDGED, RESOLVED or DISMISSED. */
	public record ResolveRequest(@NotBlank String status, @Size(max = 200) String note) {
	}

	public record ScanResponse(Instant scannedAt, int findings, long activeAnomalies) {
	}
}
