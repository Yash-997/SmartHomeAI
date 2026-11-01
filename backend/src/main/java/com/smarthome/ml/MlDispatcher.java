package com.smarthome.ml;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Runs ML work (anomaly scans, training) on one dedicated, low-priority thread so it can never delay the MQTT
 * consumer, the command sweeper or the automation engine. The queue is tiny on purpose: if a scan is still running when
 * the next tick arrives, that tick is simply skipped.
 */
@Component
public class MlDispatcher {

	private static final Logger log = LoggerFactory.getLogger(MlDispatcher.class);

	private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(4), r -> {
		Thread t = new Thread(r, "ml-worker");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	public void submit(Runnable work) {
		try {
			executor.execute(() -> {
				try {
					work.run();
				} catch (RuntimeException e) {
					log.error("ML work failed: {}", e.getClass().getSimpleName());
				}
			});
		} catch (RejectedExecutionException e) {
			log.debug("ML worker busy; skipping this tick");
		}
	}

	/** Blocks until everything submitted so far has finished. Used by tests. */
	public void awaitIdle() {
		try {
			Future<?> marker = executor.submit(() -> { });
			marker.get(60, TimeUnit.SECONDS);
		} catch (ExecutionException | TimeoutException e) {
			throw new IllegalStateException("ML queue did not drain", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@PreDestroy
	void stop() {
		executor.shutdownNow();
	}
}
