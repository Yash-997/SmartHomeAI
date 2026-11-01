package com.smarthome.alert;

/** Sends one plain-text email. Tests replace it with a recording fake; production uses {@link SmtpAlertMailer}. */
public interface AlertMailer {

	/** False when no SMTP account is set up (MAIL_USERNAME / MAIL_PASSWORD), so alerts are recorded but not mailed. */
	boolean configured();

	/** @throws MailDeliveryException with a short, secret-free reason */
	void send(String to, String subject, String body) throws MailDeliveryException;

	/** Carries a safe description only: never the SMTP password, and not the raw server message. */
	class MailDeliveryException extends Exception {

		public MailDeliveryException(String message) {
			super(message);
		}
	}
}
