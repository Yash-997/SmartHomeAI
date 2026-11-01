package com.smarthome.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.smarthome.assistant.EmbeddingClient;
import com.smarthome.assistant.LlmClient;
import com.smarthome.assistant.RagChunkStore;
import com.smarthome.assistant.Text;
import com.smarthome.command.CommandStatus;
import com.smarthome.device.DeviceConnection;
import com.smarthome.device.DeviceDtos.DeviceResponse;
import com.smarthome.device.DeviceService;
import com.smarthome.health.SystemStatusDtos.AssistantHealth;
import com.smarthome.health.SystemStatusDtos.CommandHealth;
import com.smarthome.health.SystemStatusDtos.CommandProblem;
import com.smarthome.health.SystemStatusDtos.Component;
import com.smarthome.health.SystemStatusDtos.DeviceAvailability;
import com.smarthome.health.SystemStatusDtos.Issue;
import com.smarthome.health.SystemStatusDtos.Level;
import com.smarthome.health.SystemStatusDtos.MlReadiness;
import com.smarthome.health.SystemStatusDtos.SensorFreshness;
import com.smarthome.health.SystemStatusDtos.SensorState;
import com.smarthome.health.SystemStatusDtos.SystemStatus;
import com.smarthome.ml.AnomalyService;
import com.smarthome.ml.OccupancyService;
import com.smarthome.mqtt.CommandPublisher;

/**
 * One call that gathers the health of everything the user depends on. Every part is wrapped so that a failing check is
 * reported as a problem instead of failing the whole view, and nothing here changes any state or calls an external service
 * (the language model is not pinged; only its configuration is reported).
 */
@Service
public class SystemStatusService {

	private static final Logger log = LoggerFactory.getLogger(SystemStatusService.class);
	static final int WINDOW_HOURS = 24;

	private final JdbcTemplate jdbc;
	private final CommandPublisher publisher;
	private final DeviceService devices;
	private final HealthQueries queries;
	private final OccupancyService occupancy;
	private final AnomalyService anomalies;
	private final LlmClient llm;
	private final EmbeddingClient embedder;
	private final RagChunkStore ragStore;
	private final Clock clock;
	private final Duration sensorStale;

	public SystemStatusService(JdbcTemplate jdbc, CommandPublisher publisher, DeviceService devices, HealthQueries queries,
			OccupancyService occupancy, AnomalyService anomalies, LlmClient llm, EmbeddingClient embedder, RagChunkStore ragStore, Clock clock,
			@Value("${app.health.sensor-stale-seconds:300}") long sensorStaleSeconds) {
		this.jdbc = jdbc;
		this.publisher = publisher;
		this.devices = devices;
		this.queries = queries;
		this.occupancy = occupancy;
		this.anomalies = anomalies;
		this.llm = llm;
		this.embedder = embedder;
		this.ragStore = ragStore;
		this.clock = clock;
		this.sensorStale = Duration.ofSeconds(sensorStaleSeconds);
	}

	public SystemStatus status(UUID owner) {
		Instant now = clock.instant();
		List<Issue> issues = new ArrayList<>();

		Component db = database(issues);
		Component mqtt = mqtt(issues);
		List<DeviceResponse> list = db.level() == Level.DOWN ? List.of() : safeDevices(owner, issues);

		List<DeviceAvailability> availability = new ArrayList<>();
		List<SensorFreshness> sensors = new ArrayList<>();
		List<MlReadiness> ml = new ArrayList<>();
		for (DeviceResponse d : list) {
			String name = Text.oneLine(d.name(), 60);
			availability.add(new DeviceAvailability(d.id(), name, d.connection().name(), d.lastSeenAgeSeconds()));
			if (d.connection() == DeviceConnection.OFFLINE) {
				issues.add(new Issue(Level.DEGRADED, "devices", "\"" + name + "\" is offline."));
			} else if (d.connection() == DeviceConnection.NEVER_CONNECTED) {
				issues.add(new Issue(Level.DEGRADED, "devices", "\"" + name + "\" has never connected."));
			}
			sensors.add(freshness(d, name, now, issues));
			ml.add(mlReadiness(owner, d, name, issues));
		}
		CommandHealth commands = db.level() == Level.DOWN ? new CommandHealth(WINDOW_HOURS, 0, Map.of(), null, List.of()) : commandHealth(owner, now, issues);
		AssistantHealth assistant = assistant(owner, db, issues);

		Level overall = issues.stream().map(Issue::level).max(Enum::compareTo).orElse(Level.OK);
		return new SystemStatus(overall, now, issues, db, mqtt, availability, commands, sensors, ml, assistant);
	}

