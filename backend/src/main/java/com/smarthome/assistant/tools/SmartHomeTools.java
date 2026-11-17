package com.smarthome.assistant.tools;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.smarthome.analytics.AnalyticsRepository;
import com.smarthome.analytics.AnalyticsRepository.AnomalyRow;
import com.smarthome.analytics.AnalyticsRepository.CommandRow;
import com.smarthome.analytics.AnalyticsRepository.ConnectivityRow;
import com.smarthome.analytics.AnalyticsRepository.ExecutionRow;
import com.smarthome.analytics.AnalyticsRepository.GasAlertRow;
import com.smarthome.analytics.AnalyticsRepository.GasIncidentRow;
import com.smarthome.analytics.AnalyticsRepository.SecurityRow;
import com.smarthome.analytics.Metric;
import com.smarthome.analytics.SensorAnalytics;
import com.smarthome.analytics.SensorAnalytics.Comparison;
import com.smarthome.analytics.SensorAnalytics.DeviceStats;
import com.smarthome.analytics.SensorAnalytics.MetricStats;
import com.smarthome.analytics.SensorAnalytics.MotionActivity;
import com.smarthome.analytics.SensorAnalytics.Point;
import com.smarthome.analytics.SensorAnalytics.Series;
import com.smarthome.analytics.SensorAnalytics.Summary;
import com.smarthome.analytics.TimeRange;
import com.smarthome.analytics.Visual;
import com.smarthome.analytics.Visual.VisualSeries;
import com.smarthome.assistant.Text;
import com.smarthome.assistant.tools.ToolRegistry.Tool;
import com.smarthome.assistant.tools.ToolTypes.Focus;
import com.smarthome.assistant.tools.ToolTypes.Param;
import com.smarthome.assistant.tools.ToolTypes.PendingAction;
import com.smarthome.assistant.tools.ToolTypes.ToolArgumentException;
import com.smarthome.assistant.tools.ToolTypes.ToolContext;
import com.smarthome.assistant.tools.ToolTypes.ToolOutput;
import com.smarthome.command.CommandActions;
import com.smarthome.device.DeviceDtos.DeviceResponse;
import com.smarthome.device.DeviceDtos.TelemetryResponse;
import com.smarthome.device.DeviceService;
import com.smarthome.health.SystemStatusDtos.Issue;
import com.smarthome.health.SystemStatusDtos.SystemStatus;
import com.smarthome.health.SystemStatusService;
import com.smarthome.report.ReportDtos.ReportDetail;
import com.smarthome.report.ReportService;

/**
 * The smart-home tools. Numbers come from SQL aggregates over the caller's own rows (see {@link SensorAnalytics} and
 * {@link AnalyticsRepository}); text is written for both the model and a person, with units and the exact period.
 */
@Component
public class SmartHomeTools {

	private static final List<String> PERIODS = TimeRange.PRESETS;
	private static final Param PERIOD = Param.choice("period", "Time period. Use custom with from/to for specific dates.", PERIODS, false);
	private static final Param FROM = Param.date("from", "First day (inclusive) of a custom period.");
	private static final Param TO = Param.date("to", "Last day (inclusive) of a custom period.");
	private static final Param DEVICE = Param.string("device", "Device name (or id) of one of the user's devices. Omit for all devices.", false);
	private static final Param METRIC = Param.choice("metric", "Sensor: temperature, humidity or gas (raw MQ-2 value).", List.of("temperature", "humidity", "gas"), false);

	private final SensorAnalytics analytics;
	private final AnalyticsRepository repo;
	private final DeviceService devices;
	private final SystemStatusService systemStatus;
	private final ReportService reports;
	private final KnowledgeSearch knowledge;

	public SmartHomeTools(SensorAnalytics analytics, AnalyticsRepository repo, DeviceService devices, SystemStatusService systemStatus,
			ReportService reports, KnowledgeSearch knowledge) {
		this.analytics = analytics;
		this.repo = repo;
		this.devices = devices;
		this.systemStatus = systemStatus;
		this.reports = reports;
		this.knowledge = knowledge;
	}

	/** Searching documents and help text needs the retriever, which lives in the assistant package. */
	public interface KnowledgeSearch {
		/** Plain-text passages with their titles, best first. */
		List<String[]> search(UUID owner, String query, int limit);
	}

