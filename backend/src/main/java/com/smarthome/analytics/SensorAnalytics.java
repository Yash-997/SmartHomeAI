package com.smarthome.analytics;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.alert.AlertProperties;
import com.smarthome.analytics.AnalyticsRepository.Agg;
import com.smarthome.analytics.AnalyticsRepository.Bucket;

/**
 * Exact statistics over stored telemetry, computed from SQL aggregates (never from retrieved text and never by a
 * language model). Every method takes the caller's user id and only sees that user's devices. A sensor without values in
 * a period is reported as having no values, never as zero.
 */
@Service
@Transactional(readOnly = true)
public class SensorAnalytics {

	public record MetricStats(Metric metric, long count, Double avg, Double min, Double max, Instant minWindow, Instant maxWindow) {
		static MetricStats none(Metric m) {
			return new MetricStats(m, 0, null, null, null, null, null);
		}
	}

	/** Stats of one device (or of all devices together when {@code device} is null). Windows are 15-minute windows. */
	public record DeviceStats(UUID device, String name, long readings, Instant firstWindow, Instant lastWindow, Map<Metric, MetricStats> metrics,
			long gasOverThreshold, long motionReadings, long motionDetected) {
		public MetricStats of(Metric m) {
			return metrics.get(m);
		}
	}

	public record Summary(TimeRange range, int gasThreshold, List<DeviceStats> devices, DeviceStats overall) {
		public boolean empty() {
			return overall.readings() == 0;
		}
	}

	public record Point(Instant start, Double avg, Double min, Double max, long count) {
	}

	public record Series(Metric metric, String granularity, TimeRange range, List<Point> points) {
	}

	public record Comparison(Metric metric, TimeRange current, TimeRange previous, MetricStats now, MetricStats before, Double difference, Double percent) {
	}

	public record MotionActivity(TimeRange range, long readings, long detected, long activeWindows, long[] detectedByLocalHour, Integer busiestHour,
			Instant lastMotionEver) {
	}

	private final AnalyticsRepository repo;
	private final int gasThreshold;

	public SensorAnalytics(AnalyticsRepository repo, AlertProperties alerts) {
		this.repo = repo;
		this.gasThreshold = alerts.gasThreshold();
	}

	public int gasThreshold() {
		return gasThreshold;
	}

	/** @param names the caller's devices (id -> name); buckets of any other device are ignored */
	public Summary summary(UUID owner, UUID deviceOrNull, TimeRange range, Map<UUID, String> names) {
		List<Bucket> buckets = repo.buckets(owner, deviceOrNull, range.from(), range.to(), gasThreshold);
		Map<UUID, List<Bucket>> byDevice = new LinkedHashMap<>();
		for (Bucket b : buckets) {
			if (names.containsKey(b.device())) {
				byDevice.computeIfAbsent(b.device(), k -> new ArrayList<>()).add(b);
			}
		}
		List<DeviceStats> devices = new ArrayList<>();
		names.forEach((id, name) -> {
			if (deviceOrNull == null || deviceOrNull.equals(id)) {
				devices.add(stats(id, name, byDevice.getOrDefault(id, List.of())));
			}
		});
		List<Bucket> all = new ArrayList<>();
		byDevice.values().forEach(all::addAll);
		DeviceStats overall = stats(null, deviceOrNull == null ? "all devices" : names.get(deviceOrNull), all);
		return new Summary(range, gasThreshold, devices, overall);
	}

	static DeviceStats stats(UUID device, String name, List<Bucket> buckets) {
		Map<Metric, Agg> agg = new EnumMap<>(Metric.class);
		Map<Metric, Instant> minAt = new EnumMap<>(Metric.class);
		Map<Metric, Instant> maxAt = new EnumMap<>(Metric.class);
		long readings = 0, over = 0, mr = 0, md = 0;
		Instant first = null, last = null;
		for (Bucket b : buckets) {
			readings += b.readings();
			over += b.gasOverThreshold();
			mr += b.motionReadings();
			md += b.motionDetected();
			first = first == null || b.start().isBefore(first) ? b.start() : first;
			last = last == null || b.start().isAfter(last) ? b.start() : last;
			for (Metric m : Metric.values()) {
				Agg a = of(b, m);
				if (a.count() == 0) {
					continue;
				}
				Agg prev = agg.getOrDefault(m, Agg.EMPTY);
				if (prev.count() == 0 || a.min() < prev.min()) {
					minAt.put(m, b.start());
				}
				if (prev.count() == 0 || a.max() > prev.max()) {
					maxAt.put(m, b.start());
				}
				agg.put(m, prev.plus(a));
			}
		}
		Map<Metric, MetricStats> metrics = new EnumMap<>(Metric.class);
		for (Metric m : Metric.values()) {
			Agg a = agg.getOrDefault(m, Agg.EMPTY);
			metrics.put(m, a.count() == 0 ? MetricStats.none(m) : new MetricStats(m, a.count(), a.avg(), a.min(), a.max(), minAt.get(m), maxAt.get(m)));
		}
		return new DeviceStats(device, name, readings, first, last, metrics, over, mr, md);
	}

