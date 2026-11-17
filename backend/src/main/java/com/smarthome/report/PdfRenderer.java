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
import com.smarthome.report.ReportDtos.Kpi;
import com.smarthome.report.ReportDtos.ReportContent;
import com.smarthome.report.ReportDtos.Section;

/**
 * A small, dependency-free PDF writer for VÉYRA reports: A4 pages, a branded cover, a logo watermark, KPI cards,
 * numbered sections, zebra tables and line/bar charts, all drawn as vectors. It uses the standard PDF base fonts
 * (Helvetica and Times, WinAnsi encoding), which every PDF reader provides, so nothing needs embedding. It only ever
 * writes text it is given, escaped, so report content cannot inject PDF operators.
 */
public final class PdfRenderer {

	private static final float W = 595, H = 842, M = 48;
	/** Interior pages: content runs from TOP down to BOTTOM; header above, footer below. */
	private static final float TOP = H - 84, BOTTOM = 62;
	private static final Charset WIN = Charset.forName("windows-1252");
	private static final DateTimeFormatter AXIS_HOUR = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter AXIS_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH);
	private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	// Brand colours (RGB 0..1): the website's light burgundy and white palette.
	private static final float[] BURGUNDY = rgb(0x71, 0x3B, 0x50), ACCENT = rgb(0x8B, 0x53, 0x69), WINE = rgb(0x5E, 0x2F, 0x42),
			ROSE = rgb(0xA6, 0x6D, 0x82), ROSE_LIGHT = rgb(0xF2, 0xE9, 0xED), BLUSH = rgb(0xF8, 0xF6, 0xF4), PAPER = rgb(0xFF, 0xFF, 0xFF),
			LINE = rgb(0xE9, 0xE3, 0xE5), INK = rgb(0x29, 0x2B, 0x35), MUTED = rgb(0x74, 0x71, 0x7A), WHITE = { 1, 1, 1 },
			SOFT = rgb(0xCD, 0xBF, 0xC5), OK = rgb(0x26, 0x9B, 0x70), WARN = rgb(0xD9, 0x9A, 0x35), ALARM = rgb(0xD9, 0x53, 0x5D);

	/** Helvetica advance widths (1/1000 em) for ASCII 32..126. */
	private static final int[] WIDTHS = { 278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556,
			556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667,
			778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222,
			833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584 };

	// ---- the VÉYRA mark and wordmark, in the 64-unit SVG coordinate space of /public/brand/*.svg -------------------
	private static final String HOUSE = "16 50 m 16 29 l 32 15 l 48 29 l 48 50 l S\n";
	private static final String ARCS = "27.05 39.05 m 29.78 36.32 34.22 36.32 36.95 39.05 c S\n22.81 34.81 m 27.88 29.73 36.12 29.73 41.19 34.81 c S\n";
	private static final String DIAMOND = "32 9.5 m 37.5 15 l 32 20.5 l 26.5 15 l h f\n";
	private static final String WORDMARK = "0 0 m 9 24 l 18 0 l S\n" + "39 0 m 26 0 l 26 24 l 39 24 l S 26 12 m 36 12 l S 30.5 -3.5 m 35 -8 l S\n"
			+ "46 0 m 55 12.5 l 64 0 l S 55 12.5 m 55 24 l S\n"
			+ "72 24 m 72 0 l 79.5 0 l 82.95 0 85.75 2.8 85.75 6.25 c 85.75 9.7 82.95 12.5 79.5 12.5 c 72 12.5 l S 78.5 12.5 m 87 24 l S\n"
			+ "94 24 m 103 0 l 112 24 l S 97.4 15 m 108.6 15 l S\n";

	private final List<StringBuilder> pages = new ArrayList<>();
	private StringBuilder page;
	private float y;
	private final ZoneId zone;
	private final String title;

	private PdfRenderer(ZoneId zone, String title) {
		this.zone = zone;
		this.title = title;
	}

	public static byte[] render(ReportContent c, ZoneId zone) {
		PdfRenderer r = new PdfRenderer(zone, c.title());
		r.cover(c);
		r.newPage();
		if (c.kpis() != null && !c.kpis().isEmpty()) {
			r.kpis(c.kpis());
		}
		int n = 0;
		for (Section s : c.sections()) {
			r.section(++n, s);
		}
		r.notes(c.notes());
		return r.finish(c);
	}

	// ---- cover -------------------------------------------------------------------------------------------------

	private void cover(ReportContent c) {
		page = new StringBuilder();
		pages.add(page);
		// Full-bleed gradient (white to light burgundy), then a large faint burgundy mark bleeding off the lower right corner.
		page.append("q 0 0 ").append(f(W)).append(' ').append(f(H)).append(" re W n /SC sh Q\n");
		page.append("q /GC gs\n");
		mark(W - 250, -40, 420, BURGUNDY, false);
		page.append("Q\n");
		// A thin burgundy rule along the top edge.
		fill(BURGUNDY);
		page.append("0 ").append(f(H - 4)).append(' ').append(f(W)).append(" 4 re f\n");

		// Logo lock-up.
		mark(M, H - 64 - 44, 44, WHITE, true);
		wordmark(M + 58, H - 64 - 44 + 12, 0.85f, BURGUNDY);

		float yy = H - 330;
		txt("INTELLIGENT HOME REPORT", M, yy, "F2", 9, ACCENT, 2.2f);
		yy -= 44;
		for (String line : wrap(c.title(), 34, W - 2 * M - 40, true)) {
			txt(line, M, yy, "F3", 34, BURGUNDY, 0);
			yy -= 40;
		}
		fill(ROSE);
		page.append(f(M)).append(' ').append(f(yy + 12)).append(" 56 2 re f\n");
		yy -= 30;

		String[][] facts = { { "REPORTING PERIOD", cap(c.periodLabel()) }, { "SCOPE", cap(c.scope()) },
				{ "GENERATED", c.generatedAt() == null ? "-" : STAMP.format(c.generatedAt().atZone(zone)) + " (" + zone.getId() + ")" } };
		for (String[] fact : facts) {
			txt(fact[0], M, yy, "F2", 7.5f, ACCENT, 1.6f);
			yy -= 15;
			for (String line : wrap(fact[1], 12, W - 2 * M - 120, false)) {
				txt(line, M, yy, "F1", 12, INK, 0);
				yy -= 15;
			}
			yy -= 10;
		}
		yy -= 6;
		String about = "This report summarises the sensor readings, device activity, automation outcomes, alerts and model insights that VÉYRA "
				+ "recorded for the period above. Every figure was computed from stored data when the report was generated; nothing is estimated "
				+ "or written by a language model.";
		for (String line : wrap(about, 10, 340, false)) {
			txt(line, M, yy, "F1", 10, MUTED, 0);
			yy -= 14.5f;
		}

		txt("Your Home. Your Intelligence.", M, 70, "F4", 15, BURGUNDY, 0);
		txt("CONFIDENTIAL  ·  PREPARED FOR THE ACCOUNT OWNER", M, 48, "F2", 7, MUTED, 1.4f);
	}

	// ---- interior page frame -------------------------------------------------------------------------------------

	private void newPage() {
		page = new StringBuilder();
		pages.add(page);
		// Watermark first, so everything else is drawn above it.
		page.append("q /GW gs\n");
		mark(W / 2 - 170, H / 2 - 190, 340, BURGUNDY, false);
		page.append("Q\n");
		// Header: small mark + wordmark on the left, the report title on the right, a hairline below.
		mark(M, H - 50, 18, WHITE, true);
		wordmark(M + 25, H - 50 + 4.8f, 0.4f, BURGUNDY);
		String t = fit(title, 7.5f, false, 300);
		txt(t, W - M - width(t, 7.5f, false), H - 43, "F1", 7.5f, MUTED, 0);
		stroke(LINE);
		page.append("0.6 w ").append(f(M)).append(" ").append(f(H - 60)).append(" m ").append(f(W - M)).append(' ').append(f(H - 60)).append(" l S\n");
		y = TOP;
	}

	private void need(float h) {
		if (y - h < BOTTOM) {
			newPage();
		}
	}

	// ---- content blocks ------------------------------------------------------------------------------------------

	private void kpis(List<Kpi> kpis) {
		heading("Key figures", 0);
		float gap = 10, cw = (W - 2 * M - 3 * gap) / 4, ch = 64;
		for (int i = 0; i < kpis.size(); i++) {
			if (i % 4 == 0) {
				if (i > 0) {
					y -= ch + gap;
				}
				need(ch + 10);
			}
			Kpi k = kpis.get(i);
			float x = M + (i % 4) * (cw + gap), top = y;
			fill(WHITE);
			stroke(LINE);
			page.append("0.7 w ");
			roundRect(x, top - ch, cw, ch, 6);
			page.append("B\n");
			fill(tone(k.tone()));
			roundRect(x, top - ch, 3, ch, 1.5f);
			page.append("f\n");
			txt(fit(k.label().toUpperCase(Locale.ROOT), 6.5f, true, cw - 18), x + 11, top - 16, "F2", 6.5f, MUTED, 0.8f);
			float size = 16;
			while (size > 9 && width(k.value(), size, true) > cw - 18) {
				size -= 1;
			}
			txt(k.value(), x + 11, top - 38, "F2", size, BURGUNDY, 0);
			txt(fit(k.detail(), 7, false, cw - 18), x + 11, top - 53, "F1", 7, MUTED, 0);
		}
		y -= ch + 22;
	}

	private void section(int n, Section s) {
		need(70);
		y -= 6;
		heading(s.heading(), n);
		for (String p : s.paragraphs()) {
			paragraph(p, 10, INK, 0);
			y -= 5;
		}
		for (String b : s.bullets()) {
			List<String> lines = wrap(b, 10, W - 2 * M - 14, false);
			need(15);
			fill(ACCENT);
			page.append(f(M + 2)).append(' ').append(f(y - 7.5f)).append(" 3.4 3.4 re f\n");
			for (String line : lines) {
				need(14.5f);
				txt(line, M + 14, y - 10, "F1", 10, INK, 0);
				y -= 14.5f;
			}
			y -= 3;
		}
		for (Visual v : s.visuals()) {
			y -= 6;
			switch (v.kind()) {
				case "table" -> table(v);
				case "line", "bar" -> chart(v);
				default -> { }
			}
		}
		y -= 10;
	}

	/** A section heading: a numbered burgundy badge (none for n = 0), the title and a rose hairline. */
	private void heading(String text, int n) {
		need(40);
		float x = M;
		if (n > 0) {
			fill(ACCENT);
			roundRect(M, y - 20, 20, 20, 4);
			page.append("f\n");
			String num = Integer.toString(n);
			txt(num, M + 10 - width(num, 9.5f, true) / 2, y - 13.6f, "F2", 9.5f, WHITE, 0);
			x = M + 30;
		}
		txt(text, x, y - 15, "F3", 16, BURGUNDY, 0);
		stroke(ROSE);
		page.append("0.8 w ").append(f(x)).append(' ').append(f(y - 26)).append(" m ").append(f(W - M)).append(' ').append(f(y - 26)).append(" l S\n");
		y -= 40;
	}

	private void paragraph(String s, float size, float[] color, float indent) {
		for (String line : wrap(s, size, W - 2 * M - indent, false)) {
			need(size + 5);
			txt(line, M + indent, y - size, "F1", size, color, 0);
			y -= size + 4.5f;
		}
	}

	private void notes(List<String> notes) {
		if (notes.isEmpty()) {
			return;
		}
		List<String> lines = new ArrayList<>();
		for (String n : notes) {
			lines.addAll(wrap(n, 7.5f, W - 2 * M - 24, false));
		}
		float h = 30 + lines.size() * 10.5f;
		need(h + 10);
		fill(BLUSH);
		stroke(LINE);
		page.append("0.6 w ");
		roundRect(M, y - h, W - 2 * M, h, 6);
		page.append("B\n");
		txt("ABOUT THIS DATA", M + 12, y - 17, "F2", 7, ACCENT, 1.4f);
		float yy = y - 31;
		for (String line : lines) {
			txt(line, M + 12, yy, "F1", 7.5f, MUTED, 0);
			yy -= 10.5f;
		}
		y -= h + 10;
	}

	// ---- tables --------------------------------------------------------------------------------------------------

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
			cw[c] = longest + 12;
			sum += cw[c];
		}
		for (int c = 0; c < cols; c++) {
			cw[c] = cw[c] / sum * total;
		}
		need(60);
		if (v.title() != null && !v.title().isBlank()) {
			txt(v.title(), M, y - 9, "F2", 9, ACCENT, 0);
			y -= 16;
		}
		row(v.columns(), cw, true, false);
		int n = 0;
		for (List<String> r : v.rows()) {
			if (n >= 60) {
				y -= 2;
				paragraph("(" + (v.rows().size() - 60) + " more rows not shown)", 7.5f, MUTED, 0);
				break;
			}
			if (rowHeight(r, cw, false) > y - BOTTOM) {
				newPage();
				row(v.columns(), cw, true, false);          // the header repeats on the new page
			}
			row(r, cw, false, n++ % 2 == 1);
		}
		y -= 10;
	}

	private float rowHeight(List<String> cells, float[] cw, boolean header) {
		int lines = 1;
		for (int c = 0; c < cw.length; c++) {
			lines = Math.max(lines, wrap(c < cells.size() ? cells.get(c) : "", 8, cw[c] - 10, header).size());
		}
		return lines * 10 + 9;
	}

	private void row(List<String> cells, float[] cw, boolean header, boolean zebra) {
		float rh = rowHeight(cells, cw, header);
		if (y - rh < BOTTOM) {
			newPage();
		}
		if (header) {
			fill(BURGUNDY);
			page.append(f(M)).append(' ').append(f(y - rh)).append(' ').append(f(W - 2 * M)).append(' ').append(f(rh)).append(" re f\n");
		} else if (zebra) {
			fill(BLUSH);
			page.append(f(M)).append(' ').append(f(y - rh)).append(' ').append(f(W - 2 * M)).append(' ').append(f(rh)).append(" re f\n");
		}
		float x = M;
		for (int c = 0; c < cw.length; c++) {
			float yy = y - 12.5f;
			for (String l : wrap(c < cells.size() ? cells.get(c) : "", 8, cw[c] - 10, header)) {
				txt(l, x + 5, yy, header ? "F2" : "F1", 8, header ? WHITE : INK, 0);
				yy -= 10;
			}
			x += cw[c];
		}
		if (!header) {
			stroke(LINE);
			page.append("0.4 w ").append(f(M)).append(' ').append(f(y - rh)).append(" m ").append(f(W - M)).append(' ').append(f(y - rh)).append(" l S\n");
		}
		y -= rh;
	}

	// ---- charts --------------------------------------------------------------------------------------------------

	private static final float[][] SERIES = { BURGUNDY, rgb(0x74, 0x71, 0x7A), ROSE };

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
		boolean bar = "bar".equals(v.kind());
		boolean legend = v.series().size() > 1 && !bar;
		float ch = 104, cardH = ch + 58 + (legend ? 14 : 0);
		need(cardH + 8);
		float top = y;
		// Card
		fill(WHITE);
		stroke(LINE);
		page.append("0.7 w ");
		roundRect(M, top - cardH, W - 2 * M, cardH, 8);
		page.append("B\n");
		String unit = v.unit() == null || v.unit().isBlank() ? "" : "  (" + v.unit() + ")";
		txt(fit(v.title() + unit, 9, true, W - 2 * M - 28), M + 14, top - 20, "F2", 9, BURGUNDY, 0);

		double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
		double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(1);
		if (bar) {
			min = Math.min(0, min);
		}
		if (max - min < 1e-9) {
			max += 1;
			min -= bar ? 0 : 1;
		}
		float left = M + 46, right = W - M - 16, ptop = top - 34, bottom = ptop - ch;
		// Horizontal grid and value labels
		for (int i = 0; i <= 3; i++) {
			float gy = bottom + (ptop - bottom) * i / 3f;
			stroke(i == 0 ? SOFT : LINE);
			page.append(i == 0 ? "[] 0 d " : "[2 3] 0 d ").append("0.5 w ").append(f(left)).append(' ').append(f(gy)).append(" m ").append(f(right)).append(' ')
					.append(f(gy)).append(" l S [] 0 d\n");
			String label = String.format(Locale.ROOT, "%.1f", min + (max - min) * i / 3);
			txt(label, left - 6 - width(label, 7, false), gy - 2.5f, "F1", 7, MUTED, 0);
		}
		int n = v.labels().size();
		float span = right - left - 10;
		final double lo = min, hi = max;
		if (bar) {
			VisualSeries s = v.series().get(0);
			float slot = span / n, bw = Math.max(2, slot * 0.62f);
			fill(BURGUNDY);
			for (int i = 0; i < n && i < s.values().size(); i++) {
				Double d = s.values().get(i);
				if (d == null) {
					continue;                                  // no data: no bar (never a zero-height stand-in)
				}
				float h = (float) ((d - lo) / (hi - lo) * (ptop - bottom));
				if (h > 0.2f) {
					page.append(f(left + 5 + slot * i + (slot - bw) / 2)).append(' ').append(f(bottom)).append(' ').append(f(bw)).append(' ').append(f(h)).append(" re f\n");
				}
			}
		} else {
			float step = n > 1 ? span / (n - 1) : 0;
			for (int si = 0; si < v.series().size() && si < 3; si++) {
				VisualSeries s = v.series().get(si);
				// Contiguous runs of values; a null ends a run, so gaps stay open.
				List<List<float[]>> runs = new ArrayList<>();
				List<float[]> run = new ArrayList<>();
				for (int i = 0; i < n && i < s.values().size(); i++) {
					Double d = s.values().get(i);
					if (d == null) {
						if (!run.isEmpty()) {
							runs.add(run);
						}
						run = new ArrayList<>();
						continue;
					}
					run.add(new float[] { left + 5 + step * i, bottom + (float) ((d - lo) / (hi - lo) * (ptop - bottom)) });
				}
				if (!run.isEmpty()) {
					runs.add(run);
				}
				if (si == 0) {
					fill(ROSE_LIGHT);                          // area under the main series, per run, ending at its last point
					for (List<float[]> r : runs) {
						if (r.size() < 2) {
							continue;
						}
						page.append(f(r.get(0)[0])).append(' ').append(f(bottom)).append(" m ");
						for (float[] p : r) {
							page.append(f(p[0])).append(' ').append(f(p[1])).append(" l ");
						}
						page.append(f(r.get(r.size() - 1)[0])).append(' ').append(f(bottom)).append(" l h f\n");
					}
				}
				stroke(SERIES[si]);
				page.append(si == 0 ? "1.6" : "0.9").append(" w 1 J 1 j\n");
				for (List<float[]> r : runs) {
					if (r.size() == 1) {
						fill(SERIES[si]);
						circle(r.get(0)[0], r.get(0)[1], 1.6f);
						page.append("f\n");
						continue;
					}
					for (int i = 0; i < r.size(); i++) {
						page.append(f(r.get(i)[0])).append(' ').append(f(r.get(i)[1])).append(i == 0 ? " m " : " l ");
					}
					page.append("S\n");
				}
				page.append("0 J 0 j\n");
			}
		}
		// x labels: first / middle / last for lines; every k-th hour for bars.
		List<Integer> idx = new ArrayList<>();
		if (bar) {
			int k = Math.max(1, (int) Math.ceil(n / 8.0));
			for (int i = 0; i < n; i += k) {
				idx.add(i);
			}
		} else {
			idx.add(0);
			if (n > 2) {
				idx.add((n - 1) / 2);
			}
			if (n > 1) {
				idx.add(n - 1);
			}
		}
		for (int i : idx) {
			String label = axisLabel(v.labels().get(i));
			float px = bar ? left + 5 + span / n * (i + 0.5f) : left + 5 + (n > 1 ? span / (n - 1) : 0) * i;
			float lx = Math.max(left - 4, Math.min(right - width(label, 7, false), px - width(label, 7, false) / 2));
			txt(label, lx, bottom - 12, "F1", 7, MUTED, 0);
		}
		if (legend) {
			float lx = left;
			for (int si = 0; si < v.series().size() && si < 3; si++) {
				fill(SERIES[si]);
				page.append(f(lx)).append(' ').append(f(bottom - 26)).append(" 10 2.4 re f\n");
				String name = v.series().get(si).name();
				txt(name, lx + 14, bottom - 27.5f, "F1", 7, INK, 0);
				lx += 26 + width(name, 7, false);
			}
		}
		y = top - cardH - 10;
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

	// ---- logo --------------------------------------------------------------------------------------------------

	/**
	 * The mark in a size x size box whose lower-left corner is (x, y). With {@code tile} it sits on the burgundy
	 * gradient tile and is drawn white; otherwise only the strokes are drawn in {@code color}.
	 */
	private void mark(float x, float yBottom, float size, float[] color, boolean tile) {
		float s = size / 64f;
		// Flip into SVG coordinates: (sx, sy) -> (x + s*sx, yBottom + size - s*sy).
		page.append("q ").append(f(s)).append(" 0 0 ").append(f(-s)).append(' ').append(f(x)).append(' ').append(f(yBottom + size)).append(" cm\n");
		float[] c = color;
		if (tile) {
			page.append("q ");
			roundRect(0, 0, 64, 64, 15);
			page.append("W n /ST sh Q\n");
			c = WHITE;
		}
		stroke(c);
		fill(c);
		page.append("1 J 1 j 4 w ").append(HOUSE).append("3 w ").append(ARCS);
		circle(32, 44, 2.6f);
		page.append("f ");
		circle(16, 29, 3.4f);
		page.append("f ");
		circle(48, 29, 3.4f);
		page.append("f ").append(DIAMOND).append("Q\n");
	}

	/** The stroked wordmark; (x, yBaseline) is the bottom-left of the letters, {@code scale} = points per unit (24 units tall). */
	private void wordmark(float x, float yBaseline, float scale, float[] color) {
		page.append("q ").append(f(scale)).append(" 0 0 ").append(f(-scale)).append(' ').append(f(x)).append(' ').append(f(yBaseline + 24 * scale))
				.append(" cm 0 J 0 j 2.6 w\n");
		stroke(color);
		page.append(WORDMARK).append("Q\n");
	}

	// ---- drawing primitives ------------------------------------------------------------------------------------

	private void roundRect(float x, float y0, float w, float h, float r) {
		float k = 0.5523f * r;
		page.append(f(x + r)).append(' ').append(f(y0)).append(" m ").append(f(x + w - r)).append(' ').append(f(y0)).append(" l ")
				.append(f(x + w - r + k)).append(' ').append(f(y0)).append(' ').append(f(x + w)).append(' ').append(f(y0 + r - k)).append(' ').append(f(x + w))
				.append(' ').append(f(y0 + r)).append(" c ").append(f(x + w)).append(' ').append(f(y0 + h - r)).append(" l ").append(f(x + w)).append(' ')
				.append(f(y0 + h - r + k)).append(' ').append(f(x + w - r + k)).append(' ').append(f(y0 + h)).append(' ').append(f(x + w - r)).append(' ')
				.append(f(y0 + h)).append(" c ").append(f(x + r)).append(' ').append(f(y0 + h)).append(" l ").append(f(x + r - k)).append(' ').append(f(y0 + h))
				.append(' ').append(f(x)).append(' ').append(f(y0 + h - r + k)).append(' ').append(f(x)).append(' ').append(f(y0 + h - r)).append(" c ")
				.append(f(x)).append(' ').append(f(y0 + r)).append(" l ").append(f(x)).append(' ').append(f(y0 + r - k)).append(' ').append(f(x + r - k))
				.append(' ').append(f(y0)).append(' ').append(f(x + r)).append(' ').append(f(y0)).append(" c h ");
	}

	private void circle(float cx, float cy, float r) {
		float k = 0.5523f * r;
		page.append(f(cx + r)).append(' ').append(f(cy)).append(" m ").append(f(cx + r)).append(' ').append(f(cy + k)).append(' ').append(f(cx + k)).append(' ')
				.append(f(cy + r)).append(' ').append(f(cx)).append(' ').append(f(cy + r)).append(" c ").append(f(cx - k)).append(' ').append(f(cy + r)).append(' ')
				.append(f(cx - r)).append(' ').append(f(cy + k)).append(' ').append(f(cx - r)).append(' ').append(f(cy)).append(" c ").append(f(cx - r)).append(' ')
				.append(f(cy - k)).append(' ').append(f(cx - k)).append(' ').append(f(cy - r)).append(' ').append(f(cx)).append(' ').append(f(cy - r)).append(" c ")
				.append(f(cx + k)).append(' ').append(f(cy - r)).append(' ').append(f(cx + r)).append(' ').append(f(cy - k)).append(' ').append(f(cx + r)).append(' ')
				.append(f(cy)).append(" c h ");
	}

	private void fill(float[] c) {
		page.append(f(c[0])).append(' ').append(f(c[1])).append(' ').append(f(c[2])).append(" rg ");
	}

	private void stroke(float[] c) {
		page.append(f(c[0])).append(' ').append(f(c[1])).append(' ').append(f(c[2])).append(" RG ");
	}

	/** Text with its baseline at {@code baseline}. */
	private void txt(String s, float x, float baseline, String font, float size, float[] c, float charSpace) {
		page.append(f(c[0])).append(' ').append(f(c[1])).append(' ').append(f(c[2])).append(" rg BT /").append(font).append(' ').append(f(size)).append(" Tf ");
		if (charSpace != 0) {
			page.append(f(charSpace)).append(" Tc ");
		}
		page.append(f(x)).append(' ').append(f(baseline)).append(" Td (").append(escape(s)).append(") Tj ");
		if (charSpace != 0) {
			page.append("0 Tc ");
		}
		page.append("ET\n");
	}

	private static float[] tone(String t) {
		return switch (t == null ? "" : t) {
			case "ok" -> OK;
			case "warn" -> WARN;
			case "alarm" -> ALARM;
			default -> ACCENT;
		};
	}

	// ---- text measurement --------------------------------------------------------------------------------------

	static List<String> wrap(String s, float size, float max, boolean bold) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : (s == null ? "" : s).split(" ")) {
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
		// A single word longer than the line is cut into pieces that fit, so text never runs outside its box.
		List<String> fitted = new ArrayList<>();
		for (String l : out) {
			String rest = l;
			while (width(rest, size, bold) > max && rest.length() > 1) {
				int cut = rest.length() - 1;
				while (cut > 1 && width(rest.substring(0, cut), size, bold) > max) {
					cut--;
				}
				fitted.add(rest.substring(0, cut));
				rest = rest.substring(cut);
			}
			fitted.add(rest);
		}
		return fitted;
	}

	static float width(String s, float size, boolean bold) {
		float w = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			w += c >= 32 && c <= 126 ? WIDTHS[c - 32] : 667;
		}
		return w * size / 1000 * (bold ? 1.06f : 1f);
	}

	/** The text, shortened with "..." if it does not fit {@code max}. */
	private static String fit(String s, float size, boolean bold, float max) {
		if (s == null) {
			return "";
		}
		if (width(s, size, bold) <= max) {
			return s;
		}
		String t = s;
		while (t.length() > 1 && width(t + "...", size, bold) > max) {
			t = t.substring(0, t.length() - 1);
		}
		return t.stripTrailing() + "...";
	}

	private static String cap(String s) {
		return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	// ---- PDF objects -------------------------------------------------------------------------------------------

	private byte[] finish(ReportContent c) {
		int pageCount = pages.size();
		String generated = c.generatedAt() == null ? "" : SHORT.format(c.generatedAt().atZone(zone));
		// Footers need the page count, so they are added last (they sit below the content area).
		for (int i = 1; i < pageCount; i++) {
			page = pages.get(i);
			stroke(LINE);
			page.append("0.6 w ").append(f(M)).append(" 42 m ").append(f(W - M)).append(" 42 l S\n");
			mark(M, 20, 13, WHITE, true);
			txt("VÉYRA", M + 19, 24, "F2", 7.5f, BURGUNDY, 1.2f);
			txt("Confidential  ·  Generated " + generated, M + 58, 24, "F1", 7, MUTED, 0);
			String p = "Page " + (i + 1) + " of " + pageCount;
			txt(p, W - M - width(p, 7, true), 24, "F2", 7, BURGUNDY, 0);
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		List<Integer> offsets = new ArrayList<>();
		write(out, "%PDF-1.4\n%âãÏÓ\n");
		int firstPage = 12;
		// 1 catalog, 2 pages, 3-6 fonts, 7-8 transparency states, 9-10 shadings, 11 document info, then page/content pairs
		StringBuilder kids = new StringBuilder();
		for (int i = 0; i < pageCount; i++) {
			kids.append(firstPage + i * 2).append(" 0 R ");
		}
		String[] objects = { "<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [" + kids + "] /Count " + pageCount + " >>",
				"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
				"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>",
				"<< /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >>",
				"<< /Type /Font /Subtype /Type1 /BaseFont /Times-Italic /Encoding /WinAnsiEncoding >>",
				"<< /Type /ExtGState /ca 0.045 /CA 0.045 >>", "<< /Type /ExtGState /ca 0.07 /CA 0.07 >>",
				shading(0, H, W, 0, PAPER, ROSE_LIGHT), shading(0, 0, 64, 64, ACCENT, WINE),
				"<< /Title (" + escape("VÉYRA - " + c.title()) + ") /Author (VÉYRA) /Subject (" + escape(cap(c.periodLabel()) + ", " + c.scope())
						+ ") /Creator (VÉYRA report engine) /Producer (VÉYRA) >>" };
		for (int i = 0; i < objects.length; i++) {
			offsets.add(out.size());
			write(out, (i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n");
		}
		String resources = "<< /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R /F4 6 0 R >> /ExtGState << /GW 7 0 R /GC 8 0 R >> /Shading << /SC 9 0 R /ST 10 0 R >> >>";
		for (int i = 0; i < pageCount; i++) {
			int pageObj = firstPage + i * 2;
			byte[] stream = encode(pages.get(i).toString());
			offsets.add(out.size());
			write(out, pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources " + resources + " /Contents " + (pageObj + 1)
					+ " 0 R >>\nendobj\n");
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
		x.append("trailer\n<< /Size ").append(offsets.size() + 1).append(" /Root 1 0 R /Info 11 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
		write(out, x.toString());
		return out.toByteArray();
	}

	private static String shading(float x0, float y0, float x1, float y1, float[] c0, float[] c1) {
		return "<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [" + f(x0) + " " + f(y0) + " " + f(x1) + " " + f(y1) + "] /Function << /FunctionType 2 /Domain [0 1] /C0 ["
				+ f(c0[0]) + " " + f(c0[1]) + " " + f(c0[2]) + "] /C1 [" + f(c1[0]) + " " + f(c1[1]) + " " + f(c1[2]) + "] /N 1 >> /Extend [true true] >>";
	}

	/** Object headers and the info dictionary are Latin-1 (identical to PDFDocEncoding for these characters). */
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

	private static float[] rgb(int r, int g, int b) {
		return new float[] { r / 255f, g / 255f, b / 255f };
	}

	private static String f(float v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}
}
