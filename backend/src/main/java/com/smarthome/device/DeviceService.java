package com.smarthome.device;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.assistant.RagChunkStore;
import com.smarthome.security.SecurityEventRepository;
import com.smarthome.automation.AutomationRuleRepository;
import com.smarthome.ml.MlAnomalyRepository;
import com.smarthome.ml.MlModelRepository;
import com.smarthome.command.CommandActions;
import com.smarthome.command.CommandDtos.CommandResponse;
import com.smarthome.command.CommandDtos.LedState;
import com.smarthome.command.CommandDtos.OutputResponse;
import com.smarthome.command.CommandDtos.ReportedStateResponse;
import com.smarthome.command.DeviceCommandRepository;
import com.smarthome.command.DeviceReportedState;
import com.smarthome.command.DeviceReportedStateRepository;
import com.smarthome.device.DeviceDtos.DeviceResponse;
import com.smarthome.device.DeviceDtos.TelemetryResponse;
import com.smarthome.device.DeviceExceptions.DeviceNotFoundException;
import com.smarthome.mqtt.BrokerAccess;
import com.smarthome.telemetry.TelemetryReading;
import com.smarthome.telemetry.TelemetryRepository;

/** Owner-facing operations. Every method takes the owner's id (from the JWT) and scopes queries by it. */
@Service
public class DeviceService {

	private final DeviceRepository devices;
	private final TelemetryRepository telemetry;
	private final BrokerAccess broker;
	private final DeviceCommandRepository commandRepo;
	private final DeviceReportedStateRepository stateRepo;
	private final AutomationRuleRepository automationRules;
	private final MlModelRepository mlModels;
	private final MlAnomalyRepository mlAnomalies;
	private final RagChunkStore ragChunks;
	private final SecurityEventRepository securityEvents;
	private final Clock clock;
	private final Duration staleAfter;

	public DeviceService(DeviceRepository devices, TelemetryRepository telemetry, BrokerAccess broker,
			DeviceCommandRepository commandRepo, DeviceReportedStateRepository stateRepo, AutomationRuleRepository automationRules, MlModelRepository mlModels,
			MlAnomalyRepository mlAnomalies, RagChunkStore ragChunks, SecurityEventRepository securityEvents, Clock clock,
			@Value("${app.telemetry.stale-after-seconds:90}") long staleAfterSeconds) {
		this.devices = devices;
		this.telemetry = telemetry;
		this.broker = broker;
		this.commandRepo = commandRepo;
		this.stateRepo = stateRepo;
		this.automationRules = automationRules;
		this.mlModels = mlModels;
		this.mlAnomalies = mlAnomalies;
		this.ragChunks = ragChunks;
		this.securityEvents = securityEvents;
		this.clock = clock;
		this.staleAfter = Duration.ofSeconds(staleAfterSeconds);
	}