	// ---- parts ---------------------------------------------------------------------------------

	private Component database(List<Issue> issues) {
		try {
			jdbc.queryForObject("select 1", Integer.class);
			return new Component(Level.OK, "reachable");
		} catch (DataAccessException e) {
			log.error("Status check: database unreachable ({})", e.getClass().getSimpleName());
			issues.add(new Issue(Level.DOWN, "database", "The database is not reachable."));
			return new Component(Level.DOWN, "not reachable");
		}
	}

	private Component mqtt(List<Issue> issues) {
		CommandPublisher.Link link = publisher.link();
		return switch (link) {
			case CONNECTED -> new Component(Level.OK, "connected to the message broker");
			case DISABLED -> {
				issues.add(new Issue(Level.DEGRADED, "mqtt", "MQTT is switched off (MQTT_ENABLED=false): no telemetry arrives and commands cannot be sent."));
				yield new Component(Level.DEGRADED, "disabled");
			}
			case DISCONNECTED -> {
				issues.add(new Issue(Level.DOWN, "mqtt", "The backend is not connected to the message broker: no telemetry arrives and commands cannot be sent."));
				yield new Component(Level.DOWN, "not connected");
			}
		};
	}

	private List<DeviceResponse> safeDevices(UUID owner, List<Issue> issues) {
		try {
			return devices.list(owner);
		} catch (DataAccessException e) {
			issues.add(new Issue(Level.DOWN, "database", "Your devices could not be read."));
			return List.of();
		}
	}

	private SensorFreshness freshness(DeviceResponse d, String name, Instant now, List<Issue> issues) {
		Map<String, SensorState> out = new LinkedHashMap<>();
		try {
			out.put("temperature", state(queries.lastTemperatureAt(d.id()), now));
			out.put("humidity", state(queries.lastHumidityAt(d.id()), now));
			out.put("gas", state(queries.lastGasAt(d.id()), now));
			out.put("motion", state(queries.lastMotionAt(d.id()), now));
		} catch (DataAccessException e) {
			issues.add(new Issue(Level.DOWN, "sensors", "Sensor readings of \"" + name + "\" could not be read."));
			return new SensorFreshness(d.id(), name, d.connection().name(), out);
		}
		// Only a device that is online makes a silent sensor a finding; an offline device is already reported as offline.
		if (d.connection() == DeviceConnection.ONLINE) {
			out.forEach((sensor, st) -> {
				if (!"FRESH".equals(st.state())) {
					issues.add(new Issue(Level.DEGRADED, "sensors", "\"" + name + "\" is online but has "
							+ ("NEVER".equals(st.state()) ? "never reported a " : "no recent ") + sensor + " value."));
				}
			});
		}
		return new SensorFreshness(d.id(), name, d.connection().name(), out);
	}

	private SensorState state(Instant last, Instant now) {
		if (last == null) {
			return new SensorState("NEVER", null);
		}
		long age = Math.max(0, Duration.between(last, now).toSeconds());
		return new SensorState(age <= sensorStale.toSeconds() ? "FRESH" : "STALE", age);
	}

