package com.smarthome.automation;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.smarthome.telemetry.TelemetryStoredEvent;

/**
 * Hands each committed telemetry message to the engine. Only telemetry triggers rules: acknowledgements, state reports
 * and the engine's own commands do not, so an automation's action can never re-trigger automations.
 */
@Component
public class AutomationTelemetryListener {

	private final AutomationEngine engine;
	private final AutomationDispatcher dispatcher;

	public AutomationTelemetryListener(AutomationEngine engine, AutomationDispatcher dispatcher) {
		this.engine = engine;
		this.dispatcher = dispatcher;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void on(TelemetryStoredEvent event) {
		dispatcher.submit(() -> engine.onTelemetry(event));
	}
}
