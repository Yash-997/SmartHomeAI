package com.smarthome.alert;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.smarthome.alert.AlertMailer.MailDeliveryException;
import com.smarthome.alert.GasAlert.Delivery;
import com.smarthome.alert.GasAlert.State;
import com.smarthome.device.Device;
import com.smarthome.device.DeviceRepository;
import com.smarthome.telemetry.TelemetryStoredEvent;
import com.smarthome.user.User;
import com.smarthome.user.UserRepository;

/**
 * Turns MQ-2 readings into incidents and emails. An incident starts after {@code confirm} consecutive readings at or above
 * the threshold and ends after {@code confirm} consecutive readings below the (lower) recovery level, so a reading that
 * hovers around the threshold cannot produce a stream of emails. Exactly one alert email and at most one recovery email
 * are sent per incident. The incident row is the source of truth (an ACTIVE row survives a restart); only the
 * consecutive-reading counters live in memory. Must run on a single thread ({@link AlertDispatcher}).
 */
@Service
public class GasAlertService {

	private static final Logger log = LoggerFactory.getLogger(GasAlertService.class);

	private final AlertProperties props;
	private final GasAlertRepository alerts;
	private final DeviceRepository devices;
	private final UserRepository users;
	private final AlertMailer mailer;
	private final Clock clock;

	private final Map<UUID, Integer> highStreak = new ConcurrentHashMap<>();
	private final Map<UUID, Integer> lowStreak = new ConcurrentHashMap<>();

	public GasAlertService(AlertProperties props, GasAlertRepository alerts, DeviceRepository devices, UserRepository users,
			AlertMailer mailer, Clock clock) {
		this.props = props;
		this.alerts = alerts;
		this.devices = devices;
		this.users = users;
		this.mailer = mailer;
		this.clock = clock;
	}

	/** Evaluates one stored telemetry message. Messages without a gas value change nothing. */
	public void onTelemetry(TelemetryStoredEvent event) {
		Integer gas = event.gasRaw();
		if (!props.enabled() || gas == null) {
			return;
		}
		UUID deviceUuid = event.deviceUuid();
		Optional<GasAlert> active = alerts.findFirstByDeviceUuidAndStateOrderByStartedAtDesc(deviceUuid, State.ACTIVE);
		if (active.isEmpty()) {
			lowStreak.remove(deviceUuid);
			if (gas >= props.gasThreshold()) {
				int n = highStreak.merge(deviceUuid, 1, Integer::sum);
				if (n >= props.confirm()) {
					highStreak.remove(deviceUuid);
					start(deviceUuid, gas);
				}
			} else {
				highStreak.remove(deviceUuid);
			}
			return;
		}
		highStreak.remove(deviceUuid);
		GasAlert alert = active.get();
		alert.observe(gas);
		if (gas < alert.getRecoveryThreshold()) {
			int n = lowStreak.merge(deviceUuid, 1, Integer::sum);
			if (n >= props.confirm()) {
				lowStreak.remove(deviceUuid);
				alert.recover(clock.instant());
				alerts.save(alert);
				deliverRecovery(alert);
				return;
			}
		} else {
			lowStreak.remove(deviceUuid);
		}
		alerts.save(alert);
	}

	private void start(UUID deviceUuid, int gas) {
		Optional<Device> device = devices.findById(deviceUuid);
		UUID ownerId = device.map(Device::getOwnerUserId).orElse(null);
		if (ownerId == null) {
			return;                      // an unpaired device has no one to warn
		}
		GasAlert alert = alerts.save(new GasAlert(ownerId, deviceUuid, deviceLabel(device.get()), props.gasThreshold(),
				props.recoveryThreshold(), gas, clock.instant()));
		deliverAlert(alert);
	}

	private void deliverAlert(GasAlert alert) {
		Optional<String> to = recipient(alert.getOwnerUserId());
		if (to.isEmpty()) {
			alert.alertDelivered(Delivery.SKIPPED, clock.instant(), "The device owner has no email address.");
		} else if (!mailer.configured()) {
			alert.alertDelivered(Delivery.SKIPPED, clock.instant(), "Email is not configured (MAIL_USERNAME / MAIL_PASSWORD).");
		} else {
			try {
				mailer.send(AlertEmailTemplates.gasAlert(alert, props), to.get());
				alert.alertDelivered(Delivery.SENT, clock.instant(), null);
			} catch (MailDeliveryException e) {
				log.warn("Gas alert email for device {} failed: {}", alert.getDeviceUuid(), e.getMessage());
				alert.alertDelivered(Delivery.FAILED, clock.instant(), trim(e.getMessage()));
			}
		}
		alerts.save(alert);
	}

