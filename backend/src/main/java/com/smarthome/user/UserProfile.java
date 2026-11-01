package com.smarthome.user;

import java.time.Instant;
import java.util.UUID;

/** Public view of a user. Deliberately has no password hash. */
public record UserProfile(UUID id, String username, String email, Instant createdAt) {

	public static UserProfile from(User u) {
		return new UserProfile(u.getId(), u.getUsername(), u.getEmail(), u.getCreatedAt());
	}
}
