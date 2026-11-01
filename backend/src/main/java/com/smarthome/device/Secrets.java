package com.smarthome.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/** Generation and hashing of pairing codes and device credentials. Plain values are never stored or logged. */
final class Secrets {

	// No I, L, O, 0 or 1, so codes are easy to read and type.
	private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
	private static final int CODE_LENGTH = 8;
	private static final SecureRandom RANDOM = new SecureRandom();

	private Secrets() {
	}

	/** 8 random characters (about 39 bits), unformatted. */
	static String newPairingCode() {
		StringBuilder sb = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++) {
			sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
		}
		return sb.toString();
	}

	/** "ABCD2345" -> "ABCD-2345" for display. */
	static String formatCode(String code) {
		return code.substring(0, 4) + "-" + code.substring(4);
	}

	/** Accepts what a person might type: any case, with or without dash or spaces. */
	static String normalizeCode(String input) {
		return input.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
	}

	/** 256-bit random credential, URL-safe. */
	static String newCredential() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** SHA-256 is appropriate here because both inputs are high-entropy random values (unlike passwords). */
	static String sha256Hex(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Constant-time comparison of two hex digests. */
	static boolean hashesEqual(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
}
