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

	/**
	 * Evaluation on the most recent slots, which the evaluation model did not see during fitting. Values that are not
	 * defined (for example precision with no positive predictions) are null, never invented. The baseline is the score
	 * of always guessing the training average.
	 */
	public record MetricsResponse(int trainSlots, int testSlots, int testSlotsWithMotion, double accuracy, Double precision,
			Double recall, Double f1, Double auc, double logLoss, double brier, double baselineAccuracy, double baselineLogLoss,
			String evaluation) {
	}

	/** One change in connectivity. state: ONLINE or OFFLINE. */
	public record StatusEventResponse(String state, String reason, Instant changedAt, Instant lastSeenAt) {
	}

	public record ConnectivityResponse(String currentState, long offlineEventsLast7Days, List<StatusEventResponse> events) {
	}

	public record AnomalyResponse(UUID id, AnomalyKind kind, String scope, AnomalySeverity severity, AnomalyStatus status,
			String explanation, JsonNode details, Instant detectedAt, Instant lastSeenAt, int occurrences, Instant readingAt,
			Instant resolvedAt, String resolutionNote, String emailStatus, Instant emailAt, String emailError, String detector,
			Double detectedValue, Double expectedLow, Double expectedHigh) {
	}

	public record AnomalySummary(long active, long critical, long warning, long info, Instant lastScanAt) {
	}

	/**
	 * One stored anomaly model. state: READY, LOW_QUALITY or INSUFFICIENT_DATA. {@code model}: forests, threshold and seed
	 * (null when none was trained). {@code evaluation}: for REAL data the alarm rates (no labels exist, so no precision or
	 * recall); for SYNTHETIC data the full labelled evaluation.
	 */
	public record AnomalyModelInfo(String modelType, String dataSource, String state, int version, Instant trainedAt, String message,
			String timeZone, Instant dataFrom, Instant dataTo, int trainingMinutes, JsonNode model, JsonNode evaluation) {
	}

	/** {@code liveDetection}: the real-data model is READY and the live scanner uses it. */
	public record AnomalyModelStatusResponse(AnomalyModelInfo real, AnomalyModelInfo synthetic, boolean liveDetection, String notice) {
	}

	public record MlStatusResponse(com.smarthome.predict.PredictiveModelService.ModelInfo occupancy, AnomalySummary anomalies, String notice) {
	}

	/** status: ACKNOWLEDGED, RESOLVED or DISMISSED. */
	public record ResolveRequest(@NotBlank String status, @Size(max = 200) String note) {
	}

	public record ScanResponse(Instant scannedAt, int findings, long activeAnomalies) {
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
}