	public List<Tool> all() {
		return List.of(
				new Tool("get_current_readings", "Latest stored temperature, humidity, gas (raw) and motion of the user's devices, with how old each reading is.",
						List.of(DEVICE), this::currentReadings),
				new Tool("get_sensor_statistics", "Exact average, minimum and maximum of temperature, humidity and gas, motion counts and the number of readings "
						+ "over a period, computed by the database. Use for any question about statistics, highs, lows or averages.",
						List.of(PERIOD, FROM, TO, DEVICE, METRIC), this::statistics),
				new Tool("get_sensor_trend", "Hourly or daily averages of one sensor over a period, for trends and charts.",
						List.of(Param.choice("metric", "Sensor to chart.", List.of("temperature", "humidity", "gas"), true), PERIOD, FROM, TO, DEVICE,
								Param.choice("granularity", "hour, day or auto.", List.of("hour", "day", "auto"), false)), this::trend),
				new Tool("compare_periods", "Compare a sensor's average between two periods (e.g. today vs yesterday, this week vs last week). The difference is computed for you.",
						List.of(METRIC, PERIOD, FROM, TO, DEVICE,
								Param.choice("compare_with", "The other period; 'previous' = the matching period just before.",
										concat(List.of("previous"), PERIODS.stream().filter(p -> !p.equals("custom")).toList()), false)),
						this::compare),
				new Tool("get_motion_activity", "Motion/occupancy: how often motion was detected in a period, the busiest hour of the day and when motion was last seen.",
						List.of(PERIOD, FROM, TO, DEVICE), this::motion),
				new Tool("get_device_status", "Connectivity of the user's devices: online/offline, last contact, connection changes in a period and command results.",
						List.of(DEVICE, PERIOD, FROM, TO), this::deviceStatus),
				new Tool("get_anomalies", "Sensor anomalies detected by the system (outliers, stuck values, missing or stale data) in a period.",
						List.of(PERIOD, FROM, TO, DEVICE, Param.choice("status", "open = only unresolved ones.", List.of("open", "all"), false)), this::anomalies),
				new Tool("get_automation_history", "What the user's automation rules did in a period: runs, skips and failures.",
						List.of(PERIOD, FROM, TO, DEVICE), this::automation),
				new Tool("get_security_events", "Security events (motion alerts in Away/Night mode, offline devices, mode changes) and gas incidents in a period.",
						List.of(PERIOD, FROM, TO, DEVICE), this::security),
				new Tool("get_gas_incidents", "Exact stored gas incidents (gas alert records: when the raw MQ-2 value reached the alert threshold): "
						+ "start and recovery times, trigger/peak/last raw values, threshold and the total count. Use for 'last gas incident', "
						+ "'how many gas incidents', 'gas reading during the incident'. Omit period for all stored history.",
						List.of(PERIOD, FROM, TO, DEVICE, Param.integer("limit", "How many of the newest incidents to list (default 10).", 1, 20)),
						this::gasIncidents),
				new Tool("get_command_history", "Remote LED commands sent from the app or by automations (set_gas_led / set_pir_led), newest first, "
						+ "with the device's acknowledgement, plus the LED state the device last reported. Use for 'when was the gas LED last turned on'. "
						+ "Omit period for all stored history.",
						List.of(Param.choice("action", "Which output.", List.of(CommandActions.SET_GAS_LED, CommandActions.SET_PIR_LED), false),
								Param.choice("value", "Only commands that set this mode.", List.of("auto", "on", "off"), false), DEVICE, PERIOD, FROM, TO,
								Param.integer("limit", "How many commands to list (default 10).", 1, 20)),
						this::commandHistory),
				new Tool("get_system_health", "Overall health: database, MQTT broker, device availability, sensor freshness, command failures, ML and assistant status.",
						List.of(), this::health),
				new Tool("search_knowledge", "Search the app's help text and the user's own notes/documents for explanations (not for numbers).",
						List.of(Param.string("query", "What to look for.", true)), this::searchKnowledge),
				new Tool("create_report", "Generate and save a report (summary, statistics, events, recommendations) the user can preview and download as PDF.",
						List.of(Param.choice("kind", "Report period.", List.of("daily", "weekly", "monthly", "custom"), true), FROM, TO, DEVICE), this::createReport),
				new Tool("propose_device_command", "Prepare a device command for the user to CONFIRM in the app. It does not send anything. "
						+ "Allowed: set_pir_led auto|on|off, set_gas_led auto|on (the gas LED can never be turned off).",
						List.of(Param.string("device", "Device name or id.", true),
								Param.choice("action", "Output to change.", List.of(CommandActions.SET_PIR_LED, CommandActions.SET_GAS_LED), true),
								Param.choice("value", "New mode.", List.of("auto", "on", "off"), true)), this::proposeCommand));
	}

	private static List<String> concat(List<String> a, List<String> b) {
		List<String> out = new ArrayList<>(a);
		out.addAll(b);
		return out;
	}

	// ---- readings ----------------------------------------------------------------------------------

	private ToolOutput currentReadings(ToolContext ctx, ToolArgs a) {
		UUID dev = a.device(ctx, "device");
		List<DeviceResponse> list = devices.list(ctx.owner()).stream().filter(d -> dev == null || d.id().equals(dev)).toList();
		if (list.isEmpty()) {
			return ToolOutput.of("Current readings", "You have no devices paired, so there are no readings.", List.of(), new Focus(null, null, null, null, null));
		}
		StringBuilder sb = new StringBuilder("Latest stored readings (now is " + Fmt.time(ctx.now(), ctx.zone()) + "):\n");
		List<List<String>> rows = new ArrayList<>();
		for (DeviceResponse d : list) {
			String name = Text.oneLine(d.name(), 60);
			TelemetryResponse t = d.latestTelemetry();
			if (t == null) {
				sb.append("- ").append(name).append(" (").append(d.connection()).append("): no readings stored yet.\n");
				rows.add(List.of(name, d.connection().name(), "-", "-", "-", "-", "never"));
				continue;
			}
			sb.append("- ").append(name).append(" (").append(d.connection()).append("), received ").append(Fmt.age(t.ageSeconds())).append(" at ")
					.append(Fmt.time(t.receivedAt(), ctx.zone())).append(t.stale() ? " - STALE, not current" : "").append(": temperature ")
					.append(Metric.TEMPERATURE.format(t.temperatureC())).append(", humidity ").append(Metric.HUMIDITY.format(t.humidityPct()))
					.append(", gas ").append(t.gasRaw() == null ? "no value" : t.gasRaw() + " (raw MQ-2 value, not ppm; alert threshold " + analytics.gasThreshold() + ")")
					.append(", motion ").append(t.motion() == null ? "no value" : t.motion() ? "detected" : "not detected").append(".\n");
			rows.add(List.of(name, d.connection().name(), val(Metric.TEMPERATURE, t.temperatureC()), val(Metric.HUMIDITY, t.humidityPct()),
					t.gasRaw() == null ? "-" : t.gasRaw().toString(), t.motion() == null ? "-" : t.motion() ? "yes" : "no",
					Fmt.age(t.ageSeconds()) + (t.stale() ? " (stale)" : "")));
		}
		Visual table = Visual.table("Latest readings", List.of("Device", "Connection", "Temp (°C)", "Humidity (%)", "Gas (raw)", "Motion", "Received"), rows);
		return ToolOutput.of("Current readings", sb.toString().strip(), List.of(table), new Focus(null, "last_hour", null, null, dev == null ? null : ctx.devices().get(dev)));
	}

