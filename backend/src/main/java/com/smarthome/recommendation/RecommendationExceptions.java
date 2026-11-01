package com.smarthome.recommendation;

/** Exception types for recommendations; mapped to HTTP statuses in GlobalExceptionHandler. */
public final class RecommendationExceptions {

	private RecommendationExceptions() {
	}

	/** 404: not found, or not yours. */
	public static class RecommendationNotFoundException extends RuntimeException {
		public RecommendationNotFoundException() {
			super("Recommendation not found");
		}
	}

	/** 409: already applied, dismissed or out of date. */
	public static class RecommendationNotPendingException extends RuntimeException {
		public RecommendationNotPendingException(String message) {
			super(message);
		}
	}
}
