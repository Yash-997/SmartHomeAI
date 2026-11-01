package com.smarthome.security;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Runs security work on one dedicated thread, in arrival order: after the telemetry transaction has committed (work
 * started from an AFTER_COMMIT callback would silently join the finished transaction), never on the MQTT thread, and
 * never two evaluations for the same device at once (which also makes the alert cooldown race-free). Bounded queue: a
 * flood drops work and logs it rather than exhausting memory.
 */
@Component
public class SecurityDispatcher {

	private static final Logger log = LoggerFactory.getLogger(SecurityDispatcher.class);

	private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(2000), r -> {
		Thread t = new Thread(r, "security-worker");
		t.setDaemon(true);
		return t;
	});

	public void submit(Runnable work) {
		try {
			executor.execute(() -> {
				try {
					work.run();
				} catch (RuntimeException e) {
					log.error("Security work failed: {}", e.getClass().getSimpleName());
				}
			});
		} catch (RejectedExecutionException e) {
			log.warn("Security queue is full; a telemetry evaluation was dropped");
		}
	}

	/** Blocks until everything submitted so far has finished. Used by tests. */
	public void awaitIdle() {
		try {
			Future<?> marker = executor.submit(() -> { });
			marker.get(30, TimeUnit.SECONDS);
		} catch (ExecutionException | java.util.concurrent.TimeoutException e) {
			throw new IllegalStateException("Security queue did not drain", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@PreDestroy
	void stop() {
		executor.shutdownNow();
	}
}
