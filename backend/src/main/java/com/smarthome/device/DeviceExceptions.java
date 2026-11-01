package com.smarthome.device;

/** Exception types for the device feature; mapped to HTTP statuses in GlobalExceptionHandler. */
public final class DeviceExceptions {

	private DeviceExceptions() {
	}

	/** Device does not exist or is not owned by the caller (deliberately indistinguishable). */
	public static class DeviceNotFoundException extends RuntimeException {
		public DeviceNotFoundException() {
			super("Device not found");
		}
	}

	/** Unknown, expired or already-used code. One message for all cases so codes can't be probed. */
	public static class InvalidPairingCodeException extends RuntimeException {
		public InvalidPairingCodeException() {
			super("Invalid or expired pairing code");
		}
	}

	public static class TooManyAttemptsException extends RuntimeException {
		public TooManyAttemptsException() {
			super("Too many failed pairing attempts. Try again later.");
		}
	}
}
