package com.smarthome.security;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.security.SecurityDtos.ConfigRequest;
import com.smarthome.security.SecurityDtos.EventResponse;
import com.smarthome.security.SecurityDtos.ModeRequest;
import com.smarthome.security.SecurityDtos.SettingsResponse;

import jakarta.validation.Valid;

/** User JWT required. Everything is scoped to the token's user; there is no way to name another user. */
@RestController
@RequestMapping("/api/security")
public class SecurityController {

	private final SecurityService service;

	public SecurityController(SecurityService service) {
		this.service = service;
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	@GetMapping("/settings")
	public SettingsResponse settings(@AuthenticationPrincipal Jwt jwt) {
		return service.get(userId(jwt));
	}

	@PutMapping("/mode")
	public SettingsResponse mode(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ModeRequest req) {
		return service.setMode(userId(jwt), req.mode());
	}

	@PutMapping("/config")
	public SettingsResponse config(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConfigRequest req) {
		return service.configure(userId(jwt), req.away(), req.night());
	}

	@GetMapping("/events")
	public List<EventResponse> events(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String kind) {
		return service.events(userId(jwt), limit, kind);
	}

	@PostMapping("/events/{id}/acknowledge")
	public EventResponse acknowledge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return service.acknowledge(userId(jwt), id);
	}

	@PostMapping("/events/acknowledge-all")
	public Map<String, Integer> acknowledgeAll(@AuthenticationPrincipal Jwt jwt) {
		return Map.of("acknowledged", service.acknowledgeAll(userId(jwt)));
	}
}
