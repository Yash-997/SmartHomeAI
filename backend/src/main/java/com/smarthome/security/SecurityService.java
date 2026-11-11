package com.smarthome.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.alert.MotionEmailService;
import com.smarthome.command.CommandActions;
import com.smarthome.command.CommandDtos.CreateCommandRequest;
import com.smarthome.command.CommandService;
import com.smarthome.command.DeviceCommand;
import com.smarthome.command.DeviceCommandRepository;
import com.smarthome.device.Device;
import com.smarthome.device.DeviceConnection;
import com.smarthome.device.DeviceDtos.DeviceResponse;
import com.smarthome.device.DeviceRepository;
import com.smarthome.device.DeviceService;
import com.smarthome.security.SecurityDtos.EventResponse;
import com.smarthome.security.SecurityDtos.ModeConfigDto;
import com.smarthome.security.SecurityDtos.SettingsResponse;
import com.smarthome.security.SecurityEvent.Kind;
import com.smarthome.security.SecurityEvent.Severity;
import com.smarthome.security.SecurityExceptions.InvalidSecurityRequestException;
import com.smarthome.security.SecurityExceptions.SecurityEventNotFoundException;
import com.smarthome.security.SecuritySettings.ModeConfig;
import com.smarthome.telemetry.TelemetryRepository;
import com.smarthome.telemetry.TelemetryStoredEvent;

import com.smarthome.assistant.Text;

/**
 * Away / Night security modes.
 *
 * <p>Motion episodes: a device's motion episode starts with the first motion report in an armed mode and ends when the
 * device reports no motion (its telemetry says "motion: false", i.e. a whole report interval without motion) or goes
 * silent about motion for longer than {@link #EPISODE_GAP}. Each episode produces exactly one MOTION_ALERT (and, if the
 * owner chose it, one PIR LED command), however long it lasts. The alert is a persistent event: it stays in the history,
 * and in the list of open alerts, until someone acknowledges it. HOME mode records nothing.
 *
 * <p>What a mode can do: record motion alerts (one per episode per device), record devices that drop offline while armed,
 * and, if the owner chose it, set the PIR indicator LED to "on" through the normal {@link CommandService} (the same
 * ownership, online and validation checks as a person's command). What it can never do: send any gas-LED command, or
 * change anything that affects the firmware's own gas warning. The only command this class builds is
 * {@code set_pir_led}, and there is a test that asserts no security action ever produces a {@code set_gas_led}.
 */
@Service
public class SecurityService {

	static final String NOTICE = "Security modes only watch for motion and device outages and write the history below. They never change the gas "
			+ "warning, which works on the device by itself. This is not a certified alarm system.";
	private static final Logger log = LoggerFactory.getLogger(SecurityService.class);
	/** Telemetry older than this is a replay, not something happening now. */
	private static final Duration MAX_AGE = Duration.ofMinutes(2);
	/**
	 * Motion reports this far apart, with nothing in between, belong to different episodes even without a "no motion"
	 * report between them (e.g. the device was offline). Devices report every 30 s while motion continues, so this is
	 * three missed reports.
	 */
	static final Duration EPISODE_GAP = Duration.ofSeconds(90);

	private final SecuritySettingsRepository settingsRepo;
	private final SecurityEventRepository eventRepo;
	private final DeviceRepository devices;
	private final DeviceService deviceService;
	private final CommandService commands;
	private final DeviceCommandRepository commandRepo;
	private final TelemetryRepository telemetry;
	private final MotionEmailService motionEmails;
	private final Clock clock;
	/** Devices already reported offline in the current armed period (so one outage is one event). */
	private final Set<UUID> offlineReported = ConcurrentHashMap.newKeySet();

	public SecurityService(SecuritySettingsRepository settingsRepo, SecurityEventRepository eventRepo, DeviceRepository devices,
			DeviceService deviceService, CommandService commands, DeviceCommandRepository commandRepo, TelemetryRepository telemetry,
			MotionEmailService motionEmails, Clock clock) {
		this.settingsRepo = settingsRepo;
		this.eventRepo = eventRepo;
		this.devices = devices;
		this.deviceService = deviceService;
		this.commands = commands;
		this.commandRepo = commandRepo;
		this.telemetry = telemetry;
		this.motionEmails = motionEmails;
		this.clock = clock;
	}