	private static String val(Metric m, Double v) {
		return v == null ? "-" : m.number(v);
	}

	private ToolOutput statistics(ToolContext ctx, ToolArgs a) {
		UUID dev = a.device(ctx, "device");
		TimeRange r = a.range(ctx, "last_24h");
		Metric only = a.metric("metric");
		Summary s = analytics.summary(ctx.owner(), dev, r, ctx.devices());
		StringBuilder sb = new StringBuilder("Sensor statistics for ").append(dev == null ? "all your devices" : "device " + ctx.devices().get(dev))
				.append(" over ").append(Fmt.period(r)).append(", computed by the database:\n");
		if (s.empty()) {
			sb.append("No readings were stored in this period.");
		} else {
			sb.append("Readings: ").append(s.overall().readings()).append(".\n");
			describe(sb, s.overall(), only, r.zone(), s.gasThreshold());
			if (s.devices().size() > 1) {
				sb.append("Per device:\n");
				for (DeviceStats d : s.devices()) {
					sb.append("* ").append(d.name()).append(": ");
					if (d.readings() == 0) {
						sb.append("no readings in this period.\n");
						continue;
					}
					List<String> parts = new ArrayList<>();
					parts.add(d.readings() + " readings");
					for (Metric m : Metric.values()) {
						MetricStats ms = d.of(m);
						if ((only == null || only == m) && ms.count() > 0) {
							parts.add(m.label() + " average " + m.format(ms.avg()) + " (min " + m.number(ms.min()) + ", max " + m.number(ms.max()) + ")");
						}
					}
					sb.append(String.join(", ", parts)).append(".\n");
				}
			}
		}
		List<List<String>> rows = new ArrayList<>();
		for (DeviceStats d : s.devices()) {
			for (Metric m : Metric.values()) {
				MetricStats ms = d.of(m);
				if (only == null || only == m) {
					rows.add(List.of(d.name(), m.label() + " (" + m.unit() + ")", ms.count() == 0 ? "-" : m.number(ms.avg()), ms.count() == 0 ? "-" : m.number(ms.min()),
							ms.count() == 0 ? "-" : m.number(ms.max()), Long.toString(ms.count())));
				}
			}
		}
		Visual table = Visual.table("Statistics, " + r.label(), List.of("Device", "Sensor", "Average", "Min", "Max", "Values"), rows);
		return ToolOutput.of("Sensor statistics, " + r.label(), sb.toString().strip(), s.empty() ? List.of() : List.of(table),
				focus(only, r, a, ctx, dev));
	}

	static void describe(StringBuilder sb, DeviceStats d, Metric only, ZoneId zone, int gasThreshold) {
		for (Metric m : Metric.values()) {
			if (only != null && only != m) {
				continue;
			}
			MetricStats ms = d.of(m);
			String name = m == Metric.GAS ? "Gas (raw MQ-2 value, not ppm)" : Character.toUpperCase(m.label().charAt(0)) + m.label().substring(1);
			if (ms.count() == 0) {
				sb.append("- ").append(name).append(": no values in this period.\n");
				continue;
			}
			sb.append("- ").append(name).append(": average ").append(m.format(ms.avg())).append(", minimum ").append(m.format(ms.min()))
					.append(Fmt.window(ms.minWindow(), zone)).append(", maximum ").append(m.format(ms.max())).append(Fmt.window(ms.maxWindow(), zone))
					.append(", from ").append(ms.count()).append(" values");
			if (m == Metric.GAS) {
				sb.append("; ").append(d.gasOverThreshold()).append(" readings at or above the alert threshold of ").append(gasThreshold);
			}
			sb.append(".\n");
		}
		if (only == null) {
			sb.append("- Motion: detected in ").append(d.motionDetected()).append(" of ").append(d.motionReadings()).append(" motion readings.\n");
		}
	}

	private Focus focus(Metric m, TimeRange r, ToolArgs a, ToolContext ctx, UUID dev) {
		return new Focus(m == null ? null : m.label(), r.preset(), a.date("from") == null ? null : a.date("from").toString(),
				a.date("to") == null ? null : a.date("to").toString(), dev == null ? null : ctx.devices().get(dev));
	}

