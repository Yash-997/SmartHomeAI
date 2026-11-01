package com.smarthome.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.smarthome.telemetry.TelemetryStoredEvent;

/** Feeds committed telemetry to the security service (motion alerts) and periodically checks for devices that went offline. */
@Component
public class SecurityTelemetryListener {

	private final SecurityService service;
	private final SecurityDispatcher dispatcher;

	public SecurityTelemetryListener(SecurityService service, SecurityDispatcher dispatcher) {
		this.service = service;
		this.dispatcher = dispatcher;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void on(TelemetryStoredEvent event) {
		if (Boolean.TRUE.equals(event.motion())) {          // nothing else is of interest, so most messages cost nothing here
			dispatcher.submit(() -> service.onTelemetry(event));
		}
	}

	@Scheduled(fixedDelayString = "${app.security.sweep-ms:30000}", initialDelayString = "${app.security.sweep-ms:30000}")
	void sweep() {
		dispatcher.submit(service::sweepOffline);
	}
}
