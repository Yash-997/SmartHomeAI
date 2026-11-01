package com.smarthome.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Built-in embedder: "feature hashing" of words and word pairs into a fixed-size vector, normalised to length 1, so
 * cosine similarity measures shared vocabulary. It needs no network, key or model download and is fully deterministic.
 *
 * It is NOT a neural embedding: it matches on words (after simple plural/verb-ending stripping and a small table of
 * smart-home synonyms), not on meaning, so "it is boiling in here" will not find "temperature" on its own. Configure
 * EMBEDDING_PROVIDER=openai with a real embedding model for semantic matching.
 */
public class LocalHashEmbedding implements EmbeddingClient {

	static final int DIMS = 256;
	static final String MODEL_ID = "local-hash-256-v1";

	private static final Set<String> STOP = Set.of("a", "an", "the", "is", "are", "was", "were", "be", "been", "am", "do", "does", "did",
			"of", "to", "in", "on", "at", "for", "and", "or", "it", "its", "this", "that", "these", "those", "my", "me", "i", "you", "your",
			"what", "which", "who", "whom", "how", "when", "where", "why", "there", "with", "as", "by", "from", "can", "could", "would",
			"should", "will", "about", "any", "has", "have", "had", "now", "please", "tell", "show", "much", "many", "than", "then", "so",
			"if", "not", "no", "yes", "up", "out", "over", "into", "right", "currently", "current");

	/** word -> extra token that is indexed alongside it, so related wording lands nearby. Keys are stemmed when the table is built. */
	private static final Map<String, String> SYNONYMS = stemmedKeys(Map.ofEntries(
			Map.entry("hot", "temperature"), Map.entry("cold", "temperature"), Map.entry("warm", "temperature"),
			Map.entry("cool", "temperature"), Map.entry("heat", "temperature"), Map.entry("degree", "temperature"),
			Map.entry("temp", "temperature"), Map.entry("celsius", "temperature"),
			Map.entry("humid", "humidity"), Map.entry("damp", "humidity"), Map.entry("moist", "humidity"), Map.entry("dry", "humidity"),
			Map.entry("smoke", "gas"), Map.entry("leak", "gas"), Map.entry("mq2", "gas"), Map.entry("mq", "gas"),
			Map.entry("movement", "motion"), Map.entry("presence", "motion"), Map.entry("pir", "motion"), Map.entry("someone", "motion"),
			Map.entry("occupied", "motion"), Map.entry("occupancy", "motion"),
			Map.entry("automate", "automation"), Map.entry("rule", "automation"), Map.entry("schedule", "automation"),
			Map.entry("unusual", "anomaly"), Map.entry("abnormal", "anomaly"), Map.entry("strange", "anomaly"),
			Map.entry("weird", "anomaly"), Map.entry("outlier", "anomaly"), Map.entry("alert", "anomaly"), Map.entry("problem", "anomaly"),
			Map.entry("online", "connection"), Map.entry("offline", "connection"), Map.entry("connected", "connection"),
			Map.entry("disconnected", "connection"), Map.entry("status", "connection"),
			Map.entry("light", "led"), Map.entry("lamp", "led"), Map.entry("prediction", "predict"), Map.entry("forecast", "predict")));

	private static Map<String, String> stemmedKeys(Map<String, String> raw) {
		Map<String, String> out = new java.util.HashMap<>();
		raw.forEach((k, v) -> out.put(stem(k), v));
		return Map.copyOf(out);
	}

	@Override
	public String modelId() {
		return MODEL_ID;
	}

	@Override
	public boolean local() {
		return true;
	}

	@Override
	public List<float[]> embed(List<String> texts) {
		List<float[]> out = new ArrayList<>(texts.size());
		for (String t : texts) {
			out.add(vector(t));
		}
		return out;
	}

	static float[] vector(String text) {
		List<String> tokens = tokens(text);
		// Count each feature first and weight it 1 + ln(count): a word repeated ten times (a device name) must not
		// drown out the other words of a chunk.
		Map<String, double[]> counts = new java.util.LinkedHashMap<>();
		String prev = null;
		for (String tok : tokens) {
			count(counts, tok, 1.0);
			String syn = SYNONYMS.get(tok);
			if (syn != null) {
				count(counts, syn, 0.8);
			}
			if (prev != null) {
				count(counts, prev + "_" + tok, 0.5);
			}
			prev = tok;
		}
		float[] v = new float[DIMS];
		counts.forEach((feature, c) -> add(v, feature, (float) (c[0] * (1 + Math.log(c[1])))));
		double norm = 0;
		for (float x : v) {
			norm += (double) x * x;
		}
		if (norm > 0) {
			float inv = (float) (1.0 / Math.sqrt(norm));
			for (int i = 0; i < v.length; i++) {
				v[i] *= inv;
			}
		}
		return v;
	}

	static List<String> tokens(String text) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		// Identifiers are random: they must not change where a chunk lands, so UUIDs and long letter-and-digit codes are dropped.
		String s = text == null ? "" : UUID_PATTERN.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
		for (int i = 0; i <= s.length(); i++) {
			char c = i < s.length() ? s.charAt(i) : ' ';
			if (Character.isLetterOrDigit(c)) {
				cur.append(c);
			} else if (cur.length() > 0) {
				String raw = cur.toString();
				cur.setLength(0);
				String w = stem(raw);
				if (!w.isEmpty() && !STOP.contains(w) && !looksLikeId(raw)) {
					out.add(w);
				}
			}
		}
		return out;
	}

	private static final java.util.regex.Pattern UUID_PATTERN =
			java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	/** Eight or more characters mixing letters and digits (hardware ids, hashes, compact timestamps). */
	private static boolean looksLikeId(String w) {
		if (w.length() < 8) {
			return false;
		}
		boolean letter = false;
		boolean digit = false;
		for (int i = 0; i < w.length(); i++) {
			letter |= Character.isLetter(w.charAt(i));
			digit |= Character.isDigit(w.charAt(i));
		}
		return letter && digit;
	}

	/** Very small suffix stripper: enough to make "readings"/"reading" and "sensors"/"sensor" match. */
	static String stem(String w) {
		String s = w;
		if (s.length() > 4 && s.endsWith("ies")) {
			s = s.substring(0, s.length() - 3) + "y";
		} else if (s.length() > 3 && s.endsWith("s") && !s.endsWith("ss")) {
			s = s.substring(0, s.length() - 1);
		}
		if (s.length() > 5 && s.endsWith("ing")) {
			s = s.substring(0, s.length() - 3);
		} else if (s.length() > 4 && s.endsWith("ed")) {
			s = s.substring(0, s.length() - 2);
		}
		return s;
	}

	/** c[0] = base weight of the feature, c[1] = how many times it occurred. */
	private static void count(Map<String, double[]> counts, String feature, double weight) {
		double[] c = counts.computeIfAbsent(feature, k -> new double[] { weight, 0 });
		c[1]++;
	}

	private static void add(float[] v, String feature, float weight) {
		long h = 0xcbf29ce484222325L;                      // FNV-1a, 64 bit
		for (int i = 0; i < feature.length(); i++) {
			h ^= feature.charAt(i);
			h *= 0x100000001b3L;
		}
		int idx = (int) Long.remainderUnsigned(h, DIMS);
		v[idx] += ((h >>> 63) == 0 ? 1 : -1) * weight;
	}
}
