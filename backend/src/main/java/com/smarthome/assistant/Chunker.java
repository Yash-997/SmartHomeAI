package com.smarthome.assistant;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into chunks of at most {@code maxChars}, preferring to break at blank lines, then line ends, then
 * sentence ends, then spaces. Consecutive chunks of one text share about {@code overlap} characters so a sentence
 * that straddles a boundary is still found whole in one of them.
 */
public final class Chunker {

	private Chunker() {
	}

	public static List<String> split(String text, int maxChars, int overlap) {
		if (maxChars < 50) {
			throw new IllegalArgumentException("maxChars must be at least 50");
		}
		int ov = Math.max(0, Math.min(overlap, maxChars / 3));
		String t = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
		List<String> out = new ArrayList<>();
		if (t.isEmpty()) {
			return out;
		}
		int start = 0;
		while (start < t.length()) {
			int hardEnd = Math.min(t.length(), start + maxChars);
			int end = hardEnd == t.length() ? hardEnd : breakPoint(t, start, hardEnd);
			String piece = t.substring(start, end).strip();
			if (!piece.isEmpty()) {
				out.add(piece);
			}
			if (end >= t.length()) {
				break;
			}
			int next = end - ov;
			if (next > start && ov > 0) {
				// start the overlap on a word boundary
				int space = t.indexOf(' ', next);
				if (space != -1 && space < end) {
					next = space + 1;
				}
			}
			start = Math.max(next, start + 1);
			while (start < t.length() && Character.isWhitespace(t.charAt(start))) {
				start++;
			}
		}
		return out;
	}

	/** Last good place to cut inside (start, hardEnd]; falls back to hardEnd. Never cuts in the first third. */
	private static int breakPoint(String t, int start, int hardEnd) {
		int floor = start + (hardEnd - start) / 3;
		for (String sep : new String[] { "\n\n", "\n", ". ", "? ", "! ", "; ", ", ", " " }) {
			int i = t.lastIndexOf(sep, hardEnd - sep.length());
			if (i >= floor) {
				return i + (sep.equals(" ") || sep.equals("\n") || sep.equals("\n\n") ? 0 : sep.length() - 1) + 1;
			}
		}
		return hardEnd;
	}
}
