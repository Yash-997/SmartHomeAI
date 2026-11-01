package com.smarthome.alert;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.smarthome.telemetry.TelemetryStoredEvent;

/** Feeds committed telemetry that carries a gas value to the alert service, off the MQTT thread. */
@Component
public class GasAlertTelemetryListener {

	private final GasAlertService service;
	private final AlertDispatcher dispatcher;

	public GasAlertTelemetryListener(GasAlertService service, AlertDispatcher dispatcher) {
		this.service = service;
		this.dispatcher = dispatcher;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void on(TelemetryStoredEvent event) {
		if (event.gasRaw() != null) {
			dispatcher.submit(() -> service.onTelemetry(event));
		}
	}
}
