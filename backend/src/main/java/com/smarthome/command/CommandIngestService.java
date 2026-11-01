package com.smarthome.command;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.device.Device;
import com.smarthome.device.DeviceRepository;
import com.smarthome.device.DeviceStatus;
import com.smarthome.device.ReportedState;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Handles what a device sends back: acknowledgements ({@code .../acks}) and reported output state
 * ({@code .../state}). Like telemetry, the device's identity is the (broker-enforced) topic, never the payload, and
 * a device can only ever resolve commands that belong to itself.
 */
@Service
public class CommandIngestService {

	public enum Result {
		ACK_APPLIED, ACK_IGNORED, STATE_STORED,
		IGNORED_RETAINED,
		REJECTED_TOPIC, REJECTED_UNKNOWN_DEVICE, REJECTED_INVALID
	}

	private static final Logger log = LoggerFactory.getLogger(CommandIngestService.class);
	private static final Pattern TOPIC = Pattern.compile("^home/devices/([^/+#]+)/(acks|state)$");
	static final int MAX_PAYLOAD_BYTES = 1024;
	static final int MAX_DETAIL = 120;
	private static final Set<String> PIR_MODES = Set.of("auto", "on", "off");
	private static final Set<String> GAS_MODES = Set.of("auto", "on");   // "off" is not a valid gas LED mode

	private final DeviceRepository devices;
	private final DeviceCommandRepository commands;
	private final DeviceReportedStateRepository states;
	private final CommandStore store;
	private final ObjectMapper mapper;
	private final Clock clock;

	public CommandIngestService(DeviceRepository devices, DeviceCommandRepository commands, DeviceReportedStateRepository states,
			CommandStore store, ObjectMapper mapper, Clock clock) {
		this.devices = devices;
		this.commands = commands;
		this.states = states;
		this.store = store;
		this.mapper = mapper;
		this.clock = clock;
	}

