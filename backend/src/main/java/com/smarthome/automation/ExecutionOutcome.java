package com.smarthome.automation;

/** What happened when a rule was evaluated and (maybe) acted. Everything except EXECUTED is a deliberate no-op. */
public enum ExecutionOutcome {
	/** A command was handed to the existing command service (see commandId). */
	EXECUTED,
	/** The command could not be created or delivered. */
	FAILED,
	SKIPPED_COOLDOWN,
	SKIPPED_OFFLINE,
	/** A needed sensor reading was missing (never treated as zero). */
	SKIPPED_NO_DATA,
	/** The latest reading is too old to act on. */
	SKIPPED_STALE_DATA,
	/** A higher-priority rule owns the same output right now. */
	SKIPPED_CONFLICT,
	/** The device already reports the requested mode. */
	SKIPPED_ALREADY_SET,
	SKIPPED_RATE_LIMITED,
	/** The same rule was already being executed. */
	SKIPPED_BUSY,
	/** The owner no longer owns the target device. */
	SKIPPED_NOT_OWNED,
	/** The action is not allowed (for example anything that would switch off the gas warning). */
	SKIPPED_UNSAFE,
	/** A scheduled rule fired but its conditions were false. */
	SKIPPED_CONDITIONS_FALSE,
	/** The backend was not running during the scheduled window. */
	SKIPPED_MISSED;

	/** Outcomes that are folded into the previous row when they repeat. */
	public boolean isCompactable() {
		return this != EXECUTED && this != FAILED;
	}
}
