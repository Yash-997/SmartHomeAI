package com.smarthome.predict;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.smarthome.command.CommandActions;
import com.smarthome.command.CommandDtos.CommandResponse;
import com.smarthome.command.CommandDtos.CreateCommandRequest;
import com.smarthome.command.CommandService;
import com.smarthome.device.DeviceConnection;
import com.smarthome.device.DeviceDtos.DeviceResponse;
import com.smarthome.device.DeviceService;
import com.smarthome.ml.MlSampleRepository;
import com.smarthome.ml.TelemetryPoint;
import com.smarthome.predict.LightingDecisionEngine.Decision;
import com.smarthome.predict.LightingDecisionEngine.Light;
import com.smarthome.predict.LightingDecisionEngine.Override;
import com.smarthome.predict.LightingDecisionEngine.Settings;

/**
 * LIVE ESP32 mode: real stored PIR readings in, the normal command pipeline out, so every acknowledgement shown is the
 * device's own. Nothing is sent unless the user has armed control for that device. State is kept in memory: after a restart
 * every device is disarmed.
 *
 * <p>Live readings are only used for inference. The model is the one trained on the primary synthetic dataset; nothing read
 * here is written to that dataset or used to train.
 */
@Service
public class LivePredictionService {

	private static final Logger log = LoggerFactory.getLogger(LivePredictionService.class);
	static final String ORIGIN = "predictive:live";
	static final String SIM_ORIGIN = "predictive:sim";
	private static final int FRESH_SECONDS = 60;
	private static final int RESEND_GUARD_SECONDS = 15;
	private static final String MODEL_NOTE = "EXPERIMENTAL: a model trained on SYNTHETIC data applied to live ESP32 readings. "
			+ "It is not validated on real-world data, and these readings are never used for training or analytics.";
	private static final int EVENT_LIMIT = 20;

	private final DeviceService devices;
	private final CommandService commands;
	private final MlSampleRepository samples;
	private final Clock clock;
	private final PredictiveModelService models;
	private final Map<UUID, State> states = new ConcurrentHashMap<>();

	private static final class State {
		UUID owner;
		boolean armed;
		Settings settings = Settings.DEFAULT;
		Override override = Override.NONE;
		LocalDateTime lastCommandAt;
		String lastCommand;
		String error;
		LightingDecisionEngine.PreArrival preArrival = LightingDecisionEngine.PreArrival.NONE;
		final java.util.LinkedList<Event> events = new java.util.LinkedList<>();
		Decision lastDecision;
	}

	/** A change of decision while control was armed. */
	public record Event(LocalDateTime at, String type, String command, String cause, String reason) {
	}

	public record View(String mode, String dataLabel, boolean armed, LocalDateTime now, String timeZone, DeviceConnection connection,
			boolean sensorFresh, Long sensorAgeSeconds, int motionReadingsToday, Integer minutesSinceMotion,
			PredictiveModel.Presence presence, PredictiveModel.Arrival arrival, boolean arrivalApplicable, boolean nightActive,
			Settings settings, Override override, Light light, boolean deviceStateReported, Decision decision,
			List<CommandResponse> commands, String sendError, Instant lastTrainedAt, int modelVersion, List<Event> events,
			LightingDecisionEngine.PreArrival preArrival) {
	}

	public LivePredictionService(DeviceService devices, CommandService commands, MlSampleRepository samples, Clock clock,
			PredictiveModelService models) {
		this.devices = devices;
		this.commands = commands;
		this.samples = samples;
		this.clock = clock;
		this.models = models;
	}

	public View view(UUID owner, UUID deviceId) {
		devices.get(owner, deviceId); // 404 before any state is created for a device the caller does not own
		State s = state(owner, deviceId);
		synchronized (s) {
			return evaluate(s, deviceId, false);
		}
	}

	/** Any null argument leaves that setting as it is. */
	public View configure(UUID owner, UUID deviceId, Boolean armed, Override override, Settings settings) {
		devices.get(owner, deviceId);
		State s = state(owner, deviceId);
		synchronized (s) {
			if (settings != null) {
				s.settings = settings;
				s.preArrival = LightingDecisionEngine.PreArrival.NONE;
			}
			if (override != null) {
				s.override = override;
				s.lastCommandAt = null;
			}
			boolean disarming = s.armed && Boolean.FALSE.equals(armed);
			if (armed != null) {
				s.armed = armed;
				s.preArrival = LightingDecisionEngine.PreArrival.NONE;
			}
			s.error = null;
			if (disarming) {
				s.override = Override.NONE;
				send(s, deviceId, "auto", ORIGIN); // hand the LED back to the firmware
			}
			return evaluate(s, deviceId, s.armed);
		}
	}

	/** Sends the command for a simulated decision to real hardware, only on the user's explicit request. */
	public CommandResponse sendSimulatedDecision(UUID owner, UUID deviceId, String value) {
		return commands.createFromAutomation(owner, deviceId, new CreateCommandRequest(CommandActions.SET_PIR_LED, value), SIM_ORIGIN);
	}

	@Scheduled(fixedDelayString = "${app.predictive.live-ms:5000}")
	public void runArmed() {
		states.forEach((deviceId, s) -> {
			synchronized (s) {
				if (!s.armed) {
					return;
				}
				try {
					evaluate(s, deviceId, true);
				} catch (RuntimeException e) {
					log.warn("Predictive lighting evaluation failed: {}", e.getClass().getSimpleName());
				}
			}
		});
	}

