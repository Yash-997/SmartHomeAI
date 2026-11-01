package com.smarthome.automation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Wakes up regularly and runs any schedule that has become due. All the logic is in {@link AutomationEngine}. */
@Component
public class AutomationScheduler {

	private static final Logger log = LoggerFactory.getLogger(AutomationScheduler.class);

	private final AutomationEngine engine;
	private final AutomationDispatcher dispatcher;

	public AutomationScheduler(AutomationEngine engine, AutomationDispatcher dispatcher) {
		this.engine = engine;
		this.dispatcher = dispatcher;
	}

	@Scheduled(fixedDelayString = "${app.automation.schedule-tick-ms:15000}", initialDelayString = "${app.automation.schedule-tick-ms:15000}")
	public void tick() {
		try {
			// Same single worker thread as telemetry evaluation, so the two never run at the same time.
			dispatcher.submit(engine::runDueSchedules);
		} catch (RuntimeException e) {
			log.warn("Could not schedule automation tick: {}", e.getClass().getSimpleName());
		}
	}
}
