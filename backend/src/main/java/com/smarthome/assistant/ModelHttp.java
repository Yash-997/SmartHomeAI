package com.smarthome.assistant;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.smarthome.assistant.LlmUnavailableException.Reason;

/** One JSON POST to a model API, with every failure turned into a safe {@link LlmUnavailableException}. */
final class ModelHttp {

	record Response(int status, String body) {
	}

	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER).build();

	private ModelHttp() {
	}

	/** Joins a base address and a path, tolerating a trailing slash. Only http(s) addresses are accepted. */
	static URI uri(String base, String path, String what) throws LlmUnavailableException {
		try {
			URI u = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path);
			if (u.getScheme() == null || !(u.getScheme().equals("http") || u.getScheme().equals("https")) || u.getHost() == null) {
				throw new IllegalArgumentException();
			}
			return u;
		} catch (IllegalArgumentException e) {
			throw new LlmUnavailableException(Reason.NOT_CONFIGURED, "The " + what + " address is not a valid http(s) URL.");
		}
	}

	static Response postJson(URI uri, Map<String, String> headers, String json, Duration timeout, String what) throws LlmUnavailableException {
		HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json");
		headers.forEach(b::header);
		try {
			HttpResponse<String> r = CLIENT.send(b.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
					HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			return new Response(r.statusCode(), r.body());
		} catch (HttpTimeoutException e) {
			throw new LlmUnavailableException(Reason.TIMEOUT, "The " + what + " did not answer in time.");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LlmUnavailableException(Reason.NETWORK, "The request to the " + what + " was interrupted.");
		} catch (IOException e) {
			// Deliberately not including e.getMessage(): it can contain the address.
			throw new LlmUnavailableException(Reason.NETWORK, "The " + what + " could not be reached.");
		}
	}
}