	/** Never throws for bad input. */
	@Transactional
	public Result handle(String topic, byte[] payload, boolean retained) {
		Matcher m = TOPIC.matcher(topic == null ? "" : topic);
		if (!m.matches()) {
			return reject(Result.REJECTED_TOPIC, "?", "unexpected topic");
		}
		String deviceId = m.group(1).toLowerCase();
		boolean isAck = m.group(2).equals("acks");

		Device device = devices.findByDeviceId(deviceId).orElse(null);
		if (device == null || device.getStatus() != DeviceStatus.ACTIVE || device.getOwnerUserId() == null) {
			return reject(Result.REJECTED_UNKNOWN_DEVICE, deviceId, "not a registered, active device");
		}
		if (retained) {
			// An old retained acknowledgement or state must never be mistaken for something that just happened.
			return Result.IGNORED_RETAINED;
		}
		if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
			return reject(Result.REJECTED_INVALID, deviceId, "payload size out of range");
		}
		JsonNode root;
		try {
			root = mapper.readTree(payload);
		} catch (JacksonException e) {
			return reject(Result.REJECTED_INVALID, deviceId, "not valid JSON");
		}
		if (root == null || !root.isObject()) {
			return reject(Result.REJECTED_INVALID, deviceId, "payload is not a JSON object");
		}
		Instant now = clock.instant();
		try {
			Result result = isAck ? ack(device, root, now) : state(device, root, now);
			device.markSeen(now, ReportedState.ONLINE);   // a valid live message is proof of life
			return result;
		} catch (InvalidPayload e) {
			return reject(Result.REJECTED_INVALID, deviceId, e.getMessage());
		}
	}

	// ---- acknowledgements ----------------------------------------------------------------------

	private Result ack(Device device, JsonNode root, Instant now) {
		UUID id = requireUuid(root.get("id"), "id");
		String status = requireString(root.get("status"), "status");
		CommandStatus to = switch (status) {
			case "applied" -> CommandStatus.ACKNOWLEDGED;
			case "rejected", "error" -> CommandStatus.FAILED;
			case "expired" -> CommandStatus.EXPIRED;
			default -> throw new InvalidPayload("unknown ack status");
		};
		String detail = sanitize(optionalString(root.get("detail")));
		if (detail == null) {
			detail = to == CommandStatus.ACKNOWLEDGED ? "applied by the device" : null;
		}
		boolean changed = store.applyDeviceResult(device.getId(), id, to, detail, now);
		if (!changed) {
			log.debug("Ignored ack for command {} from {} (unknown, not theirs, or already final)", id, device.getDeviceId());
		}
		return changed ? Result.ACK_APPLIED : Result.ACK_IGNORED;
	}

	// ---- reported state ------------------------------------------------------------------------

	private Result state(Device device, JsonNode root, Instant now) {
		JsonNode pir = requireObject(root.get("pirLed"), "pirLed");
		JsonNode gas = requireObject(root.get("gasLed"), "gasLed");
		String pirMode = requireOneOf(pir.get("mode"), "pirLed.mode", PIR_MODES);
		boolean pirOn = requireBoolean(pir.get("on"), "pirLed.on");
		String gasMode = requireOneOf(gas.get("mode"), "gasLed.mode", GAS_MODES);
		boolean gasOn = requireBoolean(gas.get("on"), "gasLed.on");
		boolean gasWarning = requireBoolean(root.get("gasWarning"), "gasWarning");
		JsonNode last = root.get("lastCommandId");
		UUID lastCommandId = (last == null || last.isNull()) ? null : requireUuid(last, "lastCommandId");

		DeviceReportedState s = states.findById(device.getId()).orElseGet(() -> new DeviceReportedState(device.getId()));
		s.update(pirMode, pirOn, gasMode, gasOn, gasWarning, lastCommandId, now);
		states.save(s);

		if (lastCommandId != null) {
			// The device says it applied this command: settle it even if the acknowledgement itself was lost.
			store.applyDeviceResult(device.getId(), lastCommandId, CommandStatus.ACKNOWLEDGED, "confirmed by the device's reported state", now);
		}
		return Result.STATE_STORED;
	}

	// ---- helpers -------------------------------------------------------------------------------

	private static UUID requireUuid(JsonNode n, String field) {
		String s = requireString(n, field);
		try {
			return UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			throw new InvalidPayload(field + " must be a UUID");
		}
	}

	private static String requireString(JsonNode n, String field) {
		if (n == null || !n.isString()) {
			throw new InvalidPayload(field + " must be a string");
		}
		return n.asString();
	}

	private static String optionalString(JsonNode n) {
		return (n != null && n.isString()) ? n.asString() : null;
	}

	private static JsonNode requireObject(JsonNode n, String field) {
		if (n == null || !n.isObject()) {
			throw new InvalidPayload(field + " must be an object");
		}
		return n;
	}

	private static boolean requireBoolean(JsonNode n, String field) {
		if (n == null || !n.isBoolean()) {
			throw new InvalidPayload(field + " must be true or false");
		}
		return n.booleanValue();
	}

	private static String requireOneOf(JsonNode n, String field, Set<String> allowed) {
		String s = requireString(n, field);
		if (!allowed.contains(s)) {
			throw new InvalidPayload(field + " has an unsupported value");
		}
		return s;
	}

	/** Device-supplied text is untrusted: drop control characters and cap the length before storing it. */
	static String sanitize(String s) {
		if (s == null) {
			return null;
		}
		String cleaned = s.replaceAll("\\p{Cntrl}", " ").trim();
		if (cleaned.isEmpty()) {
			return null;
		}
		return cleaned.length() > MAX_DETAIL ? cleaned.substring(0, MAX_DETAIL) : cleaned;
	}

	private static Result reject(Result result, String deviceId, String reason) {
		log.warn("Rejected MQTT message from '{}': {}", deviceId.replaceAll("[^A-Za-z0-9:_.-]", "?"), reason);
		return result;
	}

	private static final class InvalidPayload extends RuntimeException {
		InvalidPayload(String message) {
			super(message, null, false, false);
		}
	}
}
