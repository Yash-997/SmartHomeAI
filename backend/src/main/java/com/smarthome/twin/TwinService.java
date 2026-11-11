package com.smarthome.twin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.assistant.Text;
import com.smarthome.command.CommandActions;
import com.smarthome.command.CommandDtos.ReportedStateResponse;
import com.smarthome.command.CommandStatus;
import com.smarthome.command.DeviceCommand;
import com.smarthome.command.DeviceCommandRepository;
import com.smarthome.device.DeviceDtos.DeviceResponse;
import com.smarthome.device.DeviceDtos.TelemetryResponse;
import com.smarthome.device.DeviceService;
import com.smarthome.twin.TwinDtos.OutputTwin;
import com.smarthome.twin.TwinDtos.PendingCommand;
import com.smarthome.twin.TwinDtos.SyncState;
import com.smarthome.twin.TwinDtos.TwinResponse;

/**
 * Builds a twin from what the existing services already know (the device, its latest reading, the state it reported, its
 * command history). It stores nothing and sends nothing; ownership comes from {@link DeviceService}.
 */
@Service
public class TwinService {

	private static final Set<CommandStatus> IN_FLIGHT = EnumSet.of(CommandStatus.PENDING, CommandStatus.SENT);
	private static final Set<CommandStatus> PROBLEMS = EnumSet.of(CommandStatus.FAILED, CommandStatus.EXPIRED, CommandStatus.UNKNOWN);
	/** Mirrors the firmware (config.h): a remote PIR "on" lasts PIR_LED_REMOTE_ON_MS, any forced mode OVERRIDE_MAX_MS. */
	static final Duration PIR_ON_MAX = Duration.ofMinutes(2);
	static final Duration OVERRIDE_MAX = Duration.ofHours(1);
	/** Ack detail prefixes the firmware uses (Logic.h remotePirDetail) when a PIR "on" changed nothing. */
	private static final String KEPT_AUTO = "kept auto";
	private static final String KEPT_AUTO_OLD = "automatic 2-minute timer kept";   // firmware before the prefixes
	private static final String NOT_RENEWED = "not renewed";
	private static final String NOT_RENEWED_OLD = "timer not renewed";

	private final DeviceService devices;
	private final DeviceCommandRepository commands;
	private final Clock clock;

