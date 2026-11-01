package com.smarthome.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Tunables for gas/smoke email alerts (prefix app.alerts). Readings are raw MQ-2 ADC values (0-4095), never ppm. */
@ConfigurationProperties(prefix = "app.alerts")
public record AlertProperties(
		@DefaultValue("true") boolean enabled,
		/** An incident starts when the reading is at or above this. */
		@DefaultValue("2500") int gasThreshold,
		/** Readings must fall below threshold minus this percentage before the incident counts as over (hysteresis). */
		@DefaultValue("15") int recoveryMarginPercent,
		/** Consecutive readings needed to start an incident or to end it, so one noisy sample does neither. */
		@DefaultValue("2") int confirmReadings,
		/** Sender address. Blank = the SMTP username (Gmail requires the sender to be that account). */
		@DefaultValue("") String mailFrom) {

	/** The reading below which an active incident may end. */
	public int recoveryThreshold() {
		int margin = Math.max(0, Math.min(90, recoveryMarginPercent));
		return Math.max(0, (int) Math.floor(gasThreshold * (100 - margin) / 100.0));
	}

	public int confirm() {
		return Math.max(1, confirmReadings);
	}
}
