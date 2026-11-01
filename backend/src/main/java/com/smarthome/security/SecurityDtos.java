package com.smarthome.security;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request and response shapes of the security API. The owner always comes from the token, never from a body. */
public final class SecurityDtos {

	private SecurityDtos() {
	}

	/**
	 * What AWAY or NIGHT does. {@code cooldownSeconds}: at most one motion alert per device in that time.
	 * {@code lightOnMotion}: also set the PIR indicator LED to "on" through the normal command service
	 * (it is given back to "auto" when the mode ends). It needs {@code motionAlerts}. It can never touch the gas LED.
	 */
	public record ModeConfigDto(@NotNull Boolean motionAlerts, @NotNull @Min(10) @Max(3600) Integer cooldownSeconds, @NotNull Boolean lightOnMotion) {
	}

	/** Either part may be omitted to leave it unchanged. */
	public record ConfigRequest(@Valid ModeConfigDto away, @Valid ModeConfigDto night) {
	}

	public record ModeRequest(@NotBlank String mode) {
	}

	public record SettingsResponse(SecurityMode mode, Instant modeSince, ModeConfigDto away, ModeConfigDto night, long unacknowledgedAlerts,
			boolean pirLedForcedBySecurity, String notice) {
	}

	public record EventResponse(UUID id, UUID deviceId, SecurityEvent.Kind kind, SecurityEvent.Severity severity, SecurityMode mode,
			String message, Instant createdAt, Instant acknowledgedAt) {
	}
}
