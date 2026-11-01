package com.smarthome.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Manages broker accounts through Mosquitto's Dynamic Security plugin ($CONTROL/dynamic-security/v1).
 *
 * <ul>
 *   <li>Role {@code device}: may publish only to {@code home/devices/<own username>/telemetry|status|acks|state}
 *       and subscribe only to {@code .../<own username>/commands} ({@code %u} is the connecting username, so one
 *       device can never act as, or listen to, another).</li>
 *   <li>Role {@code backend}: may subscribe to every device's telemetry, status, acks and state, and is the only
 *       account allowed to publish commands.</li>
 * </ul>
 * Secrets are only ever sent to the broker; they are never logged.
 */
@Component
@ConditionalOnProperty(name = "app.mqtt.enabled", havingValue = "true")
public class DynsecBrokerAccess implements BrokerAccess {

	private static final Logger log = LoggerFactory.getLogger(DynsecBrokerAccess.class);

	static final String CONTROL_TOPIC = "$CONTROL/dynamic-security/v1";
	static final String RESPONSE_TOPIC = CONTROL_TOPIC + "/response";
	static final String DEVICE_ROLE = "device";
	static final String BACKEND_ROLE = "backend";
	private static final int TIMEOUT_SECONDS = 5;

	private final MqttProperties props;
	private final ObjectMapper mapper;
	/** The response topic is shared by all admin clients, so run one command batch at a time. */
	private final Object lock = new Object();

