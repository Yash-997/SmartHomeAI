package com.smarthome.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.smarthome.assistant.AssistantProperties.Rag;
import com.smarthome.automation.AutomationDtos.ConditionResponse;
import com.smarthome.automation.AutomationDtos.ExecutionResponse;
import com.smarthome.automation.AutomationDtos.RuleResponse;
import com.smarthome.automation.AutomationService;
import com.smarthome.command.CommandDtos.CommandResponse;
import com.smarthome.command.CommandDtos.ReportedStateResponse;
import com.smarthome.device.DeviceDtos.DeviceResponse;
import com.smarthome.device.DeviceDtos.TelemetryResponse;
import com.smarthome.device.DeviceService;
import com.smarthome.ml.AnomalyService;
import com.smarthome.ml.MlDtos.AnomalyResponse;
import com.smarthome.ml.MlDtos.MetricsResponse;
import com.smarthome.ml.MlDtos.OccupancyStatusResponse;
import com.smarthome.ml.MlSampleRepository;
import com.smarthome.ml.MotionSample;
import com.smarthome.ml.OccupancyService;
import com.smarthome.ml.SensorSample;
import com.smarthome.telemetry.TelemetryRepository;

/**
 * Turns a user's real records into plain-language facts that can be embedded and retrieved. Everything comes through
 * the same owner-scoped services the REST API uses ({@link DeviceService#list}, {@link AnomalyService#list}, ...), so a
 * user's facts can only ever describe that user's own devices. Nothing is estimated: a missing reading is written as
 * "no reading", never as zero.
 */
@Component
public class LiveFacts {

	public static final String DEVICE_STATUS = "DEVICE_STATUS";
	public static final String SENSOR_HISTORY = "SENSOR_HISTORY";
	public static final String ANOMALY = "ANOMALY";
	public static final String AUTOMATION = "AUTOMATION";
	public static final String ML_MODEL = "ML_MODEL";

	private static final int MAX_SAMPLES_PER_DEVICE = 40_000;
	private static final int NAME = 60;

	private final DeviceService deviceService;
	private final AnomalyService anomalyService;
	private final AutomationService automationService;
	private final OccupancyService occupancyService;
	private final MlSampleRepository samples;
	private final TelemetryRepository telemetry;
	private final Rag rag;
	private final Clock clock;

	public LiveFacts(DeviceService deviceService, AnomalyService anomalyService, AutomationService automationService,
			OccupancyService occupancyService, MlSampleRepository samples, TelemetryRepository telemetry, AssistantProperties props, Clock clock) {
		this.deviceService = deviceService;
		this.anomalyService = anomalyService;
		this.automationService = automationService;
		this.occupancyService = occupancyService;
		this.samples = samples;
		this.telemetry = telemetry;
		this.rag = props.rag();
		this.clock = clock;
	}

	/** All facts for one user, grouped by source type (so each group can be synchronised on its own). */
	public Map<String, List<FactDoc>> build(UUID ownerId) {
		Instant now = clock.instant();
		List<DeviceResponse> devices = deviceService.list(ownerId);
		Map<UUID, String> names = new HashMap<>();
		devices.forEach(d -> names.put(d.id(), Text.oneLine(d.name(), NAME)));

		Map<String, List<FactDoc>> out = new TreeMap<>();
		out.put(DEVICE_STATUS, deviceFacts(devices, now));
		out.put(SENSOR_HISTORY, historyFacts(devices, names, now));
		out.put(ANOMALY, anomalyFacts(ownerId, devices, names, now));
		out.put(AUTOMATION, automationFacts(ownerId, names, now));
		out.put(ML_MODEL, mlFacts(ownerId, devices, names, now));
		return out;
	}

	// ---- devices -------------------------------------------------------------------------------

