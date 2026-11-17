package com.smarthome.report;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.analytics.AnalyticsRepository;
import com.smarthome.analytics.AnalyticsRepository.AnomalyRow;
import com.smarthome.analytics.AnalyticsRepository.ConnectivityRow;
import com.smarthome.analytics.AnalyticsRepository.ExecutionRow;
import com.smarthome.analytics.AnalyticsRepository.GasAlertRow;
import com.smarthome.analytics.Metric;
import com.smarthome.analytics.SensorAnalytics;
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
import com.smarthome.assistant.tools.Fmt;
import com.smarthome.device.DeviceDtos.DeviceResponse;
import com.smarthome.report.ReportDtos.ReportContent;
import com.smarthome.report.ReportDtos.Section;

/**
 * Builds a report from the database. Everything in it is computed (SQL aggregates and fixed rules); no language model
 * writes any part of a report, so every number and every recommendation can be traced to stored data. Recommendations
 * state the number they rest on.
 */
@Component
public class ReportBuilder {

	/** General indoor-comfort guidance used by the recommendations (not a medical or safety standard). */
	static final double HUMID_HIGH = 60, HUMID_LOW = 30, TEMP_HIGH = 28, TEMP_LOW = 17;

	private final SensorAnalytics analytics;
	private final AnalyticsRepository repo;

	public ReportBuilder(SensorAnalytics analytics, AnalyticsRepository repo) {
		this.analytics = analytics;
		this.repo = repo;
	}