	private State state(UUID owner, UUID deviceId) {
		return states.compute(deviceId, (id, old) -> {
			if (old != null && owner.equals(old.owner)) {
				return old;
			}
			State s = new State();
			s.owner = owner;
			return s;
		});
	}

	private View evaluate(State s, UUID deviceId, boolean act) {
		DeviceResponse d = devices.get(s.owner, deviceId);
		ZoneId zone = models.zone();
		ZonedDateTime local = clock.instant().atZone(zone);
		LocalDateTime now = local.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS);
		int minute = local.getHour() * 60 + local.getMinute();
		List<TelemetryPoint> readings = new java.util.ArrayList<>(samples.findTelemetryPoints(deviceId,
				local.truncatedTo(ChronoUnit.DAYS).toInstant(), clock.instant().plusSeconds(1), PageRequest.of(0, 50_000)));
		readings.sort(java.util.Comparator.comparing(TelemetryPoint::at));
		DayTrace today = DemoData.blank(local.toLocalDate());
		int motionReadings = 0;
		for (TelemetryPoint r : readings) {
			ZonedDateTime at = r.at().atZone(zone);
			int m = at.getHour() * 60 + at.getMinute();
			today.observed()[m] = true;
			if (r.motion() != null) {
				motionReadings++;
				today.motion()[m] |= r.motion();
			}
			if (r.temperature() != null) {
				today.temperature()[m] = r.temperature().floatValue();
			}
			if (r.humidity() != null) {
				today.humidity()[m] = r.humidity().floatValue();
			}
			if (r.gas() != null) {
				today.gas()[m] = r.gas().floatValue();
			}
		}
		var latest = d.latestTelemetry();
		Long age = latest == null || latest.motion() == null ? null
				: Duration.between(latest.receivedAt(), clock.instant()).getSeconds();
		boolean fresh = age != null && age <= FRESH_SECONDS;

		PredictiveModel.Presence presence = null;
		PredictiveModel.Arrival arrival = null;
		Instant trainedAt = null;
		int version = 0;
		try {
			PredictiveModelService.Ready ready = models.current();
			trainedAt = ready.trainedAt();
			version = ready.version();
			if (motionReadings > 0) { // never predict from a day without readings
				presence = ready.model().presence(today, minute);
				arrival = ready.model().arrival(today, minute);
			}
		} catch (RuntimeException e) {
			log.warn("Prediction failed: {}", e.getClass().getSimpleName());
		}
		var reported = d.reportedState();
		Light light = reported == null || reported.pirLed() == null ? new Light("auto", false)
				: new Light(reported.pirLed().mode(), reported.pirLed().on());
		int since = today.sinceMotion(minute, 720);
		var inputs = new LightingDecisionEngine.Inputs(now, presence, arrival, since >= 720 ? null : since, fresh,
				d.connection() == DeviceConnection.ONLINE, light, s.override, s.lastCommandAt, s.preArrival);
		Decision decision = LightingDecisionEngine.decide(inputs, s.settings);
		if (act) {
			s.preArrival = s.preArrival.after(inputs, decision, s.settings);
			if (s.lastDecision == null || s.lastDecision.type() != decision.type() || s.lastDecision.cause() != decision.cause()) {
				s.events.addFirst(new Event(now, decision.type().name(), decision.command(), decision.cause().name(), decision.reason()));
				if (s.events.size() > EVENT_LIMIT) {
					s.events.removeLast();
				}
				log.info("Predictive decision for a device: {} ({})", decision.type(), decision.cause());
			}
			s.lastDecision = decision;
		}
		if (act && decision.command() != null && !recentlySent(s, decision.command(), now)) {
			send(s, deviceId, decision.command(), ORIGIN);
			s.lastCommandAt = now;
			s.lastCommand = decision.command();
			d = devices.get(s.owner, deviceId);
		}
		List<CommandResponse> sent = d.recentCommands().stream()
				.filter(c -> c.origin() != null && c.origin().startsWith("predictive:")).limit(5).toList();
		return new View("LIVE_ESP32", MODEL_NOTE, s.armed, now, zone.getId(), d.connection(), fresh, age, motionReadings,
				since >= 720 ? null : since, presence, arrival,
				presence != null && presence.state() != PredictiveModel.State.LIKELY_OCCUPIED,
				s.settings.isNight(now.toLocalTime()), s.settings, s.override, light, reported != null, decision, sent, s.error,
				trainedAt, version, List.copyOf(s.events), s.preArrival);
	}

	private static boolean recentlySent(State s, String command, LocalDateTime now) {
		return command.equals(s.lastCommand) && s.lastCommandAt != null
				&& Duration.between(s.lastCommandAt, now).getSeconds() < RESEND_GUARD_SECONDS;
	}

	private void send(State s, UUID deviceId, String value, String origin) {
		try {
			commands.createFromAutomation(s.owner, deviceId, new CreateCommandRequest(CommandActions.SET_PIR_LED, value), origin);
			s.error = null;
		} catch (RuntimeException e) {
			s.error = e.getMessage();
		}
	}
}