	private void deliverRecovery(GasAlert alert) {
		Optional<String> to = recipient(alert.getOwnerUserId());
		if (to.isEmpty()) {
			alert.recoveryDelivered(Delivery.SKIPPED, clock.instant(), "The device owner has no email address.");
		} else if (!mailer.configured()) {
			alert.recoveryDelivered(Delivery.SKIPPED, clock.instant(), "Email is not configured (MAIL_USERNAME / MAIL_PASSWORD).");
		} else {
			try {
				mailer.send(AlertEmailTemplates.gasRecovered(alert, props), to.get());
				alert.recoveryDelivered(Delivery.SENT, clock.instant(), null);
			} catch (MailDeliveryException e) {
				log.warn("Gas recovery email for device {} failed: {}", alert.getDeviceUuid(), e.getMessage());
				alert.recoveryDelivered(Delivery.FAILED, clock.instant(), trim(e.getMessage()));
			}
		}
		alerts.save(alert);
	}

	private Optional<String> recipient(UUID ownerId) {
		return users.findById(ownerId).map(User::getEmail).filter(e -> e != null && !e.isBlank());
	}

	private static String deviceLabel(Device d) {
		String n = d.getName();
		return n == null || n.isBlank() ? d.getDeviceId() : n;
	}

	private static String trim(String s) {
		return s == null ? null : (s.length() > 200 ? s.substring(0, 200) : s);
	}

	// ---- read side (scoped to the owner) -------------------------------------------------------

	public List<GasAlert> history(UUID ownerId, int limit) {
		return alerts.findByOwnerUserIdOrderByStartedAtDesc(ownerId, PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
	}

	public AlertDtos.ConfigResponse config(UUID ownerId) {
		String email = recipient(ownerId).map(GasAlertService::mask).orElse(null);
		return new AlertDtos.ConfigResponse(props.enabled(), mailer.configured(), props.gasThreshold(), props.recoveryThreshold(),
				props.confirm(), email);
	}

	/** "yash@gmail.com" -> "y***@gmail.com": enough to recognise the address, not to harvest it from a stolen screenshot. */
	static String mask(String email) {
		int at = email.indexOf('@');
		return at <= 1 ? "***" + email.substring(Math.max(at, 0)) : email.charAt(0) + "***" + email.substring(at);
	}

	private final Map<UUID, Instant> lastTestEmail = new ConcurrentHashMap<>();

	/**
	 * Sends a harmless test email to the caller's own registered address. Creates no incident and changes no alert state.
	 * Limited to one per minute per user so the endpoint cannot be used to spam an inbox or hammer Gmail.
	 *
	 * @return empty when rate limited
	 */
	public Optional<AlertDtos.TestEmailResponse> sendTestEmail(UUID ownerId) {
		Instant now = clock.instant();
		Instant prev = lastTestEmail.get(ownerId);
		if (prev != null && now.isBefore(prev.plusSeconds(60))) {
			return Optional.empty();
		}
		lastTestEmail.put(ownerId, now);
		Optional<String> to = recipient(ownerId);
		if (to.isEmpty()) {
			return Optional.of(new AlertDtos.TestEmailResponse("SKIPPED", null, "Your account has no email address."));
		}
		String masked = mask(to.get());
		if (!mailer.configured()) {
			return Optional.of(new AlertDtos.TestEmailResponse("SKIPPED", masked, "Email is not configured (MAIL_USERNAME / MAIL_PASSWORD)."));
		}
		try {
			mailer.send(AlertEmailTemplates.testEmail(props), to.get());
			return Optional.of(new AlertDtos.TestEmailResponse("SENT", masked, "The SMTP server accepted the message."));
		} catch (MailDeliveryException e) {
			log.warn("Test email failed: {}", e.getMessage());
			return Optional.of(new AlertDtos.TestEmailResponse("FAILED", masked, trim(e.getMessage())));
		}
	}

	/** Tests only: forget the in-memory counters. */
	public void resetCounters() {
		highStreak.clear();
		lowStreak.clear();
	}
}