	private ToolOutput trend(ToolContext ctx, ToolArgs a) {
		UUID dev = a.device(ctx, "device");
		TimeRange r = a.range(ctx, "last_24h");
		Metric m = a.metric("metric");
		Series s;
		try {
			s = analytics.series(ctx.owner(), dev, m, r, a.string("granularity"), ctx.devices());
		} catch (IllegalArgumentException e) {
			throw new ToolArgumentException(e.getMessage());
		}
		StringBuilder sb = new StringBuilder(Character.toUpperCase(m.label().charAt(0)) + m.label().substring(1)).append(" ")
				.append("hour".equals(s.granularity()) ? "hourly" : "daily").append(" averages for ").append(dev == null ? "all your devices" : "device " + ctx.devices().get(dev)).append(" over ").append(Fmt.period(r)).append(":\n");
		if (s.points().isEmpty()) {
			sb.append("No ").append(m.label()).append(" values were stored in this period.");
			return ToolOutput.of(m.label() + " trend", sb.toString(), List.of(), focus(m, r, a, ctx, dev));
		}
		List<String> labels = new ArrayList<>();
		List<Double> avg = new ArrayList<>();
		List<Double> min = new ArrayList<>();
		List<Double> max = new ArrayList<>();
		int shown = 0;
		for (Point p : s.points()) {
			labels.add(p.start().toString());
			avg.add(round(p.avg(), m));
			min.add(round(p.min(), m));
			max.add(round(p.max(), m));
			if (shown++ < 48) {
				sb.append("- ").append("hour".equals(s.granularity()) ? Fmt.hour(p.start(), r.zone()) : Fmt.day(p.start(), r.zone())).append(": average ")
						.append(m.number(p.avg())).append(", min ").append(m.number(p.min())).append(", max ").append(m.number(p.max())).append(" (")
						.append(p.count()).append(" values)\n");
			}
		}
		if (s.points().size() > 48) {
			sb.append("(").append(s.points().size() - 48).append(" more points are in the chart.)\n");
		}
		Point first = s.points().get(0);
		Point last = s.points().get(s.points().size() - 1);
		if (s.points().size() >= 2) {
			double change = last.avg() - first.avg();
			sb.append("From the first to the last point the average changed by ").append(Fmt.signed(change, m.decimals())).append(" ").append(m.unit()).append(".");
		}
		Visual chart = Visual.line(Character.toUpperCase(m.label().charAt(0)) + m.label().substring(1) + ", " + r.label(), m.unit(), labels,
				List.of(new VisualSeries("average", avg), new VisualSeries("min", min), new VisualSeries("max", max)));
		return ToolOutput.of(m.label() + " trend, " + r.label(), sb.toString().strip(), List.of(chart), focus(m, r, a, ctx, dev));
	}

	private static Double round(Double v, Metric m) {
		if (v == null) {
			return null;
		}
		double f = Math.pow(10, m.decimals());
		return Math.round(v * f) / f;
	}

	private ToolOutput compare(ToolContext ctx, ToolArgs a) {
		UUID dev = a.device(ctx, "device");
		TimeRange current = a.range(ctx, "today");
		String with = a.has("compare_with") ? a.string("compare_with") : "previous";
		TimeRange previous;
		try {
			previous = "previous".equals(with) ? current.previous() : TimeRange.resolve(with, null, null, ctx.zone(), ctx.now());
		} catch (IllegalArgumentException e) {
			throw new ToolArgumentException(e.getMessage());
		}
		Metric only = a.metric("metric");
		List<Metric> metrics = only == null ? List.of(Metric.values()) : List.of(only);
		StringBuilder sb = new StringBuilder("Comparison for ").append(dev == null ? "all your devices" : "device " + ctx.devices().get(dev)).append(":\n")
				.append("Period A: ").append(Fmt.period(current)).append(".\nPeriod B: ").append(Fmt.period(previous)).append(".\n");
		List<List<String>> rows = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		List<Double> av = new ArrayList<>();
		List<Double> bv = new ArrayList<>();
		for (Metric m : metrics) {
			Comparison c = analytics.compare(ctx.owner(), dev, m, current, previous, ctx.devices());
			sb.append("- ").append(m.label()).append(": average ").append(m.format(c.now().avg())).append(" in A (").append(c.now().count())
					.append(" values) vs ").append(m.format(c.before().avg())).append(" in B (").append(c.before().count()).append(" values)");
			if (c.difference() != null) {
				String dir = Math.abs(c.difference()) < Math.pow(10, -m.decimals()) / 2 ? "no change" : c.difference() > 0 ? "higher in A" : "lower in A";
				sb.append(": difference ").append(Fmt.signed(c.difference(), m.decimals())).append(" ").append(m.unit()).append(" (").append(dir);
				if (c.percent() != null) {
					sb.append(", ").append(Fmt.signed(c.percent(), 1)).append("%");
				}
				sb.append(")");
			} else {
				sb.append(": cannot compare, one period has no values");
			}
			sb.append(".\n");
			rows.add(List.of(m.label() + " (" + m.unit() + ")", c.now().avg() == null ? "-" : m.number(c.now().avg()),
					c.before().avg() == null ? "-" : m.number(c.before().avg()), c.difference() == null ? "-" : Fmt.signed(c.difference(), m.decimals())));
			if (only != null) {
				labels.add(m.label());
			}
			av.add(round(c.now().avg(), m));
			bv.add(round(c.before().avg(), m));
		}
		List<Visual> visuals = new ArrayList<>();
		visuals.add(Visual.table("Comparison", List.of("Sensor", "A: " + current.label(), "B: " + previous.label(), "Difference (A - B)"), rows));
		if (only != null) {
			visuals.add(Visual.bar(only.label() + " average", only.unit(), List.of("A: " + current.label(), "B: " + previous.label()),
					List.of(new VisualSeries("average", java.util.Arrays.asList(av.get(0), bv.get(0))))));
		}
		return ToolOutput.of("Comparison", sb.toString().strip(), visuals, focus(only, current, a, ctx, dev));
	}

	// ---- occupancy and devices ---------------------------------------------------------------------

