package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.smarthome.alert.AlertMailer.MailDeliveryException;
import com.smarthome.alert.AlertProperties;
import com.smarthome.alert.SmtpAlertMailer;

/** The real Gmail mailer, with the SMTP transport replaced: configuration checks and secret-free error reporting. */
class SmtpAlertMailerTests {

	private static final String PASSWORD = "abcd efgh ijkl mnop";

	/** Records messages, or throws the given exception instead of talking to a server. */
	static class FakeSender extends JavaMailSenderImpl {
		final List<SimpleMailMessage> sent = new ArrayList<>();
		MailException fail;

		@Override
		public void send(SimpleMailMessage... messages) {
			if (fail != null) {
				throw fail;
			}
			sent.addAll(List.of(messages));
		}
	}

	private static SmtpAlertMailer mailer(FakeSender sender, String username, String password, String mailFrom) {
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		if (sender != null) {
			beans.addBean("mailSender", sender);
		}
		return new SmtpAlertMailer(beans.getBeanProvider(JavaMailSender.class), new AlertProperties(true, 2500, 15, 2, mailFrom),
				username, password);
	}

	@Test
	void configuredOnlyWithUsernamePasswordAndASender() {
		FakeSender s = new FakeSender();
		assertTrue(mailer(s, "home@gmail.com", PASSWORD, "").configured());
		assertFalse(mailer(s, "", PASSWORD, "").configured());
		assertFalse(mailer(s, "   ", PASSWORD, "").configured());
		assertFalse(mailer(s, "home@gmail.com", "", "").configured());
		assertFalse(mailer(s, "home@gmail.com", null, "").configured());
		assertFalse(mailer(null, "home@gmail.com", PASSWORD, "").configured());
	}

	@Test
	void sendsFromTheGmailAccountUnlessAnotherSenderIsSet() throws Exception {
		FakeSender s = new FakeSender();
		mailer(s, " home@gmail.com ", PASSWORD, "").send("owner@example.com", "subject", "body");
		mailer(s, "home@gmail.com", PASSWORD, "alerts@example.com").send("owner@example.com", "subject", "body");
		assertEquals("home@gmail.com", s.sent.get(0).getFrom());
		assertEquals("owner@example.com", s.sent.get(0).getTo()[0]);
		assertEquals("alerts@example.com", s.sent.get(1).getFrom());
	}

	@Test
	void notConfiguredRefusesToSend() {
		FakeSender s = new FakeSender();
		MailDeliveryException e = assertThrows(MailDeliveryException.class, () -> mailer(s, "", "", "").send("a@b.c", "s", "b"));
		assertTrue(e.getMessage().contains("not configured"));
		assertTrue(s.sent.isEmpty());
	}

	@Test
	void loginAndConnectionFailuresAreReportedWithoutSecretsOrServerText() {
		FakeSender s = new FakeSender();
		SmtpAlertMailer m = mailer(s, "home@gmail.com", PASSWORD, "");

		s.fail = new MailAuthenticationException("535-5.7.8 Username and Password not accepted for home@gmail.com / " + PASSWORD);
		MailDeliveryException auth = assertThrows(MailDeliveryException.class, () -> m.send("a@b.c", "s", "b"));
		assertTrue(auth.getMessage().contains("SMTP login was rejected"));
		assertFalse(auth.getMessage().contains(PASSWORD));
		assertFalse(auth.getMessage().contains("535"));

		s.fail = new MailSendException("Mail server connection failed", new SocketTimeoutException("connect timed out " + PASSWORD));
		MailDeliveryException conn = assertThrows(MailDeliveryException.class, () -> m.send("a@b.c", "s", "b"));
		assertEquals("SMTP send failed (SocketTimeoutException).", conn.getMessage());
	}
}
