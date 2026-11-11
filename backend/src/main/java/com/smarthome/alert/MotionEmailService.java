package com.smarthome.alert;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.smarthome.alert.AlertEmailTemplates.MotionDetails;
import com.smarthome.alert.AlertMailer.MailDeliveryException;
import com.smarthome.device.Device;
import com.smarthome.security.SecurityEvent;
import com.smarthome.security.SecurityEvent.Kind;
import com.smarthome.security.SecurityEvent.Severity;
import com.smarthome.security.SecurityEventRepository;
import com.smarthome.security.SecurityMode;
import com.smarthome.user.User;
import com.smarthome.user.UserRepository;

/**
 * Emails the owner when a motion alert is recorded in AWAY mode. Security decides what an alert is (one per motion
 * episode, nothing at HOME); this class only delivers it, once per alert event.
 *
 * <p>The alert is saved before this is called, and sending happens later on the alert worker thread, so a slow or broken
 * mail server can neither delay nor prevent the security event. A failed send is recorded in the security history as an
 * INFO event with a secret-free reason (it does not count as an open alert).
 */
@Service
public class MotionEmailService {

	private static final Logger log = LoggerFactory.getLogger(MotionEmailService.class);
	private static final int REMEMBER = 2000;

	private final AlertProperties props;
	private final AlertMailer mailer;
	private final UserRepository users;
	private final SecurityEventRepository events;
	private final AlertDispatcher dispatcher;
	private final Clock clock;
	/** Alert ids already queued, so the same event can never be emailed twice (bounded, oldest forgotten first). */
	private final Set<UUID> queued = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<UUID, Boolean>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
			return size() > REMEMBER;
		}
	}));

	public MotionEmailService(AlertProperties props, AlertMailer mailer, UserRepository users, SecurityEventRepository events,
			AlertDispatcher dispatcher, Clock clock) {
		this.props = props;
		this.mailer = mailer;
		this.users = users;
		this.events = events;
		this.dispatcher = dispatcher;
		this.clock = clock;
	}

	/**
	 * Queues the email for a MOTION_ALERT that has just been saved. Does nothing for other kinds, other modes, or when motion
	 * emails are switched off. Never throws.
	 */
	public void alertSaved(SecurityEvent alert, Device device, Instant awaySince) {
		try {
			if (!props.motionEmailEnabled() || alert == null || alert.getId() == null || alert.getKind() != Kind.MOTION_ALERT
					|| alert.getMode() != SecurityMode.AWAY || device == null) {
				return;
			}
			if (!queued.add(alert.getId())) {
				return;                                             // already handled: never a second email for one event
			}
			MotionDetails details = new MotionDetails(alert.getId(), device.getName(), device.getDeviceId(), alert.getCreatedAt(), awaySince);
			UUID owner = alert.getOwnerUserId();
			UUID deviceUuid = device.getId();
			dispatcher.submit(() -> deliver(owner, deviceUuid, details));
		} catch (RuntimeException e) {
			log.warn("Could not queue the Away motion email: {}", e.getClass().getSimpleName());
		}
	}

	private void deliver(UUID owner, UUID deviceUuid, MotionDetails details) {
		Optional<String> to = users.findById(owner).map(User::getEmail).filter(e -> e != null && !e.isBlank());
		if (to.isEmpty() || !mailer.configured()) {
			log.info("Away motion alert not emailed: {}", to.isEmpty() ? "the owner has no email address" : "email is not configured");
			return;
		}
		try {
			mailer.send(AlertEmailTemplates.motionWhileAway(details, props), to.get());
		} catch (MailDeliveryException e) {
			log.warn("Away motion email for device {} failed: {}", deviceUuid, e.getMessage());
			recordFailure(owner, deviceUuid, e.getMessage());
		} catch (RuntimeException e) {
			log.warn("Away motion email for device {} failed: {}", deviceUuid, e.getClass().getSimpleName());
			recordFailure(owner, deviceUuid, "unexpected error (" + e.getClass().getSimpleName() + ")");
		}
	}

	private void recordFailure(UUID owner, UUID deviceUuid, String reason) {
		String safe = reason == null ? "unknown reason" : reason.replaceAll("\\p{Cntrl}", " ");
		if (safe.length() > 160) {
			safe = safe.substring(0, 160);
		}
		try {
			events.save(new SecurityEvent(owner, deviceUuid, Kind.ACTION_FAILED, Severity.INFO, SecurityMode.AWAY,
					"The Away motion alert email could not be sent: " + safe, clock.instant()));
		} catch (RuntimeException e) {
			log.warn("Could not record the email failure: {}", e.getClass().getSimpleName());
		}
	}
}