	private ToolOutput motion(ToolContext ctx, ToolArgs a) {
		UUID dev = a.device(ctx, "device");
		TimeRange r = a.range(ctx, "today");
		MotionActivity m = analytics.motion(ctx.owner(), dev, r, ctx.devices());
		StringBuilder sb = new StringBuilder("Motion activity for ").append(dev == null ? "all your devices" : "device " + ctx.devices().get(dev)).append(" over ")
				.append(Fmt.period(r)).append(":\n");
		if (m.readings() == 0) {
			sb.append("No motion readings were stored in this period.\n");
		} else {
			sb.append("Motion was detected in ").append(m.detected()).append(" of ").append(m.readings()).append(" motion readings, in ")
					.append(m.activeWindows()).append(" separate 15-minute windows.\n");
			if (m.busiestHour() != null) {
				sb.append("Busiest hour of the day: ").append(String.format(Locale.ROOT, "%02d:00-%02d:00", m.busiestHour(), (m.busiestHour() + 1) % 24))
						.append(" with ").append(m.detectedByLocalHour()[m.busiestHour()]).append(" detections.\n");
			}
		}
		sb.append("Motion was last detected: ").append(m.lastMotionEver() == null ? "never" : Fmt.time(m.lastMotionEver(), r.zone())).append('.');
		List<String> labels = new ArrayList<>();
		List<Double> counts = new ArrayList<>();
		for (int h = 0; h < 24; h++) {
			labels.add(String.format(Locale.ROOT, "%02d", h));
			counts.add((double) m.detectedByLocalHour()[h]);
		}
		List<Visual> v = m.detected() == 0 ? List.of() : List.of(Visual.bar("Motion detections by hour of day, " + r.label(), "detections", labels,
				List.of(new VisualSeries("detections", counts))));
		return ToolOutput.of("Motion activity", sb.toString().strip(), v, new Focus("motion", r.preset(), null, null, dev == null ? null : ctx.devices().get(dev)));
	}

	private ToolOutput deviceStatus(ToolContext ctx, ToolArgs a) {
		UUID dev = a.device(ctx, "device");
		TimeRange r = a.range(ctx, "last_7_days");
		List<DeviceResponse> list = devices.list(ctx.owner()).stream().filter(d -> dev == null || d.id().equals(dev)).toList();
		if (list.isEmpty()) {
			return ToolOutput.of("Device status", "You have no devices paired.", List.of(), Focus.EMPTY);
		}
		StringBuilder sb = new StringBuilder("Device status (now is " + Fmt.time(ctx.now(), ctx.zone()) + "):\n");
		List<List<String>> rows = new ArrayList<>();
		List<ConnectivityRow> changes = repo.connectivity(ctx.owner(), dev, r.from(), r.to(), 100);
		for (DeviceResponse d : list) {
			String name = Text.oneLine(d.name(), 60);
			long offline = changes.stream().filter(c -> c.device().equals(d.id()) && "OFFLINE".equals(c.state())).count();
			sb.append("- ").append(name).append(": ").append(d.connection()).append(", last heard from ")
					.append(d.lastSeenAt() == null ? "never" : Fmt.age(d.lastSeenAgeSeconds()) + " (" + Fmt.time(d.lastSeenAt(), ctx.zone()) + ")")
					.append("; went offline ").append(offline).append(" times during ").append(r.label());
			if (d.reportedState() != null) {
				sb.append("; gas warning on the device ").append(d.reportedState().gasWarning() ? "ACTIVE" : "not active");
			}
			sb.append(".\n");
			rows.add(List.of(name, d.connection().name(), d.lastSeenAt() == null ? "never" : Fmt.time(d.lastSeenAt(), ctx.zone()), Long.toString(offline)));
		}
		Map<String, Long> cmds = repo.commandCounts(ctx.owner(), dev, r.from(), r.to());
		if (!cmds.isEmpty()) {
			sb.append("Commands sent during ").append(r.label()).append(": ").append(join(cmds)).append(".\n");
		}
		if (!changes.isEmpty()) {
			sb.append("Most recent connection changes: ");
			List<String> parts = new ArrayList<>();
			changes.stream().limit(8).forEach(c -> parts.add(Text.oneLine(c.deviceName(), 40) + " " + c.state() + " at " + Fmt.time(c.at(), ctx.zone())
					+ " (" + Text.oneLine(c.reason(), 40) + ")"));
			sb.append(String.join("; ", parts)).append('.');
		}
		Visual table = Visual.table("Devices", List.of("Device", "Connection", "Last contact", "Offline events (" + r.label() + ")"), rows);
		return ToolOutput.of("Device status", sb.toString().strip(), List.of(table), new Focus(null, r.preset(), null, null, dev == null ? null : ctx.devices().get(dev)));
	}

	private static String join(Map<String, Long> counts) {
		List<String> parts = new ArrayList<>();
		counts.forEach((k, v) -> parts.add(v + " " + Text.oneLine(k, 30).toLowerCase(Locale.ROOT)));
		return String.join(", ", parts);
	}

	// ---- events ------------------------------------------------------------------------------------

	private ToolOutput anomalies(ToolContext ctx, ToolArgs a) {
		UUID dev = a.device(ctx, "device");
		TimeRange r = a.range(ctx, "last_7_days");
		boolean openOnly = "open".equals(a.string("status"));
		List<AnomalyRow> rows = repo.anomalies(ctx.owner(), dev, r.from(), r.to(), 50).stream()
				.filter(x -> !openOnly || "OPEN".equals(x.status()) || "ACKNOWLEDGED".equals(x.status())).toList();
		StringBuilder sb = new StringBuilder("Anomalies").append(openOnly ? " (unresolved only)" : "").append(" active during ").append(Fmt.period(r)).append(": ");
		if (rows.isEmpty()) {
			sb.append("none were recorded.");
			return ToolOutput.of("Anomalies", sb.toString(), List.of(), new Focus(null, r.preset(), null, null, null));
		}
		sb.append(rows.size()).append(".\n");
		List<List<String>> table = new ArrayList<>();
		for (AnomalyRow x : rows) {
			sb.append("- ").append(x.severity()).append(" ").append(x.kind()).append(" on ").append(x.scope()).append(" of ").append(Text.oneLine(x.deviceName(), 40))
					.append(", status ").append(x.status()).append(", first seen ").append(Fmt.time(x.detectedAt(), r.zone())).append(", last seen ")
					.append(Fmt.time(x.lastSeenAt(), r.zone())).append(", ").append(x.occurrences()).append(" occurrences. ")
					.append(Text.oneLine(x.explanation(), 300)).append('\n');
			table.add(List.of(Text.oneLine(x.deviceName(), 40), x.kind(), x.scope(), x.severity(), x.status(), Fmt.time(x.lastSeenAt(), r.zone())));
		}
		return ToolOutput.of("Anomalies", sb.toString().strip(),
				List.of(Visual.table("Anomalies, " + r.label(), List.of("Device", "Kind", "Sensor", "Severity", "Status", "Last seen"), table)),
				new Focus(null, r.preset(), null, null, dev == null ? null : ctx.devices().get(dev)));
	}

