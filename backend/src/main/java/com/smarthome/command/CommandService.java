package com.smarthome.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.command.CommandDtos.CommandResponse;
import com.smarthome.command.CommandDtos.CreateCommandRequest;
import com.smarthome.command.CommandExceptions.DeviceOfflineException;
import com.smarthome.command.CommandExceptions.InvalidCommandException;
import com.smarthome.command.CommandExceptions.TooManyCommandsException;
import com.smarthome.device.Device;
import com.smarthome.device.DeviceConnection;
import com.smarthome.command.CommandExceptions.CommandNotFoundException;
import com.smarthome.device.DeviceService;
import com.smarthome.mqtt.BrokerAccess.BrokerUnavailableException;
import com.smarthome.mqtt.CommandPublisher;

import tools.jackson.databind.ObjectMapper;

/**
 * Creates and sends remote commands. The order of checks matters and none of them trusts the client:
 * ownership comes from the JWT subject (a device that isn't yours, is disowned or doesn't exist is "not found"),
 * the action and value come from a fixed allow-list, and the device must currently be online.
 */
@Service
public class CommandService {

	private static final Logger log = LoggerFactory.getLogger(CommandService.class);
	static final int MAX_IN_FLIGHT = 5;
	private static final EnumSet<CommandStatus> OPEN = EnumSet.of(CommandStatus.PENDING, CommandStatus.SENT);

	private final DeviceService devices;
	private final DeviceCommandRepository commands;
	private final CommandStore store;
	private final CommandPublisher publisher;
	private final ObjectMapper mapper;
	private final Clock clock;
	private final Duration ttl;
	private final Duration unknownGrace;

	public CommandService(DeviceService devices, DeviceCommandRepository commands, CommandStore store, CommandPublisher publisher,
			ObjectMapper mapper, Clock clock,
			@Value("${app.commands.ttl-seconds:30}") long ttlSeconds,
			@Value("${app.commands.unknown-grace-seconds:10}") long graceSeconds) {
		this.devices = devices;
		this.commands = commands;
		this.store = store;
		this.publisher = publisher;
		this.mapper = mapper;
		this.clock = clock;
		this.ttl = Duration.ofSeconds(ttlSeconds);
		this.unknownGrace = Duration.ofSeconds(graceSeconds);
	}

	/** Deliberately not @Transactional: each step commits on its own (see CommandStore). */
	public CommandResponse create(UUID ownerId, UUID deviceUuid, CreateCommandRequest req) {
		return create(ownerId, deviceUuid, req, null);
	}

	/**
	 * Same checks, same delivery path and same lifecycle as a manual command; the only difference is the recorded
	 * origin. The automation engine uses this so there is exactly one place that publishes commands.
	 */
	public CommandResponse createFromAutomation(UUID ownerId, UUID deviceUuid, CreateCommandRequest req, String origin) {
		return create(ownerId, deviceUuid, req, origin);
	}

	private CommandResponse create(UUID ownerId, UUID deviceUuid, CreateCommandRequest req, String origin) {
		Device device = devices.requireOwned(ownerId, deviceUuid);   // 404 for anyone but the current owner
		validate(req);

		Instant now = clock.instant();
		DeviceConnection connection = devices.connectionOf(device, now);
		if (connection != DeviceConnection.ONLINE) {
			throw new DeviceOfflineException(connection == DeviceConnection.NEVER_CONNECTED
					? "This device has never connected, so the command was not sent."
					: "This device is offline, so the command was not sent. Try again when it is back online.");
		}

		// Same command already on its way (e.g. a double click): return it instead of sending twice.
		var existing = commands.findFirstByDeviceUuidAndActionAndValueAndStatusInAndExpiresAtAfterOrderByCreatedAtDesc(
				deviceUuid, req.action(), req.value(), OPEN, now);
		if (existing.isPresent()) {
			return CommandResponse.from(existing.get());
		}
		if (commands.countByDeviceUuidAndStatusInAndExpiresAtAfter(deviceUuid, OPEN, now) >= MAX_IN_FLIGHT) {
			throw new TooManyCommandsException();
		}

		DeviceCommand cmd = store.createPending(deviceUuid, ownerId, req.action(), req.value(), now, now.plus(ttl), origin);
		try {
			publisher.publish(device.getDeviceId(), wirePayload(cmd));
		} catch (BrokerUnavailableException e) {
			store.markFailed(cmd.getId(), deviceUuid, "could not be delivered to the message broker", clock.instant());
			throw e;
		}
		store.markSent(cmd.getId(), clock.instant());
		log.info("Command {} ({} {}) sent to device {}", cmd.getId(), cmd.getAction(), cmd.getValue(), device.getDeviceId());
		return CommandResponse.from(commands.findById(cmd.getId()).orElse(cmd));
	}

	@Transactional(readOnly = true)
	public List<CommandResponse> list(UUID ownerId, UUID deviceUuid, int limit) {
		devices.requireOwned(ownerId, deviceUuid);
		int size = Math.max(1, Math.min(limit, 100));
		return commands.findByDeviceUuidOrderByCreatedAtDesc(deviceUuid, PageRequest.of(0, size)).stream()
				.map(CommandResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public CommandResponse get(UUID ownerId, UUID deviceUuid, UUID commandId) {
		devices.requireOwned(ownerId, deviceUuid);
		return commands.findByIdAndDeviceUuid(commandId, deviceUuid).map(CommandResponse::from)
				.orElseThrow(CommandNotFoundException::new);
	}

	/** Called by the sweeper (and tests). */
	public int sweep() {
		return store.sweep(clock.instant(), unknownGrace);
	}

	private static void validate(CreateCommandRequest req) {
		var output = CommandActions.find(req.action())
				.orElseThrow(() -> new InvalidCommandException("action", "Unsupported action"));
		if (!output.values().contains(req.value())) {
			String msg = CommandActions.SET_GAS_LED.equals(req.action()) && "off".equals(req.value())
					? "The gas warning LED cannot be turned off remotely: it always lights when a gas warning is active"
					: "Unsupported value for this action (allowed: " + String.join(", ", output.values()) + ")";
			throw new InvalidCommandException("value", msg);
		}
	}

	/** Exactly what the firmware validates: id, action, value and an absolute expiry in epoch seconds. */
	private String wirePayload(DeviceCommand c) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", c.getId().toString());
		m.put("action", c.getAction());
		m.put("value", c.getValue());
		m.put("expiresAt", c.getExpiresAt().getEpochSecond());
		return mapper.writeValueAsString(m);
	}
}
