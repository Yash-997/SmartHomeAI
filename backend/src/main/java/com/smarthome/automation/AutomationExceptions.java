package com.smarthome.automation;

import java.util.Map;

/** Exception types for automation; mapped to HTTP statuses in GlobalExceptionHandler. */
public final class AutomationExceptions {

	private AutomationExceptions() {
	}

	/** 400 with per-field messages such as {@code conditions[1].value}. */
	public static class InvalidRuleException extends RuntimeException {
		private final Map<String, String> fieldErrors;

		public InvalidRuleException(Map<String, String> fieldErrors) {
			super("Invalid automation rule");
			this.fieldErrors = Map.copyOf(fieldErrors);
		}

		public InvalidRuleException(String field, String message) {
			this(Map.of(field, message));
		}

		public Map<String, String> fieldErrors() {
			return fieldErrors;
		}
	}

	/** 404: not yours, or it does not exist (indistinguishable on purpose). */
	public static class RuleNotFoundException extends RuntimeException {
		public RuleNotFoundException() {
			super("Automation rule not found");
		}
	}
}