	private List<FactDoc> deviceFacts(List<DeviceResponse> devices, Instant now) {
		List<FactDoc> docs = new ArrayList<>();
		StringBuilder overview = new StringBuilder();
		if (devices.isEmpty()) {
			overview.append("You have no devices paired.");
		} else {
			overview.append("You have ").append(devices.size()).append(devices.size() == 1 ? " device: " : " devices: ");
			List<String> parts = new ArrayList<>();
			devices.forEach(d -> parts.add("\"" + Text.oneLine(d.name(), NAME) + "\" (" + d.connection() + ")"));
			overview.append(String.join(", ", parts)).append('.');
		}
		docs.add(new FactDoc(DEVICE_STATUS, "devices:overview", null, "Device overview", overview.toString(), now));

		for (DeviceResponse d : devices) {
			String name = Text.oneLine(d.name(), NAME);
			StringBuilder sb = new StringBuilder();
			sb.append("Device \"").append(name).append("\". Connection: ").append(d.connection()).append('.');
			if (d.lastSeenAt() == null) {
				sb.append(" It has never been heard from.");
			} else {
				sb.append(" Last heard from ").append(age(d.lastSeenAgeSeconds())).append(" (").append(d.lastSeenAt()).append(").");
			}
			TelemetryResponse t = d.latestTelemetry();
			if (t == null) {
				sb.append(" No sensor readings are stored yet.");
			} else {
				sb.append(" Latest stored reading (").append(t.stale() ? "STALE, " : "current, ").append("received ").append(age(t.ageSeconds()))
						.append(" at ").append(t.receivedAt()).append("): ")
						.append(t.temperatureC() == null ? "no temperature value" : "temperature " + num(t.temperatureC()) + " C").append(", ")
						.append(t.humidityPct() == null ? "no humidity value" : "humidity " + num(t.humidityPct()) + " %").append(", ")
						.append(t.motion() == null ? "no motion value" : t.motion() ? "motion detected" : "no motion").append(", ")
						.append(t.gasRaw() == null ? "no gas value" : "gas raw " + t.gasRaw() + " (a raw sensor value, not ppm)").append('.');
			}
			sb.append(" Device identifiers: hardware id ").append(Text.oneLine(d.deviceId(), 64)).append(", id ").append(d.id()).append('.');
			sb.append(" ").append(telemetry.countByDeviceUuid(d.id())).append(" readings are stored in total.");
			ReportedStateResponse r = d.reportedState();
			if (r == null) {
				sb.append(" The device has not reported its LED state.");
			} else {
				sb.append(" As reported by the device ").append(age(r.ageSeconds())).append(r.stale() ? " (STALE)" : "").append(": PIR LED mode ")
						.append(r.pirLed().mode()).append(r.pirLed().on() ? " (lit)" : " (not lit)").append(", gas LED mode ").append(r.gasLed().mode())
						.append(r.gasLed().on() ? " (lit)" : " (not lit)").append(", gas warning ").append(r.gasWarning() ? "ACTIVE" : "not active").append('.');
			}
			if (d.recentCommands() != null && !d.recentCommands().isEmpty()) {
				List<String> cmds = new ArrayList<>();
				for (CommandResponse c : d.recentCommands()) {
					cmds.add(Text.oneLine(c.action(), 30) + " " + Text.oneLine(c.value(), 10) + " -> " + c.status() + " (" + c.createdAt() + ")");
				}
				sb.append(" Recent commands: ").append(String.join("; ", cmds)).append('.');
			}
			docs.add(new FactDoc(DEVICE_STATUS, "device:" + d.id(), d.id(), "Status of device " + name, sb.toString(), now));
		}
		return docs;
	}

	// ---- sensor history ------------------------------------------------------------------------

