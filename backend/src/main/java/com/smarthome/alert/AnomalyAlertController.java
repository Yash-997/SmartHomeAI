package com.smarthome.alert;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.alert.AlertDtos.AnomalyEmailConfig;
import com.smarthome.alert.AlertDtos.AnomalyEmailResponse;

/** User JWT required. Read-only and scoped to the token's user: settings come from .env. */
@RestController
@RequestMapping("/api/alerts/anomaly")
public class AnomalyAlertController {

	private final AnomalyEmailService service;

	public AnomalyAlertController(AnomalyEmailService service) {
		this.service = service;
	}

	@GetMapping("/config")
	public AnomalyEmailConfig config(@AuthenticationPrincipal Jwt jwt) {
		return service.config(UUID.fromString(jwt.getSubject()));
	}

	@GetMapping
	public List<AnomalyEmailResponse> history(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "20") int limit) {
		return service.history(UUID.fromString(jwt.getSubject()), limit);
	}
}
