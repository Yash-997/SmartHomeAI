package com.smarthome.assistant;

/** Cleaning for text that came from users or devices (names, notes, documents) before it is stored or shown to a model. */
public final class Text {

	private Text() {
	}

	/** One line: control characters and line breaks become spaces, runs of spaces collapse, and the length is capped. */
	public static String oneLine(String s, int max) {
		if (s == null) {
			return "";
		}
		String t = s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").replaceAll("\\s{2,}", " ").trim();
		return t.length() <= max ? t : t.substring(0, Math.max(0, max - 1)).trim() + "…";
	}

	/** Multi-line text (documents): control characters other than newline/tab are removed, line endings normalised. */
	public static String multiLine(String s, int max) {
		if (s == null) {
			return "";
		}
		String t = s.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
		return t.length() <= max ? t : t.substring(0, max);
	}

	/**
	 * Makes untrusted text safe to place between the prompt's delimiters: it can no longer contain the characters used by
	 * the context boundary or the suggestion block, so it cannot close the data section or fake a rule suggestion.
	 */
	public static String defang(String s) {
		return s.replace("<<<", "<​<​<").replace(">>>", ">​>​>");
	}
}
