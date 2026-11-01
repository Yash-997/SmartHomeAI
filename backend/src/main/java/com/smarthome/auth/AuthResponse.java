package com.smarthome.auth;

import com.smarthome.user.UserProfile;

public record AuthResponse(String token, String tokenType, long expiresInSeconds, UserProfile user) {
}
