package com.smarthome.auth;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.user.User;
import com.smarthome.user.UserProfile;
import com.smarthome.user.UserRepository;

@Service
public class AuthService {

	private final UserRepository users;
	private final PasswordEncoder encoder;
	private final JwtService jwt;
	/** Compared against when the username is unknown, so response time doesn't reveal which usernames exist. */
	private final String dummyHash;

	public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt) {
		this.users = users;
		this.encoder = encoder;
		this.jwt = jwt;
		this.dummyHash = encoder.encode("not-a-real-password");
	}

	public AuthResponse register(RegisterRequest req) {
		String username = req.username().trim();
		String email = req.email().trim().toLowerCase();
		if (users.existsByUsernameIgnoreCase(username)) {
			throw new ConflictException("Username is already taken");
		}
		if (users.existsByEmailIgnoreCase(email)) {
			throw new ConflictException("Email is already registered");
		}
		User user;
		try {
			user = users.saveAndFlush(new User(username, email, encoder.encode(req.password())));
		} catch (DataIntegrityViolationException e) {
			// Lost a race with a concurrent registration.
			throw new ConflictException("Username or email is already in use");
		}
		return respond(user);
	}

	@Transactional(readOnly = true)
	public AuthResponse login(LoginRequest req) {
		User user = users.findByUsernameIgnoreCase(req.username().trim()).orElse(null);
		String hash = user != null ? user.getPasswordHash() : dummyHash;
		boolean passwordMatches = encoder.matches(req.password(), hash);
		if (user == null || !passwordMatches) {
			throw new InvalidCredentialsException();
		}
		return respond(user);
	}

	private AuthResponse respond(User user) {
		return new AuthResponse(jwt.issue(user), "Bearer", jwt.lifetimeSeconds(), UserProfile.from(user));
	}
}
