package com.smarthome.alert;

import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.smarthome.ml.AnomalySeverity;

/**
 * Anomaly emails (prefix app.alerts.anomaly). One email per anomaly (an ongoing problem keeps updating one row), and
 * none for the same device, kind and sensor within the cooldown after a successful one, so a problem that keeps
 * closing and reopening cannot flood the inbox. SMTP settings, sender and time zone are shared with gas and motion
 * alerts ({@link AlertProperties}).
 */
@ConfigurationProperties(prefix = "app.alerts.anomaly")
public record AnomalyAlertProperties(
		@DefaultValue("true") boolean emailEnabled,
		/** Lowest severity that is emailed: INFO, WARNING or CRITICAL. Invalid = WARNING. */
		@DefaultValue("WARNING") String emailMinSeverity,
		/** Minutes after a sent email during which the same problem on the same device is not emailed again. */
		@DefaultValue("60") int emailCooldownMinutes) {

	public AnomalySeverity minSeverity() {
		try {
			return AnomalySeverity.valueOf(emailMinSeverity == null ? "" : emailMinSeverity.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return AnomalySeverity.WARNING;
		}
	}

	public int cooldownMinutes() {
		return Math.max(0, emailCooldownMinutes);
	}
}
