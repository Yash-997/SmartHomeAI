package com.smarthome.command;

import java.util.Map;

/** Exception types for commands; mapped to HTTP statuses in GlobalExceptionHandler. */
public final class CommandExceptions {

	private CommandExceptions() {
	}

	/** 400: unknown action, or a value this action doesn't accept. */
	public static class InvalidCommandException extends RuntimeException {
		private final Map<String, String> fieldErrors;

		public InvalidCommandException(String field, String message) {
			super("Invalid command");
			this.fieldErrors = Map.of(field, message);
		}

		public Map<String, String> fieldErrors() {
			return fieldErrors;
		}
	}

	/** 404: no such command for this device. */
	public static class CommandNotFoundException extends RuntimeException {
		public CommandNotFoundException() {
			super("Command not found");
		}
	}

	/** 409: the device isn't currently reachable, so nothing was sent. */
	public static class DeviceOfflineException extends RuntimeException {
		public DeviceOfflineException(String message) {
			super(message);
		}
	}

	/** 429: too many commands already waiting for this device. */
	public static class TooManyCommandsException extends RuntimeException {
		public TooManyCommandsException() {
			super("Too many commands are already waiting for this device. Wait for them to finish.");
		}
	}
}
