package com.smarthome.device;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.device.DeviceDtos.CreatePairingCodeRequest;
import com.smarthome.device.DeviceDtos.DeviceResponse;
import com.smarthome.device.DeviceDtos.PairingCodeResponse;
import com.smarthome.device.DeviceDtos.RenameDeviceRequest;
import com.smarthome.device.DeviceDtos.TelemetryResponse;

import jakarta.validation.Valid;

/** User-facing device API. Requires a user JWT; the owner is always taken from the token's subject. */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

	private final DeviceService devices;
	private final PairingService pairing;

	public DeviceController(DeviceService devices, PairingService pairing) {
		this.devices = devices;
		this.pairing = pairing;
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	@PostMapping("/pairing-codes")
	public ResponseEntity<PairingCodeResponse> createPairingCode(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody CreatePairingCodeRequest req) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(pairing.createCode(userId(jwt), req.name()));
	}

	@GetMapping
	public List<DeviceResponse> list(@AuthenticationPrincipal Jwt jwt) {
		return devices.list(userId(jwt));
	}

	@GetMapping("/{id}")
	public DeviceResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return devices.get(userId(jwt), id);
	}

	/** Most recent stored readings, newest first (limit 1-200). */
	@GetMapping("/{id}/telemetry")
	public List<TelemetryResponse> telemetry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@RequestParam(defaultValue = "50") int limit) {
		return devices.history(userId(jwt), id, limit);
	}

	@PatchMapping("/{id}")
	public DeviceResponse rename(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody RenameDeviceRequest req) {
		return devices.rename(userId(jwt), id, req.name());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void disown(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		devices.disown(userId(jwt), id);
	}
}
