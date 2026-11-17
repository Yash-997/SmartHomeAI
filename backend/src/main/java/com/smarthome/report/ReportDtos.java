package com.smarthome.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.smarthome.analytics.Visual;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ReportDtos {

	private ReportDtos() {
	}

	/**
	 * kind: daily (one day: {@code from}, default today so far), weekly (the last 7 days), monthly (the last 30 days) or
	 * custom ({@code from} to {@code to}, inclusive, at most 366 days). {@code timeZone} is an IANA name; blank = the
	 * server's default. {@code deviceId} limits the report to one of the caller's devices.
	 */
	public record ReportRequest(@NotBlank @Size(max = 10) String kind, LocalDate from, LocalDate to, @Size(max = 50) String timeZone, UUID deviceId) {
	}

	/** A part of a report: a heading, paragraphs, bullet points and charts/tables. */
	public record Section(String heading, List<String> paragraphs, List<String> bullets, List<Visual> visuals) {
	}

	/**
	 * A headline figure shown as a card at the top of the PDF. {@code tone} is ok, warn, alarm or neutral. Every value is
	 * computed from stored data by {@link ReportBuilder}.
	 */
	public record Kpi(String label, String value, String detail, String tone) {
	}

	/**
	 * The stored snapshot. {@code kpis} was added later: reports stored before it was introduced read back with null
	 * and are rendered without the cards.
	 */
	public record ReportContent(String title, String periodLabel, String scope, Instant generatedAt, List<Section> sections, List<String> notes,
			List<Kpi> kpis) {
	}

	public record ReportSummary(UUID id, String kind, String title, Instant periodFrom, Instant periodTo, String timeZone, UUID deviceId,
			String summary, Instant createdAt) {
	}

	public record ReportDetail(UUID id, String kind, String title, Instant periodFrom, Instant periodTo, String timeZone, UUID deviceId,
			String summary, Instant createdAt, ReportContent content) {
	}
}
