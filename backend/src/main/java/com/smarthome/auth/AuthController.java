package com.smarthome.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.user.UserProfile;
import com.smarthome.user.UserRepository;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService auth;
	private final UserRepository users;

	public AuthController(AuthService auth, UserRepository users) {
		this.auth = auth;
		this.users = users;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
		return auth.register(req);
	}

	@PostMapping("/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest req) {
		return auth.login(req);
	}

	@GetMapping("/me")
	public UserProfile me(@AuthenticationPrincipal Jwt jwt) {
		return users.findById(UUID.fromString(jwt.getSubject()))
				.map(UserProfile::from)
				// Valid token for a user that no longer exists.
				.orElseThrow(InvalidCredentialsException::new);
	}
}
