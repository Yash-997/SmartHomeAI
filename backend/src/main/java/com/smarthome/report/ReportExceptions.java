package com.smarthome.report;

/** Report errors; mapped to HTTP statuses by {@link ReportController}'s handlers. */
public final class ReportExceptions {

	private ReportExceptions() {
	}

	/** 404: missing, or another user's (the two look the same). */
	public static class ReportNotFoundException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public ReportNotFoundException() {
			super("Report not found");
		}
	}

	/** 429: too many saved reports, or too many generated in a minute. */
	public static class TooManyReportsException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public TooManyReportsException(String message) {
			super(message);
		}
	}

	/** 503: the database could not be reached; nothing was stored. */
	public static class ReportUnavailableException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public ReportUnavailableException() {
			super("Reports are unavailable right now because the database could not be reached. Nothing was changed; please try again shortly.");
		}
	}
}
