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

	/** A cheap look at stored data, available even before any training. */
	public record DataSummary(long motionReadings, Instant firstAt, Instant lastAt) {
	}

	/**
	 * state: NOT_TRAINED, INSUFFICIENT_DATA, READY or LOW_QUALITY. {@code message} always says in plain words what is
	 * going on. {@code metrics} is null unless a model was evaluated.
	 */
	public record OccupancyStatusResponse(String state, boolean predictionsAvailable, String message, Instant trainedAt,
			String timeZone, CountsResponse counts, RequirementsResponse requirements, MetricsResponse metrics, DataSummary data,
			String method) {
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
