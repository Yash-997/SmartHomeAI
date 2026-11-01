package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;

/** The authenticated status view: every part is derived from real state, and a part that cannot be read is reported instead of hidden. */
class SystemStatusTests extends IntelTestBase {

	@Autowired ScriptedLlm llm;
	@Autowired ToggleRagChunkStore ragStore;

	@AfterEach
	void resetAssistantFakes() {
		llm.reset();
		ragStore.setDown(false);
	}

	private JsonNode view(String token) throws Exception {
		return json(getAs(token, "/api/system/status").andExpect(status().isOk()));
	}

	private static boolean hasIssue(JsonNode s, String area, String containing) {
		for (JsonNode i : s.get("issues")) {
			if (area.equals(i.get("area").asString()) && i.get("message").asString().contains(containing)) {
				return true;
			}
		}
		return false;
	}

	private void fullReading(Paired p, int seq) {
		sendTelemetry(p, "{\"seq\":" + seq + ",\"temperature\":22.5,\"humidity\":48.0,\"motion\":false,\"gasRaw\":1500}");
	}

	@Test
	void theStatusViewNeedsALoginAndTheOldPublicHealthCheckStillWorks() throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/system/status")).andExpect(status().isUnauthorized());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/health")).andExpect(status().isOk());
	}

	@Test
	void aHealthySetupReportsOkWithEveryPartFilledIn() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Healthy");
		fullReading(p, 810_001);

		JsonNode s = view(t);
		assertEquals("OK", s.get("overall").asString(), s.get("issues").toString());
		assertEquals("OK", s.get("database").get("level").asString());
		assertEquals("OK", s.get("mqtt").get("level").asString());
		assertEquals("ONLINE", s.get("devices").get(0).get("connection").asString());
		JsonNode sensors = s.get("sensors").get(0).get("sensors");
		for (String name : new String[] { "temperature", "humidity", "gas", "motion" }) {
			assertEquals("FRESH", sensors.get(name).get("state").asString(), name);
		}
		assertTrue(s.get("commands").get("failureRate").isNull(), "no commands: no rate, not 0%");
		assertEquals(0, s.get("commands").get("total").asInt());
		assertEquals("NOT_TRAINED", s.get("ml").get(0).get("occupancyState").asString());
		assertTrue(s.get("assistant").get("modelConfigured").asBoolean());
		assertEquals("OK", s.get("assistant").get("level").asString());
		assertTrue(s.get("assistant").get("detail").asString().contains("not called"), "the model is not pinged, and that is stated");
	}

	@Test
	void aUserWithNoDevicesGetsEmptyListsNotErrors() throws Exception {
		JsonNode s = view(newUserToken());
		assertEquals(0, s.get("devices").size());
		assertEquals(0, s.get("sensors").size());
		assertEquals(0, s.get("ml").size());
		assertEquals("OK", s.get("overall").asString());
	}

	@Test
	void aBrokerThatIsDownMakesTheWholeSystemDown() throws Exception {
		String t = newUserToken();
		onlineNamed(t, "Link test");
		publisher.setDown(true);

		JsonNode s = view(t);
		assertEquals("DOWN", s.get("overall").asString());
		assertEquals("DOWN", s.get("mqtt").get("level").asString());
		assertTrue(hasIssue(s, "mqtt", "not connected to the message broker"));
	}

	@Test
	void anOfflineDeviceIsADegradation() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Offline one");
		markOffline(p);

		JsonNode s = view(t);
		assertEquals("DEGRADED", s.get("overall").asString());
		assertEquals("OFFLINE", s.get("devices").get(0).get("connection").asString());
		assertTrue(hasIssue(s, "devices", "\"Offline one\" is offline"));
		assertFalse(hasIssue(s, "sensors", "Offline one"), "an offline device is not also blamed for silent sensors");
	}

	@Test
	void aSensorThatNeverReportedOnAnOnlineDeviceIsFlagged() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Broken DHT");
		sendTelemetry(p, "{\"seq\":810002,\"motion\":true}");       // only the PIR ever speaks

		JsonNode s = view(t);
		JsonNode sensors = s.get("sensors").get(0).get("sensors");
		assertEquals("NEVER", sensors.get("temperature").get("state").asString());
		assertTrue(sensors.get("temperature").get("ageSeconds").isNull());
		assertEquals("FRESH", sensors.get("motion").get("state").asString());
		assertTrue(hasIssue(s, "sensors", "never reported a temperature"));
		assertTrue(hasIssue(s, "sensors", "never reported a gas"));
	}

	@Test
	void aSensorThatWentSilentWhileTheDeviceStaysOnlineIsStale() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Went quiet");
		fullReading(p, 810_003);
		jdbc.update("update telemetry_readings set received_at = ? where device_uuid = ? and temperature_c is not null",
				java.sql.Timestamp.from(Instant.now().minusSeconds(900)), p.uuid());
		sendTelemetry(p, "{\"seq\":810004,\"motion\":false}");                                  // the device is still alive, but sends only motion

		JsonNode s = view(t);
		JsonNode sensors = s.get("sensors").get(0).get("sensors");
		assertEquals("STALE", sensors.get("temperature").get("state").asString());
		assertTrue(sensors.get("temperature").get("ageSeconds").asLong() >= 850);
		assertEquals("FRESH", sensors.get("motion").get("state").asString());
		assertTrue(hasIssue(s, "sensors", "no recent temperature"));
		assertEquals("DEGRADED", s.get("overall").asString());
	}

	@Test
	void commandFailuresAreCountedWithTheirReasonAndRate() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Cmds");
		ack(p, sendCommand(p, "set_pir_led", "on"), "applied");
		ack(p, sendCommand(p, "set_pir_led", "off"), "rejected");

		JsonNode c = view(t).get("commands");
		assertEquals(2, c.get("total").asInt());
		assertEquals(1, c.get("byStatus").get("FAILED").asInt());
		assertEquals(1, c.get("byStatus").get("ACKNOWLEDGED").asInt());
		assertEquals(0.5, c.get("failureRate").asDouble(), 0.0001);
		assertEquals("FAILED", c.get("recentProblems").get(0).get("status").asString());
		assertEquals("set_pir_led", c.get("recentProblems").get(0).get("action").asString());
		assertTrue(hasIssue(view(t), "commands", "1 of 2 command(s)"));
	}

	@Test
	void mostlyFailingCommandsAreDownNotJustDegraded() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Mostly failing");
		ack(p, sendCommand(p, "set_pir_led", "on"), "rejected");
		JsonNode s = view(t);
		assertEquals("DOWN", s.get("overall").asString());
		assertEquals(1.0, s.get("commands").get("failureRate").asDouble(), 0.0001);
	}

	@Test
	void mlReadinessShowsTheRealModelState() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Learner");
		seedMotionHistory(p, Instant.now(), 21, 7);
		occupancy.trainManually(p.userId(), p.uuid(), "UTC");

		JsonNode ml = view(t).get("ml").get(0);
		assertEquals("READY", ml.get("occupancyState").asString());
		assertTrue(ml.get("predictionsAvailable").asBoolean());
		assertEquals(0, ml.get("activeAnomalies").asInt());
	}

	@Test
	void anAssistantWithoutAModelIsReportedAsReferencesOnly() throws Exception {
		String t = newUserToken();
		llm.setConfigured(false);
		JsonNode s = view(t);
		assertEquals("DEGRADED", s.get("assistant").get("level").asString());
		assertFalse(s.get("assistant").get("modelConfigured").asBoolean());
		assertTrue(s.get("assistant").get("indexReadable").asBoolean());
		assertTrue(hasIssue(s, "assistant", "references only"));
	}

	@Test
	void anUnreadableAssistantIndexIsDownForTheAssistantButTheRestOfTheViewStillWorks() throws Exception {
		String t = newUserToken();
		onlineNamed(t, "Still shown");
		ragStore.setDown(true);

		JsonNode s = view(t);
		assertEquals("DOWN", s.get("assistant").get("level").asString());
		assertFalse(s.get("assistant").get("indexReadable").asBoolean());
		assertEquals(1, s.get("devices").size(), "the failing part does not take the others with it");
		assertFalse(s.toString().contains("secret-host"), "no internal detail from the failure");
	}

	@Test
	void theViewOnlyDescribesTheCallersOwnThings() throws Exception {
		String ta = newUserToken();
		String tb = newUserToken();
		Paired a = onlineNamed(ta, "Alice only");
		Paired b = onlineNamed(tb, "Bob only");
		ack(b, sendCommand(b, "set_pir_led", "on"), "rejected");

		JsonNode sa = view(ta);
		assertFalse(sa.toString().contains("Bob only"));
		assertFalse(sa.toString().contains(b.uuid().toString()));
		assertEquals(0, sa.get("commands").get("total").asInt(), "B's failed command is not in A's numbers");
		assertEquals(1, sa.get("devices").size());
		assertEquals(UUID.fromString(sa.get("devices").get(0).get("deviceId").asString()), a.uuid());
		assertEquals(1, view(tb).get("commands").get("total").asInt());
	}
}

