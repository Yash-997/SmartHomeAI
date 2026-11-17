package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.smarthome.assistant.tools.ToolRegistry;
import com.smarthome.assistant.tools.ToolTypes.ToolContext;
import com.smarthome.assistant.tools.ToolTypes.ToolResult;

import tools.jackson.databind.JsonNode;

/**
 * Exact historical questions (gas incidents, LED commands) are answered by parameterized SQL over the caller's own rows,
 * not by similarity search, and say so plainly when nothing is stored.
 */
class AssistantHistoryTests extends AssistantTestBase {

	@Autowired ToolRegistry registry;
	@Autowired JdbcTemplate jdbc;

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private ToolContext ctx(Paired p, String name) {
		return new ToolContext(p.userId(), IST, Instant.now(), Map.of(p.uuid(), name));
	}

	private void incident(Paired p, UUID owner, String name, Instant start, Instant recovered, int trigger, int peak, int last) {
		jdbc.update("insert into gas_alerts (id, owner_user_id, device_uuid, device_name, threshold, recovery_threshold, trigger_gas_raw, peak_gas_raw, "
				+ "last_gas_raw, state, started_at, recovered_at, alert_email_status) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
				UUID.randomUUID(), owner, p.uuid(), name, 600, 500, trigger, peak, last, recovered == null ? "ACTIVE" : "RECOVERED",
				Timestamp.from(start), recovered == null ? null : Timestamp.from(recovered), "SENT");
	}

	private void command(Paired p, UUID requester, String action, String value, Instant at) {
		jdbc.update("insert into device_commands (id, device_uuid, requested_by, action, action_value, status, created_at, expires_at, completed_at) "
				+ "values (?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), p.uuid(), requester, action, value, "ACKNOWLEDGED", Timestamp.from(at),
				Timestamp.from(at.plusSeconds(30)), Timestamp.from(at.plusSeconds(1)));
	}

	@Test
	void gasIncidentsComeFromTheAlertTableWithExactTimesInTheUsersZone() throws Exception {
		Paired p = pairNamed(newUserToken(), "Gas Pantry");
		Instant base = Instant.parse("2026-11-15T10:20:14Z");
		incident(p, p.userId(), "Gas Pantry", base, base.plusSeconds(59), 631, 631, 419);
		incident(p, p.userId(), "Gas Pantry", base.minus(2, ChronoUnit.DAYS), base.minus(2, ChronoUnit.DAYS).plusSeconds(517), 863, 900, 463);

		ToolResult all = registry.call(ctx(p, "Gas Pantry"), "get_gas_incidents", "{\"limit\":1}");
		assertTrue(all.ok(), all.error());
		String t = all.output().text();
		assertTrue(t.contains("2 in total (the newest 1 are listed)"), t);
		assertTrue(t.contains("Most recent: Gas Pantry, started 15 Nov 2026 15:50"), "10:20 UTC is 15:50 in Asia/Kolkata: " + t);
		assertTrue(t.contains("triggered 631 raw, peak reading 631 raw"), t);
		assertTrue(t.contains("recovered 15 Nov 2026 15:51 after 59 s, reading at recovery 419 raw"), t);
		assertFalse(t.contains("863"), "only the newest one is listed: " + t);
	}

	@Test
	void aPeriodCountsOnlyIncidentsThatStartedInIt() throws Exception {
		Paired p = pairNamed(newUserToken(), "Gas Loft");
		Instant now = Instant.now();
		incident(p, p.userId(), "Gas Loft", now.minus(1, ChronoUnit.HOURS), now.minus(50, ChronoUnit.MINUTES), 650, 700, 400);
		incident(p, p.userId(), "Gas Loft", now.minus(3, ChronoUnit.DAYS), now.minus(3, ChronoUnit.DAYS).plusSeconds(60), 700, 800, 400);

		ToolResult r = registry.call(ctx(p, "Gas Loft"), "get_gas_incidents", "{\"period\":\"last_24h\"}");
		assertTrue(r.output().text().contains(": 1 in total."), r.output().text());
	}

	@Test
	void noIncidentMeansNoInventedTime() throws Exception {
		Paired p = pairNamed(newUserToken(), "Quiet Den");
		ToolResult r = registry.call(ctx(p, "Quiet Den"), "get_gas_incidents", "{}");
		assertTrue(r.ok(), r.error());
		assertTrue(r.output().text().contains("none. No gas incident is recorded, so there is no incident time"), r.output().text());
		assertTrue(r.output().visuals().isEmpty());
	}

	@Test
	void anotherUsersIncidentsAndCommandsOnTheSameDeviceStayHidden() throws Exception {
		Paired p = pairNamed(newUserToken(), "Shared Hall");
		Paired other = pairNamed(newUserToken(), "Other Hall");
		Instant t = Instant.parse("2026-11-11T10:16:53Z");
		// rows written while the device belonged to someone else
		incident(p, other.userId(), "Shared Hall", t, t.plusSeconds(90), 919, 919, 490);
		command(p, other.userId(), "set_gas_led", "on", t);

		String inc = registry.call(ctx(p, "Shared Hall"), "get_gas_incidents", "{}").output().text();
		assertTrue(inc.contains("none"), inc);
		assertFalse(inc.contains("919"), inc);
		String cmd = registry.call(ctx(p, "Shared Hall"), "get_command_history", "{\"action\":\"set_gas_led\"}").output().text();
		assertTrue(cmd.contains("none recorded"), cmd);
	}

	@Test
	void ledCommandHistoryListsTheLastOnCommandAndSaysAutomaticChangesAreNotStored() throws Exception {
		Paired p = pairNamed(newUserToken(), "Led Lab");
		Instant t = Instant.parse("2026-11-13T10:12:06Z");
		command(p, p.userId(), "set_gas_led", "on", t);
		command(p, p.userId(), "set_gas_led", "auto", t.plusSeconds(2));
		command(p, p.userId(), "set_pir_led", "on", t.plusSeconds(8));

		ToolResult r = registry.call(ctx(p, "Led Lab"), "get_command_history", "{\"action\":\"set_gas_led\",\"value\":\"on\"}");
		assertTrue(r.ok(), r.error());
		String text = r.output().text();
		assertTrue(text.contains("Most recent: set_gas_led = on to Led Lab, requested 13 Nov 2026 15:42, status ACKNOWLEDGED"), text);
		assertFalse(text.contains("set_pir_led"), text);
		assertFalse(text.contains("= auto"), text);
		assertTrue(text.contains("not recorded, so they cannot be dated"), text);
	}

	@Test
	void withoutAModelAGasIncidentQuestionIsStillAnsweredFromTheAlertTable() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Gas Galley Two");
		Instant base = Instant.now().minus(2, ChronoUnit.HOURS);
		incident(p, p.userId(), "Gas Galley Two", base, base.plusSeconds(120), 640, 655, 410);
		llm.setConfigured(false);

		JsonNode r = ask(token, "When was the last gas incident?");

		String text = answerText(r);
		assertTrue(text.contains("Gas incidents"), text);
		assertTrue(text.contains("peak reading 655 raw"), text);
		assertFalse(text.contains("Security events"), "an incident question is not answered with the security log: " + text);
		JsonNode tools = r.get("assistantMessage").get("extras").get("tools");
		assertEquals("get_gas_incidents", tools.get(0).get("tool").asString());
	}
}