	static Agg of(Bucket b, Metric m) {
		return switch (m) {
			case TEMPERATURE -> b.temperature();
			case HUMIDITY -> b.humidity();
			case GAS -> b.gas();
		};
	}

	/** "hour", "day" or "auto" (hours up to two days, days beyond). */
	public static String granularity(String requested, TimeRange range) {
		String g = requested == null ? "auto" : requested.trim().toLowerCase(java.util.Locale.ROOT);
		return switch (g) {
			case "hour", "hourly" -> {
				if (range.length().compareTo(Duration.ofDays(14)) > 0) {
					throw new IllegalArgumentException("Hourly points are available for periods of up to 14 days; use daily points");
				}
				yield "hour";
			}
			case "day", "daily" -> "day";
			case "auto", "" -> range.length().compareTo(Duration.ofHours(48)) <= 0 ? "hour" : "day";
			default -> throw new IllegalArgumentException("Granularity must be hour, day or auto");
		};
	}

	/** Averages per local hour or local day. Windows without values are left out (a gap, not a zero). */
	public Series series(UUID owner, UUID deviceOrNull, Metric metric, TimeRange range, String granularity, Map<UUID, String> names) {
		String g = granularity(granularity, range);
		ZoneId zone = range.zone();
		TreeMap<Instant, Agg> grouped = new TreeMap<>();
		for (Bucket b : repo.buckets(owner, deviceOrNull, range.from(), range.to(), gasThreshold)) {
			if (!names.containsKey(b.device())) {
				continue;
			}
			Agg a = of(b, metric);
			if (a.count() == 0) {
				continue;
			}
			ZonedDateTime z = b.start().atZone(zone);
			Instant key = ("hour".equals(g) ? z.truncatedTo(ChronoUnit.HOURS) : z.truncatedTo(ChronoUnit.DAYS)).toInstant();
			grouped.merge(key, a, Agg::plus);
		}
		List<Point> points = new ArrayList<>();
		grouped.forEach((k, a) -> points.add(new Point(k, a.avg(), a.min(), a.max(), a.count())));
		return new Series(metric, g, range, points);
	}

	/** Average of {@code metric} now vs. before; the difference is computed here, so nobody has to do arithmetic on text. */
	public Comparison compare(UUID owner, UUID deviceOrNull, Metric metric, TimeRange current, TimeRange previous, Map<UUID, String> names) {
		MetricStats a = summary(owner, deviceOrNull, current, names).overall().of(metric);
		MetricStats b = summary(owner, deviceOrNull, previous, names).overall().of(metric);
		Double diff = a.avg() != null && b.avg() != null ? a.avg() - b.avg() : null;
		Double pct = diff != null && b.avg() != 0 ? diff / Math.abs(b.avg()) * 100 : null;
		return new Comparison(metric, current, previous, a, b, diff, pct);
	}

	public MotionActivity motion(UUID owner, UUID deviceOrNull, TimeRange range, Map<UUID, String> names) {
		long readings = 0, detected = 0, active = 0;
		long[] byHour = new long[24];
		for (Bucket b : repo.buckets(owner, deviceOrNull, range.from(), range.to(), gasThreshold)) {
			if (!names.containsKey(b.device())) {
				continue;
			}
			readings += b.motionReadings();
			detected += b.motionDetected();
			if (b.motionDetected() > 0) {
				active++;
				byHour[b.start().atZone(range.zone()).getHour()] += b.motionDetected();
			}
		}
		Integer busiest = null;
		for (int h = 0; h < 24; h++) {
			if (byHour[h] > 0 && (busiest == null || byHour[h] > byHour[busiest])) {
				busiest = h;
			}
		}
		return new MotionActivity(range, readings, detected, active, byHour, busiest, repo.lastMotion(owner, deviceOrNull));
	}
}
