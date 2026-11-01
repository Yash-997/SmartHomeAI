package com.smarthome.ml;

import java.util.Map;

/** Exception types for the ML features; mapped to HTTP statuses in GlobalExceptionHandler. */
public final class MlExceptions {

	private MlExceptions() {
	}

	/** 409: there is no usable model, and the message says exactly why. */
	public static class ModelNotReadyException extends RuntimeException {
		public ModelNotReadyException(String message) {
			super(message);
		}
	}

	/** 409: a training run for this device is already in progress. */
	public static class TrainingBusyException extends RuntimeException {
		public TrainingBusyException() {
			super("Training for this device is already running. Try again in a moment.");
		}
	}

	/** 429: asked again too soon. */
	public static class TooFrequentException extends RuntimeException {
		public TooFrequentException(String message) {
			super(message);
		}
	}

	/** 400 with a per-field message. */
	public static class InvalidMlRequestException extends RuntimeException {
		private final Map<String, String> fieldErrors;

		public InvalidMlRequestException(String field, String message) {
			super("Invalid request");
			this.fieldErrors = Map.of(field, message);
		}

		public Map<String, String> fieldErrors() {
			return fieldErrors;
		}
	}

	/** 404. */
	public static class AnomalyNotFoundException extends RuntimeException {
		public AnomalyNotFoundException() {
			super("Anomaly not found");
		}
	}

	/** 409: the anomaly is already closed. */
	public static class InvalidAnomalyTransitionException extends RuntimeException {
		public InvalidAnomalyTransitionException(String message) {
			super(message);
		}
	}
}
