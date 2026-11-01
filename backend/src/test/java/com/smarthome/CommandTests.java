package com.smarthome;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.smarthome.command.CommandIngestService;
import com.smarthome.command.CommandIngestService.Result;
import com.smarthome.command.CommandService;
import com.smarthome.command.CommandStatus;
import com.smarthome.command.CommandStore;
import com.smarthome.command.DeviceCommand;
import com.smarthome.command.DeviceCommandRepository;
import com.smarthome.command.DeviceReportedState;
import com.smarthome.command.DeviceReportedStateRepository;
import com.smarthome.device.Device;
import com.smarthome.device.DeviceRepository;
import com.smarthome.telemetry.TelemetryIngestService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class CommandTests {

	private static final AtomicInteger SEQ = new AtomicInteger();
	private static final AtomicInteger TSEQ = new AtomicInteger(1);

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper mapper;
	@Autowired DeviceRepository devices;
	@Autowired DeviceCommandRepository commands;
	@Autowired DeviceReportedStateRepository states;
	@Autowired CommandStore store;
	@Autowired CommandService commandService;
	@Autowired CommandIngestService commandIngest;
	@Autowired TelemetryIngestService telemetryIngest;
	@Autowired RecordingCommandPublisher publisher;
	@Autowired RecordingBrokerAccess broker;

	@AfterEach
	void restore() {
		publisher.setDown(false);
		broker.setDown(false);
	}

	// ---- helpers -------------------------------------------------------------------------------

	private record Paired(String token, UUID uuid, String deviceId, UUID userId) {
		String acksTopic() { return "home/devices/" + deviceId + "/acks"; }
		String stateTopic() { return "home/devices/" + deviceId + "/state"; }
	}

	private String newUserToken() throws Exception {
		int n = SEQ.incrementAndGet();
		String res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"cmduser" + n + "\",\"email\":\"cmduser" + n + "@example.com\",\"password\":\"password123\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return mapper.readTree(res).get("token").asString();
	}

	/** A paired device that is genuinely ONLINE (it has sent a live telemetry message). */
	private Paired onlineDevice(String token) throws Exception {
		Paired p = pair(token);
		goOnline(p);
		return p;
	}

	private Paired pair(String token) throws Exception {
		String hw = "esp32-" + UUID.randomUUID().toString().substring(0, 12);
		String code = mapper.readTree(mvc.perform(post("/api/devices/pairing-codes").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Cmd test\"}"))
				.andReturn().getResponse().getContentAsString()).get("code").asString();
		mvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON)
				.content("{\"pairingCode\":\"" + code + "\",\"deviceId\":\"" + hw + "\"}")).andExpect(status().isOk());
		Device d = devices.findByDeviceId(hw).orElseThrow();
		return new Paired(token, d.getId(), hw, d.getOwnerUserId());
	}

	private void goOnline(Paired p) {
		telemetryIngest.handle("home/devices/" + p.deviceId() + "/telemetry",
				("{\"seq\":" + TSEQ.getAndIncrement() + ",\"motion\":false}").getBytes(StandardCharsets.UTF_8), false);
	}

	private ResultActions send(Paired p, String body) throws Exception {
		return mvc.perform(post("/api/devices/" + p.uuid() + "/commands").header("Authorization", "Bearer " + p.token())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions send(Paired p, String action, String value) throws Exception {
		return send(p, "{\"action\":\"" + action + "\",\"value\":\"" + value + "\"}");
	}

	private UUID sendOk(Paired p, String action, String value) throws Exception {
		String res = send(p, action, value).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(mapper.readTree(res).get("id").asString());
	}

	private Result ack(Paired p, UUID commandId, String status, String detail) {
		String json = "{\"id\":\"" + commandId + "\",\"status\":\"" + status + "\""
				+ (detail == null ? "" : ",\"detail\":" + mapper.writeValueAsString(detail)) + "}";
		return commandIngest.handle(p.acksTopic(), json.getBytes(StandardCharsets.UTF_8), false);
	}

	private Result report(Paired p, String json) {
		return commandIngest.handle(p.stateTopic(), json.getBytes(StandardCharsets.UTF_8), false);
	}

	private static String stateJson(String pirMode, boolean pirOn, String gasMode, boolean gasOn, boolean warning, UUID last) {
		return "{\"pirLed\":{\"mode\":\"" + pirMode + "\",\"on\":" + pirOn + "},\"gasLed\":{\"mode\":\"" + gasMode
				+ "\",\"on\":" + gasOn + "},\"gasWarning\":" + warning + (last == null ? "" : ",\"lastCommandId\":\"" + last + "\"") + "}";
	}

	private DeviceCommand row(UUID id) {
		return commands.findById(id).orElseThrow();
	}

	private CommandStatus statusOf(UUID id) {
		return row(id).getStatus();
	}

	/** Moves a command's expiry (and optionally status) around in time, as if time had passed. */
	private void setExpiry(UUID id, Instant expiresAt) {
		DeviceCommand c = row(id);
		ReflectionTestUtils.setField(c, "expiresAt", expiresAt);
		commands.saveAndFlush(c);
	}

	private UUID insertCommand(Paired p, String action, String value, CommandStatus status, Instant expiresAt) {
		DeviceCommand c = store.createPending(p.uuid(), p.userId(), action, value, Instant.now(), expiresAt);
		ReflectionTestUtils.setField(c, "status", status);
		commands.saveAndFlush(c);
		return c.getId();
	}

	// ---- creating and delivering ---------------------------------------------------------------

	@Test
	void ownerCommandIsStoredAndPublishedWithTheExactWireFormat() throws Exception {
		Paired p = onlineDevice(newUserToken());
		long before = Instant.now().getEpochSecond();

		String res = send(p, "set_pir_led", "on").andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("SENT"))
				.andExpect(jsonPath("$.action").value("set_pir_led"))
				.andExpect(jsonPath("$.value").value("on"))
				.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(mapper.readTree(res).get("id").asString());

		var sent = publisher.publishedTo(p.deviceId());
		assertEquals(1, sent.size());
		JsonNode wire = mapper.readTree(sent.get(0).json());
		// exactly these four fields, nothing about owners or authorization
		assertEquals(Set.of("id", "action", "value", "expiresAt"), Set.copyOf(wire.propertyNames()));
		assertEquals(id.toString(), wire.get("id").asString());
		assertEquals("set_pir_led", wire.get("action").asString());
		assertEquals("on", wire.get("value").asString());
		long expiresAt = wire.get("expiresAt").asLong();
		assertTrue(expiresAt > before && expiresAt <= before + 35, "expires about 30 s from now, was +" + (expiresAt - before));

		DeviceCommand stored = row(id);
		assertEquals(CommandStatus.SENT, stored.getStatus());
		assertEquals(p.userId(), stored.getRequestedBy());
		assertNotNull(stored.getSentAt());
	}

	@Test
	void ownershipComesFromTheTokenNotFromAnythingInTheRequest() throws Exception {
		Paired p = onlineDevice(newUserToken());
		// claims in the body are ignored; the real owner is unaffected by them
		send(p, "{\"action\":\"set_pir_led\",\"value\":\"on\",\"ownerUserId\":\"" + UUID.randomUUID()
				+ "\",\"deviceId\":\"someone-else\",\"authorized\":true,\"admin\":true}").andExpect(status().isAccepted());
		assertEquals(p.userId(), commands.findByDeviceUuidOrderByCreatedAtDesc(p.uuid(), org.springframework.data.domain.PageRequest.of(0, 1)).get(0).getRequestedBy());

		// the same claims do not help another user
		String mallory = newUserToken();
		mvc.perform(post("/api/devices/" + p.uuid() + "/commands").header("Authorization", "Bearer " + mallory)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"action\":\"set_pir_led\",\"value\":\"off\",\"ownerUserId\":\"" + p.userId() + "\",\"authorized\":true}"))
				.andExpect(status().isNotFound());
		assertEquals(1, publisher.publishedTo(p.deviceId()).size());
	}

	@Test
	void anotherUserCannotSendReadOrListCommands() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID id = sendOk(p, "set_pir_led", "on");
		String mallory = newUserToken();
		String auth = "Bearer " + mallory;
		mvc.perform(post("/api/devices/" + p.uuid() + "/commands").header("Authorization", auth)
				.contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"set_pir_led\",\"value\":\"off\"}"))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/devices/" + p.uuid() + "/commands").header("Authorization", auth)).andExpect(status().isNotFound());
		mvc.perform(get("/api/devices/" + p.uuid() + "/commands/" + id).header("Authorization", auth)).andExpect(status().isNotFound());
		assertEquals(1, publisher.publishedTo(p.deviceId()).size(), "nothing extra was published");
		assertEquals(1, commands.findByDeviceUuidOrderByCreatedAtDesc(p.uuid(), org.springframework.data.domain.PageRequest.of(0, 10)).size());
	}

	@Test
	void unauthenticatedAndDeviceCredentialRequestsAreRejected() throws Exception {
		Paired p = onlineDevice(newUserToken());
		mvc.perform(post("/api/devices/" + p.uuid() + "/commands").contentType(MediaType.APPLICATION_JSON)
				.content("{\"action\":\"set_pir_led\",\"value\":\"on\"}")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/devices/" + p.uuid() + "/commands")).andExpect(status().isUnauthorized());
		// a device's own credentials are not a user login
		mvc.perform(post("/api/devices/" + p.uuid() + "/commands").header("X-Device-Id", p.deviceId()).header("X-Device-Key", "whatever")
				.contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"set_pir_led\",\"value\":\"on\"}"))
				.andExpect(status().isUnauthorized());
		assertTrue(publisher.publishedTo(p.deviceId()).isEmpty());
	}

	// ---- validation ----------------------------------------------------------------------------

	@Test
	void unsupportedActionsAndValuesAreRejectedBeforeAnythingIsSent() throws Exception {
		Paired p = onlineDevice(newUserToken());
		String[][] bad = {
				{ "reboot", "now" }, { "set_relay", "on" }, { "set_pir_led", "blink" }, { "set_pir_led", "ON" },
				{ "SET_PIR_LED", "on" }, { "set_gas_led", "toggle" }, { "set_pir_led", "1" }, { "set_gas_led", "" }, { " ", "on" } };
		for (String[] b : bad) {
			send(p, b[0], b[1]).andExpect(status().isBadRequest());
		}
		send(p, "{}").andExpect(status().isBadRequest());
		send(p, "{\"action\":\"set_pir_led\"}").andExpect(status().isBadRequest());
		send(p, "not json").andExpect(status().isBadRequest());
		assertTrue(publisher.publishedTo(p.deviceId()).isEmpty());
		assertEquals(0, commands.findByDeviceUuidOrderByCreatedAtDesc(p.uuid(), org.springframework.data.domain.PageRequest.of(0, 10)).size());
	}

	@Test
	void theGasWarningLedCanNeverBeSwitchedOffRemotely() throws Exception {
		Paired p = onlineDevice(newUserToken());
		send(p, "set_gas_led", "off").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.value").value(containsString("cannot be turned off remotely")));
		assertTrue(publisher.publishedTo(p.deviceId()).isEmpty());
		// forcing it on, or returning it to automatic, is fine
		send(p, "set_gas_led", "on").andExpect(status().isAccepted());
		send(p, "set_gas_led", "auto").andExpect(status().isAccepted());
	}

	// ---- offline, disowned ---------------------------------------------------------------------

	@Test
	void commandsToADeviceThatIsNotOnlineAreRefusedClearly() throws Exception {
		// never connected
		Paired fresh = pair(newUserToken());
		send(fresh, "set_pir_led", "on").andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(containsString("never connected")));

		// silent for too long
		Paired quiet = onlineDevice(newUserToken());
		Device d = devices.findById(quiet.uuid()).orElseThrow();
		ReflectionTestUtils.setField(d, "lastSeenAt", Instant.now().minusSeconds(600));
		devices.saveAndFlush(d);
		send(quiet, "set_pir_led", "on").andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(containsString("offline")));

		// said it is offline (Last Will)
		Paired gone = onlineDevice(newUserToken());
		telemetryIngest.handle("home/devices/" + gone.deviceId() + "/status", "{\"state\":\"offline\"}".getBytes(StandardCharsets.UTF_8), false);
		send(gone, "set_pir_led", "on").andExpect(status().isConflict());

		assertTrue(publisher.published().stream().noneMatch(x -> Set.of(fresh.deviceId(), quiet.deviceId(), gone.deviceId()).contains(x.deviceId())));
		assertEquals(0, commands.findByDeviceUuidOrderByCreatedAtDesc(fresh.uuid(), org.springframework.data.domain.PageRequest.of(0, 10)).size());
	}

	@Test
	void disownedDeviceRejectsCommandsAndLosesItsHistory() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID inFlight = sendOk(p, "set_pir_led", "on");
		report(p, stateJson("on", true, "auto", false, false, null));
		assertTrue(states.findById(p.uuid()).isPresent());

		mvc.perform(delete("/api/devices/" + p.uuid()).header("Authorization", "Bearer " + p.token())).andExpect(status().isNoContent());

		send(p, "set_pir_led", "off").andExpect(status().isNotFound());
		mvc.perform(get("/api/devices/" + p.uuid() + "/commands").header("Authorization", "Bearer " + p.token())).andExpect(status().isNotFound());
		assertTrue(commands.findById(inFlight).isEmpty(), "pending/in-flight commands are removed with the owner's data");
		assertTrue(states.findById(p.uuid()).isEmpty(), "reported state is removed");

		// a stray reply from the (still connected or spoofing) disowned device is ignored
		assertEquals(Result.REJECTED_UNKNOWN_DEVICE, ack(p, inFlight, "applied", null));
		assertEquals(Result.REJECTED_UNKNOWN_DEVICE, report(p, stateJson("auto", false, "auto", false, false, null)));
	}

	// ---- acknowledgements ----------------------------------------------------------------------

	@Test
	void appliedAckMakesTheCommandAcknowledgedAndRepeatsAreHarmless() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID id = sendOk(p, "set_pir_led", "on");

		assertEquals(Result.ACK_APPLIED, ack(p, id, "applied", null));
		assertEquals(CommandStatus.ACKNOWLEDGED, statusOf(id));
		assertNotNull(row(id).getCompletedAt());

		// duplicate delivery of the same ack
		assertEquals(Result.ACK_IGNORED, ack(p, id, "applied", null));
		// a contradicting late message cannot change a final result
		assertEquals(Result.ACK_IGNORED, ack(p, id, "rejected", "too late"));
		assertEquals(CommandStatus.ACKNOWLEDGED, statusOf(id));

		mvc.perform(get("/api/devices/" + p.uuid() + "/commands/" + id).header("Authorization", "Bearer " + p.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
	}

	@Test
	void rejectedAckFailsTheCommandAndTheReasonIsSanitised() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID id = sendOk(p, "set_pir_led", "off");
		String nasty = "bad\u0000value\n<script>alert(1)</script>" + "x".repeat(300);
		assertEquals(Result.ACK_APPLIED, ack(p, id, "rejected", nasty));
		DeviceCommand c = row(id);
		assertEquals(CommandStatus.FAILED, c.getStatus());
		assertTrue(c.getDetail().length() <= 120);
		assertFalse(c.getDetail().contains("\u0000") || c.getDetail().contains("\n"));
	}

	@Test
	void deviceCanReportThatACommandArrivedTooLate() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID id = sendOk(p, "set_pir_led", "on");
		assertEquals(Result.ACK_APPLIED, ack(p, id, "expired", "arrived after expiry"));
		assertEquals(CommandStatus.EXPIRED, statusOf(id));
	}

	@Test
	void aDeviceCanOnlyAcknowledgeItsOwnCommands() throws Exception {
		Paired a = onlineDevice(newUserToken());
		Paired b = onlineDevice(newUserToken());
		UUID cmdForA = sendOk(a, "set_pir_led", "on");

		assertEquals(Result.ACK_IGNORED, ack(b, cmdForA, "applied", null));   // B pretends to have done A's command
		assertEquals(CommandStatus.SENT, statusOf(cmdForA));
		assertEquals(Result.ACK_IGNORED, ack(a, UUID.randomUUID(), "applied", null));   // unknown id
		assertEquals(CommandStatus.SENT, statusOf(cmdForA));
	}

	@Test
	void malformedAcksAreRejected() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID id = sendOk(p, "set_pir_led", "on");
		for (String json : new String[] { "garbage", "[]", "{}", "{\"id\":\"nope\",\"status\":\"applied\"}",
				"{\"id\":\"" + id + "\"}", "{\"id\":\"" + id + "\",\"status\":\"done\"}", "{\"id\":\"" + id + "\",\"status\":1}",
				"{\"id\":5,\"status\":\"applied\"}" }) {
			assertEquals(Result.REJECTED_INVALID, commandIngest.handle(p.acksTopic(), json.getBytes(StandardCharsets.UTF_8), false), json);
		}
		assertEquals(Result.REJECTED_INVALID, commandIngest.handle(p.acksTopic(), new byte[2000], false));
		assertEquals(Result.IGNORED_RETAINED, commandIngest.handle(p.acksTopic(),
				("{\"id\":\"" + id + "\",\"status\":\"applied\"}").getBytes(StandardCharsets.UTF_8), true));
		assertEquals(Result.REJECTED_TOPIC, commandIngest.handle("home/devices/" + p.deviceId() + "/commands", "{}".getBytes(), false));
		assertEquals(CommandStatus.SENT, statusOf(id), "none of that changed the command");
	}

	@Test
	void anAckThatBeatsTheSentMarkerIsNotOverwritten() throws Exception {
		// The device can answer before the backend has recorded "sent". The ack must win.
		Paired p = onlineDevice(newUserToken());
		UUID id = insertCommand(p, "set_pir_led", "on", CommandStatus.PENDING, Instant.now().plusSeconds(30));
		assertEquals(Result.ACK_APPLIED, ack(p, id, "applied", null));
		store.markSent(id, Instant.now());
		assertEquals(CommandStatus.ACKNOWLEDGED, statusOf(id));
	}

	// ---- expiry and timeouts -------------------------------------------------------------------

	@Test
	void overdueCommandsAreSettledHonestly() throws Exception {
		Paired p = onlineDevice(newUserToken());
		Instant now = Instant.now();
		UUID neverSentOverdue = insertCommand(p, "set_pir_led", "auto", CommandStatus.PENDING, now.minusSeconds(5));
		UUID neverSentFresh = insertCommand(p, "set_pir_led", "on", CommandStatus.PENDING, now.plusSeconds(20));
		UUID sentFresh = insertCommand(p, "set_pir_led", "off", CommandStatus.SENT, now.plusSeconds(20));
		UUID sentInGrace = insertCommand(p, "set_gas_led", "on", CommandStatus.SENT, now.minusSeconds(3));    // expired, within 10 s grace
		UUID sentNoAck = insertCommand(p, "set_gas_led", "auto", CommandStatus.SENT, now.minusSeconds(60));   // long gone, never acked

		commandService.sweep();

		assertEquals(CommandStatus.EXPIRED, statusOf(neverSentOverdue), "never sent, so definitely did not run");
		assertEquals(CommandStatus.PENDING, statusOf(neverSentFresh));
		assertEquals(CommandStatus.SENT, statusOf(sentFresh));
		assertEquals(CommandStatus.SENT, statusOf(sentInGrace), "still inside the grace period");
		assertEquals(CommandStatus.UNKNOWN, statusOf(sentNoAck), "handed to the broker but no word back: honestly unknown");
		assertNotNull(row(sentNoAck).getDetail());

		// idempotent
		assertEquals(0, commandService.sweep());
	}

	@Test
	void aLateAcknowledgementResolvesAnUnknownCommand() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID id = insertCommand(p, "set_pir_led", "on", CommandStatus.SENT, Instant.now().minusSeconds(60));
		commandService.sweep();
		assertEquals(CommandStatus.UNKNOWN, statusOf(id));

		assertEquals(Result.ACK_APPLIED, ack(p, id, "applied", null));
		assertEquals(CommandStatus.ACKNOWLEDGED, statusOf(id));
	}

	@Test
	void expiredAndFinishedCommandsAreNotRevivedByTheSweeper() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID id = sendOk(p, "set_pir_led", "on");
		ack(p, id, "applied", null);
		setExpiry(id, Instant.now().minusSeconds(600));
		commandService.sweep();
		assertEquals(CommandStatus.ACKNOWLEDGED, statusOf(id));
	}

	// ---- reported state ------------------------------------------------------------------------

	@Test
	void reportedStateIsStoredAndShownSeparatelyFromWhatWasRequested() throws Exception {
		Paired p = onlineDevice(newUserToken());
		mvc.perform(get("/api/devices/" + p.uuid()).header("Authorization", "Bearer " + p.token()))
				.andExpect(jsonPath("$.reportedState").doesNotExist());

		assertEquals(Result.STATE_STORED, report(p, stateJson("on", true, "auto", true, true, null)));

		mvc.perform(get("/api/devices/" + p.uuid()).header("Authorization", "Bearer " + p.token()))
				.andExpect(jsonPath("$.reportedState.pirLed.mode").value("on"))
				.andExpect(jsonPath("$.reportedState.pirLed.on").value(true))
				.andExpect(jsonPath("$.reportedState.gasLed.mode").value("auto"))
				.andExpect(jsonPath("$.reportedState.gasLed.on").value(true))
				.andExpect(jsonPath("$.reportedState.gasWarning").value(true))   // LED is on because of a real warning, not a command
				.andExpect(jsonPath("$.reportedState.stale").value(false));
	}

	@Test
	void reportedStateCanConfirmACommandWhoseAckWasLost() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID id = sendOk(p, "set_pir_led", "on");
		report(p, stateJson("on", true, "auto", false, false, id));
		assertEquals(CommandStatus.ACKNOWLEDGED, statusOf(id));

		// ...and also resolves one that had already been written off as UNKNOWN
		UUID lost = insertCommand(p, "set_pir_led", "off", CommandStatus.SENT, Instant.now().minusSeconds(60));
		commandService.sweep();
		assertEquals(CommandStatus.UNKNOWN, statusOf(lost));
		report(p, stateJson("off", false, "auto", false, false, lost));
		assertEquals(CommandStatus.ACKNOWLEDGED, statusOf(lost));
	}

	@Test
	void aReportCannotConfirmSomeoneElsesCommand() throws Exception {
		Paired a = onlineDevice(newUserToken());
		Paired b = onlineDevice(newUserToken());
		UUID cmdForA = sendOk(a, "set_pir_led", "on");
		report(b, stateJson("on", true, "auto", false, false, cmdForA));
		assertEquals(CommandStatus.SENT, statusOf(cmdForA));
	}

	@Test
	void invalidAndRetainedStateReportsAreRejected() throws Exception {
		Paired p = onlineDevice(newUserToken());
		String[] bad = {
				"garbage", "{}", stateJson("auto", false, "off", false, false, null),            // gas LED mode "off" does not exist
				stateJson("blink", false, "auto", false, false, null),
				"{\"pirLed\":{\"mode\":\"auto\",\"on\":\"yes\"},\"gasLed\":{\"mode\":\"auto\",\"on\":false},\"gasWarning\":false}",
				"{\"pirLed\":{\"mode\":\"auto\",\"on\":false},\"gasLed\":{\"mode\":\"auto\",\"on\":false}}",   // no gasWarning
				"{\"pirLed\":{\"mode\":\"auto\",\"on\":false},\"gasLed\":{\"mode\":\"auto\",\"on\":false},\"gasWarning\":false,\"lastCommandId\":\"x\"}" };
		for (String json : bad) {
			assertEquals(Result.REJECTED_INVALID, report(p, json), json);
		}
		assertEquals(Result.IGNORED_RETAINED, commandIngest.handle(p.stateTopic(),
				stateJson("auto", false, "auto", false, false, null).getBytes(StandardCharsets.UTF_8), true));
		assertTrue(states.findById(p.uuid()).isEmpty(), "nothing invalid was stored");
	}

	@Test
	void oldReportedStateIsFlaggedStale() throws Exception {
		Paired p = onlineDevice(newUserToken());
		report(p, stateJson("auto", false, "auto", false, false, null));
		DeviceReportedState s = states.findById(p.uuid()).orElseThrow();
		ReflectionTestUtils.setField(s, "reportedAt", Instant.now().minusSeconds(600));
		states.saveAndFlush(s);
		mvc.perform(get("/api/devices/" + p.uuid()).header("Authorization", "Bearer " + p.token()))
				.andExpect(jsonPath("$.reportedState.stale").value(true));
	}

	// ---- failures, duplicates, limits, listing -------------------------------------------------

	@Test
	void whenTheBrokerIsDownTheCommandFailsVisiblyAndNothingIsClaimed() throws Exception {
		Paired p = onlineDevice(newUserToken());
		publisher.setDown(true);
		send(p, "set_pir_led", "on").andExpect(status().isServiceUnavailable());

		var rows = commands.findByDeviceUuidOrderByCreatedAtDesc(p.uuid(), org.springframework.data.domain.PageRequest.of(0, 10));
		assertEquals(1, rows.size());
		assertEquals(CommandStatus.FAILED, rows.get(0).getStatus());
		assertNull(rows.get(0).getSentAt());

		publisher.setDown(false);
		send(p, "set_pir_led", "on").andExpect(status().isAccepted());   // a failed command doesn't block a retry
	}

	@Test
	void sendingTheSameCommandTwiceWhileItIsInFlightDoesNotSendItTwice() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID first = sendOk(p, "set_pir_led", "on");
		UUID second = sendOk(p, "set_pir_led", "on");
		assertEquals(first, second);
		assertEquals(1, publisher.publishedTo(p.deviceId()).size());

		// a different value is a different command
		UUID other = sendOk(p, "set_pir_led", "off");
		assertFalse(first.equals(other));
		assertEquals(2, publisher.publishedTo(p.deviceId()).size());

		// once finished, the same command can be sent again
		ack(p, first, "applied", null);
		UUID again = sendOk(p, "set_pir_led", "on");
		assertFalse(first.equals(again));
	}

	@Test
	void tooManyCommandsWaitingForOneDeviceAreRefused() throws Exception {
		Paired p = onlineDevice(newUserToken());
		for (int i = 0; i < 5; i++) {
			insertCommand(p, "set_pir_led", "auto", CommandStatus.SENT, Instant.now().plusSeconds(25));
		}
		send(p, "set_gas_led", "on").andExpect(status().isTooManyRequests());
		assertTrue(publisher.publishedTo(p.deviceId()).isEmpty());
	}

	@Test
	void deviceListAdvertisesTheSupportedOutputsAndRecentCommands() throws Exception {
		Paired p = onlineDevice(newUserToken());
		sendOk(p, "set_pir_led", "on");
		mvc.perform(get("/api/devices").header("Authorization", "Bearer " + p.token()))
				.andExpect(jsonPath("$[0].outputs", hasSize(2)))
				.andExpect(jsonPath("$[0].outputs[0].action").value("set_pir_led"))
				.andExpect(jsonPath("$[0].outputs[0].values", hasSize(3)))
				.andExpect(jsonPath("$[0].outputs[1].action").value("set_gas_led"))
				.andExpect(jsonPath("$[0].outputs[1].values", hasSize(2)))   // no "off" for the gas LED
				.andExpect(jsonPath("$[0].recentCommands", hasSize(1)))
				.andExpect(jsonPath("$[0].recentCommands[0].status").value("SENT"));
	}

	@Test
	void historyIsNewestFirstAndLimited() throws Exception {
		Paired p = onlineDevice(newUserToken());
		UUID c1 = sendOk(p, "set_pir_led", "on");
		ack(p, c1, "applied", null);
		UUID c2 = sendOk(p, "set_pir_led", "off");
		ack(p, c2, "applied", null);
		UUID c3 = sendOk(p, "set_pir_led", "auto");

		mvc.perform(get("/api/devices/" + p.uuid() + "/commands").header("Authorization", "Bearer " + p.token()))
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].id").value(c3.toString()))
				.andExpect(jsonPath("$[2].id").value(c1.toString()));
		mvc.perform(get("/api/devices/" + p.uuid() + "/commands?limit=2").header("Authorization", "Bearer " + p.token()))
				.andExpect(jsonPath("$", hasSize(2)));
		mvc.perform(get("/api/devices/" + p.uuid() + "/commands/" + UUID.randomUUID()).header("Authorization", "Bearer " + p.token()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Command not found"));
	}

	@Test
	void acknowledgementsCountAsLivenessButNotFromUnregisteredDevices() throws Exception {
		Paired p = pair(newUserToken());
		mvc.perform(get("/api/devices/" + p.uuid()).header("Authorization", "Bearer " + p.token()))
				.andExpect(jsonPath("$.connection").value("NEVER_CONNECTED"));
		report(p, stateJson("auto", false, "auto", false, false, null));
		mvc.perform(get("/api/devices/" + p.uuid()).header("Authorization", "Bearer " + p.token()))
				.andExpect(jsonPath("$.connection").value("ONLINE"));
		assertEquals(Result.REJECTED_UNKNOWN_DEVICE, commandIngest.handle("home/devices/esp32-never-registered/state",
				stateJson("auto", false, "auto", false, false, null).getBytes(StandardCharsets.UTF_8), false));
	}
}
