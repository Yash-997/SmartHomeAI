package com.smarthome.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.smarthome.analytics.Visual;
import com.smarthome.analytics.Visual.VisualSeries;
import com.smarthome.report.ReportDtos.ReportContent;
import com.smarthome.report.ReportDtos.Section;

class PdfRendererTest {

	@Test
	void producesAWellFormedMultiPagePdf() {
		List<List<String>> rows = new ArrayList<>();
		for (int i = 0; i < 90; i++) {
			rows.add(List.of("Device " + i, "temperature (°C)", "21.5", "20.0", "23.0", "12"));
		}
		Visual table = Visual.table("Stats", List.of("Device", "Sensor", "Avg", "Min", "Max", "Values"), rows);
		Visual line = Visual.line("Temperature", "°C", List.of("2026-11-01T00:00:00Z", "2026-11-02T00:00:00Z", "2026-11-03T00:00:00Z"),
				List.of(new VisualSeries("average", java.util.Arrays.asList(20.0, null, 22.0))));
		Visual bar = Visual.bar("Motion", "detections", List.of("00", "01"), List.of(new VisualSeries("detections", List.of(3.0, 0.0))));
		ReportContent c = new ReportContent("Weekly report (test) \\ ) (", "the last 7 days", "all devices", Instant.EPOCH,
				List.of(new Section("Executive summary", List.of("A long paragraph ".repeat(40)), List.of("one", "two"), List.of(table, line, bar))),
				List.of("Gas values are raw."));

		byte[] pdf = PdfRenderer.render(c, ZoneId.of("UTC"));
		String s = new String(pdf, StandardCharsets.ISO_8859_1);

		assertTrue(s.startsWith("%PDF-1.4"));
		assertTrue(s.strip().endsWith("%%EOF"));
		assertTrue(s.contains("/Count 2") || s.contains("/Count 3") || s.contains("/Count 4"), "a long report spans pages");
		assertTrue(s.contains("Weekly report \\(test\\) \\\\ \\) \\("), "parentheses and backslashes are escaped");
		int xref = Integer.parseInt(s.substring(s.lastIndexOf("startxref") + 10).strip().split("\\s")[0]);
		assertEquals("xref", s.substring(xref, xref + 4), "startxref points at the xref table");
	}

	@Test
	void wrappingKeepsLinesWithinTheWidth() {
		for (String line : PdfRenderer.wrap("word ".repeat(100).strip(), 10, 200, false)) {
			assertTrue(PdfRenderer.width(line, 10, false) <= 200);
		}
	}
}