	private ToolOutput automation(ToolContext ctx, ToolArgs a) {
		UUID dev = a.device(ctx, "device");
		TimeRange r = a.range(ctx, "last_7_days");
		Map<String, Long> outcomes = repo.automationOutcomes(ctx.owner(), dev, r.from(), r.to());
		List<ExecutionRow> runs = repo.automationRuns(ctx.owner(), dev, r.from(), r.to(), 20);
		StringBuilder sb = new StringBuilder("Automation activity during ").append(Fmt.period(r)).append(": ");
		if (outcomes.isEmpty()) {
			sb.append("no rule was evaluated with a recorded outcome.");
			return ToolOutput.of("Automation history", sb.toString(), List.of(), new Focus(null, r.preset(), null, null, null));
		}
		sb.append(join(outcomes)).append(".\nMost recent:\n");
		List<List<String>> table = new ArrayList<>();
		for (ExecutionRow e : runs) {
			sb.append("- rule \"").append(Text.oneLine(e.ruleName(), 60)).append("\" ").append(e.outcome()).append(" at ").append(Fmt.time(e.at(), r.zone()))
					.append(" (").append(e.action()).append(" = ").append(e.value()).append(e.repeats() > 1 ? ", repeated " + e.repeats() + " times" : "")
					.append(e.reason() == null ? "" : ", " + Text.oneLine(e.reason(), 120)).append(").\n");
			table.add(List.of(Text.oneLine(e.ruleName(), 60), e.outcome(), e.action() + " = " + e.value(), Fmt.time(e.at(), r.zone()), Integer.toString(e.repeats())));
		}
		return ToolOutput.of("Automation history", sb.toString().strip(),
				List.of(Visual.table("Automation runs, " + r.label(), List.of("Rule", "Outcome", "Action", "Last at", "Times"), table)),
				new Focus(null, r.preset(), null, null, dev == null ? null : ctx.devices().get(dev)));
	}

	private ToolOutput security(ToolContext ctx, ToolArgs a) {
		UUID dev = a.device(ctx, "device");
		TimeRange r = a.range(ctx, "last_7_days");
		Map<String, Long> counts = repo.securityCounts(ctx.owner(), dev, r.from(), r.to());
		List<SecurityRow> events = repo.securityEvents(ctx.owner(), dev, r.from(), r.to(), 15);
		List<GasAlertRow> gas = repo.gasAlerts(ctx.owner(), dev, r.from(), r.to(), 20);
		StringBuilder sb = new StringBuilder("Security and safety during ").append(Fmt.period(r)).append(":\n");
		sb.append("Security events: ").append(counts.isEmpty() ? "none" : join(counts)).append(".\n");
		for (SecurityRow e : events) {
			sb.append("- ").append(e.severity()).append(" ").append(e.kind()).append(" (").append(e.mode()).append(" mode) at ").append(Fmt.time(e.at(), r.zone()))
					.append(": ").append(Text.oneLine(e.message(), 160)).append(e.acknowledged() ? " [acknowledged]" : "").append('\n');
		}
		sb.append("Gas incidents (raw MQ-2 value at or above the alert threshold): ").append(gas.isEmpty() ? "none" : Integer.toString(gas.size())).append(".\n");
		for (GasAlertRow g : gas) {
			sb.append("- ").append(Text.oneLine(g.deviceName(), 40)).append(": ").append(g.state()).append(", started ").append(Fmt.time(g.startedAt(), r.zone()))
					.append(", peak ").append(g.peak()).append(" (threshold ").append(g.threshold()).append(")")
					.append(g.recoveredAt() == null ? ", not yet recovered" : ", recovered " + Fmt.time(g.recoveredAt(), r.zone())).append(".\n");
		}
		List<List<String>> rows = new ArrayList<>();
		events.forEach(e -> rows.add(List.of(Fmt.time(e.at(), r.zone()), e.kind(), e.severity(), Text.oneLine(e.message(), 80))));
		gas.forEach(g -> rows.add(List.of(Fmt.time(g.startedAt(), r.zone()), "GAS_" + g.state(), "CRITICAL",
				Text.oneLine(g.deviceName(), 40) + ": peak " + g.peak() + " raw")));
		List<Visual> v = rows.isEmpty() ? List.of() : List.of(Visual.table("Security and gas events, " + r.label(), List.of("Time", "Event", "Severity", "Details"), rows));
		return ToolOutput.of("Security events", sb.toString().strip(), v, new Focus(null, r.preset(), null, null, dev == null ? null : ctx.devices().get(dev)));
	}

	/** An explicit period, or null = all stored history. */
	private static TimeRange optionalRange(ToolContext ctx, ToolArgs a) {
		return a.has("period") || a.has("from") ? a.range(ctx, "last_7_days") : null;
	}

	private static String scope(ToolContext ctx, UUID dev) {
		return dev == null ? "all your devices" : "device " + Text.oneLine(ctx.devices().get(dev), 60);
	}

