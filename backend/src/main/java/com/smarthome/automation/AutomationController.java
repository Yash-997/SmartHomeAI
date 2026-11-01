package com.smarthome.automation;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.automation.AutomationDtos.EnabledRequest;
import com.smarthome.automation.AutomationDtos.ExecutionResponse;
import com.smarthome.automation.AutomationDtos.RuleRequest;
import com.smarthome.automation.AutomationDtos.RuleResponse;

import jakarta.validation.Valid;

/** Requires a user JWT (user security chain). The owner is always the token's subject, never a request field. */
@RestController
@RequestMapping("/api/automations")
public class AutomationController {

	private final AutomationService automations;

	public AutomationController(AutomationService automations) {
		this.automations = automations;
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public RuleResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RuleRequest req) {
		return automations.create(userId(jwt), req);
	}

	@GetMapping
	public List<RuleResponse> list(@AuthenticationPrincipal Jwt jwt) {
		return automations.list(userId(jwt));
	}

	/** Audit trail across all of the user's rules, newest first. */
	@GetMapping("/executions")
	public List<ExecutionResponse> allHistory(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "50") int limit) {
		return automations.history(userId(jwt), limit);
	}

	@GetMapping("/{id}")
	public RuleResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return automations.get(userId(jwt), id);
	}

	@PutMapping("/{id}")
	public RuleResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody RuleRequest req) {
		return automations.update(userId(jwt), id, req);
	}

	@PatchMapping("/{id}/enabled")
	public RuleResponse setEnabled(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody EnabledRequest req) {
		return automations.setEnabled(userId(jwt), id, req.enabled());
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		automations.delete(userId(jwt), id);
	}

	@GetMapping("/{id}/executions")
	public List<ExecutionResponse> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
		return automations.history(userId(jwt), id, limit);
	}
}
