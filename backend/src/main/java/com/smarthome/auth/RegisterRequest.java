package com.smarthome.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
		@NotBlank @Size(min = 3, max = 30)
		@Pattern(regexp = "[A-Za-z0-9_.-]*", message = "may contain only letters, digits, '.', '_' and '-'")
		String username,

		@NotBlank @Email @Size(max = 254)
		String email,

		// BCrypt only uses the first 72 bytes, so cap the length.
		@NotBlank @Size(min = 8, max = 72)
		String password) {
}
