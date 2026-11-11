package com.smarthome;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import com.smarthome.command.CommandIngestService;
import com.smarthome.device.Device;
import com.smarthome.recommendation.RecommendationRepository;
import com.smarthome.recommendation.RecommendationService;
import com.smarthome.security.SecurityDispatcher;
import com.smarthome.security.SecurityEventRepository;
import com.smarthome.security.SecuritySettingsRepository;
import com.smarthome.security.SecurityService;
import com.smarthome.telemetry.TelemetryStoredEvent;

import tools.jackson.databind.JsonNode;

/** Helpers for the Chunk 9 tests (security modes, digital twin, recommendations, system status). All data is created through real services. */
abstract class IntelTestBase extends MlTestBase {

	private static final AtomicInteger SEQ = new AtomicInteger(800_000);

	@Autowired CommandIngestService commandIngest;
	@Autowired SecurityService security;
	@Autowired SecurityDispatcher securityDispatcher;
	@Autowired SecurityEventRepository securityEvents;
	@Autowired SecuritySettingsRepository securitySettings;
	@Autowired RecommendationRepository recommendationRepo;
	@Autowired RecommendationService recommendations;
	@Autowired JdbcTemplate jdbc;

	@AfterEach
	void restoreFakes() {
		publisher.setDown(false);
		broker.setDown(false);
	}

	Paired pairNamed(String token, String name) throws Exception {
		String hw = "esp32-" + UUID.randomUUID().toString().substring(0, 12);
		String code = mapper.readTree(mvc.perform(post("/api/devices/pairing-codes").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
				.andReturn().getResponse().getContentAsString()).get("code").asString();
		mvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON)
				.content("{\"pairingCode\":\"" + code + "\",\"deviceId\":\"" + hw + "\"}")).andExpect(status().isOk());
		Device d = devices.findByDeviceId(hw).orElseThrow();
		return new Paired(token, d.getId(), hw, d.getOwnerUserId());
	}

	Paired onlineNamed(String token, String name) throws Exception {
		Paired p = pairNamed(token, name);
		sendTelemetry(p, "{\"seq\":" + SEQ.incrementAndGet() + ",\"motion\":false}");
		return p;
	}

	/** A real motion message through the real ingest path, followed by the security worker finishing. */
	void motion(Paired p) {
		sendTelemetry(p, "{\"seq\":" + SEQ.incrementAndGet() + ",\"motion\":true}");
		securityDispatcher.awaitIdle();
	}

	void motionWithGas(Paired p, int gas) {
		sendTelemetry(p, "{\"seq\":" + SEQ.incrementAndGet() + ",\"motion\":true,\"gasRaw\":" + gas + "}");
		securityDispatcher.awaitIdle();
	}

	void stillness(Paired p) {
		sendTelemetry(p, "{\"seq\":" + SEQ.incrementAndGet() + ",\"motion\":false}");
		securityDispatcher.awaitIdle();
	}

	/** The device says what its LEDs are doing (the real state-topic path). */
	void reportState(Paired p, String pirMode, boolean pirOn, String gasMode, boolean gasOn, boolean warning) {
		String json = "{\"pirLed\":{\"mode\":\"" + pirMode + "\",\"on\":" + pirOn + "},\"gasLed\":{\"mode\":\"" + gasMode + "\",\"on\":" + gasOn
				+ "},\"gasWarning\":" + warning + "}";
		commandIngest.handle("home/devices/" + p.deviceId() + "/state", json.getBytes(StandardCharsets.UTF_8), false);
	}

	/** The device acknowledges a command (the real ack-topic path). */
	void ack(Paired p, UUID commandId, String status) {
		String json = "{\"id\":\"" + commandId + "\",\"status\":\"" + status + "\"}";
		commandIngest.handle("home/devices/" + p.deviceId() + "/acks", json.getBytes(StandardCharsets.UTF_8), false);
	}

	/** Same, with the firmware's detail text (e.g. "kept auto: ..." when a PIR "on" changed nothing). */
	void ack(Paired p, UUID commandId, String status, String detail) {
		String json = "{\"id\":\"" + commandId + "\",\"status\":\"" + status + "\",\"detail\":\"" + detail + "\"}";
		commandIngest.handle("home/devices/" + p.deviceId() + "/acks", json.getBytes(StandardCharsets.UTF_8), false);
	}

	UUID sendCommand(Paired p, String action, String value) throws Exception {
		String res = postAs(p.token(), "/api/devices/" + p.uuid() + "/commands", "{\"action\":\"" + action + "\",\"value\":\"" + value + "\"}")
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(mapper.readTree(res).get("id").asString());
	}

	TelemetryStoredEvent motionEventAt(Paired p, Instant at) {
		return new TelemetryStoredEvent(p.uuid(), at, null, null, true, null);
	}

	JsonNode json(ResultActions r) throws Exception {
		return mapper.readTree(r.andReturn().getResponse().getContentAsString());
	}

	ResultActions getAs(String token, String path) throws Exception {
		return mvc.perform(get(path).header("Authorization", "Bearer " + token));
	}

	ResultActions postAs(String token, String path, String body) throws Exception {
		return mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body == null ? "" : body));
	}

	ResultActions putAs(String token, String path, String body) throws Exception {
		return mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	ResultActions deleteAs(String token, String path) throws Exception {
		return mvc.perform(delete(path).header("Authorization", "Bearer " + token));
	}

	void setMode(String token, String mode) throws Exception {
		putAs(token, "/api/security/mode", "{\"mode\":\"" + mode + "\"}").andExpect(status().isOk());
	}

	JsonNode events(String token) throws Exception {
		return json(getAs(token, "/api/security/events?limit=100").andExpect(status().isOk()));
	}

	long countKind(String token, String kind) throws Exception {
		long n = 0;
		for (JsonNode e : events(token)) {
			if (kind.equals(e.get("kind").asString())) {
				n++;
			}
		}
		return n;
	}
}
