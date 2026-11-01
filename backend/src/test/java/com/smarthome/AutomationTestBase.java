package com.smarthome;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.smarthome.automation.AutomationDispatcher;
import com.smarthome.automation.AutomationEngine;
import com.smarthome.automation.AutomationExecution;
import com.smarthome.automation.AutomationExecutionRepository;
import com.smarthome.automation.AutomationRule;
import com.smarthome.automation.AutomationRuleRepository;
import com.smarthome.automation.AutomationStore;
import com.smarthome.automation.ExecutionOutcome;
import com.smarthome.command.CommandService;
import com.smarthome.command.CommandStatus;
import com.smarthome.command.CommandStore;
import com.smarthome.command.DeviceCommand;
import com.smarthome.command.DeviceCommandRepository;
import com.smarthome.command.DeviceReportedStateRepository;
import com.smarthome.device.Device;
import com.smarthome.device.DeviceRepository;
import com.smarthome.device.ReportedState;
import com.smarthome.telemetry.TelemetryIngestService;
import com.smarthome.telemetry.TelemetryRepository;
import com.smarthome.telemetry.TelemetryStoredEvent;

import tools.jackson.databind.ObjectMapper;

/** Shared helpers for the automation tests. Uses the same fake broker and command publisher as the other suites. */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AutomationTestBase {

	private static final AtomicInteger USERS = new AtomicInteger();
	private static final AtomicInteger SEQ = new AtomicInteger(1000);

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper mapper;
	@Autowired DeviceRepository devices;
	@Autowired TelemetryRepository telemetryRepo;
	@Autowired DeviceCommandRepository commands;
	@Autowired DeviceReportedStateRepository states;
	@Autowired AutomationRuleRepository rules;
	@Autowired AutomationExecutionRepository executions;
	@Autowired AutomationEngine engine;
	@Autowired AutomationStore automationStore;
	@Autowired AutomationDispatcher dispatcher;
	@Autowired CommandService commandService;
	@Autowired CommandStore commandStore;
	@Autowired TelemetryIngestService telemetryIngest;
	@Autowired RecordingCommandPublisher publisher;
	@Autowired RecordingBrokerAccess broker;

	record Paired(String token, UUID uuid, String deviceId, UUID userId) {
		String auth() { return "Bearer " + token; }
	}

	// ---- users and devices ---------------------------------------------------------------------

	String newUserToken() throws Exception {
		int n = USERS.incrementAndGet();
		String res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"autouser" + n + "\",\"email\":\"autouser" + n + "@example.com\",\"password\":\"password123\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return mapper.readTree(res).get("token").asString();
	}

	Paired pair(String token) throws Exception {
		String hw = "esp32-" + UUID.randomUUID().toString().substring(0, 12);
		String code = mapper.readTree(mvc.perform(post("/api/devices/pairing-codes").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Auto test\"}"))
				.andReturn().getResponse().getContentAsString()).get("code").asString();
		mvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON)
				.content("{\"pairingCode\":\"" + code + "\",\"deviceId\":\"" + hw + "\"}")).andExpect(status().isOk());
		Device d = devices.findByDeviceId(hw).orElseThrow();
		return new Paired(token, d.getId(), hw, d.getOwnerUserId());
	}

	/** Paired and genuinely ONLINE (a live telemetry message was received). Call BEFORE creating rules for it. */
	Paired onlineDevice(String token) throws Exception {
		Paired p = pair(token);
		sendTelemetry(p, "{\"seq\":" + SEQ.incrementAndGet() + ",\"motion\":false}");
		return p;
	}

	void sendTelemetry(Paired p, String json) {
		telemetryIngest.handle("home/devices/" + p.deviceId() + "/telemetry", json.getBytes(StandardCharsets.UTF_8), false);
	}

	/** Makes the device look online at an arbitrary instant (for tests that run the engine at a chosen time). */
	void markOnlineAt(Paired p, Instant at) {
		Device d = devices.findById(p.uuid()).orElseThrow();
		ReflectionTestUtils.setField(d, "lastSeenAt", at);
		ReflectionTestUtils.setField(d, "reportedState", ReportedState.ONLINE);
		devices.saveAndFlush(d);
	}

	void markOffline(Paired p) {
		Device d = devices.findById(p.uuid()).orElseThrow();
		ReflectionTestUtils.setField(d, "lastSeenAt", Instant.now().minusSeconds(3600));
		devices.saveAndFlush(d);
	}

	// ---- rules ---------------------------------------------------------------------------------

	static final String TELEMETRY = "{\"type\":\"TELEMETRY\"}";

	static String cond(String sensor, String op, String value) {
		return "{\"sensor\":\"" + sensor + "\",\"operator\":\"" + op + "\",\"value\":" + value + "}";
	}

	static String ruleJson(String name, UUID device, UUID target, String trigger, String logic, List<String> conditions,
			String action, String value, Integer priority, Integer cooldown) {
		StringBuilder sb = new StringBuilder("{\"name\":\"").append(name).append("\",\"deviceId\":\"").append(device).append("\"");
		if (target != null) sb.append(",\"targetDeviceId\":\"").append(target).append("\"");
		sb.append(",\"trigger\":").append(trigger);
		if (logic != null) sb.append(",\"logic\":\"").append(logic).append("\"");
		sb.append(",\"conditions\":[").append(String.join(",", conditions)).append("]");
		sb.append(",\"action\":{\"action\":\"").append(action).append("\",\"value\":\"").append(value).append("\"}");
		if (priority != null) sb.append(",\"priority\":").append(priority);
		if (cooldown != null) sb.append(",\"cooldownSeconds\":").append(cooldown);
		return sb.append("}").toString();
	}

	ResultActions postRule(String token, String json) throws Exception {
		return mvc.perform(post("/api/automations").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	UUID createRule(Paired p, String json) throws Exception {
		String res = postRule(p.token(), json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(mapper.readTree(res).get("id").asString());
	}

	/** A simple telemetry rule: when {@code sensor op value} then {@code action value}. */
	UUID simpleRule(Paired p, String name, String sensor, String op, String value, String action, String actionValue,
			int priority, int cooldown) throws Exception {
		return createRule(p, ruleJson(name, p.uuid(), null, TELEMETRY, "AND", List.of(cond(sensor, op, value)), action, actionValue, priority, cooldown));
	}

	// ---- events and results --------------------------------------------------------------------

	static TelemetryStoredEvent event(Paired p, Instant at, Double temp, Double hum, Boolean motion, Integer gas) {
		return new TelemetryStoredEvent(p.uuid(), at, temp, hum, motion, gas);
	}

	List<AutomationExecution> history(UUID ruleId) {
		return executions.findByRuleIdOrderByLastAtDesc(ruleId, PageRequest.of(0, 100));
	}

	AutomationExecution latest(UUID ruleId) {
		return history(ruleId).get(0);
	}

	ExecutionOutcome latestOutcome(UUID ruleId) {
		return latest(ruleId).getOutcome();
	}

	AutomationRule rule(UUID id) {
		return rules.findById(id).orElseThrow();
	}

	/** Marks every open command for the device as done, so the next identical command is a new one. */
	void finishAllCommands(Paired p) {
		for (DeviceCommand c : commands.findByDeviceUuidOrderByCreatedAtDesc(p.uuid(), PageRequest.of(0, 100))) {
			commandStore.applyDeviceResult(p.uuid(), c.getId(), CommandStatus.ACKNOWLEDGED, "test", Instant.now());
		}
	}

	int published(Paired p) {
		return publisher.publishedTo(p.deviceId()).size();
	}
}
