package com.smarthome.ml;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import com.smarthome.device.Device;
import com.smarthome.device.DeviceRepository;
import com.smarthome.device.DeviceService;
import com.smarthome.device.DeviceStatus;
import com.smarthome.ml.MlDtos.ScanResponse;
import com.smarthome.ml.MlExceptions.TooFrequentException;

/**
 * Looks for anomalies on a schedule (and on request), never inside the telemetry ingest path.
 *
 * <p>Each scan reads only a small recent window of readings, judges readings newer than the previous scan against a
 * cached baseline of "normal", and checks the conditions that need a view over several readings (stuck, missing,
 * silent device). Conditions that have ended are closed automatically. It only writes anomaly rows: it never sends
 * commands and never touches rules, so the device's own gas protection and the automation engine are independent of it.
 */
@Component
public class AnomalyScanner {

	private static final Logger log = LoggerFactory.getLogger(AnomalyScanner.class);
	private static final Set<AnomalyKind> CONDITION_KINDS = EnumSet.of(AnomalyKind.STUCK_VALUE, AnomalyKind.MISSING_DATA, AnomalyKind.STALE_DATA);

	private record CachedBaselines(Baselines baselines, Instant builtAt) {
	}

	private final DeviceRepository devices;
	private final DeviceService deviceService;
	private final MlSampleRepository samples;
	private final AnomalyService anomalies;
	private final MlProperties props;
	private final Clock clock;
	private final Map<UUID, Instant> watermark = new ConcurrentHashMap<>();
	private final Map<UUID, Instant> lastScan = new ConcurrentHashMap<>();
	private final Map<UUID, Instant> lastManual = new ConcurrentHashMap<>();
	private final Map<UUID, CachedBaselines> baselineCache = new ConcurrentHashMap<>();
	private final Map<UUID, Object> deviceLocks = new ConcurrentHashMap<>();

	public AnomalyScanner(DeviceRepository devices, DeviceService deviceService, MlSampleRepository samples, AnomalyService anomalies,
			MlProperties props, Clock clock) {
		this.devices = devices;
		this.deviceService = deviceService;
		this.samples = samples;
		this.anomalies = anomalies;
		this.props = props;
		this.clock = clock;
	}

	public Instant lastScanAt(UUID deviceUuid) {
		return lastScan.get(deviceUuid);
	}

	/** Scheduled entry point: every active device, each in its own error boundary. */
	public void scanAll(Instant now) {
		for (Device d : devices.findByStatus(DeviceStatus.ACTIVE)) {
			try {
				scan(d.getId(), now, Duration.ofMinutes(props.anomaly().firstScanLookbackMinutes()));
			} catch (RuntimeException e) {
				log.warn("Anomaly scan failed for device {}: {}", d.getDeviceId(), e.getClass().getSimpleName());
			}
		}
	}

	/** Owner-requested scan of the last hour. Rate limited. */
	public ScanResponse scanManually(UUID ownerId, UUID deviceUuid) {
		Device d = deviceService.requireOwned(ownerId, deviceUuid);
		Instant now = clock.instant();
		Instant last = lastManual.get(d.getId());
		int min = props.anomaly().minManualScanIntervalSeconds();
		if (last != null && Duration.between(last, now).getSeconds() < min) {
			throw new TooFrequentException("A check was run a moment ago. Wait about " + min + " seconds.");
		}
		lastManual.put(d.getId(), now);
		int findings = scan(d.getId(), now, Duration.ofMinutes(props.anomaly().manualScanLookbackMinutes()));
		return new ScanResponse(now, findings, anomalies.activeCount(d.getId()));
	}

