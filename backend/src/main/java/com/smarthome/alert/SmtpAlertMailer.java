package com.smarthome.alert;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Gmail SMTP (smtp.gmail.com:587, STARTTLS) through Spring's JavaMailSender. The account comes from MAIL_USERNAME and a
 * Google App Password in MAIL_PASSWORD; neither is ever logged, stored or returned.
 */
@Component
public class SmtpAlertMailer implements AlertMailer {

	private final ObjectProvider<JavaMailSender> sender;
	private final String username;
	private final String from;

	public SmtpAlertMailer(ObjectProvider<JavaMailSender> sender, AlertProperties props,
			@Value("${spring.mail.username:}") String username, @Value("${spring.mail.password:}") String password) {
		this.sender = sender;
		this.username = username == null ? "" : username.trim();
		String f = props.mailFrom() == null ? "" : props.mailFrom().trim();
		this.from = f.isEmpty() ? this.username : f;
		this.configured = !this.username.isEmpty() && password != null && !password.isBlank();
	}

	private final boolean configured;

	@Override
	public boolean configured() {
		return configured && sender.getIfAvailable() != null;
	}

	@Override
	public void send(String to, String subject, String body) throws MailDeliveryException {
		JavaMailSender mail = sender.getIfAvailable();
		if (!configured || mail == null) {
			throw new MailDeliveryException("Email is not configured (MAIL_USERNAME / MAIL_PASSWORD).");
		}
		SimpleMailMessage msg = new SimpleMailMessage();
		msg.setFrom(from);
		msg.setTo(to);
		msg.setSubject(subject);
		msg.setText(body);
		try {
			mail.send(msg);
		} catch (MailAuthenticationException e) {
			throw new MailDeliveryException("SMTP login was rejected. Check MAIL_USERNAME and the Google App Password.");
		} catch (MailSendException e) {
			Throwable cause = e.getMostSpecificCause();
			throw new MailDeliveryException("SMTP send failed (" + cause.getClass().getSimpleName() + ").");
		} catch (MailException e) {
			throw new MailDeliveryException("Email could not be sent (" + e.getClass().getSimpleName() + ").");
		}
	}
}
