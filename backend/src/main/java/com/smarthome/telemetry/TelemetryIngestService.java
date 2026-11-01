package com.smarthome.telemetry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 * Turns an MQTT message into stored data. Transport-independent so it can be tested without a broker.
 *
 * <p>Identity comes from the topic, never from the payload: the broker only lets a device publish under its own
 * username, and this service additionally requires that the topic's device is registered, active and owned. A
 * {@code deviceId} inside the payload is optional and, if present, must match the topic or the message is rejected.
 */
@Service
public class TelemetryIngestService {

	public enum Result {
		STORED, DUPLICATE, STATUS_UPDATED,
		IGNORED_RETAINED,
		REJECTED_TOPIC, REJECTED_UNKNOWN_DEVICE, REJECTED_INVALID
	}

	private static final Logger log = LoggerFactory.getLogger(TelemetryIngestService.class);

	private static final Pattern TOPIC = Pattern.compile("^home/devices/([^/+#]+)/(telemetry|status)$");
	static final int MAX_PAYLOAD_BYTES = 1024;
	/** Identical payloads inside this window are treated as QoS 1 redeliveries. */
	static final Duration DEDUPE_WINDOW = Duration.ofMinutes(10);
	/** Device timestamps before this (Nov 2023) are an unsynced clock; more than 5 minutes ahead is wrong too. */
	static final long MIN_EPOCH_SECONDS = 1_700_000_000L;
	static final Duration MAX_CLOCK_AHEAD = Duration.ofMinutes(5);

	static final double TEMP_MIN = -40, TEMP_MAX = 125;
	static final double HUMIDITY_MIN = 0, HUMIDITY_MAX = 100;
	/** ESP32 ADC is 12-bit. */
	static final int GAS_RAW_MAX = 4095;

	private final DeviceRepository devices;
	private final TelemetryRepository readings;
	private final ObjectMapper mapper;
	private final Clock clock;
	private final ApplicationEventPublisher events;

	public TelemetryIngestService(DeviceRepository devices, TelemetryRepository readings, ObjectMapper mapper, Clock clock,
			ApplicationEventPublisher events) {
		this.events = events;
		this.devices = devices;
		this.readings = readings;
		this.mapper = mapper;
		this.clock = clock;
	}

	/** Never throws for bad input: a hostile or buggy device must not be able to break the MQTT consumer. */
	@Transactional
	public Result handle(String topic, byte[] payload, boolean retained) {
		Matcher m = TOPIC.matcher(topic == null ? "" : topic);
		if (!m.matches()) {
			return reject(Result.REJECTED_TOPIC, "?", "unexpected topic");
		}
		String deviceId = m.group(1).toLowerCase();
		boolean isTelemetry = m.group(2).equals("telemetry");

		Device device = devices.findByDeviceId(deviceId).orElse(null);
		if (device == null || device.getStatus() != DeviceStatus.ACTIVE || device.getOwnerUserId() == null) {
			return reject(Result.REJECTED_UNKNOWN_DEVICE, deviceId, "not a registered, active device");
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
		JsonNode claimed = root.get("deviceId");
		if (claimed != null && !claimed.isNull()
				&& !(claimed.isString() && claimed.asString().equalsIgnoreCase(deviceId))) {
			return reject(Result.REJECTED_INVALID, deviceId, "payload deviceId does not match topic");
		}

		Instant now = clock.instant();
		try {
			return isTelemetry
					? telemetry(device, root, payload, retained, now)
					: status(device, root, retained, now);
		} catch (InvalidPayloadException e) {
			if (e.sensor != null && e.value != null) {
				events.publishEvent(new SensorRejectedEvent(device.getId(), e.sensor, e.value, now));
			}
			return reject(Result.REJECTED_INVALID, deviceId, e.getMessage());
		}
	}

	// ---- telemetry -----------------------------------------------------------------------------

	private Result telemetry(Device device, JsonNode root, byte[] raw, boolean retained, Instant now) {
		if (retained) {
			// A retained reading is old by definition; storing it with today's received_at would be a lie.
			return Result.IGNORED_RETAINED;
		}
		long seq = requireSeq(root);
		Instant deviceTs = optionalTimestamp(root, now);
		Double temperature = optionalNumber(root, "temperature", TEMP_MIN, TEMP_MAX);
		Double humidity = optionalNumber(root, "humidity", HUMIDITY_MIN, HUMIDITY_MAX);
		Boolean motion = optionalBoolean(root, "motion");
		Integer gas = optionalInt(root, "gasRaw", 0, GAS_RAW_MAX);
		if (temperature == null && humidity == null && motion == null && gas == null) {
			throw new InvalidPayloadException("no sensor readings present");
		}

		// Any valid message proves the device is alive, duplicate or not.
		device.markSeen(now, ReportedState.ONLINE);

		String key = sha256Hex(raw);
		if (readings.existsByDeviceUuidAndDedupeKeyAndReceivedAtAfter(device.getId(), key, now.minus(DEDUPE_WINDOW))) {
			log.debug("Duplicate telemetry from {} dropped", device.getDeviceId());
			return Result.DUPLICATE;
		}
		readings.save(new TelemetryReading(device.getId(), now, deviceTs, seq, temperature, humidity, motion, gas, key));
		// Delivered to listeners only after this transaction commits (the automation engine runs on its own thread).
		events.publishEvent(new TelemetryStoredEvent(device.getId(), now, temperature, humidity, motion, gas));
		return Result.STORED;
	}

	// ---- status (online/offline) ---------------------------------------------------------------

	private Result status(Device device, JsonNode root, boolean retained, Instant now) {
		JsonNode state = root.get("state");
		if (state == null || !state.isString()) {
			throw new InvalidPayloadException("missing state");
		}
		switch (state.asString().toLowerCase()) {
			case "online" -> {
				if (retained) {
					// Retained "online" may be from a previous session: record the claim but don't treat it as proof of life.
					device.reportState(ReportedState.ONLINE);
				} else {
					device.markSeen(now, ReportedState.ONLINE);
				}
			}
			// Sent by the device on a clean shutdown, or by the broker as the Last Will when the connection dies.
			case "offline" -> device.reportState(ReportedState.OFFLINE);
			default -> throw new InvalidPayloadException("unknown state");
		}
		return Result.STATUS_UPDATED;
	}

	// ---- field validation ----------------------------------------------------------------------

	private static long requireSeq(JsonNode root) {
		JsonNode n = root.get("seq");
		if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.asLong() < 0) {
			throw new InvalidPayloadException("seq must be a non-negative integer");
		}
		return n.asLong();
	}

