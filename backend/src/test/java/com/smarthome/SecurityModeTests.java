package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.smarthome.RecordingCommandPublisher.Published;

import tools.jackson.databind.JsonNode;

/** Away / Night modes: alerts, history, the optional PIR LED action, input validation, ownership. No hardware: telemetry goes through the real ingest code. */
class SecurityModeTests extends IntelTestBase {

	private static final String ARMED_WITH_LED = "{\"away\":{\"motionAlerts\":true,\"cooldownSeconds\":10,\"lightOnMotion\":true}}";

	// ---- access and defaults -------------------------------------------------------------------

	@Test
	void everySecurityEndpointNeedsALogin() throws Exception {
		for (String path : new String[] { "/api/security/settings", "/api/security/events" }) {
			mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andExpect(status().isUnauthorized());
		}
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/security/mode").contentType("application/json")
				.content("{\"mode\":\"AWAY\"}")).andExpect(status().isUnauthorized());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/security/events/acknowledge-all")).andExpect(status().isUnauthorized());
	}

	@Test
	void aNewUserIsAtHomeWithSensibleDefaultsAndTheGasNotice() throws Exception {
		JsonNode s = json(getAs(newUserToken(), "/api/security/settings").andExpect(status().isOk()));
		assertEquals("HOME", s.get("mode").asString());
		assertTrue(s.get("away").get("motionAlerts").asBoolean());
		assertEquals(60, s.get("away").get("cooldownSeconds").asInt());
		assertFalse(s.get("away").get("lightOnMotion").asBoolean(), "no device action unless chosen");
		assertFalse(s.get("night").get("lightOnMotion").asBoolean());
		assertEquals(0, s.get("unacknowledgedAlerts").asInt());
		assertTrue(s.get("notice").asString().contains("never change the gas warning"));
	}

	// ---- modes and settings --------------------------------------------------------------------

	@Test
	void changingModeIsRecordedOnceAndAnUnchangedModeAddsNothing() throws Exception {
		String t = newUserToken();
		JsonNode s = json(putAs(t, "/api/security/mode", "{\"mode\":\"away\"}").andExpect(status().isOk()));
		assertEquals("AWAY", s.get("mode").asString());
		setMode(t, "AWAY");                                   // same again
		setMode(t, "NIGHT");
		assertEquals(2, countKind(t, "MODE_CHANGED"));
		assertTrue(events(t).get(0).get("message").asString().contains("from Away to Night"));
	}

	@Test
	void anUnknownModeOrMissingModeIsRejected() throws Exception {
		String t = newUserToken();
		JsonNode e = json(putAs(t, "/api/security/mode", "{\"mode\":\"PARTY\"}").andExpect(status().isBadRequest()));
		assertEquals("Must be HOME, AWAY or NIGHT", e.get("fieldErrors").get("mode").asString());
		putAs(t, "/api/security/mode", "{}").andExpect(status().isBadRequest());
		putAs(t, "/api/security/mode", "{\"mode\":\"\"}").andExpect(status().isBadRequest());
		assertEquals("HOME", json(getAs(t, "/api/security/settings")).get("mode").asString());
	}

	@Test
	void configurationIsValidatedAndPartialUpdatesKeepTheRest() throws Exception {
		String t = newUserToken();
		putAs(t, "/api/security/config", "{\"away\":{\"motionAlerts\":true,\"cooldownSeconds\":5,\"lightOnMotion\":false}}").andExpect(status().isBadRequest());
		putAs(t, "/api/security/config", "{\"away\":{\"motionAlerts\":true,\"cooldownSeconds\":99999,\"lightOnMotion\":false}}").andExpect(status().isBadRequest());
		putAs(t, "/api/security/config", "{\"away\":{\"motionAlerts\":true,\"lightOnMotion\":false}}").andExpect(status().isBadRequest());
		JsonNode e = json(putAs(t, "/api/security/config", "{\"night\":{\"motionAlerts\":false,\"cooldownSeconds\":30,\"lightOnMotion\":true}}").andExpect(status().isBadRequest()));
		assertTrue(e.get("fieldErrors").get("night").asString().contains("needs motion alerts"));

		JsonNode ok = json(putAs(t, "/api/security/config", "{\"night\":{\"motionAlerts\":true,\"cooldownSeconds\":120,\"lightOnMotion\":false}}").andExpect(status().isOk()));
		assertEquals(120, ok.get("night").get("cooldownSeconds").asInt());
		assertEquals(60, ok.get("away").get("cooldownSeconds").asInt(), "the omitted part is unchanged");
		assertEquals(1, countKind(t, "SETTINGS_CHANGED"));
		putAs(t, "/api/security/config", "{}").andExpect(status().isOk());
		assertEquals(1, countKind(t, "SETTINGS_CHANGED"), "an empty update changes nothing and logs nothing");
	}

	// ---- motion alerts -------------------------------------------------------------------------

	@Test
	void motionWhileAwayIsRecordedAndMotionAtHomeIsNot() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Front door");

		motion(p);
		assertEquals(0, countKind(t, "MOTION_ALERT"), "HOME mode watches nothing");

		setMode(t, "AWAY");
		stillness(p);
		assertEquals(0, countKind(t, "MOTION_ALERT"), "no motion, no alert");
		motion(p);
		JsonNode alert = events(t).get(0);
		assertEquals("MOTION_ALERT", alert.get("kind").asString());
		assertEquals("WARNING", alert.get("severity").asString());
		assertEquals("AWAY", alert.get("mode").asString());
		assertEquals(p.uuid().toString(), alert.get("deviceId").asString());
		assertTrue(alert.get("message").asString().contains("Front door"));
		assertEquals(1, json(getAs(t, "/api/security/settings")).get("unacknowledgedAlerts").asInt());
	}

	@Test
	void nightModeUsesItsOwnSettings() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Hall");
		putAs(t, "/api/security/config", "{\"night\":{\"motionAlerts\":false,\"cooldownSeconds\":60,\"lightOnMotion\":false}}").andExpect(status().isOk());
		setMode(t, "NIGHT");
		motion(p);
		assertEquals(0, countKind(t, "MOTION_ALERT"), "alerts are switched off for Night");
		putAs(t, "/api/security/config", "{\"night\":{\"motionAlerts\":true,\"cooldownSeconds\":60,\"lightOnMotion\":false}}").andExpect(status().isOk());
		motion(p);
		assertEquals(1, countKind(t, "MOTION_ALERT"));
		assertEquals("NIGHT", events(t).get(0).get("mode").asString());
	}

	@Test
	void repeatedMotionIsRateLimitedPerDevice() throws Exception {
		String t = newUserToken();
		Paired a = onlineNamed(t, "Garage");
		Paired b = onlineNamed(t, "Shed");
		setMode(t, "AWAY");

		motion(a);
		motion(a);
		motion(a);
		assertEquals(1, countKind(t, "MOTION_ALERT"), "one alert per device per cooldown");
		motion(b);
		assertEquals(2, countKind(t, "MOTION_ALERT"), "another device alerts on its own");

		// time passes: age the first alert beyond the cooldown (60 s default)
		jdbc.update("update security_events set created_at = ? where device_uuid = ? and kind = 'MOTION_ALERT'",
				java.sql.Timestamp.from(Instant.now().minusSeconds(120)), a.uuid());
		motion(a);
		assertEquals(3, countKind(t, "MOTION_ALERT"));
	}

	@Test
	void replayedOldTelemetryIsNotAnAlert() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Cellar");
		setMode(t, "AWAY");
		security.onTelemetry(motionEventAt(p, Instant.now().minusSeconds(600)));
		assertEquals(0, countKind(t, "MOTION_ALERT"));
		security.onTelemetry(motionEventAt(p, Instant.now()));
		assertEquals(1, countKind(t, "MOTION_ALERT"));
	}

	// ---- ownership -----------------------------------------------------------------------------

	@Test
	void usersNeverSeeOrChangeEachOthersSecurity() throws Exception {
		String ta = newUserToken();
		String tb = newUserToken();
		Paired a = onlineNamed(ta, "Alice porch");
		Paired b = onlineNamed(tb, "Bob porch");
		setMode(ta, "AWAY");                                  // only A is armed

		motion(b);
		assertEquals(0, countKind(ta, "MOTION_ALERT"));
		assertEquals(0, countKind(tb, "MOTION_ALERT"), "B is at home");
		motion(a);
		assertEquals(1, countKind(ta, "MOTION_ALERT"));
		assertEquals(0, events(tb).size(), "B sees nothing of A's history");
		assertEquals("HOME", json(getAs(tb, "/api/security/settings")).get("mode").asString());

		String alertId = events(ta).get(0).get("id").asString();
		postAs(tb, "/api/security/events/" + alertId + "/acknowledge", null).andExpect(status().isNotFound());
		assertNull(events(ta).get(0).get("acknowledgedAt").asString(null), "B could not acknowledge it");
		assertEquals(0, securityEvents.countByDeviceUuidAndKindAndCreatedAtAfter(b.uuid(), com.smarthome.security.SecurityEvent.Kind.MOTION_ALERT, Instant.EPOCH));
	}

	@Test
	void alertsCanBeAcknowledgedOneByOneOrAllAtOnce() throws Exception {
		String t = newUserToken();
		Paired a = onlineNamed(t, "North");
		Paired b = onlineNamed(t, "South");
		setMode(t, "AWAY");
		motion(a);
		motion(b);
		assertEquals(2, json(getAs(t, "/api/security/settings")).get("unacknowledgedAlerts").asInt());

		String first = events(t).get(0).get("id").asString();
		JsonNode acked = json(postAs(t, "/api/security/events/" + first + "/acknowledge", null).andExpect(status().isOk()));
		assertNotNull(acked.get("acknowledgedAt").asString(null));
		assertEquals(1, json(getAs(t, "/api/security/settings")).get("unacknowledgedAlerts").asInt());

		assertEquals(1, json(postAs(t, "/api/security/events/acknowledge-all", null).andExpect(status().isOk())).get("acknowledged").asInt());
		assertEquals(0, json(getAs(t, "/api/security/settings")).get("unacknowledgedAlerts").asInt());
		postAs(t, "/api/security/events/" + UUID.randomUUID() + "/acknowledge", null).andExpect(status().isNotFound());
	}

	@Test
	void theEventListCanBeFilteredLimitedAndRejectsUnknownKinds() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Filter");
		setMode(t, "AWAY");
		motion(p);
		setMode(t, "HOME");
		assertEquals(1, json(getAs(t, "/api/security/events?kind=motion_alert")).size());
		assertEquals(2, json(getAs(t, "/api/security/events?kind=MODE_CHANGED")).size());
		assertEquals(1, json(getAs(t, "/api/security/events?limit=1")).size());
		getAs(t, "/api/security/events?kind=NOPE").andExpect(status().isBadRequest());
	}

	@Test
	void disownedDevicesTakeTheirSecurityHistoryWithThem() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Gone soon");
		setMode(t, "AWAY");
		motion(p);
		assertEquals(1, countKind(t, "MOTION_ALERT"));
		deleteAs(t, "/api/devices/" + p.uuid()).andExpect(status().isNoContent());
		assertEquals(0, countKind(t, "MOTION_ALERT"));
		assertEquals(1, countKind(t, "MODE_CHANGED"), "events not tied to the device stay");
	}

	// ---- offline while armed -------------------------------------------------------------------

	@Test
	void aDeviceGoingOfflineWhileArmedIsReportedOncePerOutage() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Window");
		setMode(t, "AWAY");
		security.sweepOffline();
		assertEquals(0, countKind(t, "DEVICE_OFFLINE"), "online: nothing to report");

		markOffline(p);
		security.sweepOffline();
		security.sweepOffline();
		assertEquals(1, countKind(t, "DEVICE_OFFLINE"), "the same outage is reported once");

		markOnlineAt(p, Instant.now());
		security.sweepOffline();
		markOffline(p);
		security.sweepOffline();
		assertEquals(2, countKind(t, "DEVICE_OFFLINE"), "a second outage is a new event");
	}

	@Test
	void nothingIsReportedAtHomeOrForADeviceThatNeverConnected() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Quiet");
		markOffline(p);
		security.sweepOffline();
		assertEquals(0, countKind(t, "DEVICE_OFFLINE"), "HOME mode");
		setMode(t, "AWAY");
		Paired never = pairNamed(t, "Never connected");
		security.sweepOffline();
		assertEquals(1, countKind(t, "DEVICE_OFFLINE"), "only the device that was seen and then went silent");
		assertTrue(events(t).get(0).get("message").asString().contains("Quiet"));
		assertFalse(events(t).toString().contains(never.uuid().toString()));
	}

	// ---- the PIR LED action and the gas guarantee ----------------------------------------------

	@Test
	void lightOnMotionSetsThePirLedThroughTheNormalCommandPathAndGivesItBackLater() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Hall light");
		putAs(t, "/api/security/config", ARMED_WITH_LED).andExpect(status().isOk());
		setMode(t, "AWAY");
		int before = publisher.publishedTo(p.deviceId()).size();

		motion(p);

		assertEquals(before + 1, publisher.publishedTo(p.deviceId()).size());
		Published sent = publisher.publishedTo(p.deviceId()).get(before);
		assertTrue(sent.json().contains("\"set_pir_led\"") && sent.json().contains("\"on\""), sent.json());
		var cmd = commands.findByDeviceUuidOrderByCreatedAtDesc(p.uuid(), org.springframework.data.domain.PageRequest.of(0, 5)).get(0);
		assertEquals("security:away", cmd.getOrigin(), "recorded as a security action, through CommandService");
		assertEquals(p.userId(), cmd.getRequestedBy());
		assertEquals(1, countKind(t, "ACTION_TAKEN"));
		assertTrue(json(getAs(t, "/api/security/settings")).get("pirLedForcedBySecurity").asBoolean());

		setMode(t, "HOME");
		Published restore = publisher.publishedTo(p.deviceId()).get(publisher.publishedTo(p.deviceId()).size() - 1);
		assertTrue(restore.json().contains("\"set_pir_led\"") && restore.json().contains("\"auto\""), restore.json());
		assertFalse(json(getAs(t, "/api/security/settings")).get("pirLedForcedBySecurity").asBoolean());
	}

	// ---- one LED command per motion episode (never a stream of renewals) ----------------------

	/** Moves this device's motion history and the user's last mode change back in time (test database only). */
	private void ageHistory(Paired p, int seconds) {
		jdbc.update("update security_events set created_at = DATEADD('SECOND', ?, created_at) where device_uuid = ?", -seconds, p.uuid());
		jdbc.update("update security_settings set mode_changed_at = DATEADD('SECOND', ?, mode_changed_at) where owner_user_id = ?", -seconds,
				p.userId());
	}

	/** The device acknowledges everything still open, as a real one does within milliseconds. Without it, the command
	 *  service's double-send guard hands back the still-open command instead of creating a new one. */
	private void deviceAcksAll(Paired p) {
		commands.findByDeviceUuidOrderByCreatedAtDesc(p.uuid(), org.springframework.data.domain.PageRequest.of(0, 200)).stream()
				.filter(c -> c.getStatus() == com.smarthome.command.CommandStatus.SENT || c.getStatus() == com.smarthome.command.CommandStatus.PENDING)
				.forEach(c -> ack(p, c.getId(), "applied"));
	}

	private long securityLedOns(Paired p) {
		return commands.findByDeviceUuidOrderByCreatedAtDesc(p.uuid(), org.springframework.data.domain.PageRequest.of(0, 200)).stream()
				.filter(c -> "set_pir_led".equals(c.getAction()) && "on".equals(c.getValue()) && c.getOrigin() != null
						&& c.getOrigin().startsWith("security:"))
				.count();
	}

	@Test
	void continuousMotionSendsTheLedCommandOnceNotWithEveryAlert() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Busy hall");
		putAs(t, "/api/security/config", ARMED_WITH_LED).andExpect(status().isOk());
		setMode(t, "AWAY");

		motion(p);                                                // the episode starts: one command
		for (int i = 0; i < 10; i++) {                            // 5 more minutes of motion, an alert every 30 s
			deviceAcksAll(p);                                     // acked, so a resend would not be folded into it
			ageHistory(p, 30);
			motion(p);
		}

		assertEquals(11, countKind(t, "MOTION_ALERT"), "monitoring is unchanged: every alert is still recorded");
		assertEquals(1, securityLedOns(p), "but the LED was told 'on' once, so the device's 2-minute timer is never renewed");
		assertEquals(1, countKind(t, "ACTION_TAKEN"));
	}

	@Test
	void aNewMotionEpisodeAfterAQuietSpellGetsOneNewCommand() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Quiet hall");
		putAs(t, "/api/security/config", ARMED_WITH_LED).andExpect(status().isOk());
		setMode(t, "AWAY");
		motion(p);
		deviceAcksAll(p);
		ageHistory(p, 30);
		motion(p);                                                // same episode
		assertEquals(1, securityLedOns(p));

		deviceAcksAll(p);
		ageHistory(p, 200);                                       // quiet for longer than the episode gap (150 s)
		motion(p);                                                // new episode
		deviceAcksAll(p);
		ageHistory(p, 30);
		motion(p);                                                // same (second) episode

		assertEquals(2, securityLedOns(p), "one command per episode");
		assertEquals(4, countKind(t, "MOTION_ALERT"));
	}

	@Test
	void switchingToAnotherArmedModeStartsANewEpisode() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Mode switch");
		putAs(t, "/api/security/config",
				"{\"away\":{\"motionAlerts\":true,\"cooldownSeconds\":10,\"lightOnMotion\":true},\"night\":{\"motionAlerts\":true,\"cooldownSeconds\":10,\"lightOnMotion\":true}}")
				.andExpect(status().isOk());
		setMode(t, "AWAY");
		motion(p);
		deviceAcksAll(p);
		ageHistory(p, 30);
		setMode(t, "NIGHT");                                     // the earlier alert belongs to the Away episode
		motion(p);

		assertEquals(2, securityLedOns(p));
	}

	@Test
	void aPirLedTheOwnerChangedAfterwardsIsNotOverwrittenWhenTheModeEnds() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Mine");
		putAs(t, "/api/security/config", ARMED_WITH_LED).andExpect(status().isOk());
		setMode(t, "AWAY");
		motion(p);
		sendCommand(p, "set_pir_led", "off");                    // the owner then chooses their own setting
		int before = publisher.publishedTo(p.deviceId()).size();

		setMode(t, "HOME");

		assertEquals(before, publisher.publishedTo(p.deviceId()).size(), "no restore command: the owner's choice stands");
	}

	@Test
	void aFailedLedActionStillRecordsTheAlertAndSaysSo() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Flaky link");
		putAs(t, "/api/security/config", ARMED_WITH_LED).andExpect(status().isOk());
		setMode(t, "AWAY");
		publisher.setDown(true);

		motion(p);

		assertEquals(1, countKind(t, "MOTION_ALERT"));
		assertEquals(1, countKind(t, "ACTION_FAILED"));
		assertEquals(0, countKind(t, "ACTION_TAKEN"));
		assertFalse(json(getAs(t, "/api/security/settings")).get("pirLedForcedBySecurity").asBoolean());
		assertFalse(events(t).toString().contains("Exception"));
	}

	@Test
	void securityNeverSendsAGasLedCommandWhateverTheSettings() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Gas safe");
		putAs(t, "/api/security/config",
				"{\"away\":{\"motionAlerts\":true,\"cooldownSeconds\":10,\"lightOnMotion\":true},\"night\":{\"motionAlerts\":true,\"cooldownSeconds\":10,\"lightOnMotion\":true}}")
				.andExpect(status().isOk());
		for (String mode : new String[] { "AWAY", "NIGHT", "HOME", "AWAY", "HOME" }) {
			setMode(t, mode);
			motionWithGas(p, 4000);                                // a very high raw gas value arrives while armed
		}
		security.sweepOffline();

		for (var c : commands.findByDeviceUuidOrderByCreatedAtDesc(p.uuid(), org.springframework.data.domain.PageRequest.of(0, 100))) {
			assertEquals("set_pir_led", c.getAction(), "security only ever touches the PIR LED");
		}
		assertTrue(publisher.publishedTo(p.deviceId()).stream().noneMatch(m -> m.json().contains("set_gas_led")));
	}

	@Test
	void changingModeAloneSendsNothingToDevices() throws Exception {
		String t = newUserToken();
		Paired p = onlineNamed(t, "Silent");
		int before = publisher.publishedTo(p.deviceId()).size();
		setMode(t, "AWAY");
		setMode(t, "NIGHT");
		setMode(t, "HOME");
		assertEquals(before, publisher.publishedTo(p.deviceId()).size());
	}
}
