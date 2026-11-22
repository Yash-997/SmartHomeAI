package com.smarthome.alert;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends mail through Brevo's HTTPS API (port 443) instead of SMTP, for hosts that block outbound SMTP. Enabled with
 * MAIL_PROVIDER=brevo; needs BREVO_API_KEY and a sender address verified in Brevo (ALERT_MAIL_FROM). The key is never
 * logged or returned, and error messages carry only the HTTP status.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "brevo")
public class BrevoAlertMailer implements AlertMailer {

	private static final URI ENDPOINT = URI.create("https://api.brevo.com/v3/smtp/email");

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ObjectMapper json = new ObjectMapper();
	private final String apiKey;
	private final String from;

	public BrevoAlertMailer(AlertProperties props, @Value("${app.mail.brevo.api-key:}") String apiKey) {
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.from = props.mailFrom() == null ? "" : props.mailFrom().trim();
	}

	@Override
	public boolean configured() {
		return !apiKey.isEmpty() && !from.isEmpty();
	}

	@Override
	public void send(String to, String subject, String body) throws MailDeliveryException {
		post(Map.of("sender", Map.of("email", from), "to", List.of(Map.of("email", to)), "subject", subject,
				"textContent", body));
	}

	@Override
	public void send(EmailContent email, String to) throws MailDeliveryException {
		post(Map.of("sender", Map.of("email", from), "to", List.of(Map.of("email", to)), "subject", email.subject(),
				"htmlContent", email.html(), "textContent", email.text()));
	}

	private void post(Map<String, Object> payload) throws MailDeliveryException {
		if (!configured()) {
			throw new MailDeliveryException("Email is not configured (BREVO_API_KEY / ALERT_MAIL_FROM).");
		}
		try {
			HttpRequest req = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(15))
					.header("api-key", apiKey).header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
			HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
			int code = res.statusCode();
			if (code == 401 || code == 403) {
				throw new MailDeliveryException("Brevo rejected the API key or sender (HTTP " + code + "). Check BREVO_API_KEY and verify the sender address.");
			}
			if (code / 100 != 2) {
				throw new MailDeliveryException("Brevo send failed (HTTP " + code + ").");
			}
		} catch (JacksonException e) {
			throw new MailDeliveryException("Email could not be built.");
		} catch (IOException e) {
			throw new MailDeliveryException("Brevo could not be reached (" + e.getClass().getSimpleName() + ").");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MailDeliveryException("Email sending was interrupted.");
		}
	}
}
