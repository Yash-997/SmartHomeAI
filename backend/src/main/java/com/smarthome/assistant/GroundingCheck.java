package com.smarthome.assistant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cheap, deterministic check of a model's answer against the sources it was given. It cannot prove an answer is true,
 * but it catches the most damaging kind of invention: a number (a temperature, a count, a time) that appears nowhere in
 * the retrieved text. Such numbers are reported to the user as unverified.
 *
 * <ul>
 * <li>Citations like {@code [S3]} that do not match a real source are removed.</li>
 * <li>Every number in the answer must appear in the sources (or the question), allowing for rounding to fewer
 * decimals; signs are ignored. Small whole numbers (0-10) are exempt unless they carry a unit.</li>
 * <li>An answer with numbers but no valid citation gets a warning.</li>
 * </ul>
 */
public final class GroundingCheck {

	public record Result(String text, List<Integer> cited, List<String> unverifiedNumbers, List<String> warnings) {
		public boolean grounded() {
			return warnings.isEmpty();
		}
	}

	private static final Pattern CITATION = Pattern.compile("\\[S(\\d+)\\]");
	private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?\\d+(?:\\.\\d+)?");
	private static final Pattern UNIT_AFTER = Pattern.compile("^\\s?(?:°|C\\b|F\\b|%|ppm|raw\\b|seconds?\\b|minutes?\\b|hours?\\b|s\\b)");

	private GroundingCheck() {
	}

	public static Result check(String answer, int sourceCount, String sourceText, String question) {
		// 1. citations
		Set<Integer> cited = new LinkedHashSet<>();
		Matcher cm = CITATION.matcher(answer);
		StringBuilder cleaned = new StringBuilder();
		while (cm.find()) {
			int n = Integer.parseInt(cm.group(1).length() > 4 ? "0" : cm.group(1));
			if (n >= 1 && n <= sourceCount) {
				cited.add(n);
				cm.appendReplacement(cleaned, Matcher.quoteReplacement(cm.group()));
			} else {
				cm.appendReplacement(cleaned, "");
			}
		}
		cm.appendTail(cleaned);
		String text = cleaned.toString().replaceAll(" {2,}", " ").strip();

		// 2. numbers
		List<BigDecimal> known = numbers(sourceText + "\n" + question);
		String withoutCitations = CITATION.matcher(text).replaceAll(" ");
		List<String> unverified = new ArrayList<>();
		boolean anyNumber = false;
		Matcher nm = NUMBER.matcher(withoutCitations);
		while (nm.find()) {
			String tok = nm.group();
			BigDecimal value = new BigDecimal(tok).abs();
			anyNumber = true;
			boolean small = value.scale() == 0 && value.compareTo(BigDecimal.TEN) <= 0;
			if (small && !UNIT_AFTER.matcher(withoutCitations.substring(nm.end())).find()) {
				continue;
			}
			if (!isKnown(value, known) && !unverified.contains(tok)) {
				unverified.add(tok);
			}
		}

		List<String> warnings = new ArrayList<>();
		if (!unverified.isEmpty()) {
			warnings.add("These numbers could not be found in the sources and may be wrong: " + String.join(", ", unverified) + ".");
		}
		if (cited.isEmpty() && anyNumber) {
			warnings.add("This answer does not cite any source.");
		}
		return new Result(text, new ArrayList<>(cited), unverified, warnings);
	}

	/**
	 * Timestamps contribute only their whole-number parts (year, month, day, hour, minute, second). Their fractional
	 * seconds ("...:41.512Z") must not be able to "confirm" an invented 41.5.
	 */
	private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}(?:T\\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d+)?Z?)?");

	private static List<BigDecimal> numbers(String s) {
		List<BigDecimal> out = new ArrayList<>();
		Matcher t = TIMESTAMP.matcher(s);
		while (t.find()) {
			for (String part : t.group().split("\\.\\d+|[^0-9.]+")) {
				if (!part.isEmpty()) {
					out.add(new BigDecimal(part));
				}
			}
		}
		Matcher m = NUMBER.matcher(TIMESTAMP.matcher(s).replaceAll(" "));
		while (m.find()) {
			out.add(new BigDecimal(m.group()).abs());
		}
		return out;
	}

	/** Equal when the known number, rounded to the answer's number of decimals, gives the answer's number. */
	private static boolean isKnown(BigDecimal v, List<BigDecimal> known) {
		for (BigDecimal k : known) {
			if (k.setScale(v.scale(), RoundingMode.HALF_UP).compareTo(v) == 0) {
				return true;
			}
		}
		return false;
	}
}
