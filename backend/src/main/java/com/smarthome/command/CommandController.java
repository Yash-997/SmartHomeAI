package com.smarthome.command;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.command.CommandDtos.CommandResponse;
import com.smarthome.command.CommandDtos.CreateCommandRequest;

import jakarta.validation.Valid;

/** Requires a user JWT (user security chain). The owner is always the token's subject, never a request field. */
@RestController
@RequestMapping("/api/devices/{deviceId}/commands")
public class CommandController {

	private final CommandService commands;

	public CommandController(CommandService commands) {
		this.commands = commands;
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	/** 202: accepted for delivery. Progress is read back from GET; the device confirms asynchronously. */
	@PostMapping
	public ResponseEntity<CommandResponse> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId,
			@Valid @RequestBody CreateCommandRequest req) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(commands.create(userId(jwt), deviceId, req));
	}

	@GetMapping
	public List<CommandResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId,
			@RequestParam(defaultValue = "20") int limit) {
		return commands.list(userId(jwt), deviceId, limit);
	}

	@GetMapping("/{commandId}")
	public CommandResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId, @PathVariable UUID commandId) {
		return commands.get(userId(jwt), deviceId, commandId);
	}
}
