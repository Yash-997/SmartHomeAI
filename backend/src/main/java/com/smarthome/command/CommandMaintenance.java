package com.smarthome.command;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically settles commands that can no longer complete normally (expired, or never acknowledged). */
@Component
public class CommandMaintenance {

	private static final Logger log = LoggerFactory.getLogger(CommandMaintenance.class);

	private final CommandService commands;

	public CommandMaintenance(CommandService commands) {
		this.commands = commands;
	}

	@Scheduled(fixedDelayString = "${app.commands.sweep-ms:2000}", initialDelayString = "${app.commands.sweep-ms:2000}")
	public void sweep() {
		try {
			int changed = commands.sweep();
			if (changed > 0) {
				log.info("Settled {} overdue command(s)", changed);
			}
		} catch (RuntimeException e) {
			log.warn("Command sweep failed: {}", e.getClass().getSimpleName());
		}
	}
}
