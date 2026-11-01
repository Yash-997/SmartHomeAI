package com.smarthome.security;

import java.util.Map;

/** Exception types for the security features; mapped to HTTP statuses in GlobalExceptionHandler. */
public final class SecurityExceptions {

	private SecurityExceptions() {
	}

	/** 400 with a per-field message. */
	public static class InvalidSecurityRequestException extends RuntimeException {
		private final Map<String, String> fieldErrors;

		public InvalidSecurityRequestException(String field, String message) {
			super("Invalid request");
			this.fieldErrors = Map.of(field, message);
		}

		public Map<String, String> fieldErrors() {
			return fieldErrors;
		}
	}

	/** 404: not found, or not yours (the two look the same). */
	public static class SecurityEventNotFoundException extends RuntimeException {
		public SecurityEventNotFoundException() {
			super("Security event not found");
		}
	}
}
