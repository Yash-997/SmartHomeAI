package com.smarthome.alert;

import java.time.Instant;
import java.util.UUID;

public final class AlertDtos {

	private AlertDtos() {
	}

	/** {@code recipient} is masked; null when the account has no email. */
	public record ConfigResponse(boolean enabled, boolean mailConfigured, int threshold, int recoveryThreshold, int confirmReadings,
			String recipient) {
	}

	/** Result of a test email. {@code detail} is a short, secret-free reason. */
	public record TestEmailResponse(String status, String recipient, String detail) {
	}

	/** Anomaly email settings. {@code recipient} is masked; null when the account has no email. */
	public record AnomalyEmailConfig(boolean enabled, boolean mailConfigured, String minSeverity, int cooldownMinutes, String recipient) {
	}

	/** One anomaly and the outcome of its email: the same facts as the email and the report. */
	public record AnomalyEmailResponse(UUID id, UUID deviceUuid, String deviceName, String deviceId, String kind, String sensor, String severity,
			String status, String value, String expected, String description, Instant detectedAt, String emailStatus, Instant emailAt,
			String emailError) {
	}

	public record AlertResponse(UUID id, UUID deviceId, String deviceName, String state, int threshold, int triggerGasRaw,
			int peakGasRaw, int lastGasRaw, Instant startedAt, Instant recoveredAt, String alertEmailStatus, Instant alertEmailSentAt,
			String alertEmailError, String recoveryEmailStatus, Instant recoveryEmailSentAt, String recoveryEmailError) {

		static AlertResponse of(GasAlert a) {
			return new AlertResponse(a.getId(), a.getDeviceUuid(), a.getDeviceName(), a.getState().name(), a.getThreshold(),
					a.getTriggerGasRaw(), a.getPeakGasRaw(), a.getLastGasRaw(), a.getStartedAt(), a.getRecoveredAt(),
					a.getAlertEmailStatus().name(), a.getAlertEmailSentAt(), a.getAlertEmailError(),
					a.getRecoveryEmailStatus() == null ? null : a.getRecoveryEmailStatus().name(), a.getRecoveryEmailSentAt(),
					a.getRecoveryEmailError());
		}
	}
}
