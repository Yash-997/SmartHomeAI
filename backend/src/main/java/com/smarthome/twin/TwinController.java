package com.smarthome.twin;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.twin.TwinDtos.TwinResponse;

/** Read-only. User JWT required; a device that is not yours is a 404. */
@RestController
public class TwinController {

	private final TwinService service;

	public TwinController(TwinService service) {
		this.service = service;
	}

	@GetMapping("/api/twins")
	public List<TwinResponse> all(@AuthenticationPrincipal Jwt jwt) {
		return service.all(UUID.fromString(jwt.getSubject()));
	}

	@GetMapping("/api/devices/{deviceId}/twin")
	public TwinResponse one(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
		return service.get(UUID.fromString(jwt.getSubject()), deviceId);
	}
}
