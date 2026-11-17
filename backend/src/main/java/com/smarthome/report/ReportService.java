package com.smarthome.report;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.analytics.TimeRange;
import com.smarthome.assistant.Text;
import com.smarthome.device.DeviceDtos.DeviceResponse;
import com.smarthome.device.DeviceService;
import com.smarthome.ml.MlProperties;
import com.smarthome.report.ReportDtos.ReportContent;
import com.smarthome.report.ReportDtos.ReportDetail;
import com.smarthome.report.ReportDtos.ReportSummary;
import com.smarthome.report.ReportExceptions.ReportNotFoundException;
import com.smarthome.report.ReportExceptions.ReportUnavailableException;
import com.smarthome.report.ReportExceptions.TooManyReportsException;

import tools.jackson.databind.ObjectMapper;

/** Generates, stores, lists and renders reports. Every operation is scoped to the caller (owner id from the token). */
@Service
public class ReportService {

	private static final Logger log = LoggerFactory.getLogger(ReportService.class);
	static final int MAX_REPORTS = 100;
	static final int PER_MINUTE = 6;

	private final ReportRepository reports;
	private final ReportBuilder builder;
	private final DeviceService devices;
	private final ObjectMapper mapper;
	private final Clock clock;
	private final ZoneId defaultZone;
	private final Map<UUID, Deque<Instant>> recent = new ConcurrentHashMap<>();

	public ReportService(ReportRepository reports, ReportBuilder builder, DeviceService devices, ObjectMapper mapper, Clock clock, MlProperties ml) {
		this.reports = reports;
		this.builder = builder;
		this.devices = devices;
		this.mapper = mapper;
		this.clock = clock;
		this.defaultZone = ml.zone();
	}

	public ZoneId zone(String name) {
		if (name == null || name.isBlank()) {
			return defaultZone;
		}
		try {
			return ZoneId.of(name.strip());
		} catch (DateTimeException e) {
			throw new IllegalArgumentException("Unknown time zone '" + Text.oneLine(name, 50) + "'");
		}
	}

	/**
	 * @throws IllegalArgumentException for an invalid kind, period, zone or device (message safe to show)
	 */
	@Transactional
	public ReportDetail generate(UUID owner, String kind, LocalDate from, LocalDate to, String timeZone, UUID deviceOrNull) {
		String k = kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT);
		ZoneId zone = zone(timeZone);
		Instant now = clock.instant();
		TimeRange range = switch (k) {
			case "daily" -> from == null ? TimeRange.resolve("today", null, null, zone, now) : TimeRange.resolve("custom", from, from, zone, now);
			case "weekly" -> TimeRange.resolve("last_7_days", null, null, zone, now);
			case "monthly" -> TimeRange.resolve("last_30_days", null, null, zone, now);
			case "custom" -> TimeRange.resolve("custom", from, to, zone, now);
			default -> throw new IllegalArgumentException("Report kind must be daily, weekly, monthly or custom");
		};
		try {
			List<DeviceResponse> mine = devices.list(owner);
			Map<UUID, String> names = new LinkedHashMap<>();
			mine.forEach(d -> names.put(d.id(), Text.oneLine(d.name(), 60)));
			if (deviceOrNull != null && !names.containsKey(deviceOrNull)) {
				throw new IllegalArgumentException("That device is not one of yours");
			}
			if (reports.countByOwnerUserId(owner) >= MAX_REPORTS) {
				throw new TooManyReportsException("You have " + MAX_REPORTS + " saved reports. Delete some before creating more.");
			}
			limit(owner, now);
			String title = cap(k) + " report: " + range.label() + (deviceOrNull == null ? "" : " - " + names.get(deviceOrNull));
			title = Text.oneLine(title, 160);
			ReportContent content = builder.build(owner, title, range, deviceOrNull, mine, names, now);
			String summary = Text.oneLine(String.join(" ", content.sections().get(0).paragraphs()), 2000);
			Report r = reports.save(new Report(UUID.randomUUID(), owner, k.toUpperCase(Locale.ROOT), title, range.from(), range.to(), zone.getId(), deviceOrNull,
					summary, mapper.writeValueAsString(content), now));
			return detail(r, content);
		} catch (DataAccessException e) {
			log.error("Report generation could not reach the database: {}", e.getClass().getSimpleName());
			throw new ReportUnavailableException();
		}
	}

	private void limit(UUID owner, Instant now) {
		Deque<Instant> q = recent.computeIfAbsent(owner, x -> new ArrayDeque<>());
		synchronized (q) {
			while (!q.isEmpty() && q.peekFirst().isBefore(now.minusSeconds(60))) {
				q.pollFirst();
			}
			if (q.size() >= PER_MINUTE) {
				throw new TooManyReportsException("Too many reports in a minute. Wait a moment and try again.");
			}
			q.addLast(now);
		}
	}

	@Transactional(readOnly = true)
	public List<ReportSummary> list(UUID owner) {
		return guard(() -> reports.findByOwnerUserIdOrderByCreatedAtDesc(owner, PageRequest.of(0, MAX_REPORTS)).stream()
				.map(r -> new ReportSummary(r.getId(), r.getKind(), r.getTitle(), r.getPeriodFrom(), r.getPeriodTo(), r.getTimeZone(), r.getDeviceUuid(),
						r.getSummary(), r.getCreatedAt()))
				.toList());
	}

	@Transactional(readOnly = true)
	public ReportDetail get(UUID owner, UUID id) {
		Report r = guard(() -> reports.findByIdAndOwnerUserId(id, owner)).orElseThrow(ReportNotFoundException::new);
		return detail(r, mapper.readValue(r.getContentJson(), ReportContent.class));
	}

	@Transactional(readOnly = true)
	public byte[] pdf(UUID owner, UUID id) {
		ReportDetail d = get(owner, id);
		return PdfRenderer.render(d.content(), zone(d.timeZone()));
	}

	@Transactional
	public void delete(UUID owner, UUID id) {
		Report r = guard(() -> reports.findByIdAndOwnerUserId(id, owner)).orElseThrow(ReportNotFoundException::new);
		guard(() -> {
			reports.delete(r);
			return null;
		});
	}

	private static ReportDetail detail(Report r, ReportContent c) {
		return new ReportDetail(r.getId(), r.getKind(), r.getTitle(), r.getPeriodFrom(), r.getPeriodTo(), r.getTimeZone(), r.getDeviceUuid(), r.getSummary(),
				r.getCreatedAt(), c);
	}

	private static <T> T guard(java.util.function.Supplier<T> work) {
		try {
			return work.get();
		} catch (DataAccessException e) {
			log.error("Report storage unavailable: {}", e.getClass().getSimpleName());
			throw new ReportUnavailableException();
		}
	}

	private static String cap(String s) {
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
