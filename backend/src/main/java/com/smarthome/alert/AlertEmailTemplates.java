package com.smarthome.alert;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Renders the SmartHomeAI alert emails: an HTML part (table layout with inline styles, so it looks the same in Gmail,
 * Outlook and phone mail apps) and a plain-text part with the same facts. All user-supplied text (device names) is
 * escaped. Nothing here ever includes credentials: the only link is the configured dashboard address, which
 * {@link AlertProperties#dashboardBase()} has already checked.
 *
 * <p>Gas values are raw MQ-2 ADC counts (0-4095) and are always labelled as such, never as ppm.
 */
public final class AlertEmailTemplates {

	private AlertEmailTemplates() {
	}

	/** What a motion-while-away email needs; {@code eventId} is the security event it reports. */
	public record MotionDetails(UUID eventId, String deviceName, String deviceId, Instant detectedAt, Instant armedSince) {
	}

	/** Colours for one kind of email. */
	private record Theme(String band, String bandText, String accent, String panel, String panelBorder, String badgeBg, String badgeText) {
	}

	private static final Theme GAS = new Theme("#B91C1C", "#FFFFFF", "#EA580C", "#FFF7ED", "#FDBA74", "#FEE2E2", "#991B1B");
	private static final Theme RESOLVED = new Theme("#15803D", "#FFFFFF", "#16A34A", "#F0FDF4", "#86EFAC", "#DCFCE7", "#166534");
	private static final Theme SECURITY = new Theme("#1E1B4B", "#FFFFFF", "#6366F1", "#EEF2FF", "#A5B4FC", "#FEF3C7", "#92400E");
	private static final Theme NEUTRAL = new Theme("#0F172A", "#FFFFFF", "#6366F1", "#F8FAFC", "#CBD5E1", "#E2E8F0", "#334155");

	private static final String ADC_UNIT = "raw ADC (0-4095)";

	// ---- the emails ----------------------------------------------------------------------------

	public static EmailContent gasAlert(GasAlert a, AlertProperties p) {
		String device = clean(a.getDeviceName());
		String when = time(a.getStartedAt(), p.zone());
		int reading = a.getTriggerGasRaw();
		int threshold = a.getThreshold();
		String over = threshold > 0 ? Math.round((reading - threshold) * 100.0 / threshold) + "% above the threshold" : "";

		List<String[]> rows = List.of(
				row("Device", device),
				row("Gas reading", reading + " " + ADC_UNIT),
				row("Alert threshold", threshold + " " + ADC_UNIT),
				row("Detected", when),
				row("Severity", "CRITICAL"),
				row("Status", "Active: readings are still being watched"));
		List<String> actions = List.of(
				"If you smell gas or see smoke, get everyone (and pets) out of the building now.",
				"Do not switch lights or appliances on or off, and avoid flames or anything that can spark.",
				"Only if it is safe: open doors and windows and shut off the gas at the meter or cylinder.",
				"From outside, call your local emergency number or your gas provider's emergency line.",
				"Do not go back inside until the area has been declared safe.");
		String dash = p.dashboardBase();

		String subject = "GAS/SMOKE ALERT: " + device + " reads " + reading + " (threshold " + threshold + ")";
		StringBuilder html = new StringBuilder();
		html.append(open("Gas or smoke detected by " + device + ". Check the area now and leave if you smell gas.", GAS));
		html.append(band(GAS, "SmartHomeAI Emergency Alert", "GAS/SMOKE ALERT",
				"High gas or smoke readings detected by <strong>" + esc(device) + "</strong>."));
		html.append(statusStrip(GAS, "CRITICAL", "Incident active", over));
		html.append(section(details(rows, GAS)));
		html.append(section(callout(GAS, "Do this now", actions, true)));
		if (dash != null) {
			html.append(section(button(GAS.band(), "Open SmartHomeAI dashboard", dash + "/")));
		}
		html.append(section(note("Readings are raw MQ-2 sensor values, not calibrated ppm concentrations. This is a hobby sensor, "
				+ "not a certified gas or smoke alarm. You will get one more email when the readings return to normal.")));
		html.append(close("You receive this because you own the device \"" + device + "\" in your SmartHomeAI account.", p));

		StringBuilder text = new StringBuilder();
		text.append("GAS/SMOKE ALERT - SmartHomeAI\n\n");
		text.append("High gas or smoke readings were detected by \"").append(device).append("\".\n\n");
		textRows(text, rows);
		if (!over.isEmpty()) {
			text.append("The reading is ").append(over).append(".\n");
		}
		text.append("\nDO THIS NOW\n");
		textList(text, actions, true);
		if (dash != null) {
			text.append("\nDashboard: ").append(dash).append("/\n");
		}
		text.append("\nReadings are raw MQ-2 sensor values, not calibrated ppm. This is a hobby sensor, not a certified alarm.\n");
		text.append("You will get one more email when the readings return to normal.\n");
		return new EmailContent(subject, text.toString(), html.toString());
	}

	public static EmailContent gasRecovered(GasAlert a, AlertProperties p) {
		String device = clean(a.getDeviceName());
		List<String[]> rows = List.of(
				row("Device", device),
				row("Latest reading", a.getLastGasRaw() + " " + ADC_UNIT),
				row("Recovery level", "below " + a.getRecoveryThreshold() + " " + ADC_UNIT),
				row("Peak reading", a.getPeakGasRaw() + " " + ADC_UNIT),
				row("Started", time(a.getStartedAt(), p.zone())),
				row("Ended", a.getRecoveredAt() == null ? "-" : time(a.getRecoveredAt(), p.zone())),
				row("Status", "Resolved"));
		String dash = p.dashboardBase();

		String subject = "Resolved: gas/smoke readings back to normal on " + device;
		StringBuilder html = new StringBuilder();
		html.append(open("Gas/smoke readings on " + device + " are back to normal.", RESOLVED));
		html.append(band(RESOLVED, "SmartHomeAI Alert Update", "READINGS BACK TO NORMAL",
				"The gas/smoke incident on <strong>" + esc(device) + "</strong> has ended."));
		html.append(statusStrip(RESOLVED, "RESOLVED", "Incident closed", ""));
		html.append(section(details(rows, RESOLVED)));
		html.append(section(callout(RESOLVED, "Before you relax",
				List.of("Make sure the cause has been found and fixed (a gas leak can come back).",
						"Keep the area ventilated for a while."), false)));
		if (dash != null) {
			html.append(section(button(RESOLVED.band(), "View the incident history", dash + "/")));
		}
		html.append(close("You receive this because you own the device \"" + device + "\" in your SmartHomeAI account.", p));

		StringBuilder text = new StringBuilder("READINGS BACK TO NORMAL - SmartHomeAI\n\n");
		text.append("The gas/smoke incident on \"").append(device).append("\" has ended.\n\n");
		textRows(text, rows);
		text.append("\nMake sure the cause has been found and fixed, and keep the area ventilated for a while.\n");
		if (dash != null) {
			text.append("Dashboard: ").append(dash).append("/\n");
		}
		return new EmailContent(subject, text.toString(), html.toString());
	}

	public static EmailContent motionWhileAway(MotionDetails m, AlertProperties p) {
		String device = clean(m.deviceName());
		String when = time(m.detectedAt(), p.zone());
		String ref = m.eventId().toString().substring(0, 8).toUpperCase(Locale.ROOT);
		List<String[]> rows = new ArrayList<>(List.of(
				row("Security mode", "AWAY"),
				row("Device", device),
				row("Detected", when),
				row("Event", "Motion alert, start of a new motion episode"),
				row("Event reference", ref)));
		if (m.deviceId() != null && !m.deviceId().isBlank()) {
			rows.add(2, row("Device ID", clean(m.deviceId())));
		}
		if (m.armedSince() != null) {
			rows.add(1, row("Away mode since", time(m.armedSince(), p.zone())));
		}
		List<String> actions = List.of(
				"Review the event in your dashboard and acknowledge it once you know what caused it.",
				"If the motion was expected (family, a pet, a cleaner), simply acknowledge it.",
				"If nobody should be there, do not go in alone. Contact your local police.");
		String dash = p.dashboardBase();
		String link = dash == null ? null : dash + "/?event=" + m.eventId() + "#security";

		String subject = "Motion detected while you are away: " + device;
		StringBuilder html = new StringBuilder();
		html.append(open("Motion was detected by " + device + " while your home is in AWAY mode. Review the security event.", SECURITY));
		html.append(band(SECURITY, "SmartHomeAI Security", "MOTION DETECTED WHILE YOU ARE AWAY",
				"<strong>" + esc(device) + "</strong> detected motion while your home is armed in Away mode."));
		html.append(statusStrip(SECURITY, "AWAY", "Security event recorded", "Needs your review"));
		html.append(section(details(rows, SECURITY)));
		html.append(section(callout(SECURITY, "Please review this security event", actions, true)));
		if (link != null) {
			html.append(section(button(SECURITY.accent(), "Review the event in SmartHomeAI", link)));
		}
		html.append(section(note("You get one email per motion episode. Further motion on this device will not send another email "
				+ "until the motion has stopped. The event stays open in your dashboard until you acknowledge it.")));
		html.append(close("You receive this because your SmartHomeAI account is in Away mode and you own \"" + device + "\".", p));

		StringBuilder text = new StringBuilder("MOTION DETECTED WHILE YOU ARE AWAY - SmartHomeAI Security\n\n");
		text.append("\"").append(device).append("\" detected motion while your home is armed in Away mode.\n\n");
		textRows(text, rows);
		text.append("\nPLEASE REVIEW THIS SECURITY EVENT\n");
		textList(text, actions, true);
		if (link != null) {
			text.append("\nReview the event: ").append(link).append("\n");
		}
		text.append("\nYou get one email per motion episode; the event stays open in your dashboard until you acknowledge it.\n");
		return new EmailContent(subject, text.toString(), html.toString());
	}

	public static EmailContent testEmail(AlertProperties p) {
		String dash = p.dashboardBase();
		StringBuilder html = new StringBuilder();
		html.append(open("Your SmartHomeAI email alerts are working.", NEUTRAL));
		html.append(band(NEUTRAL, "SmartHomeAI", "TEST EMAIL", "Your backend can send email to this address."));
		html.append(section(callout(NEUTRAL, "What this means",
				List.of("Gas/smoke alerts and Away-mode motion alerts will reach this address.", "No incident or security event was created."),
				false)));
		if (dash != null) {
			html.append(section(button(NEUTRAL.accent(), "Open SmartHomeAI dashboard", dash + "/")));
		}
		html.append(close("You asked for this test email from your SmartHomeAI account.", p));
		String text = "SmartHomeAI test email\n\nIf you can read this, the SMTP setup works and gas/smoke and Away-mode motion alerts "
				+ "will reach this address.\nNo incident or security event was created.\n";
		return new EmailContent("SmartHomeAI test email", text, html.toString());
	}

	// ---- layout pieces -------------------------------------------------------------------------

	private static final String FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";

	private static String open(String preheader, Theme t) {
		return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
				+ "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
				+ "<meta name=\"color-scheme\" content=\"light\"><meta name=\"supported-color-schemes\" content=\"light\">"
				+ "<title>SmartHomeAI</title><style>"
				+ "@media only screen and (max-width:620px){.wrap{width:100%!important}.px{padding-left:20px!important;padding-right:20px!important}"
				+ ".h1{font-size:23px!important;line-height:29px!important}.lbl{width:42%!important}.btn a{display:block!important}}"
				+ "</style></head>"
				+ "<body style=\"margin:0;padding:0;background:#E2E8F0;-webkit-text-size-adjust:100%;\">"
				// hidden preview line shown by inboxes next to the subject
				+ "<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;\">" + esc(preheader) + "</div>"
				+ "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#E2E8F0;\"><tr>"
				+ "<td align=\"center\" style=\"padding:24px 12px;\">"
				+ "<table role=\"presentation\" class=\"wrap\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" "
				+ "style=\"width:600px;max-width:600px;background:#FFFFFF;border-radius:14px;overflow:hidden;"
				+ "box-shadow:0 1px 3px rgba(15,23,42,.12);font-family:" + FONT + ";color:#0F172A;\">"
				// brand bar
				+ "<tr><td class=\"px\" style=\"background:#0F172A;padding:16px 32px;\">"
				+ "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
				+ "<td style=\"width:30px;height:30px;background:" + t.accent() + ";border-radius:8px;text-align:center;"
				+ "font:700 16px/30px " + FONT + ";color:#FFFFFF;\">S</td>"
				+ "<td style=\"padding-left:10px;font:700 16px/30px " + FONT + ";color:#FFFFFF;letter-spacing:.3px;\">"
				+ "SmartHome<span style=\"color:#A5B4FC;\">AI</span></td>"
				+ "</tr></table></td></tr>";
	}

	private static String band(Theme t, String eyebrow, String headline, String introHtml) {
		return "<tr><td class=\"px\" style=\"background:" + t.band() + ";padding:28px 32px 26px;border-bottom:4px solid " + t.accent() + ";\">"
				+ "<div style=\"font:600 12px/16px " + FONT + ";letter-spacing:1.4px;text-transform:uppercase;color:" + t.bandText()
				+ ";opacity:.85;\">" + esc(eyebrow) + "</div>"
				+ "<h1 class=\"h1\" style=\"margin:8px 0 10px;font:800 27px/33px " + FONT + ";color:" + t.bandText() + ";letter-spacing:.2px;\">"
				+ esc(headline) + "</h1>"
				+ "<p style=\"margin:0;font:400 16px/24px " + FONT + ";color:" + t.bandText() + ";\">" + introHtml + "</p>"
				+ "</td></tr>";
	}

	private static String statusStrip(Theme t, String badge, String status, String extra) {
		return "<tr><td class=\"px\" style=\"padding:18px 32px 0;\">"
				+ "<span style=\"display:inline-block;background:" + t.badgeBg() + ";color:" + t.badgeText() + ";font:700 12px/16px " + FONT
				+ ";letter-spacing:1px;padding:5px 10px;border-radius:999px;\">" + esc(badge) + "</span>"
				+ "<span style=\"font:600 14px/20px " + FONT + ";color:#334155;padding-left:8px;\">" + esc(status) + "</span>"
				+ (extra.isEmpty() ? "" : "<span style=\"font:400 14px/20px " + FONT + ";color:#64748B;\"> &middot; " + esc(extra) + "</span>")
				+ "</td></tr>";
	}

	private static String section(String inner) {
		return "<tr><td class=\"px\" style=\"padding:20px 32px 0;\">" + inner + "</td></tr>";
	}

	private static String details(List<String[]> rows, Theme t) {
		StringBuilder b = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
				+ "style=\"border:1px solid #E2E8F0;border-radius:10px;border-collapse:separate;overflow:hidden;\">");
		for (int i = 0; i < rows.size(); i++) {
			String border = i == 0 ? "" : "border-top:1px solid #E2E8F0;";
			b.append("<tr><td class=\"lbl\" width=\"36%\" style=\"").append(border).append("background:#F8FAFC;padding:11px 14px;font:600 13px/19px ")
					.append(FONT).append(";color:#475569;vertical-align:top;\">").append(esc(rows.get(i)[0])).append("</td>")
					.append("<td style=\"").append(border).append("padding:11px 14px;font:600 15px/21px ").append(FONT)
					.append(";color:#0F172A;word-break:break-word;\">").append(esc(rows.get(i)[1])).append("</td></tr>");
		}
		return b.append("</table>").toString();
	}

	private static String callout(Theme t, String title, List<String> items, boolean numbered) {
		StringBuilder b = new StringBuilder("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:")
				.append(t.panel()).append(";border-left:4px solid ").append(t.accent()).append(";border-radius:8px;\"><tr><td style=\"padding:16px 18px;\">")
				.append("<div style=\"font:700 15px/21px ").append(FONT).append(";color:#0F172A;margin-bottom:8px;\">").append(esc(title)).append("</div>");
		for (int i = 0; i < items.size(); i++) {
			String marker = numbered ? (i + 1) + "." : "&bull;";
			b.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-top:6px;\"><tr>")
					.append("<td style=\"width:22px;vertical-align:top;font:700 14px/21px ").append(FONT).append(";color:").append(t.accent())
					.append(";\">").append(marker).append("</td><td style=\"font:400 14px/21px ").append(FONT).append(";color:#1E293B;\">")
					.append(esc(items.get(i))).append("</td></tr></table>");
		}
		return b.append("</td></tr></table>").toString();
	}

	private static String button(String color, String label, String url) {
		return "<table role=\"presentation\" class=\"btn\" cellpadding=\"0\" cellspacing=\"0\"><tr><td style=\"border-radius:8px;background:"
				+ color + ";\"><a href=\"" + esc(url) + "\" target=\"_blank\" rel=\"noopener\" style=\"display:inline-block;padding:13px 22px;"
				+ "font:700 15px/20px " + FONT + ";color:#FFFFFF;text-decoration:none;border-radius:8px;text-align:center;\">" + esc(label)
				+ " &rarr;</a></td></tr></table>";
	}

	private static String note(String text) {
		return "<p style=\"margin:0;font:400 13px/20px " + FONT + ";color:#64748B;\">" + esc(text) + "</p>";
	}

	private static String close(String reason, AlertProperties p) {
		return "<tr><td class=\"px\" style=\"padding:28px 32px 0;\"><div style=\"border-top:1px solid #E2E8F0;\"></div></td></tr>"
				+ "<tr><td class=\"px\" style=\"padding:16px 32px 28px;font:400 12px/18px " + FONT + ";color:#64748B;\">"
				+ "<div style=\"font:700 13px/18px " + FONT + ";color:#334155;\">SmartHomeAI</div>"
				+ "<div>Home monitoring, security and automation.</div>"
				+ "<div style=\"margin-top:8px;\">" + esc(reason) + " This is an automated message; replies are not read.</div>"
				+ "<div style=\"margin-top:4px;\">Times are shown in " + esc(p.zone().getId()) + ".</div>"
				+ "</td></tr></table></td></tr></table></body></html>";
	}

	// ---- plain text and helpers ----------------------------------------------------------------

	private static void textRows(StringBuilder b, List<String[]> rows) {
		for (String[] r : rows) {
			b.append(String.format(Locale.ROOT, "%-16s %s%n", r[0] + ":", r[1]));
		}
	}

	private static void textList(StringBuilder b, List<String> items, boolean numbered) {
		for (int i = 0; i < items.size(); i++) {
			b.append(numbered ? (i + 1) + ". " : "- ").append(items.get(i)).append('\n');
		}
	}

	private static String[] row(String label, String value) {
		return new String[] { label, value };
	}

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm:ss z", Locale.ENGLISH);

	static String time(Instant at, ZoneId zone) {
		return at == null ? "-" : WHEN.format(at.atZone(zone));
	}

	/** Device names are user text: no control characters (they could break the subject line), and a sane length. */
	static String clean(String s) {
		if (s == null || s.isBlank()) {
			return "Unnamed device";
		}
		String c = s.replaceAll("\\p{Cntrl}", " ").trim();
		return c.length() > 80 ? c.substring(0, 80) : c;
	}

	static String esc(String s) {
		if (s == null) {
			return "";
		}
		StringBuilder b = new StringBuilder(s.length() + 16);
		for (char ch : s.toCharArray()) {
			switch (ch) {
				case '&' -> b.append("&amp;");
				case '<' -> b.append("&lt;");
				case '>' -> b.append("&gt;");
				case '"' -> b.append("&quot;");
				case '\'' -> b.append("&#39;");
				default -> b.append(ch);
			}
		}
		return b.toString();
	}
}
