package com.smarthome.assistant.tools;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import com.smarthome.analytics.Metric;
import com.smarthome.analytics.TimeRange;
import com.smarthome.assistant.Text;
import com.smarthome.assistant.tools.ToolTypes.Param;
import com.smarthome.assistant.tools.ToolTypes.ToolArgumentException;
import com.smarthome.assistant.tools.ToolTypes.ToolContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Arguments of one tool call after validation against the tool's declared {@link Param}s. Construction fails (with a
 * message the model can act on) for: anything that is not a JSON object, unknown argument names, wrong types, values
 * outside the allowed list or range, malformed dates, over-long strings and missing required arguments.
 */
public final class ToolArgs {

	public static final int MAX_JSON = 2000;
	private static final int MAX_STRING = 120;

	private final Map<String, Object> values;

	private ToolArgs(Map<String, Object> values) {
		this.values = values;
	}

	public static ToolArgs empty() {
		return new ToolArgs(Map.of());
	}

	public static ToolArgs parse(String json, List<Param> params, ObjectMapper mapper) {
		String raw = json == null || json.isBlank() ? "{}" : json.strip();
		if (raw.length() > MAX_JSON) {
			throw new ToolArgumentException("Arguments are too long");
		}
		JsonNode node;
		try {
			node = mapper.readTree(raw);
		} catch (RuntimeException e) {
			throw new ToolArgumentException("Arguments must be a JSON object");
		}
		if (node == null || node.isNull()) {
			node = mapper.createObjectNode();
		}
		if (!node.isObject()) {
			throw new ToolArgumentException("Arguments must be a JSON object");
		}
		Map<String, Param> declared = new HashMap<>();
		params.forEach(p -> declared.put(p.name(), p));
		Map<String, Object> out = new HashMap<>();
		for (Entry<String, JsonNode> e : node.properties()) {
			Param p = declared.get(e.getKey());
			if (p == null) {
				throw new ToolArgumentException("Unknown argument '" + Text.oneLine(e.getKey(), 30).replaceAll("[^A-Za-z0-9_]", "?") + "'");
			}
			JsonNode v = e.getValue();
			if (v == null || v.isNull() || (v.isString() && v.asString().isBlank())) {
				continue;                                   // treated as "not given"
			}
			out.put(p.name(), convert(p, v));
		}
		for (Param p : params) {
			if (p.required() && !out.containsKey(p.name())) {
				throw new ToolArgumentException("Argument '" + p.name() + "' is required");
			}
		}
		return new ToolArgs(out);
	}

	private static Object convert(Param p, JsonNode v) {
		switch (p.type()) {
			case "integer" -> {
				if (!v.isIntegralNumber() && !(v.isString() && v.asString().strip().matches("-?\\d{1,9}"))) {
					throw new ToolArgumentException("Argument '" + p.name() + "' must be a whole number");
				}
				long n = v.isIntegralNumber() ? v.asLong() : Long.parseLong(v.asString().strip());
				if ((p.min() != null && n < p.min()) || (p.max() != null && n > p.max())) {
					throw new ToolArgumentException("Argument '" + p.name() + "' must be between " + p.min() + " and " + p.max());
				}
				return (int) n;
			}
			case "date" -> {
				String s = scalar(p, v);
				try {
					return LocalDate.parse(s);
				} catch (DateTimeParseException e) {
					throw new ToolArgumentException("Argument '" + p.name() + "' must be a date like 2026-01-31");
				}
			}
			default -> {
				String s = scalar(p, v);
				if (!p.allowed().isEmpty()) {
					String lower = s.toLowerCase(Locale.ROOT);
					if (!p.allowed().contains(lower)) {
						throw new ToolArgumentException("Argument '" + p.name() + "' must be one of: " + String.join(", ", p.allowed()));
					}
					return lower;
				}
				return s;
			}
		}
	}

	private static String scalar(Param p, JsonNode v) {
		if (!(v.isString() || v.isNumber() || v.isBoolean())) {
			throw new ToolArgumentException("Argument '" + p.name() + "' must be text");
		}
		String s = Text.oneLine(v.asString(), MAX_STRING + 1);
		if (s.length() > MAX_STRING) {
			throw new ToolArgumentException("Argument '" + p.name() + "' is too long");
		}
		return s;
	}

	public String string(String name) {
		Object v = values.get(name);
		return v == null ? null : v.toString();
	}

	public LocalDate date(String name) {
		return (LocalDate) values.get(name);
	}

	public Integer integer(String name) {
		return (Integer) values.get(name);
	}

	public boolean has(String name) {
		return values.containsKey(name);
	}

	// ---- shared resolution -------------------------------------------------------------------------

	/** The period from "period" (+ "from"/"to" for custom), defaulting to {@code fallback}. */
	public TimeRange range(ToolContext ctx, String fallback) {
		String preset = has("period") ? string("period") : (has("from") ? "custom" : fallback);
		LocalDate from = date("from");
		LocalDate to = date("to") != null ? date("to") : from;
		try {
			return TimeRange.resolve(preset, from, to, ctx.zone(), ctx.now());
		} catch (IllegalArgumentException e) {
			throw new ToolArgumentException(e.getMessage());
		}
	}

	public Metric metric(String name) {
		try {
			return has(name) ? Metric.parse(string(name)) : null;
		} catch (IllegalArgumentException e) {
			throw new ToolArgumentException(e.getMessage());
		}
	}

	/**
	 * Resolves a device by id or name among the caller's own devices only. Exact (case-insensitive) name first, then a
	 * unique partial match. Null when the argument was not given.
	 */
	public UUID device(ToolContext ctx, String name) {
		String q = string(name);
		if (q == null) {
			return null;
		}
		return resolveDevice(ctx.devices(), q);
	}

	public static UUID resolveDevice(Map<UUID, String> devices, String q) {
		String s = q.strip();
		try {
			UUID id = UUID.fromString(s);
			if (devices.containsKey(id)) {
				return id;
			}
			throw new ToolArgumentException("No device of yours has that id");
		} catch (IllegalArgumentException notUuid) {
			// a name
		}
		String lower = s.toLowerCase(Locale.ROOT).replaceAll("^the\\s+", "").replaceAll("\\s+(device|sensor|room)$", "");
		UUID exact = null;
		int exacts = 0;
		UUID partial = null;
		int partials = 0;
		for (Entry<UUID, String> e : devices.entrySet()) {
			String n = e.getValue().toLowerCase(Locale.ROOT);
			if (n.equals(lower)) {
				exact = e.getKey();
				exacts++;
			} else if (lower.length() >= 3 && n.length() >= 3 && (n.contains(lower) || lower.contains(n))) {
				partial = e.getKey();
				partials++;
			}
		}
		if (exacts > 1) {
			// two devices with the same name: picking one silently could answer about the wrong room
			throw new ToolArgumentException("More than one of your devices is named '" + Text.oneLine(s, 40) + "'; use the device id or rename one");
		}
		if (exact != null) {
			return exact;
		}
		if (partials == 1) {
			return partial;
		}
		if (partials > 1) {
			throw new ToolArgumentException("More than one of your devices matches '" + Text.oneLine(s, 40) + "'; name it exactly");
		}
		throw new ToolArgumentException("You have no device named '" + Text.oneLine(s, 40) + "'. Your devices: "
				+ (devices.isEmpty() ? "none" : String.join(", ", devices.values())));
	}
}
