package com.smarthome.report;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.smarthome.analytics.Visual;
import com.smarthome.analytics.Visual.VisualSeries;
import com.smarthome.report.ReportDtos.ReportContent;
import com.smarthome.report.ReportDtos.Section;

/**
 * A small, dependency-free PDF writer for reports: A4 pages, the standard Helvetica fonts (WinAnsi encoding), wrapped
 * text, tables and simple line/bar charts drawn as vectors. It only ever writes text it is given, escaped, so report
 * content cannot inject PDF operators.
 */
public final class PdfRenderer {

	private static final float W = 595, H = 842, M = 48;
	private static final Charset WIN = Charset.forName("windows-1252");
	private static final DateTimeFormatter AXIS_HOUR = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter AXIS_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

	/** Helvetica advance widths (1/1000 em) for ASCII 32..126. */
	private static final int[] WIDTHS = { 278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556,
			556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667,
			778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222,
			833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584 };

	private final List<StringBuilder> pages = new ArrayList<>();
	private StringBuilder page;
	private float y;
	private final ZoneId zone;

	private PdfRenderer(ZoneId zone) {
		this.zone = zone;
	}

	public static byte[] render(ReportContent c, ZoneId zone) {
		PdfRenderer r = new PdfRenderer(zone);
		r.newPage();
		r.text(c.title(), M, 18, true, 0.12f, 0.16f, 0.32f);
		r.y -= 24;
		r.wrapped("Period: " + c.periodLabel() + ". Scope: " + c.scope() + ".", 10, false, 0);
		r.y -= 6;
		r.rule();
		for (Section s : c.sections()) {
			r.need(48);
			r.y -= 10;
			r.text(s.heading(), M, 13, true, 0.12f, 0.16f, 0.32f);
			r.y -= 18;
			for (String p : s.paragraphs()) {
				r.wrapped(p, 10, false, 0);
				r.y -= 4;
			}
			for (String b : s.bullets()) {
				r.wrapped("• " + b, 10, false, 10);
			}
			for (Visual v : s.visuals()) {
				r.y -= 6;
				switch (v.kind()) {
					case "table" -> r.table(v);
					case "line", "bar" -> r.chart(v);
					default -> { }
				}
			}
		}
		r.need(40);
		r.y -= 8;
		r.rule();
		for (String n : c.notes()) {
			r.wrapped(n, 8, false, 0);
		}
		return r.finish();
	}

	// ---- layout ------------------------------------------------------------------------------------

	private void newPage() {
		page = new StringBuilder();
		pages.add(page);
		y = H - M;
	}

	private void need(float h) {
		if (y - h < M + 20) {
			newPage();
		}
	}

	private void rule() {
		need(10);
		page.append("0.8 0.82 0.86 RG 0.6 w ").append(f(M)).append(' ').append(f(y)).append(" m ").append(f(W - M)).append(' ').append(f(y)).append(" l S\n");
		y -= 12;
	}

	private void wrapped(String s, float size, boolean bold, float indent) {
		float max = W - 2 * M - indent;
		for (String line : wrap(s, size, max, bold)) {
			need(size + 4);
			text(line, M + indent, size, bold, 0.15f, 0.15f, 0.18f);
			y -= size + 4;
		}
	}

