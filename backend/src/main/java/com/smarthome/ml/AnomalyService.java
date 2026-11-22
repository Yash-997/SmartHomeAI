package com.smarthome.ml;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smarthome.device.DeviceService;
import com.smarthome.ml.MlDtos.AnomalyResponse;
import com.smarthome.ml.MlDtos.AnomalySummary;
import com.smarthome.ml.MlExceptions.AnomalyNotFoundException;
import com.smarthome.ml.MlExceptions.InvalidAnomalyTransitionException;
import com.smarthome.ml.MlExceptions.InvalidMlRequestException;
import com.smarthome.telemetry.SensorRejectedEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores anomalies and manages their lifecycle.
 *
 * <p>One ongoing problem is one row: while an anomaly of the same kind and sensor is OPEN or ACKNOWLEDGED, new sightings
 * only update it (occurrence count, latest numbers, worst severity). A dismissed anomaly (a false positive) is not
 * raised again for a while. Only the device's owner can change one.
 */
@Service
public class AnomalyService {

	private static final Logger log = LoggerFactory.getLogger(AnomalyService.class);
	private static final Set<AnomalyStatus> ACTIVE = EnumSet.of(AnomalyStatus.OPEN, AnomalyStatus.ACKNOWLEDGED);
	/** An out-of-range problem that keeps recurring is escalated to CRITICAL after this many occurrences. */
	static final int ESCALATE_AFTER = 5;
	private static final int LOCK_STRIPES = 32;

	private final MlAnomalyRepository anomalies;
	private final DeviceService deviceService;
	private final MlProperties props;
	private final ObjectMapper mapper;
	private final Clock clock;
	private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
	private final ApplicationEventPublisher events;

	public AnomalyService(MlAnomalyRepository anomalies, DeviceService deviceService, MlProperties props, ObjectMapper mapper, Clock clock,
			ApplicationEventPublisher events) {
		this.anomalies = anomalies;
		this.deviceService = deviceService;
		this.props = props;
		this.mapper = mapper;
		this.clock = clock;
		this.events = events;
		for (int i = 0; i < LOCK_STRIPES; i++) {
			locks[i] = new ReentrantLock();
		}
	}

	// ---- raising ---------------------------------------------------------------------------------

