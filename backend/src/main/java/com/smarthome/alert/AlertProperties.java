package com.smarthome.alert;

import java.net.URI;
import java.time.ZoneId;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables for email alerts (prefix app.alerts): gas/smoke incidents and motion while AWAY. Gas readings are raw MQ-2 ADC
 * values (0-4095), never ppm.
 */
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
		@DefaultValue("") String mailFrom,
		/** Email the owner once per motion episode detected in AWAY mode. */
		@DefaultValue("true") boolean motionEmailEnabled,
		/** Where the "Open dashboard" buttons point (the web app's address as the owner reaches it). Blank = no button. */
		@DefaultValue("") String dashboardUrl,
		/** Time zone for times shown in emails, e.g. "Asia/Kolkata". Blank or invalid = the server's zone. */
		@DefaultValue("") String timeZone) {

	/** The reading below which an active incident may end. */
	public int recoveryThreshold() {
		int margin = Math.max(0, Math.min(90, recoveryMarginPercent));
		return Math.max(0, (int) Math.floor(gasThreshold * (100 - margin) / 100.0));
	}

	public int confirm() {
		return Math.max(1, confirmReadings);
	}

	public ZoneId zone() {
		try {
			return timeZone == null || timeZone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timeZone.trim());
		} catch (RuntimeException e) {
			return ZoneId.systemDefault();
		}
	}

	/**
	 * The dashboard base URL without a trailing slash, or null when unset or unsafe. Only plain http(s) addresses are used,
	 * and never one that carries a user name or password.
	 */
	public String dashboardBase() {
		if (dashboardUrl == null || dashboardUrl.isBlank()) {
			return null;
		}
		try {
			URI u = URI.create(dashboardUrl.trim());
			String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
			if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getRawUserInfo() != null) {
				return null;
			}
			String s = u.toString();
			return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
