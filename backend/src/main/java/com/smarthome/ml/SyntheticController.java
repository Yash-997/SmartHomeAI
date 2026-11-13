package com.smarthome.ml;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.ml.SyntheticDtos.SyntheticForecastResponse;
import com.smarthome.ml.SyntheticDtos.SyntheticStatusResponse;

/**
 * The SYNTHETIC dataset and its demonstration model. User JWT required; the device must belong to the caller. Nothing
 * here runs by itself: generation and training happen only through these explicit requests.
 */
@RestController
@RequestMapping("/api/devices/{deviceId}/ml/synthetic")
public class SyntheticController {

	private final SyntheticService service;

	public SyntheticController(SyntheticService service) {
		this.service = service;
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	@GetMapping
	public SyntheticStatusResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
		return service.status(userId(jwt), deviceId);
	}

	/** Idempotent: the same settings return the existing dataset instead of creating another. */
	@PostMapping("/generate")
	public SyntheticStatusResponse generate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId,
			@RequestParam(required = false) Long seed, @RequestParam(required = false) Integer days) {
		return service.generate(userId(jwt), deviceId, seed, days);
	}

	@PostMapping("/train")
	public SyntheticStatusResponse train(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
		return service.train(userId(jwt), deviceId);
	}

	@GetMapping("/forecast")
	public SyntheticForecastResponse forecast(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId,
			@RequestParam(defaultValue = "24") int hours) {
		return service.forecast(userId(jwt), deviceId, hours);
	}
}