	@Transactional(readOnly = true)
	public ReportContent build(UUID owner, String title, TimeRange r, UUID deviceOrNull, List<DeviceResponse> devices, Map<UUID, String> names, Instant now) {
		ZoneId z = r.zone();
		Summary s = analytics.summary(owner, deviceOrNull, r, names);
		MotionActivity motion = analytics.motion(owner, deviceOrNull, r, names);
		List<AnomalyRow> anomalies = repo.anomalies(owner, deviceOrNull, r.from(), r.to(), 50);
		Map<String, Long> security = repo.securityCounts(owner, deviceOrNull, r.from(), r.to());
		List<GasAlertRow> gas = repo.gasAlerts(owner, deviceOrNull, r.from(), r.to(), 50);
		List<ConnectivityRow> conn = repo.connectivity(owner, deviceOrNull, r.from(), r.to(), 200);
		Map<String, Long> outcomes = repo.automationOutcomes(owner, deviceOrNull, r.from(), r.to());
		List<ExecutionRow> runs = repo.automationRuns(owner, deviceOrNull, r.from(), r.to(), 10);
		Map<String, Long> commands = repo.commandCounts(owner, deviceOrNull, r.from(), r.to());
		String granularity = SensorAnalytics.granularity("auto", r);
		DeviceStats all = s.overall();
		long offline = conn.stream().filter(c -> "OFFLINE".equals(c.state())).count();
		long openAnomalies = anomalies.stream().filter(a -> "OPEN".equals(a.status()) || "ACKNOWLEDGED".equals(a.status())).count();

		List<Section> sections = new ArrayList<>();

		// 1. Executive summary
		List<String> exec = new ArrayList<>();
		if (s.empty()) {
			exec.add("No sensor readings were stored for " + scope(deviceOrNull, names) + " during " + r.label()
					+ ". Statistics, trends and comfort analysis are therefore not available for this period.");
		} else {
			long reporting = s.devices().stream().filter(d -> d.readings() > 0).count();
			StringBuilder p = new StringBuilder();
			p.append(all.readings()).append(" readings were stored from ").append(reporting).append(reporting == 1 ? " device" : " devices").append(" during ")
					.append(r.label()).append(". ");
			MetricStats t = all.of(Metric.TEMPERATURE);
			MetricStats h = all.of(Metric.HUMIDITY);
			MetricStats g = all.of(Metric.GAS);
			if (t.count() > 0) {
				p.append("Temperature averaged ").append(Metric.TEMPERATURE.format(t.avg())).append(" (from ").append(Metric.TEMPERATURE.format(t.min()))
						.append(" to ").append(Metric.TEMPERATURE.format(t.max())).append("). ");
			}
			if (h.count() > 0) {
				p.append("Humidity averaged ").append(Metric.HUMIDITY.format(h.avg())).append(" (from ").append(Metric.HUMIDITY.format(h.min()))
						.append(" to ").append(Metric.HUMIDITY.format(h.max())).append("). ");
			}
			if (g.count() > 0) {
				p.append(all.gasOverThreshold() == 0 ? "Gas readings stayed below the alert threshold of " + s.gasThreshold() + " (peak " + Metric.GAS.number(g.max()) + " raw). "
						: all.gasOverThreshold() + " gas readings reached the alert threshold of " + s.gasThreshold() + " (peak " + Metric.GAS.number(g.max()) + " raw). ");
			}
			exec.add(p.toString().strip());
		}
		exec.add(String.format(Locale.ROOT, "Motion was detected in %d of %d motion readings. There were %d anomalies (%d still unresolved), %d security events, %d gas incidents and %d times a device went offline.",
				motion.detected(), motion.readings(), anomalies.size(), openAnomalies, security.values().stream().mapToLong(Long::longValue).sum(), gas.size(), offline));
		sections.add(new Section("Executive summary", exec, List.of(), List.of()));

		// 2. Sensor statistics
		List<List<String>> rows = new ArrayList<>();
		for (DeviceStats d : s.devices()) {
			for (Metric m : Metric.values()) {
				MetricStats ms = d.of(m);
				rows.add(List.of(Text.oneLine(d.name(), 40), m.label() + " (" + m.unit() + ")", ms.count() == 0 ? "-" : m.number(ms.avg()),
						ms.count() == 0 ? "-" : m.number(ms.min()), ms.count() == 0 ? "-" : m.number(ms.max()), Long.toString(ms.count())));
			}
		}
		sections.add(new Section("Sensor statistics", List.of("Average, minimum and maximum of every stored value in the period, per device."),
				List.of(), rows.isEmpty() ? List.of() : List.of(Visual.table("Statistics", List.of("Device", "Sensor", "Average", "Min", "Max", "Values"), rows))));

		// 3. Environmental analysis
		List<String> env = new ArrayList<>();
		List<Visual> envCharts = new ArrayList<>();
		for (Metric m : Metric.values()) {
			MetricStats ms = all.of(m);
			if (ms.count() == 0) {
				env.add(cap(m.label()) + ": no values in this period.");
				continue;
			}
			env.add(cap(m.label()) + ": lowest " + m.format(ms.min()) + Fmt.window(ms.minWindow(), z) + ", highest " + m.format(ms.max()) + Fmt.window(ms.maxWindow(), z) + ".");
			Series series = analytics.series(owner, deviceOrNull, m, r, granularity, names);
			if (series.points().size() >= 2) {
				List<String> labels = new ArrayList<>();
				List<Double> avg = new ArrayList<>();
				for (Point pt : series.points()) {
					labels.add(pt.start().toString());
					avg.add(Math.round(pt.avg() * Math.pow(10, m.decimals())) / Math.pow(10, m.decimals()));
				}
				envCharts.add(Visual.line(cap(m.label()) + ", " + ("hour".equals(granularity) ? "hourly" : "daily") + " average", m.unit(), labels,
						List.of(new VisualSeries("average", avg))));
			}
		}
		MetricStats hum = all.of(Metric.HUMIDITY);
		MetricStats temp = all.of(Metric.TEMPERATURE);
		if (temp.count() > 0) {
			env.add(temp.avg() > TEMP_HIGH ? "The average temperature was above the usual comfort range (about " + (int) TEMP_LOW + "-" + (int) TEMP_HIGH + " °C)."
					: temp.avg() < TEMP_LOW ? "The average temperature was below the usual comfort range (about " + (int) TEMP_LOW + "-" + (int) TEMP_HIGH + " °C)."
							: "The average temperature was within the usual comfort range (about " + (int) TEMP_LOW + "-" + (int) TEMP_HIGH + " °C).");
		}
		if (hum.count() > 0) {
			env.add(hum.avg() > HUMID_HIGH ? "Average humidity was above the usual comfort range (about " + (int) HUMID_LOW + "-" + (int) HUMID_HIGH + " %)."
					: hum.avg() < HUMID_LOW ? "Average humidity was below the usual comfort range (about " + (int) HUMID_LOW + "-" + (int) HUMID_HIGH + " %)."
							: "Average humidity was within the usual comfort range (about " + (int) HUMID_LOW + "-" + (int) HUMID_HIGH + " %).");
		}
		sections.add(new Section("Environmental analysis", List.of(), env, envCharts));

		// 4. Occupancy and security
		List<String> occ = new ArrayList<>();
		occ.add("Motion detected in " + motion.detected() + " of " + motion.readings() + " motion readings, in " + motion.activeWindows() + " separate 15-minute windows.");
		if (motion.busiestHour() != null) {
			occ.add(String.format(Locale.ROOT, "Busiest hour of the day: %02d:00-%02d:00 (%d detections).", motion.busiestHour(), (motion.busiestHour() + 1) % 24,
					motion.detectedByLocalHour()[motion.busiestHour()]));
		}
		occ.add("Security events: " + (security.isEmpty() ? "none" : join(security)) + ".");
		occ.add("Gas incidents: " + (gas.isEmpty() ? "none" : Integer.toString(gas.size())) + ".");
		List<Visual> occVisuals = new ArrayList<>();
		if (motion.detected() > 0) {
			List<String> hours = new ArrayList<>();
			List<Double> counts = new ArrayList<>();
			for (int i = 0; i < 24; i++) {
				hours.add(String.format(Locale.ROOT, "%02d", i));
				counts.add((double) motion.detectedByLocalHour()[i]);
			}
			occVisuals.add(Visual.bar("Motion detections by hour of day", "detections", hours, List.of(new VisualSeries("detections", counts))));
		}
		if (!gas.isEmpty()) {
			List<List<String>> g = new ArrayList<>();
			gas.forEach(x -> g.add(List.of(Text.oneLine(x.deviceName(), 40), Fmt.time(x.startedAt(), z), Integer.toString(x.peak()), Integer.toString(x.threshold()),
					x.recoveredAt() == null ? "not yet" : Fmt.time(x.recoveredAt(), z))));
			occVisuals.add(Visual.table("Gas incidents", List.of("Device", "Started", "Peak (raw)", "Threshold", "Recovered"), g));
		}
		sections.add(new Section("Occupancy and security", List.of(), occ, occVisuals));

		// 5. Anomalies
		List<List<String>> an = new ArrayList<>();
		anomalies.forEach(a -> an.add(List.of(Text.oneLine(a.deviceName(), 40), a.kind(), a.scope(), a.severity(), a.status(), Fmt.time(a.lastSeenAt(), z))));
		sections.add(new Section("Anomalies", List.of(anomalies.isEmpty() ? "No anomalies were active during this period."
				: anomalies.size() + " anomalies were active during this period, " + openAnomalies + " of them still unresolved."), List.of(),
				an.isEmpty() ? List.of() : List.of(Visual.table("Anomalies", List.of("Device", "Kind", "Sensor", "Severity", "Status", "Last seen"), an))));

		// 6. Devices and automation
		List<String> dev = new ArrayList<>();
		List<List<String>> drows = new ArrayList<>();
		for (DeviceResponse d : devices) {
			if (deviceOrNull != null && !deviceOrNull.equals(d.id())) {
				continue;
			}
			long off = conn.stream().filter(c -> c.device().equals(d.id()) && "OFFLINE".equals(c.state())).count();
			drows.add(List.of(Text.oneLine(d.name(), 40), d.connection().name(), d.lastSeenAt() == null ? "never" : Fmt.time(d.lastSeenAt(), z), Long.toString(off)));
		}
		dev.add("Automation outcomes: " + (outcomes.isEmpty() ? "no rule activity recorded" : join(outcomes)) + ".");
		dev.add("Device commands: " + (commands.isEmpty() ? "none sent" : join(commands)) + ".");
		List<Visual> devVisuals = new ArrayList<>();
		if (!drows.isEmpty()) {
			devVisuals.add(Visual.table("Devices (connection now)", List.of("Device", "Connection", "Last contact", "Went offline"), drows));
		}
		if (!runs.isEmpty()) {
			List<List<String>> rr = new ArrayList<>();
			runs.forEach(e -> rr.add(List.of(Text.oneLine(e.ruleName(), 40), e.outcome(), e.action() + " = " + e.value(), Fmt.time(e.at(), z))));
			devVisuals.add(Visual.table("Latest automation runs", List.of("Rule", "Outcome", "Action", "At"), rr));
		}
		sections.add(new Section("Devices and automation", List.of(), dev, devVisuals));

		// 7. Recommendations
		sections.add(new Section("Recommendations", List.of(), recommendations(s, motion, anomalies, openAnomalies, gas, offline, outcomes), List.of()));

		List<String> notes = List.of(
				"All times are in " + z.getId() + ". The period is " + Fmt.time(r.from(), z) + " to " + Fmt.time(r.to(), z) + ".",
				"Gas values are raw MQ-2 sensor readings (0-4095), not ppm. This report is not a safety system; follow the device's own gas warning.",
				"Where a sensor sent no value, it is reported as missing, never as zero. Minimum and maximum times refer to 15-minute windows.",
				"Every figure was computed from the stored readings when the report was generated (" + Fmt.time(now, z) + ").");
		return new ReportContent(title, r.label(), scope(deviceOrNull, names), now, sections, notes);
	}

