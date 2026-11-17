package com.smarthome.assistant.tools;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.smarthome.analytics.Visual;

/** The small data types shared by the assistant's tools. */
public final class ToolTypes {

	private ToolTypes() {
	}

	/** One declared argument. Anything not declared is rejected; strings are length-capped and cleaned. */
	public record Param(String name, String type, String description, List<String> allowed, boolean required, Integer min, Integer max) {

		public static Param string(String name, String description, boolean required) {
			return new Param(name, "string", description, List.of(), required, null, null);
		}

		public static Param choice(String name, String description, List<String> allowed, boolean required) {
			return new Param(name, "string", description, List.copyOf(allowed), required, null, null);
		}

		public static Param date(String name, String description) {
			return new Param(name, "date", description, List.of(), false, null, null);
		}

		public static Param integer(String name, String description, int min, int max) {
			return new Param(name, "integer", description, List.of(), false, min, max);
		}
	}

	/**
	 * Who is asking and what they own. {@code devices} (id -> name) is loaded through the owner-scoped device service, so a
	 * tool can only ever name or query the caller's own devices.
	 */
	public record ToolContext(UUID owner, ZoneId zone, Instant now, Map<UUID, String> devices) {
	}

	/**
	 * What the conversation is about after a tool ran, so a follow-up ("and yesterday?", "compare it with last week") can
	 * reuse the metric, the period and the device. Any field may be null.
	 */
	public record Focus(String metric, String period, String from, String to, String device) {
		public static final Focus EMPTY = new Focus(null, null, null, null, null);

		public Focus merge(Focus newer) {
			if (newer == null) {
				return this;
			}
			boolean newPeriod = newer.period != null;
			return new Focus(newer.metric != null ? newer.metric : metric, newPeriod ? newer.period : period,
					newPeriod ? newer.from : from, newPeriod ? newer.to : to, newer.device != null ? newer.device : device);
		}
	}

	/**
	 * A device command the assistant proposes. It is NOT sent: the user must confirm it in the app, which then uses the
	 * normal command API (with all its checks). Only allow-listed actions and values ever get here.
	 */
	public record PendingAction(UUID deviceId, String deviceName, String action, String value, String description) {
	}

	/**
	 * A tool's result. {@code text} is plain facts for the model and for the fallback answer; {@code visuals} are shown
	 * to the user; {@code reportId} is set when a report was created.
	 */
	public record ToolOutput(String title, String text, List<Visual> visuals, Focus focus, PendingAction action, UUID reportId) {
		public static ToolOutput of(String title, String text, List<Visual> visuals, Focus focus) {
			return new ToolOutput(title, text, visuals, focus, null, null);
		}
	}

	/** One executed call, as recorded for the answer. {@code ok} false: arguments were rejected or the data was unavailable. */
	public record ToolResult(String tool, String argumentsJson, boolean ok, ToolOutput output, String error) {
		public String textForModel() {
			return ok ? output.text() : "ERROR: " + error;
		}
	}

	/** Thrown for invalid arguments. The message is shown to the model (and maybe the user); it never contains SQL or ids of others. */
	public static class ToolArgumentException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public ToolArgumentException(String message) {
			super(message);
		}
	}
}
