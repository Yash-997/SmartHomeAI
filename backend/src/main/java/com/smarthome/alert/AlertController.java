package com.smarthome.alert;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.alert.AlertDtos.AlertResponse;
import com.smarthome.alert.AlertDtos.ConfigResponse;
import com.smarthome.alert.AlertDtos.TestEmailResponse;

/** User JWT required. Everything is scoped to the token's user; there is no way to name another user. */
@RestController
@RequestMapping("/api/alerts/gas")
public class AlertController {

	private final GasAlertService service;

	public AlertController(GasAlertService service) {
		this.service = service;
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	@GetMapping("/config")
	public ConfigResponse config(@AuthenticationPrincipal Jwt jwt) {
		return service.config(userId(jwt));
	}

	/** Sends a test email to the caller's own address. No incident is created. 429 if called again within a minute. */
	@PostMapping("/test-email")
	public ResponseEntity<TestEmailResponse> testEmail(@AuthenticationPrincipal Jwt jwt) {
		return service.sendTestEmail(userId(jwt)).map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
						.body(new TestEmailResponse("RATE_LIMITED", null, "Wait a minute before sending another test email.")));
	}

	@GetMapping
	public List<AlertResponse> history(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "20") int limit) {
		return service.history(userId(jwt), limit).stream().map(AlertResponse::of).toList();
	}
}