	private List<FactDoc> historyFacts(List<DeviceResponse> devices, Map<UUID, String> names, Instant now) {
		List<FactDoc> docs = new ArrayList<>();
		LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
		Instant from = today.minusDays(Math.max(1, rag.historyDays()) - 1L).atStartOfDay().toInstant(ZoneOffset.UTC);
		Instant dayAgo = now.minus(24, ChronoUnit.HOURS);
		for (DeviceResponse d : devices) {
			String name = names.get(d.id());
			// Samples are newest first and capped, so a very busy device is summarised from its most recent readings.
			List<SensorSample> rows = samples.findSensorSamples(d.id(), from, now.plusSeconds(1), PageRequest.of(0, MAX_SAMPLES_PER_DEVICE));
			List<MotionSample> motion = samples.findMotionSamples(d.id(), from, PageRequest.of(0, MAX_SAMPLES_PER_DEVICE));

			Map<LocalDate, List<SensorSample>> byDay = new TreeMap<>();
			List<SensorSample> last24 = new ArrayList<>();
			for (SensorSample s : rows) {
				byDay.computeIfAbsent(s.at().atZone(ZoneOffset.UTC).toLocalDate(), k -> new ArrayList<>()).add(s);
				if (!s.at().isBefore(dayAgo)) {
					last24.add(s);
				}
			}
			Map<LocalDate, int[]> motionByDay = new HashMap<>();   // [with motion, total]
			int[] motion24 = new int[2];
			for (MotionSample m : motion) {
				int[] c = motionByDay.computeIfAbsent(m.at().atZone(ZoneOffset.UTC).toLocalDate(), k -> new int[2]);
				c[1]++;
				if (m.motion()) {
					c[0]++;
				}
				if (!m.at().isBefore(dayAgo)) {
					motion24[1]++;
					if (m.motion()) {
						motion24[0]++;
					}
				}
			}

			docs.add(new FactDoc(SENSOR_HISTORY, "history:" + d.id() + ":last24h", d.id(), "Last 24 hours of readings for device " + name,
					summary("Sensor summary for device \"" + name + "\" over the last 24 hours (up to " + now + ")", last24, motion24), now));
			for (Map.Entry<LocalDate, List<SensorSample>> e : byDay.entrySet()) {
				boolean partial = e.getKey().equals(today);
				String label = e.getKey() + " (UTC" + (partial ? ", today so far" : "") + ")";
				docs.add(new FactDoc(SENSOR_HISTORY, "history:" + d.id() + ":" + e.getKey(), d.id(), "Readings for device " + name + " on " + e.getKey(),
						summary("Sensor summary for device \"" + name + "\" on " + label, e.getValue(), motionByDay.getOrDefault(e.getKey(), new int[2])),
						partial ? now : e.getKey().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
			}
		}
		return docs;
	}

	/** Min/average/max of the values that exist. A sensor with no values is reported as having none, never as 0. */
	private static String summary(String heading, List<SensorSample> rows, int[] motion) {
		StringBuilder sb = new StringBuilder(heading).append(": ");
		if (rows.isEmpty() && motion[1] == 0) {
			return sb.append("no readings were stored in this period.").toString();
		}
		sb.append(rows.size()).append(" readings. ");
		stats(sb, "temperature", "C", rows.stream().map(SensorSample::temperature).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).toArray(), 1);
		stats(sb, "humidity", "%", rows.stream().map(SensorSample::humidity).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue).toArray(), 1);
		stats(sb, "gas raw", "(raw sensor value, not ppm)", rows.stream().map(SensorSample::gas).filter(java.util.Objects::nonNull).mapToDouble(Integer::doubleValue).toArray(), 0);
		if (motion[1] == 0) {
			sb.append("No motion values. ");
		} else {
			sb.append("Motion was detected in ").append(motion[0]).append(" of ").append(motion[1]).append(" motion readings.");
		}
		return sb.toString().trim();
	}

	private static void stats(StringBuilder sb, String what, String unit, double[] v, int decimals) {
		if (v.length == 0) {
			sb.append("No ").append(what).append(" values. ");
			return;
		}
		double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
		for (double x : v) {
			min = Math.min(min, x);
			max = Math.max(max, x);
			sum += x;
		}
		String f = "%." + decimals + "f";
		sb.append(String.format(Locale.ROOT, "%s min " + f + ", average " + f + ", max " + f + " %s (%d values). ", what, min, sum / v.length, max, unit, v.length));
	}

	// ---- anomalies -----------------------------------------------------------------------------

	private List<FactDoc> anomalyFacts(UUID ownerId, List<DeviceResponse> devices, Map<UUID, String> names, Instant now) {
		List<FactDoc> docs = new ArrayList<>();
		for (DeviceResponse d : devices) {
			String name = names.get(d.id());
			List<AnomalyResponse> list = anomalyService.list(ownerId, d.id(), false, 15);
			if (list.isEmpty()) {
				docs.add(new FactDoc(ANOMALY, "anomaly:none:" + d.id(), d.id(), "Anomalies for device " + name,
						"No anomalies have been recorded for device \"" + name + "\".", now));
				continue;
			}
			for (AnomalyResponse a : list) {
				StringBuilder sb = new StringBuilder("Anomaly on device \"").append(name).append("\": ").append(a.severity()).append(' ').append(a.kind())
						.append(" on ").append(Text.oneLine(a.scope(), 30)).append(", status ").append(a.status()).append(". First seen ").append(a.detectedAt())
						.append(", last seen ").append(a.lastSeenAt()).append(", occurrences ").append(a.occurrences()).append(". Explanation: ")
						.append(Text.oneLine(a.explanation(), 600));
				if (a.resolvedAt() != null) {
					sb.append(" Closed ").append(a.resolvedAt()).append('.');
				}
				if (a.resolutionNote() != null && !a.resolutionNote().isBlank()) {
					sb.append(" Owner's note: ").append(Text.oneLine(a.resolutionNote(), 200));
				}
				docs.add(new FactDoc(ANOMALY, "anomaly:" + a.id(), d.id(), "Anomaly on device " + name + " (" + a.kind() + ")", sb.toString(), a.lastSeenAt()));
			}
		}
		return docs;
	}

	// ---- automation ----------------------------------------------------------------------------