	private static String duration(Instant from, Instant to) {
		long s = Math.max(0, Duration.between(from, to).getSeconds());
		return s < 90 ? s + " s" : s < 7200 ? (s + 30) / 60 + " min" : String.format(Locale.ROOT, "%.1f h", s / 3600.0);
	}

	private ToolOutput gasIncidents(ToolContext ctx, ToolArgs a) {
		UUID dev = a.device(ctx, "device");
		TimeRange r = optionalRange(ctx, a);
		ZoneId zone = ctx.zone();
		int limit = a.integer("limit") == null ? 10 : a.integer("limit");
		Instant from = r == null ? null : r.from();
		Instant to = r == null ? null : r.to();
		long total = repo.countGasIncidents(ctx.owner(), dev, from, to);
		List<GasIncidentRow> rows = repo.gasIncidents(ctx.owner(), dev, from, to, limit);
		Focus focus = new Focus("gas", r == null ? null : r.preset(), null, null, dev == null ? null : ctx.devices().get(dev));
		StringBuilder sb = new StringBuilder("Gas incidents for ").append(scope(ctx, dev)).append(' ')
				.append(r == null ? "in all stored history (times in " + zone.getId() + ")" : "that started during " + Fmt.period(r)).append(": ");
		if (total == 0) {
			sb.append("none. No gas incident is recorded").append(r == null ? "" : " in this period")
					.append(", so there is no incident time or incident reading to report.");
			return ToolOutput.of("Gas incidents", sb.toString(), List.of(), focus);
		}
		sb.append(total).append(" in total").append(total > rows.size() ? " (the newest " + rows.size() + " are listed)" : "").append(".\n");
		List<List<String>> table = new ArrayList<>();
		int i = 0;
		for (GasIncidentRow g : rows) {
			i++;
			boolean active = g.recoveredAt() == null;
			sb.append("- ").append(i == 1 ? "Most recent: " : "").append(Text.oneLine(g.deviceName(), 40)).append(", started ").append(Fmt.time(g.startedAt(), zone))
					.append(", reading when the alert triggered ").append(g.trigger()).append(" raw, peak reading ").append(g.peak()).append(" raw; ")
					.append(active ? "STILL ACTIVE, latest reading " + g.last() + " raw."
							: "recovered " + Fmt.time(g.recoveredAt(), zone) + " after " + duration(g.startedAt(), g.recoveredAt()) + ", reading at recovery "
									+ g.last() + " raw.")
					.append(" Settings: alert threshold ").append(g.threshold()).append(" raw, recovery threshold ").append(g.recoveryThreshold()).append(" raw.")
					.append('\n');
			table.add(List.of(Fmt.time(g.startedAt(), zone), Text.oneLine(g.deviceName(), 40), Integer.toString(g.trigger()), Integer.toString(g.peak()),
					active ? "active" : Fmt.time(g.recoveredAt(), zone), g.state()));
		}
		sb.append("Gas values are raw MQ-2 readings, not ppm. These are gas alert records only; security events (motion alerts, offline devices) "
				+ "and LED commands are stored separately.");
		return ToolOutput.of("Gas incidents", sb.toString(),
				List.of(Visual.table("Gas incidents" + (r == null ? "" : ", " + r.label()),
						List.of("Started", "Device", "Trigger (raw)", "Peak (raw)", "Recovered", "State"), table)),
				focus);
	}

	private ToolOutput commandHistory(ToolContext ctx, ToolArgs a) {
		UUID dev = a.device(ctx, "device");
		String action = a.string("action");
		String value = a.string("value");
		TimeRange r = optionalRange(ctx, a);
		ZoneId zone = ctx.zone();
		int limit = a.integer("limit") == null ? 10 : a.integer("limit");
		List<CommandRow> rows = repo.commands(ctx.owner(), dev, action, value, r == null ? null : r.from(), r == null ? null : r.to(), limit);
		String what = (action == null ? "LED" : CommandActions.SET_GAS_LED.equals(action) ? "gas LED" : "PIR LED") + " commands"
				+ (value == null ? "" : " that set it to " + value);
		StringBuilder sb = new StringBuilder("Remote ").append(what).append(" for ").append(scope(ctx, dev)).append(' ')
				.append(r == null ? "in all stored history (times in " + zone.getId() + ")" : "during " + Fmt.period(r)).append(": ");
		List<List<String>> table = new ArrayList<>();
		if (rows.isEmpty()) {
			sb.append("none recorded.\n");
		} else {
			sb.append(rows.size()).append(" listed, newest first.\n");
			int i = 0;
			for (CommandRow c : rows) {
				i++;
				sb.append("- ").append(i == 1 ? "Most recent: " : "").append(c.action()).append(" = ").append(c.value()).append(" to ")
						.append(Text.oneLine(c.deviceName(), 40)).append(", requested ").append(Fmt.time(c.createdAt(), zone)).append(", status ").append(c.status())
						.append(c.completedAt() == null ? "" : " at " + Fmt.time(c.completedAt(), zone))
						.append(c.origin() == null ? "" : ", from " + Text.oneLine(c.origin(), 60))
						.append(c.detail() == null ? "" : " (" + Text.oneLine(c.detail(), 80) + ")").append(".\n");
				table.add(List.of(Fmt.time(c.createdAt(), zone), Text.oneLine(c.deviceName(), 40), c.action() + " = " + c.value(), c.status()));
			}
		}
		for (DeviceResponse d : devices.list(ctx.owner())) {
			if ((dev != null && !d.id().equals(dev)) || d.reportedState() == null) {
				continue;
			}
			var s = d.reportedState();
			List<String> parts = new ArrayList<>();
			if (!CommandActions.SET_PIR_LED.equals(action)) {
				parts.add("gas LED mode " + s.gasLed().mode() + ", " + (s.gasLed().on() ? "lit" : "not lit"));
			}
			if (!CommandActions.SET_GAS_LED.equals(action)) {
				parts.add("PIR LED mode " + s.pirLed().mode() + ", " + (s.pirLed().on() ? "lit" : "not lit"));
			}
			sb.append("Last reported by ").append(Text.oneLine(d.name(), 40)).append(" at ").append(Fmt.time(s.reportedAt(), zone)).append(": ")
					.append(String.join("; ", parts)).append(".\n");
		}
		sb.append("Only commands are stored. In automatic mode the device switches its LEDs by itself (the gas LED lights during its own gas warning); "
				+ "those automatic on/off moments are not recorded, so they cannot be dated.");
		List<Visual> v = table.isEmpty() ? List.of() : List.of(Visual.table("LED commands", List.of("Requested", "Device", "Command", "Status"), table));
		return ToolOutput.of("LED command history", sb.toString(), v,
				new Focus(null, r == null ? null : r.preset(), null, null, dev == null ? null : ctx.devices().get(dev)));
	}

