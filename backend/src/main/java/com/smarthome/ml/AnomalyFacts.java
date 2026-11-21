package com.smarthome.ml;

import java.util.Locale;

import tools.jackson.databind.JsonNode;

/**
 * The human-readable facts of one anomaly, taken from the numbers the detector stored in {@code details}: which sensor,
 * the abnormal value and the range that would have been normal. Shared by reports and anomaly emails so both always
 * show the same thing. Nothing is invented: when the detector stored no number for something, a plain description is
 * returned instead.
 */
public final class AnomalyFacts {

	private AnomalyFacts() {
	}

	/** "temperature" -> "Temperature (°C)" and so on. */
	public static String sensor(String scope) {
		if (scope == null) {
			return "-";
		}
		return switch (scope) {
			case "temperature" -> "Temperature (°C)";
			case "humidity" -> "Humidity (%)";
			case "gasRaw" -> "Gas / smoke (MQ-2 raw)";
			case "motion" -> "Motion (PIR)";
			case "device" -> "Device connection";
			default -> scope;
		};
	}

	/** "STATISTICAL_OUTLIER" -> "Statistical outlier". */
	public static String kind(AnomalyKind kind) {
		return kind == null ? "-" : kind(kind.name());
	}

	public static String kind(String kind) {
		if (kind == null || kind.isEmpty()) {
			return "-";
		}
		String s = kind.toLowerCase(Locale.ROOT).replace('_', ' ');
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** The abnormal value with its unit, as the detector measured it. */
	public static String value(String kind, String scope, JsonNode d) {
		String unit = unit(scope);
		if ("MISSING_DATA".equals(kind)) {
			return "no value in " + text(d, "missingMessages", "the latest") + " messages";
		}
		if ("STALE_DATA".equals(kind)) {
			return has(d, "silentMinutes") ? "silent for " + d.get("silentMinutes").asLong() + " min" : "no recent data";
		}
		if ("UNUSUAL_ACTIVITY".equals(kind)) {
			return has(d, "localTime") ? "motion at " + d.get("localTime").asString() : "motion";
		}
		if ("SUDDEN_CHANGE".equals(kind) && has(d, "previous") && has(d, "value")) {
			double change = d.get("value").asDouble() - d.get("previous").asDouble();
			return num(d.get("previous").asDouble()) + unit + " to " + num(d.get("value").asDouble()) + unit + " (" + (change > 0 ? "+" : "")
					+ num(change) + unit + ")";
		}
		if ("STUCK_VALUE".equals(kind) && has(d, "value")) {
			return num(d.get("value").asDouble()) + unit + " repeated " + text(d, "identicalReadings", "many") + " times";
		}
		return has(d, "value") ? num(d.get("value").asDouble()) + unit : "-";
	}

	/** The range that would have been normal, from the detector's own thresholds. */
	public static String expected(String kind, String scope, JsonNode d) {
		String unit = unit(scope);
		switch (kind == null ? "" : kind) {
			case "STATISTICAL_OUTLIER" -> {
				if (has(d, "median") && has(d, "spread") && has(d, "warnAt")) {
					double m = d.get("median").asDouble();
					double w = d.get("warnAt").asDouble() * d.get("spread").asDouble();
					return num(m - w) + unit + " to " + num(m + w) + unit + " (typical " + num(m) + unit + ")";
				}
			}
			case "SUDDEN_CHANGE" -> {
				if (has(d, "typicalChange") && has(d, "spread") && has(d, "warnAt")) {
					double w = d.get("warnAt").asDouble() * d.get("spread").asDouble();
					double t = d.get("typicalChange").asDouble();
					return "a change of " + num(t - w) + unit + " to " + num(t + w) + unit + " between readings";
				}
			}
			case "OUT_OF_RANGE" -> {
				if (has(d, "usableMin") && has(d, "usableMax")) {
					return num(d.get("usableMin").asDouble()) + unit + " to " + num(d.get("usableMax").asDouble()) + unit;
				}
				if (has(d, "adcMaximum")) {
					return "below " + num(d.get("adcMaximum").asDouble()) + unit;
				}
				return "within the accepted range";
			}
			case "STUCK_VALUE" -> {
				return "values that vary slightly between readings";
			}
			case "MISSING_DATA" -> {
				return "a value in every message";
			}
			case "STALE_DATA" -> {
				return "regular reports from the device";
			}
			case "UNUSUAL_ACTIVITY" -> {
				if (has(d, "quietAtMost")) {
					return "motion in at most " + Math.round(d.get("quietAtMost").asDouble() * 100) + "% of past weeks at this hour";
				}
				return "no motion at this hour";
			}
			default -> {
			}
		}
		return "-";
	}

	private static String unit(String scope) {
		if (scope == null) {
			return "";
		}
		return switch (scope) {
			case "temperature" -> " °C";
			case "humidity" -> " %";
			case "gasRaw" -> " raw";
			default -> "";
		};
	}

	private static boolean has(JsonNode d, String field) {
		return d != null && d.has(field) && !d.get(field).isNull();
	}

	private static String text(JsonNode d, String field, String fallback) {
		return has(d, field) ? d.get(field).asString() : fallback;
	}

	private static String num(double v) {
		return v == Math.rint(v) && Math.abs(v) < 1e9 ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
	}
}