	/**
	 * @param firstLookback how far back to look for new readings if this device has not been scanned since startup
	 * @return how many findings were raised or updated
	 */
	public int scan(UUID deviceUuid, Instant now, Duration firstLookback) {
		synchronized (deviceLocks.computeIfAbsent(deviceUuid, k -> new Object())) {
			MlProperties.Anomaly cfg = props.anomaly();
			List<SensorSample> recent = samples.findSensorSamples(deviceUuid, now.minus(cfg.staleGiveUpDays() + 1L, ChronoUnit.DAYS),
					now.plusSeconds(60), PageRequest.of(0, cfg.recentWindow()));
			lastScan.put(deviceUuid, now);
			if (recent.isEmpty()) {
				return 0;   // never reported (or silent for so long it has been given up on): nothing to judge
			}
			int raised = 0;
			Set<String> active = new HashSet<>();

			// Conditions over a stretch of readings. Each is true or false right now; ended ones are closed below.
			Optional<Finding> stale = AnomalyDetectors.stale(recent.get(0).at(), now, cfg);
			if (stale.isPresent() && raise(deviceUuid, stale.get(), now, active)) {
				raised++;
			}
			for (Metric m : Metric.values()) {
				Optional<Finding> stuck = AnomalyDetectors.stuck(m, recent, cfg);
				if (stuck.isPresent() && raise(deviceUuid, stuck.get(), now, active)) {
					raised++;
				}
				Optional<Finding> missing = AnomalyDetectors.missing(m, recent, cfg);
				if (missing.isPresent() && raise(deviceUuid, missing.get(), now, active)) {
					raised++;
				}
			}

			// Individual new readings: plausibility first, then "unusual for this sensor".
			Instant since = watermark.getOrDefault(deviceUuid, now.minus(firstLookback));
			List<SensorSample> fresh = new ArrayList<>(recent.stream().filter(s -> s.at().isAfter(since)).limit(cfg.maxNewReadingsPerScan()).toList());
			fresh.sort(Comparator.comparing(SensorSample::at));
			if (!fresh.isEmpty()) {
				Baselines baselines = baselines(deviceUuid, now);
				for (SensorSample s : fresh) {
					for (Metric m : Metric.values()) {
						Optional<Finding> bad = AnomalyDetectors.plausibility(m, s, cfg);
						if (bad.isEmpty()) {
							bad = AnomalyDetectors.outlier(m, s, baselines.lookup(m, s.at()), cfg);
						}
						if (bad.isPresent() && anomalies.raise(deviceUuid, bad.get(), now).isPresent()) {
							raised++;
						}
					}
				}
			}
			watermark.put(deviceUuid, recent.get(0).at());

			anomalies.autoResolveInactive(deviceUuid, CONDITION_KINDS, active, now);
			anomalies.autoResolveQuiet(deviceUuid, now);
			return raised;
		}
	}

	private boolean raise(UUID deviceUuid, Finding f, Instant now, Set<String> active) {
		active.add(f.kind() + ":" + f.scope());   // even if suppressed, the condition still exists
		return anomalies.raise(deviceUuid, f, now).isPresent();
	}

	/** Cached for a while: learning "normal" reads up to a week of history, which is not done on every scan. */
	private Baselines baselines(UUID deviceUuid, Instant now) {
		MlProperties.Anomaly cfg = props.anomaly();
		CachedBaselines cached = baselineCache.get(deviceUuid);
		if (cached != null && cached.builtAt().isAfter(now.minus(cfg.baselineRefreshMinutes(), ChronoUnit.MINUTES))) {
			return cached.baselines();
		}
		List<SensorSample> history = samples.findSensorSamples(deviceUuid, now.minus(cfg.baselineDays(), ChronoUnit.DAYS),
				now.minus(cfg.baselineLagMinutes(), ChronoUnit.MINUTES), PageRequest.of(0, cfg.maxBaselineSamples()));
		Baselines built = Baselines.build(history, cfg);
		baselineCache.put(deviceUuid, new CachedBaselines(built, now));
		return built;
	}

	/** Forgets cached state for a device (used when it is disowned, and by tests). */
	public void forget(UUID deviceUuid) {
		watermark.remove(deviceUuid);
		lastScan.remove(deviceUuid);
		lastManual.remove(deviceUuid);
		baselineCache.remove(deviceUuid);
	}
}