	/** Fixed rules; each recommendation quotes the value that triggered it. */
	static List<String> recommendations(Summary s, MotionActivity motion, List<AnomalyRow> anomalies, long openAnomalies, List<GasAlertRow> gas, long offline,
			Map<String, Long> outcomes) {
		List<String> out = new ArrayList<>();
		DeviceStats all = s.overall();
		MetricStats h = all.of(Metric.HUMIDITY);
		MetricStats t = all.of(Metric.TEMPERATURE);
		if (!gas.isEmpty() || all.gasOverThreshold() > 0) {
			out.add("Gas: " + gas.size() + " incidents and " + all.gasOverThreshold() + " readings at or above the alert threshold. Check ventilation and possible sources near the "
					+ "sensor, and follow the device's warning and local emergency guidance if it happens again.");
		}
		if (h.count() > 0 && h.avg() > HUMID_HIGH) {
			out.add("Humidity averaged " + Metric.HUMIDITY.format(h.avg()) + ". Ventilate more or use a dehumidifier; long periods above about 60 % encourage mould.");
		} else if (h.count() > 0 && h.avg() < HUMID_LOW) {
			out.add("Humidity averaged " + Metric.HUMIDITY.format(h.avg()) + ". The air is dry; a humidifier or fewer heating hours may help comfort.");
		}
		if (t.count() > 0 && t.max() > TEMP_HIGH + 4) {
			out.add("Temperature reached " + Metric.TEMPERATURE.format(t.max()) + ". Consider shading, ventilation or cooling at the hottest times.");
		}
		if (t.count() > 0 && t.min() < TEMP_LOW - 3) {
			out.add("Temperature dropped to " + Metric.TEMPERATURE.format(t.min()) + ". Check insulation or heating for the coldest hours.");
		}
		if (openAnomalies > 0) {
			out.add(openAnomalies + " anomalies are still unresolved. Review them on the Insights page and acknowledge or resolve them.");
		}
		if (offline >= 3) {
			out.add("Devices went offline " + offline + " times. Check Wi-Fi signal strength and the power supply of the affected devices.");
		}
		long failed = outcomes.entrySet().stream().filter(e -> e.getKey().contains("FAIL") || e.getKey().contains("ERROR")).mapToLong(Map.Entry::getValue).sum();
		if (failed > 0) {
			out.add(failed + " automation evaluations failed. Open the Automation page to see why and adjust the rules.");
		}
		for (DeviceStats d : s.devices()) {
			if (d.readings() == 0) {
				out.add("Device \"" + Text.oneLine(d.name(), 40) + "\" sent no readings in this period. Check that it is powered and connected.");
			}
		}
		if (motion.readings() > 0 && motion.detected() == 0) {
			out.add("No motion was detected at all in " + motion.readings() + " motion readings. If the space was in use, check the PIR sensor's position.");
		}
		if (out.isEmpty()) {
			out.add("No action needed: readings stayed within the usual ranges and no problems were recorded.");
		}
		return out;
	}

	private static String scope(UUID deviceOrNull, Map<UUID, String> names) {
		return deviceOrNull == null ? "all devices" : "device " + Text.oneLine(names.get(deviceOrNull), 60);
	}

	private static String cap(String s) {
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String join(Map<String, Long> counts) {
		List<String> parts = new ArrayList<>();
		counts.forEach((k, v) -> parts.add(v + " " + Text.oneLine(k, 30).toLowerCase(Locale.ROOT).replace('_', ' ')));
		return String.join(", ", parts);
	}
}