	private MlReadiness mlReadiness(UUID owner, DeviceResponse d, String name, List<Issue> issues) {
		try {
			var o = occupancy.status(owner, d.id());
			int active = anomalies.list(owner, d.id(), true, 50).size();
			if (active > 0) {
				issues.add(new Issue(Level.DEGRADED, "ml", active + " active anomal" + (active == 1 ? "y" : "ies") + " on \"" + name + "\"."));
			}
			return new MlReadiness(d.id(), name, o.state(), o.predictionsAvailable(), active);
		} catch (RuntimeException e) {
			log.warn("Status check: ML readiness failed ({})", e.getClass().getSimpleName());
			issues.add(new Issue(Level.DEGRADED, "ml", "ML status of \"" + name + "\" could not be read."));
			return new MlReadiness(d.id(), name, "UNKNOWN", false, 0);
		}
	}

	private CommandHealth commandHealth(UUID owner, Instant now, List<Issue> issues) {
		Instant since = now.minus(WINDOW_HOURS, java.time.temporal.ChronoUnit.HOURS);
		try {
			Map<CommandStatus, Integer> counts = new EnumMap<>(CommandStatus.class);
			for (Object[] row : queries.commandCounts(owner, since)) {
				counts.put((CommandStatus) row[0], ((Number) row[1]).intValue());
			}
			int total = counts.values().stream().mapToInt(Integer::intValue).sum();
			int bad = counts.getOrDefault(CommandStatus.FAILED, 0) + counts.getOrDefault(CommandStatus.EXPIRED, 0) + counts.getOrDefault(CommandStatus.UNKNOWN, 0);
			Double rate = total == 0 ? null : Math.round(bad * 1000.0 / total) / 1000.0;
			List<CommandProblem> problems = queries.recentProblems(owner, since, PageRequest.of(0, 5)).stream()
					.map(c -> new CommandProblem(c.getDeviceUuid(), c.getAction(), c.getValue(), c.getStatus().name(),
							c.getDetail() == null ? null : Text.oneLine(c.getDetail(), 120), c.getCreatedAt())).toList();
			if (bad > 0) {
				issues.add(new Issue(bad * 2 > total ? Level.DOWN : Level.DEGRADED, "commands",
						bad + " of " + total + " command(s) in the last " + WINDOW_HOURS + " h failed, expired or have an unknown outcome."));
			}
			Map<String, Integer> byStatus = new LinkedHashMap<>();
			counts.forEach((k, v) -> byStatus.put(k.name(), v));
			return new CommandHealth(WINDOW_HOURS, total, byStatus, rate, problems);
		} catch (DataAccessException e) {
			issues.add(new Issue(Level.DEGRADED, "commands", "Command history could not be read."));
			return new CommandHealth(WINDOW_HOURS, 0, Map.of(), null, List.of());
		}
	}

	private AssistantHealth assistant(UUID owner, Component db, List<Issue> issues) {
		boolean readable;
		try {
			ragStore.countOwned(owner);
			readable = db.level() != Level.DOWN;
		} catch (RuntimeException e) {
			readable = false;
		}
		String embedding = embedder.local() ? "local (built in)" : embedder.modelId();
		if (!readable) {
			issues.add(new Issue(Level.DEGRADED, "assistant", "The assistant's search index cannot be read."));
			return new AssistantHealth(Level.DOWN, llm.configured(), llm.provider(), llm.model(), embedding, false, "Search index not readable.");
		}
		if (!llm.configured()) {
			// Not an outage: the assistant still answers with references. It is worth knowing though.
			issues.add(new Issue(Level.DEGRADED, "assistant", "No language model is configured, so the assistant shows references only."));
			return new AssistantHealth(Level.DEGRADED, false, llm.provider(), llm.model(), embedding, true, llm.notConfiguredReason().trim());
		}
		return new AssistantHealth(Level.OK, true, llm.provider(), llm.model(), embedding, true, "Configured. The model itself is not called by this check.");
	}
}