	private List<FactDoc> automationFacts(UUID ownerId, Map<UUID, String> names, Instant now) {
		List<FactDoc> docs = new ArrayList<>();
		List<RuleResponse> rules = automationService.list(ownerId);
		if (rules.isEmpty()) {
			docs.add(new FactDoc(AUTOMATION, "automation:none", null, "Automation rules", "You have no automation rules.", now));
		}
		for (RuleResponse r : rules) {
			String ruleName = Text.oneLine(r.name(), NAME);
			StringBuilder sb = new StringBuilder("Automation rule \"").append(ruleName).append("\" (id ").append(r.id()).append(", ")
					.append(r.enabled() ? "enabled" : "DISABLED").append("). ");
			if ("SCHEDULE".equals(r.trigger().type())) {
				sb.append("Runs at ").append(r.trigger().time()).append(' ').append(Text.oneLine(r.trigger().timeZone(), 40)).append(" on ")
						.append(String.join(", ", r.trigger().days())).append(". ");
			} else {
				sb.append("Runs on each new reading from device \"").append(names.getOrDefault(r.deviceId(), "unknown")).append("\" (id ").append(r.deviceId()).append("). ");
			}
			if (!r.conditions().isEmpty()) {
				List<String> parts = new ArrayList<>();
				for (ConditionResponse c : r.conditions()) {
					parts.add(c.sensor() + " " + c.operator() + " " + c.value());
				}
				sb.append("Condition: ").append(String.join(" " + r.logic() + " ", parts)).append(". ");
			}
			sb.append("Action: ").append(r.action().action()).append(" = ").append(r.action().value()).append(" on device \"")
					.append(names.getOrDefault(r.targetDeviceId(), "unknown")).append("\" (id ").append(r.targetDeviceId()).append("). Priority ")
					.append(r.priority()).append(", cooldown ").append(r.cooldownSeconds()).append(" s.");
			if (r.lastExecutedAt() != null) {
				sb.append(" Last ran ").append(r.lastExecutedAt()).append('.');
			}
			docs.add(new FactDoc(AUTOMATION, "rule:" + r.id(), r.deviceId(), "Automation rule " + ruleName, sb.toString(), r.updatedAt()));
		}
		List<ExecutionResponse> runs = automationService.history(ownerId, 15);
		StringBuilder act = new StringBuilder("Recent automation activity: ");
		if (runs.isEmpty()) {
			act.append("no automation rule has run yet.");
		} else {
			List<String> lines = new ArrayList<>();
			for (ExecutionResponse e : runs) {
				lines.add("rule \"" + Text.oneLine(e.ruleName(), NAME) + "\" at " + e.lastAt() + ": " + e.outcome()
						+ (e.reason() == null ? "" : " (" + Text.oneLine(e.reason(), 120) + ")")
						+ (e.commandStatus() == null ? "" : ", command " + e.commandStatus()));
			}
			act.append(String.join("; ", lines)).append('.');
		}
		docs.add(new FactDoc(AUTOMATION, "automation:activity", null, "Recent automation activity", act.toString(), now));
		return docs;
	}

	// ---- ML ------------------------------------------------------------------------------------

	private List<FactDoc> mlFacts(UUID ownerId, List<DeviceResponse> devices, Map<UUID, String> names, Instant now) {
		List<FactDoc> docs = new ArrayList<>();
		for (DeviceResponse d : devices) {
			String name = names.get(d.id());
			OccupancyStatusResponse o = occupancyService.status(ownerId, d.id());
			StringBuilder sb = new StringBuilder("Occupancy prediction model for device \"").append(name).append("\": state ").append(o.state())
					.append(". ").append(Text.oneLine(o.message(), 400));
			MetricsResponse m = o.metrics();
			if (m != null) {
				sb.append(String.format(Locale.ROOT, " Hold-out accuracy %.1f%% versus %.1f%% for always guessing the average; AUC %s; evaluated on %d unseen slots.",
						m.accuracy() * 100, m.baselineAccuracy() * 100, m.auc() == null ? "undefined" : String.format(Locale.ROOT, "%.2f", m.auc()), m.testSlots()));
			} else {
				sb.append(" There are no accuracy figures because no model has been evaluated.");
			}
			docs.add(new FactDoc(ML_MODEL, "ml:" + d.id(), d.id(), "Occupancy model of device " + name, sb.toString(), o.trainedAt() == null ? now : o.trainedAt()));
		}
		return docs;
	}

	// ---- formatting ----------------------------------------------------------------------------

	private static String num(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	private static String age(Long seconds) {
		return seconds == null ? "at an unknown time" : age(seconds.longValue());
	}

	private static String age(long seconds) {
		if (seconds < 90) {
			return seconds + " seconds ago";
		}
		Duration d = Duration.ofSeconds(seconds);
		if (d.toHours() < 2) {
			return d.toMinutes() + " minutes ago";
		}
		return d.toHours() < 48 ? d.toHours() + " hours ago" : d.toDays() + " days ago";
	}
}
