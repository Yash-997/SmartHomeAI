package com.smarthome.alert;

/** Sends one email. Tests replace it with a recording fake; production uses {@link SmtpAlertMailer}. */
public interface AlertMailer {

	/** False when no SMTP account is set up (MAIL_USERNAME / MAIL_PASSWORD), so alerts are recorded but not mailed. */
	boolean configured();

	/** A plain-text email. @throws MailDeliveryException with a short, secret-free reason */
	void send(String to, String subject, String body) throws MailDeliveryException;

	/**
	 * An HTML email with a plain-text alternative (multipart/alternative), for mail apps that do not show HTML. The default
	 * sends the text part only.
	 *
	 * @throws MailDeliveryException with a short, secret-free reason
	 */
	default void send(EmailContent email, String to) throws MailDeliveryException {
		send(to, email.subject(), email.text());
	}

	/** Carries a safe description only: never the SMTP password, and not the raw server message. */
	class MailDeliveryException extends Exception {

		public MailDeliveryException(String message) {
			super(message);
		}
	}
}