	static List<String> wrap(String s, float size, float max, boolean bold) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (width(candidate, size, bold) <= max || line.isEmpty()) {
				line.setLength(0);
				line.append(candidate);
			} else {
				out.add(line.toString());
				line.setLength(0);
				line.append(word);
			}
		}
		if (!line.isEmpty()) {
			out.add(line.toString());
		}
		return out;
	}

	static float width(String s, float size, boolean bold) {
		float w = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			w += c >= 32 && c <= 126 ? WIDTHS[c - 32] : 556;
		}
		return w * size / 1000 * (bold ? 1.06f : 1f);
	}

	private void text(String s, float x, float size, boolean bold, float r, float g, float b) {
		page.append(f(r)).append(' ').append(f(g)).append(' ').append(f(b)).append(" rg BT /").append(bold ? "F2 " : "F1 ").append(f(size)).append(" Tf ")
				.append(f(x)).append(' ').append(f(y - size)).append(" Td (").append(escape(s)).append(") Tj ET\n");
	}

	private void table(Visual v) {
		int cols = v.columns().size();
		if (cols == 0) {
			return;
		}
		float total = W - 2 * M;
		float[] cw = new float[cols];
		float sum = 0;
		for (int c = 0; c < cols; c++) {
			float longest = width(v.columns().get(c), 8, true);
			for (List<String> row : v.rows()) {
				if (c < row.size()) {
					longest = Math.max(longest, Math.min(width(row.get(c), 8, false), 200));
				}
			}
			cw[c] = longest + 8;
			sum += cw[c];
		}
		for (int c = 0; c < cols; c++) {
			cw[c] = cw[c] / sum * total;
		}
		need(30);
		if (v.title() != null && !v.title().isBlank()) {
			text(v.title(), M, 9, true, 0.3f, 0.3f, 0.35f);
			y -= 13;
		}
		row(v.columns(), cw, true);
		int n = 0;
		for (List<String> row : v.rows()) {
			if (n++ >= 60) {
				wrapped("(" + (v.rows().size() - 60) + " more rows not shown)", 8, false, 0);
				break;
			}
			row(row, cw, false);
		}
		y -= 6;
	}

	private void row(List<String> cells, float[] cw, boolean header) {
		List<List<String>> lines = new ArrayList<>();
		int height = 1;
		for (int c = 0; c < cw.length; c++) {
			List<String> l = wrap(c < cells.size() ? cells.get(c) : "", 8, cw[c] - 6, header);
			lines.add(l);
			height = Math.max(height, l.size());
		}
		float rh = height * 10 + 5;
		if (y - rh < M + 20) {
			newPage();
		}
		if (header) {
			page.append("0.92 0.93 0.96 rg ").append(f(M)).append(' ').append(f(y - rh)).append(' ').append(f(W - 2 * M)).append(' ').append(f(rh)).append(" re f\n");
		}
		float x = M;
		for (int c = 0; c < cw.length; c++) {
			float yy = y - 2;
			for (String l : lines.get(c)) {
				float save = y;
				y = yy;
				text(l, x + 3, 8, header, 0.15f, 0.15f, 0.18f);
				y = save;
				yy -= 10;
			}
			x += cw[c];
		}
		page.append("0.85 0.86 0.9 RG 0.4 w ").append(f(M)).append(' ').append(f(y - rh)).append(" m ").append(f(W - M)).append(' ').append(f(y - rh)).append(" l S\n");
		y -= rh;
	}

	private void chart(Visual v) {
		List<Double> values = new ArrayList<>();
		for (VisualSeries s : v.series()) {
			for (Double d : s.values()) {
				if (d != null) {
					values.add(d);
				}
			}
		}
		if (values.isEmpty() || v.labels().isEmpty()) {
			return;
		}
		float ch = 130;
		need(ch + 40);
		text(v.title() + (v.unit() == null || v.unit().isBlank() ? "" : " (" + v.unit() + ")"), M, 9, true, 0.3f, 0.3f, 0.35f);
		y -= 14;
		double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
		double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(1);
		if ("bar".equals(v.kind())) {
			min = Math.min(0, min);
		}
		if (max - min < 1e-9) {
			max += 1;
			min -= "bar".equals(v.kind()) ? 0 : 1;
		}
		float left = M + 34, right = W - M, top = y, bottom = y - ch;
		// axes and labels
		page.append("0.75 0.77 0.82 RG 0.5 w ").append(f(left)).append(' ').append(f(bottom)).append(" m ").append(f(right)).append(' ').append(f(bottom))
				.append(" l S ").append(f(left)).append(' ').append(f(bottom)).append(" m ").append(f(left)).append(' ').append(f(top)).append(" l S\n");
		float save = y;
		for (int i = 0; i <= 2; i++) {
			double val = min + (max - min) * i / 2;
			y = bottom + (top - bottom) * i / 2f + 4;
			text(String.format(Locale.ROOT, "%.1f", val), M, 7, false, 0.4f, 0.4f, 0.45f);
		}
		int n = v.labels().size();
		float step = n > 1 ? (right - left - 10) / (n - 1) : 0;
		float[][] colors = { { 0.31f, 0.33f, 0.85f }, { 0.55f, 0.6f, 0.7f }, { 0.85f, 0.45f, 0.3f } };
		if ("bar".equals(v.kind())) {
			VisualSeries s = v.series().get(0);
			float bw = Math.max(2, (right - left - 10) / n * 0.7f);
			float slot = (right - left - 10) / n;
			page.append(f(colors[0][0])).append(' ').append(f(colors[0][1])).append(' ').append(f(colors[0][2])).append(" rg\n");
			for (int i = 0; i < n && i < s.values().size(); i++) {
				Double d = s.values().get(i);
				if (d == null) {
					continue;
				}
				float h = (float) ((d - min) / (max - min) * (top - bottom));
				page.append(f(left + 5 + slot * i + (slot - bw) / 2)).append(' ').append(f(bottom)).append(' ').append(f(bw)).append(' ').append(f(h)).append(" re f\n");
			}
		} else {
			for (int si = 0; si < v.series().size() && si < 3; si++) {
				VisualSeries s = v.series().get(si);
				float[] c = colors[si];
				page.append(f(c[0])).append(' ').append(f(c[1])).append(' ').append(f(c[2])).append(" RG ").append(si == 0 ? "1.4" : "0.6").append(" w\n");
				boolean pen = false;
				for (int i = 0; i < n && i < s.values().size(); i++) {
					Double d = s.values().get(i);
					if (d == null) {
						pen = false;                         // a gap in the data is a gap in the line
						continue;
					}
					float px = left + 5 + step * i;
					float py = bottom + (float) ((d - min) / (max - min) * (top - bottom));
					page.append(f(px)).append(' ').append(f(py)).append(pen ? " l\n" : " m\n");
					pen = true;
				}
				page.append("S\n");
			}
		}
		// x labels: first, middle, last
		y = bottom - 2;
		int[] idx = n == 1 ? new int[] { 0 } : new int[] { 0, (n - 1) / 2, n - 1 };
		for (int i : idx) {
			String label = axisLabel(v.labels().get(i));
			float px = "bar".equals(v.kind()) ? left + 5 + (right - left - 10) / n * (i + 0.5f) : left + 5 + step * i;
			text(label, Math.max(M, Math.min(right - width(label, 7, false), px - width(label, 7, false) / 2)), 7, false, 0.4f, 0.4f, 0.45f);
		}
		y = save - ch - 18;
		if (v.series().size() > 1 && "line".equals(v.kind())) {
			StringBuilder legend = new StringBuilder("Lines: ");
			for (int si = 0; si < v.series().size() && si < 3; si++) {
				legend.append(si == 0 ? "" : ", ").append(new String[] { "dark blue", "grey", "orange" }[si]).append(" = ").append(v.series().get(si).name());
			}
			wrapped(legend.toString(), 7, false, 0);
		}
	}

	private String axisLabel(String raw) {
		try {
			Instant i = Instant.parse(raw);
			var z = i.atZone(zone);
			return z.getHour() == 0 && z.getMinute() == 0 ? AXIS_DAY.format(z) : AXIS_HOUR.format(z);
		} catch (RuntimeException e) {
			return raw;
		}
	}

	// ---- PDF objects ---------------------------------------------------------------------------------

	private byte[] finish() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		List<Integer> offsets = new ArrayList<>();
		write(out, "%PDF-1.4\n%âãÏÓ\n");
		int pageCount = pages.size();
		int firstPage = 5;
		// 1 catalog, 2 pages, 3 font, 4 bold font, then page/content pairs
		offsets.add(out.size());
		write(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
		offsets.add(out.size());
		StringBuilder kids = new StringBuilder();
		for (int i = 0; i < pageCount; i++) {
			kids.append(firstPage + i * 2).append(" 0 R ");
		}
		write(out, "2 0 obj\n<< /Type /Pages /Kids [" + kids + "] /Count " + pageCount + " >>\nendobj\n");
		offsets.add(out.size());
		write(out, "3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n");
		offsets.add(out.size());
		write(out, "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>\nendobj\n");
		for (int i = 0; i < pageCount; i++) {
			int pageObj = firstPage + i * 2;
			StringBuilder content = new StringBuilder(pages.get(i));
			content.append("0.5 0.5 0.55 rg BT /F1 7 Tf ").append(f(M)).append(" 24 Td (SmartHome AI report - page ").append(i + 1).append(" of ").append(pageCount)
					.append(") Tj ET\n");
			byte[] stream = encode(content.toString());
			offsets.add(out.size());
			write(out, pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents "
					+ (pageObj + 1) + " 0 R >>\nendobj\n");
			offsets.add(out.size());
			write(out, (pageObj + 1) + " 0 obj\n<< /Length " + stream.length + " >>\nstream\n");
			out.writeBytes(stream);
			write(out, "\nendstream\nendobj\n");
		}
		int xref = out.size();
		StringBuilder x = new StringBuilder("xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
		for (int o : offsets) {
			x.append(String.format(Locale.ROOT, "%010d 00000 n \n", o));
		}
		x.append("trailer\n<< /Size ").append(offsets.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
		write(out, x.toString());
		return out.toByteArray();
	}

	private static void write(ByteArrayOutputStream out, String s) {
		out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
	}

	/** Content streams use WinAnsi bytes; characters it cannot represent become '?'. */
	private static byte[] encode(String s) {
		CharsetEncoder enc = WIN.newEncoder();
		ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (enc.canEncode(c)) {
				out.writeBytes(String.valueOf(c).getBytes(WIN));
			} else {
				out.write('?');
			}
		}
		return out.toByteArray();
	}

	static String escape(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '\\' -> sb.append("\\\\");
				case '(' -> sb.append("\\(");
				case ')' -> sb.append("\\)");
				case '\n', '\r', '\t' -> sb.append(' ');
				default -> sb.append(c < 32 ? ' ' : c);
			}
		}
		return sb.toString();
	}

	private static String f(float v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}
}