	/** Optional epoch seconds. If present it must be plausible, otherwise the whole message is rejected. */
	private static Instant optionalTimestamp(JsonNode root, Instant now) {
		JsonNode n = root.get("ts");
		if (n == null || n.isNull()) {
			return null;
		}
		if (!n.isIntegralNumber() || !n.canConvertToLong()) {
			throw new InvalidPayloadException("ts must be epoch seconds");
		}
		long seconds = n.asLong();
		if (seconds < MIN_EPOCH_SECONDS) {
			throw new InvalidPayloadException("ts is implausibly old (device clock not set?)");
		}
		Instant ts = Instant.ofEpochSecond(seconds);
		if (ts.isAfter(now.plus(MAX_CLOCK_AHEAD))) {
			throw new InvalidPayloadException("ts is in the future");
		}
		return ts;
	}

	private static Double optionalNumber(JsonNode root, String field, double min, double max) {
		JsonNode n = root.get(field);
		if (n == null || n.isNull()) {
			return null;
		}
		if (!n.isNumber()) {
			throw new InvalidPayloadException(field + " must be a number");
		}
		double v = n.asDouble();
		if (!Double.isFinite(v) || v < min || v > max) {
			// The sensor field and value travel with the exception so the anomaly detector can record that this
			// device reported an impossible value (the message itself is still rejected and not stored).
			throw new InvalidPayloadException(field + " out of range", field, Double.isFinite(v) ? v : null);
		}
		return v;
	}

	private static Integer optionalInt(JsonNode root, String field, int min, int max) {
		JsonNode n = root.get(field);
		if (n == null || n.isNull()) {
			return null;
		}
		if (!n.isIntegralNumber() || !n.canConvertToInt()) {
			throw new InvalidPayloadException(field + " must be an integer");
		}
		int v = n.asInt();
		if (v < min || v > max) {
			throw new InvalidPayloadException(field + " out of range", field, (double) v);
		}
		return v;
	}

	private static Boolean optionalBoolean(JsonNode root, String field) {
		JsonNode n = root.get(field);
		if (n == null || n.isNull()) {
			return null;
		}
		if (!n.isBoolean()) {
			throw new InvalidPayloadException(field + " must be true or false");
		}
		return n.booleanValue();
	}

	// ---- helpers -------------------------------------------------------------------------------

	private static Result reject(Result result, String deviceId, String reason) {
		// Log the reason only: never the payload.
		log.warn("Rejected MQTT message from '{}': {}", sanitize(deviceId), reason);
		return result;
	}

	/** The topic is attacker-influenced text; keep log lines single-line and short. */
	private static String sanitize(String s) {
		String cleaned = s.replaceAll("[^A-Za-z0-9:_.-]", "?");
		return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final class InvalidPayloadException extends RuntimeException {
		/** Set only for a sensor value outside its accepted range. */
		final String sensor;
		final Double value;

		InvalidPayloadException(String message) {
			this(message, null, null);
		}

		InvalidPayloadException(String message, String sensor, Double value) {
			super(message, null, false, false);
			this.sensor = sensor;
			this.value = value;
		}
	}
}