	/** Creates the anomaly, or updates the open one for the same problem. Returns empty if it was suppressed. */
	public Optional<MlAnomaly> raise(UUID deviceUuid, Finding f, Instant now) {
		ReentrantLock lock = locks[Math.floorMod(deviceUuid.hashCode(), LOCK_STRIPES)];
		lock.lock();
		try {
			return doRaise(deviceUuid, f, now);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Runs under the per-device lock above. Each repository call is its own short transaction; the lock (not a
	 * transaction) is what keeps "look for an open one, else create" from racing with another thread.
	 */
	private Optional<MlAnomaly> doRaise(UUID deviceUuid, Finding f, Instant now) {
		String details = mapper.writeValueAsString(f.details());
		Optional<MlAnomaly> open = anomalies.findFirstByDeviceUuidAndKindAndScopeAndStatusInOrderByLastSeenAtDesc(deviceUuid, f.kind(), f.scope(), ACTIVE);
		if (open.isPresent()) {
			MlAnomaly a = open.get();
			AnomalySeverity before = a.getSeverity();
			AnomalySeverity severity = f.severity();
			if (f.kind() == AnomalyKind.OUT_OF_RANGE && a.getOccurrences() + 1 >= ESCALATE_AFTER) {
				severity = AnomalySeverity.CRITICAL;
			}
			a.seenAgain(severity, f.explanation(), details, f.readingAt(), now);
			measure(a, f);
			MlAnomaly saved = anomalies.save(a);
			if (saved.getSeverity().ordinal() > before.ordinal()) {
				published(saved, false);              // got worse: may now reach the email severity
			}
			return Optional.of(saved);
		}
		Optional<MlAnomaly> dismissed = anomalies.findFirstByDeviceUuidAndKindAndScopeAndStatusOrderByResolvedAtDesc(deviceUuid, f.kind(), f.scope(), AnomalyStatus.DISMISSED);
		if (dismissed.isPresent() && dismissed.get().getResolvedAt() != null
				&& dismissed.get().getResolvedAt().isAfter(now.minus(props.anomaly().suppressAfterDismissMinutes(), ChronoUnit.MINUTES))) {
			return Optional.empty();
		}
		MlAnomaly created = new MlAnomaly(deviceUuid, f.kind(), f.scope(), f.severity(), f.explanation(), details, f.readingAt(), now);
		measure(created, f);
		MlAnomaly saved = anomalies.save(created);
		published(saved, true);
		return Optional.of(saved);
	}

	/**
	 * Tells listeners (the anomaly email) that this anomaly was just recorded or got worse. The row is already saved, so
	 * the history never depends on a listener; a failing listener is logged and ignored, detection carries on.
	 */
	private void published(MlAnomaly a, boolean isNew) {
		publishedEvent(a, isNew);
	}

	/** Stores the measured value and normal range as numbers, from the same details the explanation was written from. */
	private static void measure(MlAnomaly a, Finding f) {
		AnomalyFacts.Numbers n = AnomalyFacts.numbers(f.kind(), f.details() == null ? Map.of() : f.details());
		a.measured(n.value(), n.low(), n.high());
	}

	private void publishedEvent(MlAnomaly a, boolean isNew) {
		try {
			events.publishEvent(new AnomalyRaisedEvent(a.getId(), a.getDeviceUuid(), a.getSeverity(), isNew));
		} catch (RuntimeException e) {
			log.warn("Anomaly notification could not be queued: {}", e.getClass().getSimpleName());
		}
	}

	/** Reading refused by the ingest path because it was outside the accepted range. */
	public void recordRejected(SensorRejectedEvent e) {
		raise(e.deviceUuid(), AnomalyDetectors.rejectedReading(e.sensor(), e.value(), e.at()), clock.instant());
	}

	// ---- automatic closing -----------------------------------------------------------------------

	/**
	 * Closes active anomalies of the given kinds whose condition is no longer present. {@code stillActive} holds
	 * "KIND:scope" keys for conditions that are true right now.
	 */
	@Transactional
	public void autoResolveInactive(UUID deviceUuid, Set<AnomalyKind> kinds, Set<String> stillActive, Instant now) {
		for (MlAnomaly a : anomalies.findByDeviceUuidAndKindInAndStatusIn(deviceUuid, kinds, ACTIVE)) {
			if (!stillActive.contains(a.getKind() + ":" + a.getScope())) {
				a.close(AnomalyStatus.AUTO_RESOLVED, "The condition ended on its own.", now);
				anomalies.save(a);
			}
		}
	}

	/** Closes event-style anomalies (outliers, out-of-range, sudden changes, unusual activity) that have not recurred for a while. */
	@Transactional
	public void autoResolveQuiet(UUID deviceUuid, Instant now) {
		Instant cutoff = now.minus(props.anomaly().autoResolveMinutes(), ChronoUnit.MINUTES);
		for (MlAnomaly a : anomalies.findByDeviceUuidAndStatusInAndLastSeenAtBefore(deviceUuid, ACTIVE, cutoff)) {
			if (a.getKind() == AnomalyKind.STATISTICAL_OUTLIER || a.getKind() == AnomalyKind.OUT_OF_RANGE
					|| a.getKind() == AnomalyKind.SUDDEN_CHANGE || a.getKind() == AnomalyKind.UNUSUAL_ACTIVITY
					|| a.getKind() == AnomalyKind.MULTIVARIATE_OUTLIER) {
				a.close(AnomalyStatus.AUTO_RESOLVED, "Not seen again for " + props.anomaly().autoResolveMinutes() + " minutes.", now);
				anomalies.save(a);
			}
		}
	}

	// ---- reading and user actions ----------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<AnomalyResponse> list(UUID ownerId, UUID deviceUuid, boolean activeOnly, int limit) {
		deviceService.requireOwned(ownerId, deviceUuid);
		PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
		List<MlAnomaly> rows = activeOnly
				? anomalies.findByDeviceUuidAndStatusInOrderByLastSeenAtDesc(deviceUuid, ACTIVE, page)
				: anomalies.findByDeviceUuidOrderByLastSeenAtDesc(deviceUuid, page);
		return rows.stream().map(this::toResponse).toList();
	}

	/** Acknowledge, resolve or dismiss. Only active anomalies can be changed; closed ones are history. */
	@Transactional
	public AnomalyResponse resolve(UUID ownerId, UUID deviceUuid, UUID anomalyId, String status, String note) {
		deviceService.requireOwned(ownerId, deviceUuid);
		MlAnomaly a = anomalies.findByIdAndDeviceUuid(anomalyId, deviceUuid).orElseThrow(AnomalyNotFoundException::new);
		AnomalyStatus target;
		try {
			target = AnomalyStatus.valueOf(status == null ? "" : status);
		} catch (IllegalArgumentException e) {
			throw new InvalidMlRequestException("status", "Must be ACKNOWLEDGED, RESOLVED or DISMISSED");
		}
		if (target != AnomalyStatus.ACKNOWLEDGED && target != AnomalyStatus.RESOLVED && target != AnomalyStatus.DISMISSED) {
			throw new InvalidMlRequestException("status", "Must be ACKNOWLEDGED, RESOLVED or DISMISSED");
		}
		if (!a.getStatus().isActive()) {
			throw new InvalidAnomalyTransitionException("This anomaly is already " + a.getStatus().name().toLowerCase().replace('_', ' ') + " and cannot be changed.");
		}
		if (target == AnomalyStatus.ACKNOWLEDGED) {
			a.acknowledge();
		} else {
			a.close(target, cleanNote(note), clock.instant());
		}
		return toResponse(anomalies.save(a));
	}

	@Transactional(readOnly = true)
	public AnomalySummary summary(UUID deviceUuid, Instant lastScanAt) {
		return new AnomalySummary(anomalies.countByDeviceUuidAndStatusIn(deviceUuid, ACTIVE),
				anomalies.countByDeviceUuidAndStatusInAndSeverity(deviceUuid, ACTIVE, AnomalySeverity.CRITICAL),
				anomalies.countByDeviceUuidAndStatusInAndSeverity(deviceUuid, ACTIVE, AnomalySeverity.WARNING),
				anomalies.countByDeviceUuidAndStatusInAndSeverity(deviceUuid, ACTIVE, AnomalySeverity.INFO), lastScanAt);
	}

	long activeCount(UUID deviceUuid) {
		return anomalies.countByDeviceUuidAndStatusIn(deviceUuid, ACTIVE);
	}

	/** User text is untrusted: drop control characters and trim. */
	static String cleanNote(String note) {
		if (note == null) {
			return null;
		}
		String cleaned = note.replaceAll("\\p{Cntrl}", " ").trim();
		return cleaned.isEmpty() ? null : cleaned;
	}

	private AnomalyResponse toResponse(MlAnomaly a) {
		JsonNode details = null;
		try {
			details = a.getDetails() == null ? null : mapper.readTree(a.getDetails());
		} catch (RuntimeException e) {
			// leave details out rather than fail the whole list
		}
		return new AnomalyResponse(a.getId(), a.getKind(), a.getScope(), a.getSeverity(), a.getStatus(), a.getExplanation(), details,
				a.getDetectedAt(), a.getLastSeenAt(), a.getOccurrences(), a.getReadingAt(), a.getResolvedAt(), a.getResolutionNote(),
				a.getEmailStatus(), a.getEmailAt(), a.getEmailError(), a.getKind().detector(), a.getDetectedValue(), a.getExpectedLow(),
				a.getExpectedHigh());
	}
}