	@Transactional(readOnly = true)
	public List<DeviceResponse> list(UUID ownerId) {
		return devices.findByOwnerUserIdOrderByRegisteredAtDesc(ownerId).stream().map(this::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public DeviceResponse get(UUID ownerId, UUID id) {
		return toResponse(owned(ownerId, id));
	}

	/** Most recent stored readings, newest first. */
	@Transactional(readOnly = true)
	public List<TelemetryResponse> history(UUID ownerId, UUID id, int limit) {
		Device device = owned(ownerId, id);
		Instant now = clock.instant();
		int size = Math.max(1, Math.min(limit, 200));
		return telemetry.findByDeviceUuidOrderByReceivedAtDesc(device.getId(), PageRequest.of(0, size))
				.stream().map(r -> toTelemetry(r, now)).toList();
	}

	@Transactional
	public DeviceResponse rename(UUID ownerId, UUID id, String name) {
		Device device = owned(ownerId, id);
		device.rename(name.trim());
		return toResponse(device);
	}

	/**
	 * Removes ownership, revokes the credential, drops the device from the MQTT broker (which also disconnects it)
	 * and deletes its stored readings so a later owner can never see them. If the broker can't be reached everything
	 * is rolled back and the owner is told to retry: a device must not look disowned while still able to connect.
	 */
	@Transactional
	public void disown(UUID ownerId, UUID id) {
		Device device = owned(ownerId, id);
		device.disown(clock.instant());
		// Everything tied to the old owner goes: readings, command history (including anything still pending) and state.
		telemetry.deleteAllForDevice(device.getId());
		commandRepo.deleteAllForDevice(device.getId());
		stateRepo.deleteForDevice(device.getId());
		automationRules.deleteAllReferencing(device.getId());
		mlModels.deleteAllForDevice(device.getId());       // trained models and detected anomalies belong to the old owner
		mlAnomalies.deleteAllForDevice(device.getId());   // rules touching this device (history goes with them)
		ragChunks.deleteForDevice(device.getId());         // anything the assistant indexed about it
		ragChunks.deleteSource(ownerId, "DEVICE_STATUS", "devices:overview");   // the owner's device list also names it
		securityEvents.deleteAllForDevice(device.getId());                      // security history of this device
		broker.revokeDevice(device.getDeviceId());
	}

	/**
	 * Online only with live evidence: the device said it is online AND we heard from it within the staleness window.
	 * A silent device (powered off, Wi-Fi down, crashed) turns OFFLINE by itself, even if its last words were
	 * "online" because no Last Will was ever delivered.
	 */
	public DeviceConnection connectionOf(Device d, Instant now) {
		if (d.getLastSeenAt() == null) {
			return DeviceConnection.NEVER_CONNECTED;
		}
		boolean recent = !d.getLastSeenAt().plus(staleAfter).isBefore(now);
		return d.getReportedState() == ReportedState.ONLINE && recent ? DeviceConnection.ONLINE : DeviceConnection.OFFLINE;
	}

	private DeviceResponse toResponse(Device d) {
		Instant now = clock.instant();
		TelemetryResponse latest = telemetry.findFirstByDeviceUuidOrderByReceivedAtDesc(d.getId())
				.map(r -> toTelemetry(r, now)).orElse(null);
		Long lastSeenAge = d.getLastSeenAt() == null ? null : Math.max(0, Duration.between(d.getLastSeenAt(), now).toSeconds());
		ReportedStateResponse reported = stateRepo.findById(d.getId()).map(s -> toReportedState(s, now)).orElse(null);
		List<CommandResponse> recent = commandRepo.findTop5ByDeviceUuidOrderByCreatedAtDesc(d.getId()).stream()
				.map(CommandResponse::from).toList();
		List<OutputResponse> outputs = CommandActions.OUTPUTS.stream()
				.map(o -> new OutputResponse(o.action(), o.label(), o.values())).toList();
		return new DeviceResponse(d.getId(), d.getDeviceId(), d.getName(), d.getStatus(), d.getRegisteredAt(),
				connectionOf(d, now), d.getLastSeenAt(), lastSeenAge, staleAfter.toSeconds(), latest,
				outputs, reported, recent);
	}

	private ReportedStateResponse toReportedState(DeviceReportedState s, Instant now) {
		long age = Math.max(0, Duration.between(s.getReportedAt(), now).toSeconds());
		return new ReportedStateResponse(new LedState(s.getPirLedMode(), s.isPirLedOn()),
				new LedState(s.getGasLedMode(), s.isGasLedOn()), s.isGasWarning(), s.getLastCommandId(),
				s.getReportedAt(), age, age > staleAfter.toSeconds());
	}

	private TelemetryResponse toTelemetry(TelemetryReading r, Instant now) {
		long age = Math.max(0, Duration.between(r.getReceivedAt(), now).toSeconds());
		return new TelemetryResponse(r.getReceivedAt(), r.getDeviceTimestamp(), r.getTemperatureC(), r.getHumidityPct(),
				r.getMotion(), r.getGasRaw(), age, age > staleAfter.toSeconds());
	}

	/** Someone else's device, a disowned device and a nonexistent device all look the same: not found. */
	private Device owned(UUID ownerId, UUID id) {
		return devices.findByIdAndOwnerUserId(id, ownerId).orElseThrow(DeviceNotFoundException::new);
	}

	/** The device if (and only if) {@code ownerId} currently owns it; otherwise not found. Used by the command API. */
	public Device requireOwned(UUID ownerId, UUID id) {
		return owned(ownerId, id);
	}
}
