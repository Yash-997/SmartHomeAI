package com.smarthome.device;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.config.DeviceAuthentication;
import com.smarthome.device.DeviceDtos.DevicePairRequest;
import com.smarthome.device.DeviceDtos.DevicePairResponse;
import com.smarthome.device.DeviceDtos.DeviceSelfResponse;
import com.smarthome.device.DeviceExceptions.InvalidPairingCodeException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Device-facing API, authenticated separately from users (see SecurityConfig): pairing uses a
 * pairing code, everything else uses the X-Device-Id / X-Device-Key headers. User JWTs do not work here.
 */
@RestController
@RequestMapping("/api/device")
public class DevicePairingController {

	private final PairingService pairing;
	private final PairAttemptLimiter limiter;
	private final DeviceRepository devices;

	public DevicePairingController(PairingService pairing, PairAttemptLimiter limiter, DeviceRepository devices) {
		this.pairing = pairing;
		this.limiter = limiter;
		this.devices = devices;
	}

	@PostMapping("/pair")
	public ResponseEntity<DevicePairResponse> pair(@Valid @RequestBody DevicePairRequest req, HttpServletRequest http) {
		String client = http.getRemoteAddr();
		limiter.checkAllowed(client);
		try {
			DevicePairResponse res = pairing.redeem(req.pairingCode(), req.deviceId());
			return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(res);
		} catch (InvalidPairingCodeException e) {
			limiter.recordFailure(client);
			throw e;
		}
	}

	/** Lets a device check that its credential is still valid (it stops working after disown). */
	@GetMapping("/me")
	public DeviceSelfResponse me(Authentication auth) {
		DeviceAuthentication device = (DeviceAuthentication) auth;
		Device d = devices.findByDeviceId(device.getDeviceId()).orElseThrow(DeviceExceptions.DeviceNotFoundException::new);
		return new DeviceSelfResponse(d.getDeviceId(), d.getName(), d.getStatus());
	}
}
