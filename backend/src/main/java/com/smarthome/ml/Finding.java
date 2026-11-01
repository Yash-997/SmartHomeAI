package com.smarthome.ml;

import java.time.Instant;
import java.util.Map;

/** One detected problem, before it is saved. {@code details} holds the numbers behind {@code explanation}. */
public record Finding(AnomalyKind kind, String scope, AnomalySeverity severity, String explanation, Map<String, Object> details,
		Instant readingAt) {
}
