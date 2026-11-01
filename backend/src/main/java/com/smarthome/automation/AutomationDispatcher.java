package com.smarthome.automation;

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
 * Runs automation work on one dedicated thread, in arrival order.
 *
 * <p>Why: rules must be evaluated after the telemetry transaction commits, and acting on them publishes MQTT commands,
 * which must never happen on the MQTT callback thread or inside a database transaction. One thread also means
 * evaluations for the same device never overlap. The queue is bounded: if the system is flooded, new evaluations are
 * dropped (and logged) instead of exhausting memory.
 */
@Component
public class AutomationDispatcher {

	private static final Logger log = LoggerFactory.getLogger(AutomationDispatcher.class);

	private final ThreadPoolExecutor executor;

	public AutomationDispatcher(AutomationProperties props) {
		this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(props.queueCapacity()), r -> {
					Thread t = new Thread(r, "automation-engine");
					t.setDaemon(true);
					return t;
				});
	}

	public void submit(Runnable work) {
		try {
			executor.execute(() -> {
				try {
					work.run();
				} catch (RuntimeException e) {
					// The engine handles its own errors; this is the last line of defence for the worker thread.
					log.error("Automation work failed: {}", e.getClass().getSimpleName());
				}
			});
		} catch (RejectedExecutionException e) {
			log.warn("Automation queue is full; a telemetry evaluation was dropped");
		}
	}

	/** Blocks until everything submitted so far has finished. Used by tests. */
	public void awaitIdle() {
		try {
			Future<?> marker = executor.submit(() -> { });
			marker.get(30, TimeUnit.SECONDS);
		} catch (ExecutionException | java.util.concurrent.TimeoutException e) {
			throw new IllegalStateException("Automation queue did not drain", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@PreDestroy
	void stop() {
		executor.shutdownNow();
	}
}