	private ToolOutput health(ToolContext ctx, ToolArgs a) {
		SystemStatus s = systemStatus.status(ctx.owner());
		StringBuilder sb = new StringBuilder("System health checked at ").append(Fmt.time(s.checkedAt(), ctx.zone())).append(": overall ").append(s.overall()).append(".\n");
		sb.append("Database: ").append(s.database().level()).append(". MQTT broker: ").append(s.mqtt().level()).append(" (").append(Text.oneLine(s.mqtt().detail(), 120)).append(").\n");
		if (s.issues().isEmpty()) {
			sb.append("No problems were found.\n");
		} else {
			sb.append("Problems:\n");
			for (Issue i : s.issues()) {
				sb.append("- ").append(i.level()).append(" (").append(i.area()).append("): ").append(Text.oneLine(i.message(), 200)).append('\n');
			}
		}
		if (s.commands() != null && s.commands().total() > 0) {
			sb.append("Commands in the last ").append(s.commands().windowHours()).append(" hours: ").append(s.commands().total())
					.append(s.commands().failureRate() == null ? "" : ", failure rate " + Fmt.pct(s.commands().failureRate() * 100)).append(".\n");
		}
		List<List<String>> rows = new ArrayList<>();
		s.devices().forEach(d -> rows.add(List.of(Text.oneLine(d.name(), 40), d.connection(), d.lastSeenAgeSeconds() == null ? "never" : Fmt.age(d.lastSeenAgeSeconds()))));
		List<Visual> v = rows.isEmpty() ? List.of() : List.of(Visual.table("Device availability", List.of("Device", "Connection", "Last contact"), rows));
		return ToolOutput.of("System health", sb.toString().strip(), v, Focus.EMPTY);
	}

	private ToolOutput searchKnowledge(ToolContext ctx, ToolArgs a) {
		List<String[]> hits = knowledge.search(ctx.owner(), a.string("query"), 4);
		if (hits.isEmpty()) {
			return ToolOutput.of("Help and notes", "Nothing in the help text or your notes matches that.", List.of(), Focus.EMPTY);
		}
		StringBuilder sb = new StringBuilder("Matching passages from the help text and your notes:\n");
		for (String[] h : hits) {
			sb.append("- ").append(h[0]).append(": ").append(Text.oneLine(h[1], 700)).append('\n');
		}
		return ToolOutput.of("Help and notes", sb.toString().strip(), List.of(), Focus.EMPTY);
	}

	private ToolOutput createReport(ToolContext ctx, ToolArgs a) {
		UUID dev = a.device(ctx, "device");
		ReportDetail r;
		try {
			r = reports.generate(ctx.owner(), a.string("kind"), a.date("from"), a.date("to") != null ? a.date("to") : a.date("from"), ctx.zone().getId(), dev);
		} catch (IllegalArgumentException | com.smarthome.report.ReportExceptions.TooManyReportsException e) {
			throw new ToolArgumentException(e.getMessage());     // e.g. the report limit: tell the user why, instead of "failed unexpectedly"
		} catch (com.smarthome.report.ReportExceptions.ReportUnavailableException e) {
			throw new ToolArgumentException("The report could not be saved right now (database unavailable). Do not guess its content.");
		}
		String text = "Created the report \"" + r.title() + "\". It is saved under Reports, where it can be previewed and downloaded as PDF. Summary: " + r.summary();
		return new ToolOutput("Report created", text, List.of(), Focus.EMPTY, null, r.id());
	}

	private ToolOutput proposeCommand(ToolContext ctx, ToolArgs a) {
		UUID dev = a.device(ctx, "device");
		String action = a.string("action");
		String value = a.string("value");
		CommandActions.Output out = CommandActions.find(action).orElseThrow(() -> new ToolArgumentException("Unknown action"));
		if (!out.values().contains(value)) {
			throw new ToolArgumentException(out.label() + " can only be set to " + String.join(", ", out.values())
					+ (CommandActions.SET_GAS_LED.equals(action) ? ". The gas warning LED can never be turned off, for safety." : "."));
		}
		String name = ctx.devices().get(dev);
		String desc = "Set the " + out.label() + " of " + name + " to " + value.toUpperCase(Locale.ROOT);
		PendingAction p = new PendingAction(dev, name, action, value, desc);
		String text = "Prepared, NOT sent: \"" + desc + "\". The user must confirm it in the app before anything is sent; tell them to press Confirm.";
		return new ToolOutput("Command proposal", text, List.of(), new Focus(null, null, null, null, name), p, null);
	}

}
