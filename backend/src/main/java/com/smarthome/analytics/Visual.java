package com.smarthome.analytics;

import java.util.List;

/**
 * A chart or table built from query results, shown with an assistant answer or in a report. {@code kind} is "line",
 * "bar" or "table". Charts use {@code labels} (x axis, ISO timestamps or plain text) and {@code series}; tables use
 * {@code columns} and {@code rows}. A null value in a series is a gap (no data), never zero.
 */
public record Visual(String kind, String title, String unit, List<String> labels, List<VisualSeries> series, List<String> columns,
		List<List<String>> rows) {

	public record VisualSeries(String name, List<Double> values) {
	}

	public static Visual line(String title, String unit, List<String> labels, List<VisualSeries> series) {
		return new Visual("line", title, unit, labels, series, List.of(), List.of());
	}

	public static Visual bar(String title, String unit, List<String> labels, List<VisualSeries> series) {
		return new Visual("bar", title, unit, labels, series, List.of(), List.of());
	}

	public static Visual table(String title, List<String> columns, List<List<String>> rows) {
		return new Visual("table", title, "", List.of(), List.of(), columns, rows);
	}
}
