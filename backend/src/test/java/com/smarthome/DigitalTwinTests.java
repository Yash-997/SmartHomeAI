package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.smarthome.command.CommandDtos.CreateCommandRequest;

import tools.jackson.databind.JsonNode;

/** The digital twin is computed from the real device, command and state records; these tests create those records through the real code paths. */
class DigitalTwinTests extends IntelTestBase {

	private JsonNode twin(String token, Paired p) throws Exception {
		return json(getAs(token, "/api/devices/" + p.uuid() + "/twin").andExpect(status().isOk()));
	}

	private static JsonNode output(JsonNode twin, String action) {
		for (JsonNode o : twin.get("outputs")) {
			if (action.equals(o.get("action").asString())) {
				return o;
			}
		}
		throw new AssertionError("no output " + action);
	}

	@Test
	void twinEndpointsNeedALoginAndRespectOwnership() throws Exception {
		String ta = newUserToken();
		String tb = newUserToken();
		Paired a = onlineNamed(ta, "Twin A");
		onlineNamed(tb, "Twin B");

		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/twins")).andExpect(status().isUnauthorized());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/devices/" + a.uuid() + "/twin")).andExpect(status().isUnauthorized());
		getAs(tb, "/api/devices/" + a.uuid() + "/twin").andExpect(status().isNotFound());
		getAs(tb, "/api/devices/" + UUID.randomUUID() + "/twin").andExpect(status().isNotFound());

		JsonNode mine = json(getAs(ta, "/api/twins").andExpect(status().isOk()));
		assertEquals(1, mine.size());
		assertEquals("Twin A", mine.get(0).get("name").asString());
		assertFalse(json(getAs(tb, "/api/twins")).toString().contains("Twin A"));
	}

	@Test
	void aDeviceWithNoConfirmedCommandHasNothingToCompareAndSaysSo() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Fresh");
		JsonNode tw = twin(t, p);
		assertEquals("ONLINE", tw.get("connection").asString());
		assertEquals(0, tw.get("pendingCommands").size());
		assertEquals(0, tw.get("problemCommands24h").asInt());
		assertFalse(tw.get("readingStale").asBoolean());
		assertTrue(tw.get("gasWarningActive").isNull(), "no state report yet: unknown, not false");
		for (JsonNode o : tw.get("outputs")) {
			assertEquals("NO_DESIRED", o.get("sync").asString());
			assertTrue(o.get("desired").isNull(), "nothing is invented as the desired value");
		}
		assertTrue(tw.get("summary").asString().startsWith("Online"));
	}

	@Test
	void aCommandInFlightIsPendingNotInSync() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Pending");
		sendCommand(p, "set_pir_led", "on");

		JsonNode tw = twin(t, p);
		JsonNode pir = output(tw, "set_pir_led");
		assertEquals("PENDING", pir.get("sync").asString());
		assertEquals("on", pir.get("requested").asString());
		assertTrue(pir.get("desired").isNull(), "not confirmed yet, so not 'desired'");
		assertEquals(1, tw.get("pendingCommands").size());
		assertEquals("SENT", tw.get("pendingCommands").get(0).get("status").asString());
		assertEquals("NO_DESIRED", output(tw, "set_gas_led").get("sync").asString(), "the other output is unaffected");
		assertTrue(tw.get("summary").asString().contains("1 command(s) pending"));
	}

	@Test
	void anAcknowledgedCommandThatTheDeviceReportsIsInSync() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Sync");
		UUID id = sendCommand(p, "set_pir_led", "on");
		ack(p, id, "applied");
		reportState(p, "on", true, "auto", false, false);

		JsonNode tw = twin(t, p);
		JsonNode pir = output(tw, "set_pir_led");
		assertEquals("IN_SYNC", pir.get("sync").asString());
		assertEquals("on", pir.get("desired").asString());
		assertEquals("on", pir.get("reportedMode").asString());
		assertTrue(pir.get("reportedLit").asBoolean());
		assertEquals("user", pir.get("desiredBy").asString());
		assertEquals(0, tw.get("pendingCommands").size());
		assertTrue(tw.get("summary").asString().contains("outputs match") || tw.get("summary").asString().contains("cannot be verified"));
	}

	@Test
	void aDeviceReportingSomethingElseIsDrift() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Drift");
		ack(p, sendCommand(p, "set_pir_led", "on"), "applied");
		reportState(p, "auto", false, "auto", false, false);       // e.g. the board restarted and went back to auto

		JsonNode pir = output(twin(t, p), "set_pir_led");
		assertEquals("DRIFT", pir.get("sync").asString());
		assertTrue(pir.get("note").asString().contains("\"auto\"") && pir.get("note").asString().contains("\"on\""), pir.get("note").asString());
	}

	@Test
	void withoutAStateReportTheTwinSaysItCannotVerify() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Silent state");
		ack(p, sendCommand(p, "set_pir_led", "off"), "applied");

		JsonNode pir = output(twin(t, p), "set_pir_led");
		assertEquals("UNREPORTED", pir.get("sync").asString());
		assertTrue(pir.get("reportedMode").isNull());
		assertEquals("off", pir.get("desired").asString());
	}

	@Test
	void aRejectedCommandIsCountedAsAProblemAndNeverBecomesTheDesiredState() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Rejects");
		ack(p, sendCommand(p, "set_pir_led", "on"), "rejected");

		JsonNode tw = twin(t, p);
		assertEquals(1, tw.get("problemCommands24h").asInt());
		assertEquals("NO_DESIRED", output(tw, "set_pir_led").get("sync").asString());
		assertTrue(tw.get("summary").asString().contains("1 command problem(s)"));
	}

	@Test
	void theTwinShowsWhoAskedForTheConfirmedState() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Origins");
		var cmd = commandService.createFromAutomation(p.userId(), p.uuid(), new CreateCommandRequest("set_pir_led", "on"), "automation:Night light");
		ack(p, cmd.id(), "applied");
		reportState(p, "on", true, "auto", false, false);

		assertEquals("automation:Night light", output(twin(t, p), "set_pir_led").get("desiredBy").asString());
	}

	@Test
	void theGasWarningIsShownAsReportedAndNotPresentedAsSomethingTheBackendControls() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Gas view");
		reportState(p, "auto", false, "auto", true, true);

		JsonNode tw = twin(t, p);
		assertTrue(tw.get("gasWarningActive").asBoolean());
		assertTrue(output(tw, "set_gas_led").get("note").asString().contains("raised by the device"));
		assertEquals(2, tw.get("outputs").size());
	}

	@Test
	void anOfflineDeviceIsShownOfflineWithItsAge() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Went dark");
		markOffline(p);
		JsonNode tw = twin(t, p);
		assertEquals("OFFLINE", tw.get("connection").asString());
		assertTrue(tw.get("lastSeenAgeSeconds").asLong() >= 3000);
		assertTrue(tw.get("summary").asString().startsWith("Offline"));
	}

	@Test
	void aDeviceThatNeverConnectedHasNoLastSeenAndNoReading() throws Exception {
		String t = newUserToken();
		Paired p = pairNamed(t, "Never");
		JsonNode tw = twin(t, p);
		assertEquals("NEVER_CONNECTED", tw.get("connection").asString());
		assertTrue(tw.get("lastSeenAt").isNull());
		assertTrue(tw.get("lastReadingAt").isNull());
		assertTrue(tw.get("gasWarningActive").isNull(), "unknown is not 'false'");
	}
}
