package com.smarthome.assistant;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

/** A throwaway local HTTP server that plays back scripted model API replies and records every request. */
final class StubModelServer implements AutoCloseable {

	record Reply(int status, String body, long delayMs) {
	}

	record Request(String path, String query, Headers headers, String body) {
	}

	private final HttpServer server;
	private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
	final List<Request> requests = new CopyOnWriteArrayList<>();

	StubModelServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", ex -> {
			requests.add(new Request(ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), ex.getRequestHeaders(),
					new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
			Reply r = replies.poll();
			if (r == null) {
				r = new Reply(500, "{\"error\":\"no scripted reply\"}", 0);
			}
			if (r.delayMs() > 0) {
				try {
					Thread.sleep(r.delayMs());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "application/json");
			try {
				ex.sendResponseHeaders(r.status(), out.length == 0 ? -1 : out.length);
				if (out.length > 0) {
					ex.getResponseBody().write(out);
				}
			} catch (IOException ignored) {
				// the client gave up (timeout tests)
			}
			ex.close();
		});
		server.start();
	}

	StubModelServer reply(int status, String body) {
		replies.add(new Reply(status, body, 0));
		return this;
	}

	StubModelServer slowReply(long delayMs, String body) {
		replies.add(new Reply(200, body, delayMs));
		return this;
	}

	String base() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	Request last() {
		return requests.get(requests.size() - 1);
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