	public TwinService(DeviceService devices, DeviceCommandRepository commands, Clock clock) {
		this.devices = devices;
		this.commands = commands;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<TwinResponse> all(UUID owner) {
		return devices.list(owner).stream().map(this::build).toList();
	}

	@Transactional(readOnly = true)
	public TwinResponse get(UUID owner, UUID deviceId) {
		return build(devices.get(owner, deviceId));       // 404 unless the caller owns it
	}

	private TwinResponse build(DeviceResponse d) {
		Instant now = clock.instant();
		List<DeviceCommand> history = commands.findByDeviceUuidOrderByCreatedAtDesc(d.id(), PageRequest.of(0, 50));
		ReportedStateResponse rep = d.reportedState();
		List<OutputTwin> outputs = new ArrayList<>();
		for (CommandActions.Output o : CommandActions.OUTPUTS) {
			outputs.add(output(o, history, rep, now));
		}
		List<PendingCommand> pending = history.stream()
				.filter(c -> IN_FLIGHT.contains(c.getStatus()) && c.getExpiresAt().isAfter(now))
				.map(c -> new PendingCommand(c.getId(), c.getAction(), c.getValue(), c.getStatus(), c.getCreatedAt(), c.getExpiresAt(), c.getOrigin()))
				.toList();
		Instant since = now.minus(24, ChronoUnit.HOURS);
		int problems = (int) history.stream().filter(c -> PROBLEMS.contains(c.getStatus()) && c.getCreatedAt().isAfter(since)).count();
		TelemetryResponse t = d.latestTelemetry();
		return new TwinResponse(d.id(), Text.oneLine(d.name(), 60), d.connection(), d.lastSeenAt(), d.lastSeenAgeSeconds(),
				t == null ? null : t.receivedAt(), t == null ? null : t.ageSeconds(), t != null && t.stale(),
				rep == null ? null : rep.gasWarning(), outputs, pending, problems, summary(d, outputs, pending, problems), now);
	}

	private OutputTwin output(CommandActions.Output o, List<DeviceCommand> history, ReportedStateResponse rep, Instant now) {
		List<DeviceCommand> mine = history.stream().filter(c -> o.action().equals(c.getAction())).toList();     // newest first
		DeviceCommand inFlight = mine.stream().filter(c -> IN_FLIGHT.contains(c.getStatus()) && c.getExpiresAt().isAfter(now)).findFirst().orElse(null);
		DeviceCommand acked = mine.stream().filter(c -> c.getStatus() == CommandStatus.ACKNOWLEDGED).findFirst().orElse(null);
		boolean unknownAfterAck = mine.stream().anyMatch(c -> c.getStatus() == CommandStatus.UNKNOWN
				&& (acked == null || c.getCreatedAt().isAfter(acked.getCreatedAt())));

		boolean pir = CommandActions.SET_PIR_LED.equals(o.action());
		String reportedMode = rep == null ? null : (pir ? rep.pirLed().mode() : rep.gasLed().mode());
		Boolean lit = rep == null ? null : (pir ? rep.pirLed().on() : rep.gasLed().on());
		boolean stale = rep == null || rep.stale();
		String desired = acked == null ? null : acked.getValue();
		String expected = desired;
		String expectedNote = "";
		if (acked != null && !"auto".equals(desired)) {
			// What the device should be doing NOW: forced modes are temporary on the device by design.
			String detail = acked.getDetail() == null ? "" : acked.getDetail().toLowerCase(Locale.ROOT);
			Instant since = appliedSince(mine, acked);
			if (pir && "on".equals(desired) && (detail.startsWith(KEPT_AUTO) || detail.contains(KEPT_AUTO_OLD))) {
				expected = "auto";
				expectedNote = "The device kept its automatic mode because motion had already lit the LED; the LED follows its local timer.";
			} else if (pir && "on".equals(desired) && !since.plus(PIR_ON_MAX).isAfter(now)) {
				expected = "auto";
				expectedNote = "The temporary \"on\" ended after 2 minutes, as designed; the device is back in automatic mode.";
			} else if (!since.plus(OVERRIDE_MAX).isAfter(now)) {
				expected = "auto";
				expectedNote = "Forced modes return to automatic after 1 hour, as designed.";
			}
		}

		SyncState sync;
		String note;
		if (inFlight != null) {
			sync = SyncState.PENDING;
			note = "A command to set " + inFlight.getValue() + " is waiting for the device to confirm it.";
		} else if (desired == null) {
			sync = SyncState.NO_DESIRED;
			note = "No command has been confirmed by the device yet, so there is nothing to compare.";
		} else if (rep == null || stale) {
			sync = SyncState.UNREPORTED;
			note = "The device has not reported its state recently, so it cannot be compared.";
		} else if (unknownAfterAck) {
			sync = SyncState.UNKNOWN;
			note = "A later command was sent but never acknowledged; whether it took effect is unknown.";
		} else if (expected.equals(reportedMode)) {
			sync = SyncState.IN_SYNC;
			note = expectedNote;
		} else {
			sync = SyncState.DRIFT;
			note = "The device reports \"" + reportedMode + "\" but the last confirmed request was \"" + desired + "\""
					+ (expected.equals(desired) ? "" : " (expected now: \"" + expected + "\")")
					+ ". It may have restarted or been changed on the device.";
		}
		if (!pir) {
			note = (note.isEmpty() ? "" : note + " ") + "The gas warning itself is raised by the device and is not controlled from here.";
		}
		return new OutputTwin(o.action(), o.label(), desired, acked == null ? null : originLabel(acked.getOrigin()), acked == null ? null : acked.getCompletedAt() != null ? acked.getCompletedAt() : acked.getCreatedAt(),
				inFlight == null ? null : inFlight.getValue(), expected, reportedMode, lit, rep == null ? null : rep.reportedAt(), stale, sync,
				note.trim());
	}

	/**
	 * When the forced mode of {@code acked} really started on the device. A PIR "on" acknowledged as "not renewed" did not
	 * restart the device's timer, so the clock runs from the earlier "on" that did.
	 */
	private static Instant appliedSince(List<DeviceCommand> mine, DeviceCommand acked) {
		for (DeviceCommand c : mine) {                                  // newest first
			if (c.getStatus() != CommandStatus.ACKNOWLEDGED || c.getCreatedAt().isAfter(acked.getCreatedAt())) {
				continue;
			}
			String d = c.getDetail() == null ? "" : c.getDetail().toLowerCase(Locale.ROOT);
			if (!c.getValue().equals(acked.getValue())) {
				break;                                                  // an earlier, different mode: stop looking
			}
			if (!(d.startsWith(NOT_RENEWED) || d.contains(NOT_RENEWED_OLD))) {
				return c.getCompletedAt() != null ? c.getCompletedAt() : c.getCreatedAt();
			}
		}
		return acked.getCompletedAt() != null ? acked.getCompletedAt() : acked.getCreatedAt();
	}

	private static String originLabel(String origin) {
		return origin == null || origin.isBlank() ? "user" : Text.oneLine(origin, 80);
	}

	private static String summary(DeviceResponse d, List<OutputTwin> outputs, List<PendingCommand> pending, int problems) {
		StringBuilder sb = new StringBuilder(switch (d.connection()) {
			case ONLINE -> "Online";
			case OFFLINE -> "Offline";
			case NEVER_CONNECTED -> "Never connected";
		});
		long drift = outputs.stream().filter(o -> o.sync() == SyncState.DRIFT).count();
		long unreported = outputs.stream().filter(o -> o.sync() == SyncState.UNREPORTED || o.sync() == SyncState.UNKNOWN).count();
		if (!pending.isEmpty()) {
			sb.append("; ").append(pending.size()).append(" command(s) pending");
		}
		if (drift > 0) {
			sb.append("; ").append(drift).append(" output(s) differ from the last confirmed request");
		}
		if (unreported > 0) {
			sb.append("; ").append(unreported).append(" output(s) cannot be verified");
		}
		if (drift == 0 && unreported == 0 && pending.isEmpty() && d.connection() == com.smarthome.device.DeviceConnection.ONLINE) {
			sb.append("; outputs match");
		}
		if (problems > 0) {
			sb.append("; ").append(problems).append(" command problem(s) in the last 24 h");
		}
		return sb.toString();
	}
}
