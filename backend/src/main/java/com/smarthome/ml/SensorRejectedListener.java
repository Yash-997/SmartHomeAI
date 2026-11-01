package com.smarthome.ml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.smarthome.telemetry.SensorRejectedEvent;

/**
 * Turns "this device sent an impossible sensor value" into an out-of-range anomaly. Repeats are folded into one row.
 *
 * <p>The write is handed to the ML worker thread on purpose. An AFTER_COMMIT listener still runs with the finished
 * transaction's resources bound to the thread, so a repository call made right there would silently join that
 * already-committed transaction and never be saved. The worker thread has no transaction, so each write is its own.
 * It also keeps this write off the MQTT thread.
 */
@Component
public class SensorRejectedListener {

	private static final Logger log = LoggerFactory.getLogger(SensorRejectedListener.class);

	private final AnomalyService anomalies;
	private final MlDispatcher dispatcher;

	public SensorRejectedListener(AnomalyService anomalies, MlDispatcher dispatcher) {
		this.anomalies = anomalies;
		this.dispatcher = dispatcher;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void on(SensorRejectedEvent event) {
		dispatcher.submit(() -> {
			try {
				anomalies.recordRejected(event);
			} catch (RuntimeException e) {
				// Recording an anomaly must never disturb anything else.
				log.warn("Could not record out-of-range anomaly: {}", e.getClass().getSimpleName());
			}
		});
	}
}
