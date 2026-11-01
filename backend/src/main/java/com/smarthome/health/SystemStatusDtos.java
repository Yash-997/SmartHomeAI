package com.smarthome.health;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The authenticated system status view. Everything is about the caller's own devices; nothing about other users. */
public final class SystemStatusDtos {

	private SystemStatusDtos() {
	}

	public enum Level { OK, DEGRADED, DOWN }

	/** What is wrong and where. {@code area}: database, mqtt, devices, commands, sensors, ml or assistant. */
	public record Issue(Level level, String area, String message) {
	}

	public record Component(Level level, String detail) {
	}

	public record DeviceAvailability(UUID deviceId, String name, String connection, Long lastSeenAgeSeconds) {
	}

	public record CommandProblem(UUID deviceId, String action, String value, String status, String detail, Instant at) {
	}

	/** {@code failureRate} is null when there were no commands (not 0%). */
	public record CommandHealth(int windowHours, int total, Map<String, Integer> byStatus, Double failureRate, List<CommandProblem> recentProblems) {
	}

	/** state: FRESH, STALE or NEVER for each sensor; age in seconds of the newest value of that sensor (null = never). */
	public record SensorFreshness(UUID deviceId, String name, String connection, Map<String, SensorState> sensors) {
	}

	public record SensorState(String state, Long ageSeconds) {
	}

	public record MlReadiness(UUID deviceId, String name, String occupancyState, boolean predictionsAvailable, int activeAnomalies) {
	}

	/** {@code reachable} is deliberately not claimed: the model is not called to check, only its configuration is reported. */
	public record AssistantHealth(Level level, boolean modelConfigured, String provider, String model, String embedding, boolean indexReadable,
			String detail) {
	}

	public record SystemStatus(Level overall, Instant checkedAt, List<Issue> issues, Component database, Component mqtt,
			List<DeviceAvailability> devices, CommandHealth commands, List<SensorFreshness> sensors, List<MlReadiness> ml, AssistantHealth assistant) {
	}
}
