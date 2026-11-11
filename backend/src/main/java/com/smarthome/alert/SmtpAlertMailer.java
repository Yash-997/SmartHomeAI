package com.smarthome.alert;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

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
		} catch (MailException e) {
			throw safe(e);
		}
	}

	/** HTML with a plain-text alternative, UTF-8. Same secret-free error reporting as plain text. */
	@Override
	public void send(EmailContent email, String to) throws MailDeliveryException {
		JavaMailSender mail = sender.getIfAvailable();
		if (!configured || mail == null) {
			throw new MailDeliveryException("Email is not configured (MAIL_USERNAME / MAIL_PASSWORD).");
		}
		MimeMessage msg = mail.createMimeMessage();
		try {
			MimeMessageHelper h = new MimeMessageHelper(msg, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
			h.setFrom(from);
			h.setTo(to);
			h.setSubject(email.subject());
			h.setText(email.text(), email.html());
		} catch (MessagingException e) {
			throw new MailDeliveryException("Email could not be built (" + e.getClass().getSimpleName() + ").");
		}
		try {
			mail.send(msg);
		} catch (MailException e) {
			throw safe(e);
		}
	}

	private static MailDeliveryException safe(MailException e) {
		if (e instanceof MailAuthenticationException) {
			return new MailDeliveryException("SMTP login was rejected. Check MAIL_USERNAME and the Google App Password.");
		}
		if (e instanceof MailSendException send) {
			return new MailDeliveryException("SMTP send failed (" + send.getMostSpecificCause().getClass().getSimpleName() + ").");
		}
		return new MailDeliveryException("Email could not be sent (" + e.getClass().getSimpleName() + ").");
	}
}
