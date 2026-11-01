package com.smarthome.config;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String error, String message, Map<String, String> fieldErrors) {

	public static ApiError of(int status, String error, String message) {
		return new ApiError(Instant.now(), status, error, message, null);
	}
}
