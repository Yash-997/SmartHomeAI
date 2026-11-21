package com.smarthome.alert;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.smarthome.alert.AlertEmailTemplates.AnomalyDetails;
import com.smarthome.alert.AlertMailer.MailDeliveryException;
import com.smarthome.device.Device;
import com.smarthome.device.DeviceRepository;
import com.smarthome.ml.AnomalyFacts;
import com.smarthome.ml.AnomalyRaisedEvent;
import com.smarthome.ml.MlAnomaly;
import com.smarthome.ml.MlAnomalyRepository;
import com.smarthome.user.User;
import com.smarthome.user.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Emails the device owner when an anomaly is recorded, as part of the same detection workflow: {@code AnomalyService}
 * saves the anomaly (so it is in the history and in reports) and then publishes {@link AnomalyRaisedEvent}; this class
 * queues the email on the alert worker thread, so a slow or broken mail server never delays or stops monitoring.
 *
 * <p>Deduplication: one anomaly row is one ongoing problem and is emailed at most once (an atomic claim on its
 * email_status), and the same device, kind and sensor is not emailed again within the cooldown after a sent email. The
 * outcome (SENT, FAILED, SKIPPED and why) is stored on the anomaly and shown in the app.
 */
@Service
public class AnomalyEmailService {

	private static final Logger log = LoggerFactory.getLogger(AnomalyEmailService.class);

	private final AnomalyAlertProperties props;
	private final AlertProperties alertProps;
	private final AlertMailer mailer;
	private final MlAnomalyRepository anomalies;
	private final DeviceRepository devices;
	private final UserRepository users;
	private final AlertDispatcher dispatcher;
	private final ObjectMapper mapper;
	private final Clock clock;

	public AnomalyEmailService(AnomalyAlertProperties props, AlertProperties alertProps, AlertMailer mailer, MlAnomalyRepository anomalies,
			DeviceRepository devices, UserRepository users, AlertDispatcher dispatcher, ObjectMapper mapper, Clock clock) {
		this.props = props;
		this.alertProps = alertProps;
		this.mailer = mailer;
		this.anomalies = anomalies;
		this.devices = devices;
		this.users = users;
		this.dispatcher = dispatcher;
		this.mapper = mapper;
		this.clock = clock;
	}

	/** Runs on the detecting thread: only checks and queues. Never throws. */
	@EventListener
	public void on(AnomalyRaisedEvent event) {
		try {
			if (!props.emailEnabled() || event.severity().ordinal() < props.minSeverity().ordinal()) {
				return;
			}
			dispatcher.submit(() -> deliver(event.anomalyId()));
		} catch (RuntimeException e) {
			log.warn("Could not queue the anomaly email: {}", e.getClass().getSimpleName());
		}
	}

	/** On the alert worker. Every outcome is recorded on the anomaly; nothing is thrown. */
	void deliver(UUID anomalyId) {
		Optional<MlAnomaly> found = anomalies.findById(anomalyId);
		if (found.isEmpty() || found.get().getEmailStatus() != null || found.get().getSeverity().ordinal() < props.minSeverity().ordinal()) {
			return;
		}
		MlAnomaly a = found.get();
		Optional<Device> device = devices.findById(a.getDeviceUuid());
		UUID owner = device.map(Device::getOwnerUserId).orElse(null);
		if (owner == null) {
			return;                                            // an unpaired device has no one to tell
		}
		Instant now = clock.instant();
		if (anomalies.claimEmail(anomalyId, now) == 0) {
			return;                                            // already handled: never a second email for one anomaly
		}
		try {
			int cooldown = props.cooldownMinutes();
			if (cooldown > 0 && anomalies.existsByDeviceUuidAndKindAndScopeAndEmailStatusAndEmailAtAfterAndIdNot(a.getDeviceUuid(), a.getKind(),
					a.getScope(), "SENT", now.minus(cooldown, ChronoUnit.MINUTES), anomalyId)) {
				record(anomalyId, "SKIPPED", "The same problem on this device was emailed less than " + cooldown + " minutes ago.");
				return;
			}
			Optional<String> to = users.findById(owner).map(User::getEmail).filter(e -> e != null && !e.isBlank());
			if (to.isEmpty()) {
				record(anomalyId, "SKIPPED", "The device owner has no email address.");
				return;
			}
			if (!mailer.configured()) {
				record(anomalyId, "SKIPPED", "Email is not configured (MAIL_USERNAME / MAIL_PASSWORD).");
				return;
			}
			mailer.send(AlertEmailTemplates.anomalyAlert(details(a, device.get()), alertProps, props.cooldownMinutes()), to.get());
			record(anomalyId, "SENT", null);
		} catch (MailDeliveryException e) {
			log.warn("Anomaly email for device {} failed: {}", a.getDeviceUuid(), e.getMessage());
			record(anomalyId, "FAILED", e.getMessage());
		} catch (RuntimeException e) {
			log.warn("Anomaly email for device {} failed: {}", a.getDeviceUuid(), e.getClass().getSimpleName());
			record(anomalyId, "FAILED", "unexpected error (" + e.getClass().getSimpleName() + ")");
		}
	}

