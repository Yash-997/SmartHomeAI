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

	/**
	 * Citation styles some models use instead of [S2]: 【S2】, ［S2］, [ S2 ], (S2), lists such as [S1, S3] or (S1 and S2),
	 * and gpt-oss's line markers 【S2†L4-L9】. Each becomes plain [S2] markers.
	 */
	private static final String ONE_REF = "S\\d{1,4}(?:†[^\\]】］),;\\n]{0,20})?";
	/** Whitespace, including the zero-width characters some models put inside brackets ("[​S1​]"). */
	private static final String GAP = "[\\s\\u200B-\\u200D\\u2060\\uFEFF]*";
	private static final Pattern ODD_CITATION = Pattern.compile("[\\[【［(]" + GAP + "(" + ONE_REF + "(?:" + GAP + "(?:[,;&/]|and)" + GAP + ONE_REF + ")*)" + GAP + "[\\]】］)]");
	private static final Pattern REF = Pattern.compile("(?<![\\p{L}†])S(\\d{1,4})");
	/** "1,234" -> "1234", so a thousands separator is not read as two numbers. */
	private static final Pattern THOUSANDS = Pattern.compile("(?<=\\d),(?=\\d{3}(?!\\d))");

	static String normaliseCitations(String answer) {
		Matcher m = ODD_CITATION.matcher(answer);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			StringBuilder refs = new StringBuilder();
			Matcher r = REF.matcher(m.group(1));
			while (r.find()) {
				refs.append("[S").append(r.group(1)).append(']');
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(refs.toString()));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	public static Result check(String answer, int sourceCount, String sourceText, String question) {
		// 1. citations (normalised first, so a model's bracket style does not make a cited answer look uncited)
		answer = normaliseCitations(answer);
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
		List<BigDecimal> known = numbers(THOUSANDS.matcher(sourceText + "\n" + question).replaceAll(""));
		String withoutCitations = THOUSANDS.matcher(CITATION.matcher(text).replaceAll(" ")).replaceAll("");
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
