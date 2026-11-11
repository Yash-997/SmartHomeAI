package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.smarthome.alert.AlertEmailTemplates;
import com.smarthome.alert.AlertEmailTemplates.MotionDetails;
import com.smarthome.alert.AlertProperties;
import com.smarthome.alert.EmailContent;
import com.smarthome.alert.GasAlert;

/**
 * The alert email templates, rendered without Spring. Also writes the rendered samples to target/email-samples/ so they
 * can be opened in a browser.
 */
class AlertEmailTemplateTests {

	private static final Instant AT = Instant.parse("2026-11-11T08:42:17Z");
	private static final UUID EVENT = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
	/** A number followed by "ppm": an ADC value labelled as a concentration. Must never appear. */
	private static final Pattern NUMBER_PPM = Pattern.compile("\\d\\s*ppm", Pattern.CASE_INSENSITIVE);

	private static AlertProperties props(String dashboard) {
		return new AlertProperties(true, 600, 15, 2, "", true, dashboard, "Asia/Kolkata");
	}

	private static GasAlert gasIncident(String deviceName) {
		GasAlert a = new GasAlert(UUID.randomUUID(), UUID.randomUUID(), deviceName, 600, 510, 2800, AT);
		a.observe(3150);
		return a;
	}

	private static MotionDetails motion(String deviceName) {
		return new MotionDetails(EVENT, deviceName, "esp32-a1b2c3d4e5f6", AT, AT.minusSeconds(3 * 3600));
	}

	@Test
	void gasAlertHasEveryRequiredFactWithTheRawUnitAndNeverPpm() {
		EmailContent e = AlertEmailTemplates.gasAlert(gasIncident("Kitchen sensor"), props("https://home.example.net"));
		for (String part : new String[] { e.html(), e.text() }) {
			assertTrue(part.contains("GAS/SMOKE ALERT"));
			assertTrue(part.contains("Kitchen sensor"));
			assertTrue(part.contains("2800 raw ADC (0-4095)"), "reading with its real unit");
			assertTrue(part.contains("600 raw ADC (0-4095)"), "threshold with its unit");
			assertTrue(part.contains("Wed 11 Nov 2026, 14:12:17 IST"), "timestamp in the configured zone");
			assertTrue(part.contains("CRITICAL"));
			assertTrue(part.contains("call your local emergency number"));
			assertTrue(part.contains("https://home.example.net/"), "dashboard link");
			assertFalse(NUMBER_PPM.matcher(part).find(), "an ADC value is never labelled ppm");
		}
		assertTrue(e.subject().startsWith("GAS/SMOKE ALERT"));
		assertTrue(e.html().contains("367% above the threshold"));
		assertTrue(e.html().contains("#B91C1C"), "red alert band");
	}

	@Test
	void motionEmailHasEveryRequiredFactAndLinksToTheEvent() {
		EmailContent e = AlertEmailTemplates.motionWhileAway(motion("Front door"), props("https://home.example.net/"));
		for (String part : new String[] { e.html(), e.text() }) {
			assertTrue(part.contains("MOTION DETECTED WHILE YOU ARE AWAY"));
			assertTrue(part.contains("AWAY"));
			assertTrue(part.contains("Front door"));
			assertTrue(part.contains("Wed 11 Nov 2026, 14:12:17 IST"));
			assertTrue(part.contains("new motion episode"));
			assertTrue(part.contains("https://home.example.net/?event=" + EVENT + "#security"), "deep link to the event");
			assertTrue(part.contains("Review the event") || part.contains("review this security event"));
		}
		assertTrue(e.subject().startsWith("Motion detected while you are away"));
		assertTrue(e.html().contains("#1E1B4B"), "security style, distinct from the gas email");
		assertFalse(e.html().contains("#B91C1C"));
	}

	@Test
	void userTextIsEscapedAndCannotBreakTheSubject() {
		String evil = "<script>alert(1)</script>\r\nBcc: x@y.z";
		EmailContent g = AlertEmailTemplates.gasAlert(gasIncident(evil), props(""));
		EmailContent m = AlertEmailTemplates.motionWhileAway(motion(evil), props(""));
		for (EmailContent e : new EmailContent[] { g, m }) {
			assertFalse(e.html().contains("<script>"));
			assertTrue(e.html().contains("&lt;script&gt;"));
			assertFalse(e.subject().contains("\n") || e.subject().contains("\r"));
		}
	}

	@Test
	void anUnsafeOrMissingDashboardAddressGivesNoButtonAndNoCredentials() {
		for (String url : new String[] { "", "javascript:alert(1)", "https://admin:secret@home.example.net", "ftp://home.example.net" }) {
			EmailContent e = AlertEmailTemplates.motionWhileAway(motion("Hall"), props(url));
			assertFalse(e.html().contains("<a href"), url);
			assertFalse(e.html().contains("secret") || e.text().contains("secret"), url);
			assertFalse(e.text().contains("Review the event: "), url);
		}
	}

	@Test
	void recoveryAndTestEmailsUseTheSameBranding() {
		GasAlert a = gasIncident("Kitchen sensor");
		a.observe(420);
		a.recover(AT.plusSeconds(600));
		EmailContent r = AlertEmailTemplates.gasRecovered(a, props("https://home.example.net"));
		assertTrue(r.subject().startsWith("Resolved"));
		assertTrue(r.html().contains("READINGS BACK TO NORMAL") && r.html().contains("3150 raw ADC (0-4095)"));
		assertFalse(NUMBER_PPM.matcher(r.html()).find());
		EmailContent t = AlertEmailTemplates.testEmail(props(""));
		for (EmailContent e : new EmailContent[] { r, t }) {
			assertTrue(e.html().contains("SmartHome<span") && e.html().contains("replies are not read"), "brand bar and footer");
		}
	}

	/** Not an assertion test: writes the samples for a human to look at. */
	@Test
	void writeRenderedSamples() throws Exception {
		Path dir = Path.of("target", "email-samples");
		Files.createDirectories(dir);
		AlertProperties p = props("http://localhost:5174");
		Files.writeString(dir.resolve("gas-alert.html"), AlertEmailTemplates.gasAlert(gasIncident("Kitchen sensor"), p).html(), StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("motion-away.html"), AlertEmailTemplates.motionWhileAway(motion("Front door"), p).html(), StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("gas-alert.txt"), AlertEmailTemplates.gasAlert(gasIncident("Kitchen sensor"), p).text(), StandardCharsets.UTF_8);
		Files.writeString(dir.resolve("motion-away.txt"), AlertEmailTemplates.motionWhileAway(motion("Front door"), p).text(), StandardCharsets.UTF_8);
		GasAlert a = gasIncident("Kitchen sensor");
		a.observe(420);
		a.recover(AT.plusSeconds(600));
		Files.writeString(dir.resolve("gas-recovered.html"), AlertEmailTemplates.gasRecovered(a, p).html(), StandardCharsets.UTF_8);
		assertTrue(Files.size(dir.resolve("gas-alert.html")) > 1000);
	}
}
