package com.smarthome.device;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.smarthome.command.CommandDtos.CommandResponse;
import com.smarthome.command.CommandDtos.OutputResponse;
import com.smarthome.command.CommandDtos.ReportedStateResponse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request/response shapes. None of the responses carry credentialHash or any stored secret. */
public final class DeviceDtos {

	private DeviceDtos() {
	}

	public record CreatePairingCodeRequest(@NotBlank @Size(max = 60) String name) {
	}

	/** The plain code is returned exactly once, here. */
	public record PairingCodeResponse(String code, String name, Instant expiresAt, long expiresInSeconds) {
	}

	public record RenameDeviceRequest(@NotBlank @Size(max = 60) String name) {
	}

	/**
	 * A stored reading. Null fields mean "no reading for that sensor". {@code ageSeconds} is measured by the server
	 * at response time so clients need not trust their own clock; {@code stale} means too old to call current.
	 */
	public record TelemetryResponse(Instant receivedAt, Instant deviceTimestamp, Double temperatureC, Double humidityPct,
			Boolean motion, Integer gasRaw, long ageSeconds, boolean stale) {
	}

	/**
	 * {@code connection} is derived from live evidence (see DeviceService.connectionOf), never from the row existing.
	 * {@code latestTelemetry} is null until the first message is stored.
	 */
	public record DeviceResponse(UUID id, String deviceId, String name, DeviceStatus status, Instant registeredAt,
			DeviceConnection connection, Instant lastSeenAt, Long lastSeenAgeSeconds, long staleAfterSeconds,
			TelemetryResponse latestTelemetry,
			List<OutputResponse> outputs, ReportedStateResponse reportedState, List<CommandResponse> recentCommands) {
	}

	/** Sent by the device itself (not a user), so there is no JWT: the pairing code is the proof. */
	public record DevicePairRequest(
			@NotBlank @Size(max = 32) String pairingCode,
			@NotBlank @Pattern(regexp = "[A-Za-z0-9:_-]{6,64}", message = "must be 6-64 characters: letters, digits, ':', '_' or '-'")
			String deviceId) {
	}

	/** The credential is returned exactly once, here. The device must store it. */
	public record DevicePairResponse(String deviceId, String name, String credential) {
	}

	public record DeviceSelfResponse(String deviceId, String name, DeviceStatus status) {
	}
}
