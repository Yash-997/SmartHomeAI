package com.smarthome.security;

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

/** A user's current security mode and what AWAY and NIGHT do. No row means HOME with defaults. */
@Entity
@Table(name = "security_settings")
public class SecuritySettings {

	public static final int DEFAULT_COOLDOWN_SECONDS = 60;

	@Id
	@Column(name = "owner_user_id")
	private UUID ownerUserId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 8)
	private SecurityMode mode;

	@Column(name = "mode_changed_at", nullable = false)
	private Instant modeChangedAt;

	@Column(name = "away_motion_alerts", nullable = false)
	private boolean awayMotionAlerts;

	@Column(name = "away_cooldown_s", nullable = false)
	private int awayCooldownSeconds;

	@Column(name = "away_light_on_motion", nullable = false)
	private boolean awayLightOnMotion;

	@Column(name = "night_motion_alerts", nullable = false)
	private boolean nightMotionAlerts;

	@Column(name = "night_cooldown_s", nullable = false)
	private int nightCooldownSeconds;

	@Column(name = "night_light_on_motion", nullable = false)
	private boolean nightLightOnMotion;

	@Column(name = "pir_led_forced", nullable = false)
	private boolean pirLedForced;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected SecuritySettings() {
	}

	/** HOME, alerts on in both armed modes, one alert per device per minute, no LED action. */
	public static SecuritySettings defaults(UUID owner, Instant now) {
		SecuritySettings s = new SecuritySettings();
		s.ownerUserId = owner;
		s.mode = SecurityMode.HOME;
		s.modeChangedAt = now;
		s.awayMotionAlerts = true;
		s.awayCooldownSeconds = DEFAULT_COOLDOWN_SECONDS;
		s.awayLightOnMotion = false;
		s.nightMotionAlerts = true;
		s.nightCooldownSeconds = DEFAULT_COOLDOWN_SECONDS;
		s.nightLightOnMotion = false;
		s.pirLedForced = false;
		s.updatedAt = now;
		return s;
	}

	/** The behaviour for {@code m}; HOME has none. */
	public ModeConfig config(SecurityMode m) {
		return switch (m) {
			case AWAY -> new ModeConfig(awayMotionAlerts, awayCooldownSeconds, awayLightOnMotion);
			case NIGHT -> new ModeConfig(nightMotionAlerts, nightCooldownSeconds, nightLightOnMotion);
			case HOME -> new ModeConfig(false, DEFAULT_COOLDOWN_SECONDS, false);
		};
	}

	public record ModeConfig(boolean motionAlerts, int cooldownSeconds, boolean lightOnMotion) {
	}

	public void setConfig(SecurityMode m, ModeConfig c, Instant now) {
		if (m == SecurityMode.AWAY) {
			awayMotionAlerts = c.motionAlerts();
			awayCooldownSeconds = c.cooldownSeconds();
			awayLightOnMotion = c.lightOnMotion();
		} else if (m == SecurityMode.NIGHT) {
			nightMotionAlerts = c.motionAlerts();
			nightCooldownSeconds = c.cooldownSeconds();
			nightLightOnMotion = c.lightOnMotion();
		} else {
			throw new IllegalArgumentException("HOME has no settings");
		}
		updatedAt = now;
	}

	public void setMode(SecurityMode m, Instant now) {
		this.mode = m;
		this.modeChangedAt = now;
		this.updatedAt = now;
	}

	public void setPirLedForced(boolean forced) {
		this.pirLedForced = forced;
	}

	public UUID getOwnerUserId() {
		return ownerUserId;
	}

	public SecurityMode getMode() {
		return mode;
	}

	public Instant getModeChangedAt() {
		return modeChangedAt;
	}

	public boolean isPirLedForced() {
		return pirLedForced;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