	// ---- settings ------------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public SettingsResponse get(UUID owner) {
		SecuritySettings s = settingsRepo.findById(owner).orElseGet(() -> SecuritySettings.defaults(owner, clock.instant()));
		return toResponse(s, eventRepo.countUnacknowledgedAlerts(owner));
	}

	/** Changes the mode. Leaving it (or turning the LED action off) hands a PIR LED forced by security back to "auto". */
	public SettingsResponse setMode(UUID owner, String modeText) {
		SecurityMode mode = parseMode(modeText);
		Instant now = clock.instant();
		SecuritySettings s = settingsRepo.findById(owner).orElseGet(() -> SecuritySettings.defaults(owner, now));
		if (s.getMode() == mode) {
			return get(owner);
		}
		SecurityMode before = s.getMode();
		s.setMode(mode, now);
		settingsRepo.save(s);
		forgetOffline(owner);
		eventRepo.save(new SecurityEvent(owner, null, Kind.MODE_CHANGED, Severity.INFO, mode, "Mode changed from " + label(before) + " to " + label(mode) + ".", now));
		if (s.isPirLedForced() && !(mode.armed() && s.config(mode).lightOnMotion())) {
			restorePirLed(owner, s, mode);
		}
		return get(owner);
	}

	/** Updates what AWAY / NIGHT do. Omitted parts are unchanged. */
	public SettingsResponse configure(UUID owner, ModeConfigDto away, ModeConfigDto night) {
		validate("away", away);
		validate("night", night);
		Instant now = clock.instant();
		SecuritySettings s = settingsRepo.findById(owner).orElseGet(() -> SecuritySettings.defaults(owner, now));
		if (away != null) {
			s.setConfig(SecurityMode.AWAY, new ModeConfig(away.motionAlerts(), away.cooldownSeconds(), away.lightOnMotion()), now);
		}
		if (night != null) {
			s.setConfig(SecurityMode.NIGHT, new ModeConfig(night.motionAlerts(), night.cooldownSeconds(), night.lightOnMotion()), now);
		}
		settingsRepo.save(s);
		if (away != null || night != null) {
			eventRepo.save(new SecurityEvent(owner, null, Kind.SETTINGS_CHANGED, Severity.INFO, s.getMode(), "Security settings were changed.", now));
		}
		if (s.isPirLedForced() && !(s.getMode().armed() && s.config(s.getMode()).lightOnMotion())) {
			restorePirLed(owner, s, s.getMode());
		}
		return get(owner);
	}

	// ---- events --------------------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<EventResponse> events(UUID owner, int limit, String kind) {
		return events(owner, limit, kind, false);
	}

	/** {@code openOnly}: only alerts that are not acknowledged yet (never INFO rows), newest first. */
	@Transactional(readOnly = true)
	public List<EventResponse> events(UUID owner, int limit, String kind, boolean openOnly) {
		PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
		List<SecurityEvent> list;
		if (openOnly) {
			list = eventRepo.findByOwnerUserIdAndAcknowledgedAtIsNullAndSeverityNotOrderByCreatedAtDescIdDesc(owner, Severity.INFO, page);
		} else if (kind == null || kind.isBlank()) {
			list = eventRepo.findByOwnerUserIdOrderByCreatedAtDescIdDesc(owner, page);
		} else {
			Kind k;
			try {
				k = Kind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				throw new InvalidSecurityRequestException("kind", "Unknown event kind");
			}
			list = eventRepo.findByOwnerUserIdAndKindOrderByCreatedAtDescIdDesc(owner, k, page);
		}
		return list.stream().map(SecurityService::toResponse).toList();
	}

	@Transactional
	public EventResponse acknowledge(UUID owner, UUID eventId) {
		SecurityEvent e = eventRepo.findByIdAndOwnerUserId(eventId, owner).orElseThrow(SecurityEventNotFoundException::new);
		e.acknowledge(clock.instant());
		return toResponse(e);
	}

	@Transactional
	public int acknowledgeAll(UUID owner) {
		return eventRepo.acknowledgeAll(owner, clock.instant());
	}

	// ---- reacting to the world -----------------------------------------------------------------

	/** Called on the security worker thread for every stored telemetry message. */
	public void onTelemetry(TelemetryStoredEvent e) {
		if (!Boolean.TRUE.equals(e.motion())) {
			return;
		}
		Instant now = clock.instant();
		if (e.receivedAt().isBefore(now.minus(MAX_AGE))) {
			return;
		}
		Device device = devices.findById(e.deviceUuid()).orElse(null);
		if (device == null || device.getOwnerUserId() == null) {
			return;
		}
		UUID owner = device.getOwnerUserId();
		SecuritySettings s = settingsRepo.findById(owner).orElse(null);
		if (s == null || !s.getMode().armed()) {
			return;
		}
		SecurityMode mode = s.getMode();
		ModeConfig cfg = s.config(mode);
		if (!cfg.motionAlerts()) {
			return;
		}
		// Only alerts from the current armed period count: switching mode starts afresh.
		Instant periodStart = s.getModeChangedAt() == null ? Instant.EPOCH : s.getModeChangedAt();
		SecurityEvent last = eventRepo
				.findFirstByDeviceUuidAndKindAndCreatedAtGreaterThanEqualOrderByCreatedAtDescIdDesc(device.getId(), Kind.MOTION_ALERT, periodStart)
				.orElse(null);
		if (last != null && sameEpisode(device.getId(), last, e.receivedAt())) {
			return;                                                   // this episode already has its alert (and its LED command)
		}
		if (last != null && last.getCreatedAt().isAfter(now.minusSeconds(cfg.cooldownSeconds()))) {
			return;                                                   // flap guard: a new episode right after the previous alert
		}
		String name = Text.oneLine(device.getName(), 60);
		SecurityEvent alert = eventRepo.save(new SecurityEvent(owner, device.getId(), Kind.MOTION_ALERT, Severity.WARNING, mode,
				"Motion detected while " + label(mode) + " by \"" + name + "\".", now));
		if (mode == SecurityMode.AWAY) {
			motionEmails.alertSaved(alert, device, s.getModeChangedAt());   // queued; the alert is already saved
		}
		if (cfg.lightOnMotion()) {
			lightPirLed(owner, device, name, mode, s);                // once per episode, like the alert
		}
	}

	/**
	 * The reading at {@code at} continues the episode that {@code lastAlert} opened: the device has not reported "no motion"
	 * since that alert, and it reported motion shortly before this reading.
	 */
	private boolean sameEpisode(UUID device, SecurityEvent lastAlert, Instant at) {
		boolean endedSince = telemetry.existsByDeviceUuidAndMotionAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(device, Boolean.FALSE,
				lastAlert.getCreatedAt(), at);
		if (endedSince) {
			return false;
		}
		return telemetry.existsByDeviceUuidAndMotionAndReceivedAtGreaterThanEqualAndReceivedAtLessThan(device, Boolean.TRUE, at.minus(EPISODE_GAP), at);
	}

	/** Reports devices that went offline while the owner is in an armed mode. One event per outage. */
	public int sweepOffline() {
		Instant now = clock.instant();
		int reported = 0;
		for (SecuritySettings s : settingsRepo.findByModeNot(SecurityMode.HOME)) {
			for (Device d : devices.findByOwnerUserIdOrderByRegisteredAtDesc(s.getOwnerUserId())) {
				DeviceConnection c = deviceService.connectionOf(d, now);
				if (c == DeviceConnection.OFFLINE) {
					if (offlineReported.add(d.getId())) {
						eventRepo.save(new SecurityEvent(s.getOwnerUserId(), d.getId(), Kind.DEVICE_OFFLINE, Severity.WARNING, s.getMode(),
								"\"" + Text.oneLine(d.getName(), 60) + "\" went offline while in " + label(s.getMode()) + " mode.", now));
						reported++;
					}
				} else {
					offlineReported.remove(d.getId());
				}
			}
		}
		return reported;
	}

	// ---- LED action (PIR only) -----------------------------------------------------------------

	private void lightPirLed(UUID owner, Device device, String name, SecurityMode mode, SecuritySettings s) {
		Instant now = clock.instant();
		try {
			commands.createFromAutomation(owner, device.getId(), new CreateCommandRequest(CommandActions.SET_PIR_LED, "on"), originOf(mode));
			s.setPirLedForced(true);
			settingsRepo.save(s);
			eventRepo.save(new SecurityEvent(owner, device.getId(), Kind.ACTION_TAKEN, Severity.INFO, mode, "PIR LED of \"" + name + "\" set to on.", now));
		} catch (RuntimeException ex) {
			log.warn("Security LED action failed: {}", ex.getClass().getSimpleName());
			eventRepo.save(new SecurityEvent(owner, device.getId(), Kind.ACTION_FAILED, Severity.WARNING, mode,
					"Could not set the PIR LED of \"" + name + "\" to on (the device may be offline).", now));
		}
	}

	/** Gives back to "auto" only the PIR LEDs that a security action turned on and nobody changed since. */
	private void restorePirLed(UUID owner, SecuritySettings s, SecurityMode mode) {
		Instant now = clock.instant();
		for (DeviceResponse d : deviceService.list(owner)) {
			List<DeviceCommand> recent = commandRepo.findByDeviceUuidOrderByCreatedAtDesc(d.id(), PageRequest.of(0, 20));
			DeviceCommand lastPir = recent.stream().filter(c -> CommandActions.SET_PIR_LED.equals(c.getAction())).findFirst().orElse(null);
			if (lastPir == null || lastPir.getOrigin() == null || !lastPir.getOrigin().startsWith("security:") || !"on".equals(lastPir.getValue())) {
				continue;                                              // never touched by security, or someone changed it since
			}
			try {
				commands.createFromAutomation(owner, d.id(), new CreateCommandRequest(CommandActions.SET_PIR_LED, "auto"), "security:restore");
			} catch (RuntimeException ex) {
				log.warn("Security LED restore failed: {}", ex.getClass().getSimpleName());
				eventRepo.save(new SecurityEvent(owner, d.id(), Kind.ACTION_FAILED, Severity.WARNING, mode,
						"Could not return the PIR LED of \"" + Text.oneLine(d.name(), 60) + "\" to auto (the device may be offline). Set it manually.", now));
			}
		}
		s.setPirLedForced(false);
		settingsRepo.save(s);
	}

	private void forgetOffline(UUID owner) {
		devices.findByOwnerUserIdOrderByRegisteredAtDesc(owner).forEach(d -> offlineReported.remove(d.getId()));
	}

	// ---- helpers -------------------------------------------------------------------------------

	private static String originOf(SecurityMode mode) {
		return "security:" + mode.name().toLowerCase(Locale.ROOT);
	}

	private static String label(SecurityMode m) {
		return switch (m) {
			case HOME -> "Home";
			case AWAY -> "Away";
			case NIGHT -> "Night";
		};
	}

	private static SecurityMode parseMode(String text) {
		try {
			return SecurityMode.valueOf(text == null ? "" : text.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new InvalidSecurityRequestException("mode", "Must be HOME, AWAY or NIGHT");
		}
	}

	private static void validate(String field, ModeConfigDto c) {
		if (c != null && c.lightOnMotion() && !c.motionAlerts()) {
			throw new InvalidSecurityRequestException(field, "Lighting the LED on motion needs motion alerts to be on");
		}
	}

	private SettingsResponse toResponse(SecuritySettings s, long unacknowledged) {
		ModeConfig a = s.config(SecurityMode.AWAY);
		ModeConfig n = s.config(SecurityMode.NIGHT);
		return new SettingsResponse(s.getMode(), s.getModeChangedAt(), new ModeConfigDto(a.motionAlerts(), a.cooldownSeconds(), a.lightOnMotion()),
				new ModeConfigDto(n.motionAlerts(), n.cooldownSeconds(), n.lightOnMotion()), unacknowledged, s.isPirLedForced(), NOTICE);
	}

	private static EventResponse toResponse(SecurityEvent e) {
		return new EventResponse(e.getId(), e.getDeviceUuid(), e.getKind(), e.getSeverity(), e.getMode(), e.getMessage(), e.getCreatedAt(), e.getAcknowledgedAt());
	}
}