	private void record(UUID id, String status, String error) {
		String safe = error == null ? null : error.replaceAll("\\p{Cntrl}", " ");
		if (safe != null && safe.length() > 200) {
			safe = safe.substring(0, 200);
		}
		try {
			anomalies.recordEmail(id, status, clock.instant(), safe);
		} catch (RuntimeException e) {
			log.warn("Could not record the anomaly email status: {}", e.getClass().getSimpleName());
		}
	}

	AnomalyDetails details(MlAnomaly a, Device d) {
		JsonNode json = parse(a.getDetails());
		String kind = a.getKind().name();
		String name = d.getName() == null || d.getName().isBlank() ? d.getDeviceId() : d.getName();
		return new AnomalyDetails(a.getId(), name, d.getDeviceId(), AnomalyFacts.kind(a.getKind()), AnomalyFacts.sensor(a.getScope()),
				a.getSeverity().name(), AnomalyFacts.value(kind, a.getScope(), json), AnomalyFacts.expected(kind, a.getScope(), json),
				a.getExplanation(), a.getDetectedAt(), a.getOccurrences());
	}

	private JsonNode parse(String details) {
		try {
			return details == null ? null : mapper.readTree(details);
		} catch (RuntimeException e) {
			return null;
		}
	}

	// ---- read side (scoped to the owner) -------------------------------------------------------

	public AlertDtos.AnomalyEmailConfig config(UUID ownerId) {
		String email = users.findById(ownerId).map(User::getEmail).filter(e -> e != null && !e.isBlank()).map(GasAlertService::mask).orElse(null);
		return new AlertDtos.AnomalyEmailConfig(props.emailEnabled(), mailer.configured(), props.minSeverity().name(), props.cooldownMinutes(), email);
	}

	/** The owner's anomalies that have an email outcome, newest first, with the same facts as the email and the report. */
	public List<AlertDtos.AnomalyEmailResponse> history(UUID ownerId, int limit) {
		Map<UUID, Device> owned = devices.findByOwnerUserIdOrderByRegisteredAtDesc(ownerId).stream()
				.collect(Collectors.toMap(Device::getId, Function.identity()));
		if (owned.isEmpty()) {
			return List.of();
		}
		return anomalies.findByDeviceUuidInAndEmailStatusIsNotNullOrderByDetectedAtDesc(owned.keySet(), PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
				.stream().map(a -> {
					AnomalyDetails x = details(a, owned.get(a.getDeviceUuid()));
					return new AlertDtos.AnomalyEmailResponse(a.getId(), a.getDeviceUuid(), x.deviceName(), x.deviceId(), a.getKind().name(), x.sensor(),
							x.severity(), a.getStatus().name(), x.value(), x.expected(), a.getExplanation(), a.getDetectedAt(), a.getEmailStatus(),
							a.getEmailAt(), a.getEmailError());
				}).toList();
	}
}
