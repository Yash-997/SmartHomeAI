package com.smarthome;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.smarthome.device.Device;
import com.smarthome.device.DeviceRepository;
import com.smarthome.telemetry.TelemetryIngestService;
import com.smarthome.telemetry.TelemetryIngestService.Result;
import com.smarthome.telemetry.TelemetryReading;
import com.smarthome.telemetry.TelemetryRepository;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class TelemetryTests {

	private static final AtomicInteger SEQ = new AtomicInteger();

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper mapper;
	@Autowired TelemetryIngestService ingest;
	@Autowired TelemetryRepository readings;
	@Autowired DeviceRepository devices;
	@Autowired RecordingBrokerAccess broker;

	@AfterEach
	void brokerBackUp() {
		broker.setDown(false);
	}

	// ---- helpers -------------------------------------------------------------------------------

	private record Paired(String token, String id, String deviceId, String credential) {
		String telemetryTopic() { return "home/devices/" + deviceId + "/telemetry"; }
		String statusTopic() { return "home/devices/" + deviceId + "/status"; }
	}

	private String newUserToken() throws Exception {
		int n = SEQ.incrementAndGet();
		String res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"teleuser" + n + "\",\"email\":\"teleuser" + n + "@example.com\",\"password\":\"password123\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return mapper.readTree(res).get("token").asString();
	}

	private Paired pair(String token, String name) throws Exception {
		String hw = "esp32-" + UUID.randomUUID().toString().substring(0, 12);
		String code = mapper.readTree(mvc.perform(post("/api/devices/pairing-codes").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
				.andReturn().getResponse().getContentAsString()).get("code").asString();
		String res = mvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON)
				.content("{\"pairingCode\":\"" + code + "\",\"deviceId\":\"" + hw + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String credential = mapper.readTree(res).get("credential").asString();
		Device d = devices.findByDeviceId(hw).orElseThrow();
		return new Paired(token, d.getId().toString(), hw, credential);
	}

	private Paired pair() throws Exception {
		return pair(newUserToken(), "Sensor");
	}

	private Result send(String topic, String json) {
		return ingest.handle(topic, json.getBytes(StandardCharsets.UTF_8), false);
	}

	private static String payload(int seq, String readings) {
		return "{\"seq\":" + seq + (readings.isEmpty() ? "" : "," + readings) + "}";
	}

	private ResultActions getDevice(Paired p) throws Exception {
		return mvc.perform(get("/api/devices/" + p.id()).header("Authorization", "Bearer " + p.token()));
	}

	private long count(Paired p) {
		return readings.countByDeviceUuid(UUID.fromString(p.id()));
	}

	// ---- storing real readings -----------------------------------------------------------------

	@Test
	void validTelemetryIsStoredAndMakesTheDeviceOnline() throws Exception {
		Paired p = pair();
		getDevice(p).andExpect(jsonPath("$.connection").value("NEVER_CONNECTED"));

		long ts = Instant.now().getEpochSecond();
		assertEquals(Result.STORED, send(p.telemetryTopic(),
				payload(1, "\"ts\":" + ts + ",\"temperature\":24.5,\"humidity\":55.2,\"motion\":true,\"gasRaw\":1834")));

		TelemetryReading r = readings.findFirstByDeviceUuidOrderByReceivedAtDesc(UUID.fromString(p.id())).orElseThrow();
		assertEquals(24.5, r.getTemperatureC());
		assertEquals(55.2, r.getHumidityPct());
		assertEquals(Boolean.TRUE, r.getMotion());
		assertEquals(1834, r.getGasRaw());
		assertEquals(Instant.ofEpochSecond(ts), r.getDeviceTimestamp());

		getDevice(p).andExpect(status().isOk())
				.andExpect(jsonPath("$.connection").value("ONLINE"))
				.andExpect(jsonPath("$.latestTelemetry.temperatureC").value(24.5))
				.andExpect(jsonPath("$.latestTelemetry.humidityPct").value(55.2))
				.andExpect(jsonPath("$.latestTelemetry.motion").value(true))
				// raw ADC value is passed through untouched, never converted to ppm
				.andExpect(jsonPath("$.latestTelemetry.gasRaw").value(1834))
				.andExpect(jsonPath("$.latestTelemetry.stale").value(false))
				.andExpect(jsonPath("$.latestTelemetry.ppm").doesNotExist());
	}

	@Test
	void missingSensorsStayNullInsteadOfBecomingZero() throws Exception {
		Paired p = pair();
		assertEquals(Result.STORED, send(p.telemetryTopic(), payload(1, "\"motion\":false,\"gasRaw\":900")));
		getDevice(p).andExpect(jsonPath("$.latestTelemetry.motion").value(false))
				.andExpect(jsonPath("$.latestTelemetry.gasRaw").value(900))
				.andExpect(jsonPath("$.latestTelemetry.temperatureC").doesNotExist())
				.andExpect(jsonPath("$.latestTelemetry.humidityPct").doesNotExist());
	}

	@Test
	void payloadWithoutTimestampUsesServerReceiveTime() throws Exception {
		Paired p = pair();
		assertEquals(Result.STORED, send(p.telemetryTopic(), payload(1, "\"temperature\":20")));
		TelemetryReading r = readings.findFirstByDeviceUuidOrderByReceivedAtDesc(UUID.fromString(p.id())).orElseThrow();
		assertNull(r.getDeviceTimestamp());
		assertNotNull(r.getReceivedAt());
	}

	// ---- payload validation --------------------------------------------------------------------

	@Test
	void invalidPayloadsAreRejectedAndNothingIsStored() throws Exception {
		Paired p = pair();
		long now = Instant.now().getEpochSecond();
		List<String> bad = List.of(
				"not json at all",
				"[1,2,3]",
				"\"just a string\"",
				"{}",
				"{\"temperature\":20}",                          // no seq
				"{\"seq\":-1,\"temperature\":20}",
				"{\"seq\":1.5,\"temperature\":20}",
				"{\"seq\":\"1\",\"temperature\":20}",
				payload(1, ""),                                   // no readings
				payload(1, "\"temperature\":200"),
				payload(1, "\"temperature\":-60"),
				payload(1, "\"temperature\":\"hot\""),
				payload(1, "\"humidity\":101"),
				payload(1, "\"humidity\":-1"),
				payload(1, "\"gasRaw\":4096"),
				payload(1, "\"gasRaw\":-5"),
				payload(1, "\"gasRaw\":12.5"),
				payload(1, "\"motion\":\"yes\""),
				payload(1, "\"motion\":1"),
				payload(1, "\"temperature\":20,\"ts\":12345"),                // implausibly old
				payload(1, "\"temperature\":20,\"ts\":" + (now + 3600)),        // future
				payload(1, "\"temperature\":20,\"ts\":\"yesterday\""));
		for (String json : bad) {
			assertEquals(Result.REJECTED_INVALID, send(p.telemetryTopic(), json), "should reject: " + json);
		}
		assertEquals(Result.REJECTED_INVALID, ingest.handle(p.telemetryTopic(), new byte[0], false));
		assertEquals(Result.REJECTED_INVALID, ingest.handle(p.telemetryTopic(), new byte[5000], false));
		assertEquals(0, count(p));
		getDevice(p).andExpect(jsonPath("$.connection").value("NEVER_CONNECTED"));
	}

	@Test
	void boundaryValuesAreAccepted() throws Exception {
		Paired p = pair();
		assertEquals(Result.STORED, send(p.telemetryTopic(),
				payload(1, "\"temperature\":-40,\"humidity\":0,\"gasRaw\":0")));
		assertEquals(Result.STORED, send(p.telemetryTopic(),
				payload(2, "\"temperature\":125,\"humidity\":100,\"gasRaw\":4095")));
		assertEquals(2, count(p));
	}

	// ---- identity: topic, not payload ----------------------------------------------------------

	@Test
	void payloadDeviceIdThatDoesNotMatchTheTopicIsRejected() throws Exception {
		Paired victim = pair();
		Paired attacker = pair();
		// attacker's authenticated topic, but the payload pretends to be the victim
		String spoof = payload(1, "\"deviceId\":\"" + victim.deviceId() + "\",\"temperature\":99");
		assertEquals(Result.REJECTED_INVALID, send(attacker.telemetryTopic(), spoof));
		assertEquals(0, count(victim));
		assertEquals(0, count(attacker));

		// a matching (or absent) deviceId is fine, case-insensitively
		assertEquals(Result.STORED, send(attacker.telemetryTopic(),
				payload(2, "\"deviceId\":\"" + attacker.deviceId().toUpperCase() + "\",\"temperature\":20")));
	}

	@Test
	void messagesFromUnregisteredDevicesAndBadTopicsAreRejected() throws Exception {
		Paired p = pair();
		String ok = payload(1, "\"temperature\":20");
		assertEquals(Result.REJECTED_UNKNOWN_DEVICE, send("home/devices/esp32-never-registered/telemetry", ok));
		assertEquals(Result.REJECTED_TOPIC, send("home/devices/" + p.deviceId() + "/commands", ok));
		assertEquals(Result.REJECTED_TOPIC, send("home/devices/" + p.deviceId() + "/telemetry/extra", ok));
		assertEquals(Result.REJECTED_TOPIC, send("home/devices/+/telemetry", ok));
		assertEquals(Result.REJECTED_TOPIC, send("other/topic", ok));
		assertEquals(Result.REJECTED_TOPIC, ingest.handle(null, ok.getBytes(StandardCharsets.UTF_8), false));
		assertEquals(0, count(p));
	}

	// ---- duplicates, retained messages ---------------------------------------------------------

	@Test
	void redeliveredMessageIsStoredOnlyOnce() throws Exception {
		Paired p = pair();
		String json = payload(7, "\"temperature\":21.5,\"gasRaw\":1000");
		assertEquals(Result.STORED, send(p.telemetryTopic(), json));
		assertEquals(Result.DUPLICATE, send(p.telemetryTopic(), json));
		assertEquals(Result.DUPLICATE, send(p.telemetryTopic(), json));
		assertEquals(1, count(p));
		// a genuinely new message with identical sensor values still counts
		assertEquals(Result.STORED, send(p.telemetryTopic(), payload(8, "\"temperature\":21.5,\"gasRaw\":1000")));
		assertEquals(2, count(p));
	}

	@Test
	void sameBytesFromDifferentDevicesAreNotDuplicates() throws Exception {
		Paired a = pair();
		Paired b = pair();
		String json = payload(1, "\"temperature\":22");
		assertEquals(Result.STORED, send(a.telemetryTopic(), json));
		assertEquals(Result.STORED, send(b.telemetryTopic(), json));
	}

	@Test
	void retainedTelemetryIsIgnored() throws Exception {
		Paired p = pair();
		assertEquals(Result.IGNORED_RETAINED,
				ingest.handle(p.telemetryTopic(), payload(1, "\"temperature\":20").getBytes(StandardCharsets.UTF_8), true));
		assertEquals(0, count(p));
		getDevice(p).andExpect(jsonPath("$.connection").value("NEVER_CONNECTED"));
	}

	// ---- online / offline ----------------------------------------------------------------------

	@Test
	void statusMessagesDriveOnlineAndOffline() throws Exception {
		Paired p = pair();
		assertEquals(Result.STATUS_UPDATED, send(p.statusTopic(), "{\"state\":\"online\"}"));
		getDevice(p).andExpect(jsonPath("$.connection").value("ONLINE"));

		// Last Will published by the broker when the connection drops
		assertEquals(Result.STATUS_UPDATED, send(p.statusTopic(), "{\"state\":\"offline\"}"));
		getDevice(p).andExpect(jsonPath("$.connection").value("OFFLINE")).andExpect(jsonPath("$.lastSeenAt").exists());

		// reconnect, then data
		send(p.statusTopic(), "{\"state\":\"online\"}");
		send(p.telemetryTopic(), payload(1, "\"temperature\":20"));
		getDevice(p).andExpect(jsonPath("$.connection").value("ONLINE"));
	}

	@Test
	void anOfflineMessageNeverCreatesOnlineAndRetainedOnlineIsNotProofOfLife() throws Exception {
		Paired p = pair();
		send(p.statusTopic(), "{\"state\":\"offline\"}");
		getDevice(p).andExpect(jsonPath("$.connection").value("NEVER_CONNECTED"));

		// retained "online" from a previous session: claim recorded, but no last-seen, so still not online
		assertEquals(Result.STATUS_UPDATED, ingest.handle(p.statusTopic(), "{\"state\":\"online\"}".getBytes(StandardCharsets.UTF_8), true));
		getDevice(p).andExpect(jsonPath("$.connection").value("NEVER_CONNECTED")).andExpect(jsonPath("$.lastSeenAt").doesNotExist());
	}

	@Test
	void invalidStatusPayloadsAreRejected() throws Exception {
		Paired p = pair();
		assertEquals(Result.REJECTED_INVALID, send(p.statusTopic(), "{\"state\":\"maybe\"}"));
		assertEquals(Result.REJECTED_INVALID, send(p.statusTopic(), "{}"));
		assertEquals(Result.REJECTED_INVALID, send(p.statusTopic(), "{\"state\":true}"));
		assertEquals(Result.REJECTED_INVALID, send(p.statusTopic(), "garbage"));
		getDevice(p).andExpect(jsonPath("$.connection").value("NEVER_CONNECTED"));
	}

	@Test
	void aSilentDeviceBecomesOfflineAndItsReadingsBecomeStale() throws Exception {
		Paired p = pair();
		send(p.telemetryTopic(), payload(1, "\"temperature\":20"));
		getDevice(p).andExpect(jsonPath("$.connection").value("ONLINE"));

		// simulate 10 minutes of silence without the Last Will ever arriving
		backdate(p, Instant.now().minusSeconds(600));

		getDevice(p).andExpect(jsonPath("$.connection").value("OFFLINE"))
				.andExpect(jsonPath("$.latestTelemetry.stale").value(true))
				// the old reading is still reported (clearly flagged), not hidden or zeroed
				.andExpect(jsonPath("$.latestTelemetry.temperatureC").value(20.0));
	}

	@Test
	void historyIsNewestFirstAndLimited() throws Exception {
		Paired p = pair();
		for (int i = 1; i <= 5; i++) {
			send(p.telemetryTopic(), payload(i, "\"temperature\":" + (20 + i)));
		}
		mvc.perform(get("/api/devices/" + p.id() + "/telemetry?limit=3").header("Authorization", "Bearer " + p.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)));
		mvc.perform(get("/api/devices/" + p.id() + "/telemetry").header("Authorization", "Bearer " + p.token()))
				.andExpect(jsonPath("$", hasSize(5))).andExpect(jsonPath("$[0].temperatureC").value(25.0));
	}

	// ---- authorization on the read side --------------------------------------------------------

	@Test
	void anotherUsersTelemetryIsNotVisible() throws Exception {
		Paired owner = pair();
		send(owner.telemetryTopic(), payload(1, "\"temperature\":20"));
		String mallory = newUserToken();
		mvc.perform(get("/api/devices/" + owner.id() + "/telemetry").header("Authorization", "Bearer " + mallory))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/devices/" + owner.id()).header("Authorization", "Bearer " + mallory))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/devices").header("Authorization", "Bearer " + mallory)).andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(get("/api/devices/" + owner.id() + "/telemetry")).andExpect(status().isUnauthorized());
	}

	// ---- broker accounts: provisioning and revocation ------------------------------------------

	@Test
	void pairingCreatesABrokerAccountWithTheIssuedCredential() throws Exception {
		Paired p = pair();
		assertTrue(broker.hasAccount(p.deviceId()));
		assertEquals(p.credential(), broker.credentialOf(p.deviceId()));
	}

	@Test
	void pairingFailsCleanlyWhenTheBrokerIsDown() throws Exception {
		String token = newUserToken();
		String code = mapper.readTree(mvc.perform(post("/api/devices/pairing-codes").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
				.andReturn().getResponse().getContentAsString()).get("code").asString();
		String hw = "esp32-" + UUID.randomUUID().toString().substring(0, 12);
		String body = "{\"pairingCode\":\"" + code + "\",\"deviceId\":\"" + hw + "\"}";

		broker.setDown(true);
		mvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isServiceUnavailable());
		assertTrue(devices.findByDeviceId(hw).isEmpty(), "no device row without a broker account");

		// broker recovers: the very same code still works
		broker.setDown(false);
		mvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
		assertTrue(broker.hasAccount(hw));
	}

	@Test
	void disownRevokesBrokerAccessDeletesReadingsAndStopsIngestion() throws Exception {
		Paired p = pair();
		send(p.telemetryTopic(), payload(1, "\"temperature\":20"));
		assertEquals(1, count(p));

		mvc.perform(delete("/api/devices/" + p.id()).header("Authorization", "Bearer " + p.token())).andExpect(status().isNoContent());

		assertFalse(broker.hasAccount(p.deviceId()), "broker account removed (live connection dropped)");
		assertEquals(0, count(p), "previous owner's data is deleted");
		// a still-connected or spoofing device is ignored by the backend too
		assertEquals(Result.REJECTED_UNKNOWN_DEVICE, send(p.telemetryTopic(), payload(2, "\"temperature\":21")));
		assertEquals(Result.REJECTED_UNKNOWN_DEVICE, send(p.statusTopic(), "{\"state\":\"online\"}"));
		assertEquals(0, count(p));
	}

	@Test
	void aNewOwnerNeverSeesThePreviousOwnersData() throws Exception {
		Paired first = pair();
		send(first.telemetryTopic(), payload(1, "\"temperature\":33"));
		mvc.perform(delete("/api/devices/" + first.id()).header("Authorization", "Bearer " + first.token())).andExpect(status().isNoContent());

		String bob = newUserToken();
		String code = mapper.readTree(mvc.perform(post("/api/devices/pairing-codes").header("Authorization", "Bearer " + bob)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Reused\"}"))
				.andReturn().getResponse().getContentAsString()).get("code").asString();
		mvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON)
				.content("{\"pairingCode\":\"" + code + "\",\"deviceId\":\"" + first.deviceId() + "\"}")).andExpect(status().isOk());

		mvc.perform(get("/api/devices").header("Authorization", "Bearer " + bob))
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].connection").value("NEVER_CONNECTED"))
				.andExpect(jsonPath("$[0].latestTelemetry").doesNotExist());
	}

	@Test
	void disownFailsAndChangesNothingWhenTheBrokerIsDown() throws Exception {
		Paired p = pair();
		send(p.telemetryTopic(), payload(1, "\"temperature\":20"));

		broker.setDown(true);
		mvc.perform(delete("/api/devices/" + p.id()).header("Authorization", "Bearer " + p.token()))
				.andExpect(status().isServiceUnavailable());

		// rolled back: still owned, still has data, credential still on the broker
		getDevice(p).andExpect(status().isOk());
		assertEquals(1, count(p));
		assertTrue(broker.hasAccount(p.deviceId()));

		broker.setDown(false);
		mvc.perform(delete("/api/devices/" + p.id()).header("Authorization", "Bearer " + p.token())).andExpect(status().isNoContent());
		assertFalse(broker.hasAccount(p.deviceId()));
	}

	// ---- test utility --------------------------------------------------------------------------

	/** Moves everything about this device's liveness back in time (what 10 silent minutes would look like). */
	private void backdate(Paired p, Instant to) {
		UUID id = UUID.fromString(p.id());
		Device d = devices.findById(id).orElseThrow();
		org.springframework.test.util.ReflectionTestUtils.setField(d, "lastSeenAt", to);
		devices.saveAndFlush(d);
		readings.findByDeviceUuidOrderByReceivedAtDesc(id, org.springframework.data.domain.PageRequest.of(0, 50)).forEach(r -> {
			org.springframework.test.util.ReflectionTestUtils.setField(r, "receivedAt", to);
			readings.saveAndFlush(r);
		});
	}
}