	public DynsecBrokerAccess(MqttProperties props, ObjectMapper mapper) {
		if (isBlank(props.adminPassword()) || isBlank(props.backendPassword())) {
			throw new IllegalStateException(
					"MQTT is enabled but MQTT_ADMIN_PASSWORD / MQTT_BACKEND_PASSWORD are not set. Set them in .env "
							+ "(see .env.example) or disable MQTT with MQTT_ENABLED=false.");
		}
		this.props = props;
		this.mapper = mapper;
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	@Override
	public void provisionDevice(String deviceId, String credential) {
		// Create-or-update, so re-pairing a device that still has an account just replaces its password.
		List<ObjectNode> cmds = List.of(
				cmd("createClient").put("username", deviceId).put("password", credential),
				cmd("setClientPassword").put("username", deviceId).put("password", credential),
				cmd("enableClient").put("username", deviceId),
				addRole(deviceId, DEVICE_ROLE));
		execute(cmds);
		log.info("Broker account provisioned for device {}", deviceId);
	}

	@Override
	public void revokeDevice(String deviceId) {
		// Disabling is what drops a live connection (deleting alone leaves it connected); then remove the account.
		execute(List.of(
				cmd("disableClient").put("username", deviceId),
				cmd("deleteClient").put("username", deviceId)));
		log.info("Broker account revoked for device {}", deviceId);
	}

	/**
	 * Creates the roles and the backend's own account if missing (idempotent). Run before the ingest client
	 * connects, so a fresh broker needs no manual setup beyond the admin account created by docker compose.
	 */
	public void reconcile() {
		List<ObjectNode> cmds = new ArrayList<>();
		cmds.add(cmd("createRole").put("rolename", DEVICE_ROLE));
		// What a device may send: its own telemetry, status, command acknowledgements and reported state.
		for (String topic : List.of("telemetry", "status", "acks", "state")) {
			cmds.add(acl(DEVICE_ROLE, "publishClientSend", "home/devices/%u/" + topic));
		}
		// What a device may receive: only commands addressed to its own username (%u). It can neither subscribe to
		// another device's commands nor publish to any commands topic.
		cmds.add(acl(DEVICE_ROLE, "subscribePattern", "home/devices/%u/commands"));
		cmds.add(acl(DEVICE_ROLE, "publishClientReceive", "home/devices/%u/commands"));
		cmds.add(cmd("createRole").put("rolename", BACKEND_ROLE));
		for (String topic : List.of("telemetry", "status", "acks", "state")) {
			cmds.add(acl(BACKEND_ROLE, "subscribePattern", "home/devices/+/" + topic));
			cmds.add(acl(BACKEND_ROLE, "publishClientReceive", "home/devices/+/" + topic));
		}
		// The backend is the only account that may publish commands.
		cmds.add(acl(BACKEND_ROLE, "publishClientSend", "home/devices/+/commands"));
		String user = props.backendUsername();
		cmds.add(cmd("createClient").put("username", user).put("password", props.backendPassword()));
		// Keeps the account in step with MQTT_BACKEND_PASSWORD if it was changed in .env.
		cmds.add(cmd("setClientPassword").put("username", user).put("password", props.backendPassword()));
		cmds.add(cmd("enableClient").put("username", user));
		cmds.add(addRole(user, BACKEND_ROLE));
		execute(cmds);
		log.info("Broker roles and backend account are in place");
	}

	// ---- command plumbing ----------------------------------------------------------------------

	private ObjectNode cmd(String name) {
		return mapper.createObjectNode().put("command", name);
	}

	private ObjectNode addRole(String username, String role) {
		return cmd("addClientRole").put("username", username).put("rolename", role);
	}

	private ObjectNode acl(String role, String type, String topic) {
		return cmd("addRoleACL").put("rolename", role).put("acltype", type).put("topic", topic).put("allow", true);
	}

	/**
	 * Errors that mean "the desired state already holds". Deliberately narrow per command: for example a missing
	 * role during addClientRole is a real misconfiguration and must not be swallowed.
	 */
	static boolean harmless(String command, String error) {
		String e = error.toLowerCase();
		return switch (command) {
			case "createClient", "createRole", "addRoleACL" -> e.contains("already");
			// Mosquitto answers a duplicate role assignment with a bare "Internal error" (verified against 2.1.2);
			// "Role not found" / "Client not found" have their own messages and are NOT tolerated.
			case "addClientRole" -> e.equals("internal error");
			// Revoking something that is already gone is success.
			case "disableClient", "deleteClient" -> e.contains("not found");
			default -> false;
		};
	}

	private void execute(List<ObjectNode> commands) {
		synchronized (lock) {
			ObjectNode batch = mapper.createObjectNode();
			ArrayNode arr = batch.putArray("commands");
			commands.forEach(arr::add);

			String response = roundTrip(mapper.writeValueAsString(batch));
			JsonNode responses = mapper.readTree(response).path("responses");
			if (!responses.isArray() || responses.size() != commands.size()) {
				throw new BrokerUnavailableException("Unexpected response from the message broker");
			}
			for (int i = 0; i < commands.size(); i++) {
				JsonNode err = responses.get(i).get("error");
				String name = commands.get(i).get("command").asString();
				if (err != null && !err.isNull() && !harmless(name, err.asString())) {
					// The error text comes from the broker and never contains the password we sent.
					throw new BrokerUnavailableException("Message broker rejected '" + name + "': " + err.asString());
				}
			}
		}
	}

	/** Connects as the broker admin, sends one batch and waits for its response. */
	private String roundTrip(String json) {
		BlockingQueue<String> inbox = new ArrayBlockingQueue<>(1);
		MqttClient client = null;
		try {
			client = new MqttClient(props.brokerUri(), "smarthome-admin-" + UUID.randomUUID(), new MemoryPersistence());
			MqttConnectOptions opts = new MqttConnectOptions();
			opts.setUserName(props.adminUsername());
			opts.setPassword(props.adminPassword().toCharArray());
			opts.setCleanSession(true);
			opts.setConnectionTimeout(TIMEOUT_SECONDS);
			client.connect(opts);
			client.subscribe(RESPONSE_TOPIC, 1, (topic, msg) -> inbox.offer(new String(msg.getPayload(), StandardCharsets.UTF_8)));
			client.publish(CONTROL_TOPIC, json.getBytes(StandardCharsets.UTF_8), 1, false);
			String response = inbox.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			if (response == null) {
				throw new BrokerUnavailableException("The message broker did not respond in time");
			}
			return response;
		} catch (MqttException e) {
			throw new BrokerUnavailableException("Cannot reach the message broker: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new BrokerUnavailableException("Interrupted while talking to the message broker", e);
		} finally {
			closeQuietly(client);
		}
	}

	private static void closeQuietly(MqttClient client) {
		if (client == null) {
			return;
		}
		try {
			if (client.isConnected()) {
				client.disconnect(1000);
			}
			client.close();
		} catch (MqttException ignored) {
			// nothing useful to do
		}
	}
}
