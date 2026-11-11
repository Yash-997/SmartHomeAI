package com.smarthome;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.smarthome.alert.AlertMailer;
import com.smarthome.alert.EmailContent;

/** Stands in for Gmail in tests: remembers every email "sent" and can simulate "not configured" or a failing server. */
@Component
@Primary
public class RecordingAlertMailer implements AlertMailer {

	/** {@code html} is null for a plain-text email. */
	public record Sent(String to, String subject, String body, String html) {
	}

	private final List<Sent> sent = new CopyOnWriteArrayList<>();
	private volatile boolean configured = true;
	private volatile String failWith;

	public void reset() {
		sent.clear();
		configured = true;
		failWith = null;
	}

	public void setConfigured(boolean configured) {
		this.configured = configured;
	}

	/** Every send throws this message until reset (null = succeed). */
	public void failWith(String message) {
		this.failWith = message;
	}

	@Override
	public boolean configured() {
		return configured;
	}

	@Override
	public void send(String to, String subject, String body) throws MailDeliveryException {
		if (failWith != null) {
			throw new MailDeliveryException(failWith);
		}
		sent.add(new Sent(to, subject, body, null));
	}

	@Override
	public void send(EmailContent email, String to) throws MailDeliveryException {
		if (failWith != null) {
			throw new MailDeliveryException(failWith);
		}
		sent.add(new Sent(to, email.subject(), email.text(), email.html()));
	}

	public List<Sent> sent() {
		return List.copyOf(sent);
	}
}
