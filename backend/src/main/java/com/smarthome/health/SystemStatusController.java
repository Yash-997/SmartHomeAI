package com.smarthome.health;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.health.SystemStatusDtos.SystemStatus;

/** Authenticated, per-user system status. The public {@code /api/health} liveness check is unchanged. */
@RestController
public class SystemStatusController {

	private final SystemStatusService service;

	public SystemStatusController(SystemStatusService service) {
		this.service = service;
	}

	@GetMapping("/api/system/status")
	public SystemStatus status(@AuthenticationPrincipal Jwt jwt) {
		return service.status(UUID.fromString(jwt.getSubject()));
	}
}
