package com.smarthome.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.smarthome.assistant.RagChunkStore.Hit;

/**
 * Hybrid retrieval over the owner-scoped chunk store:
 * <ol>
 * <li><b>Vector search</b> (pgvector cosine, or the same in Java on H2) for meaning;</li>
 * <li><b>Keyword search</b> (SQL LIKE prefilter, then BM25 scoring in Java) for exact words such as device names, error
 * kinds and rule names that word-hash or neural embeddings can blur;</li>
 * <li><b>Reciprocal-rank fusion</b> of the two lists (robust to their different score scales);</li>
 * <li><b>Reranking</b> with small, explainable boosts: chunks about a device the question names, and for questions about
 * readings the per-device status chunks (the record of the latest values) first.</li>
 * </ol>
 * Everything only reorders chunks the owner-scoped searches returned; nothing can widen what the caller may see.
 */
@Component
public class HybridRetriever implements com.smarthome.assistant.tools.SmartHomeTools.KnowledgeSearch {

	static final int RRF_K = 60;
	private static final double K1 = 1.2;
	private static final double B = 0.75;

	private final RagChunkStore store;
	private final EmbeddingClient embedder;

	public HybridRetriever(RagChunkStore store, EmbeddingClient embedder) {
		this.store = store;
		this.embedder = embedder;
	}

	/** A ranked chunk: {@code hit.score()} stays the displayable match (cosine or normalised BM25), {@code rank} orders. */
	record Ranked(Hit hit, double rank) {
	}

	public List<Hit> retrieve(UUID owner, String question, int topK, double minScore, boolean readings, Map<UUID, String> deviceNames)
			throws LlmUnavailableException {
		float[] q = embedder.embedOne(question);
		String model = embedder.modelId();
		List<Hit> vector = store.search(owner, q, model, topK * 4, minScore);
		List<String> terms = terms(question);
		List<Hit> keyword = bm25(terms, store.keywordCandidates(owner, terms, model, 300), store.countSearchable(owner, model), topK * 4);
		List<Hit> extra = readings ? store.ownedOfType(owner, LiveFacts.DEVICE_STATUS, q, model, 3) : List.of();
		return fuse(question, deviceNames, vector, keyword, extra, topK, readings);
	}

	/** Help text and the caller's own documents only (explanations, not live numbers). */
	@Override
	public List<String[]> search(UUID owner, String query, int limit) {
		try {
			List<String[]> out = new ArrayList<>();
			for (Hit h : retrieve(owner, query, limit * 3, 0.05, false, Map.of())) {
				if ((RagIndexer.KNOWLEDGE.equals(h.sourceType()) || RagIndexer.DOCUMENT.equals(h.sourceType())) && out.size() < limit) {
					out.add(new String[] { h.title(), h.content() });
				}
			}
			return out;
		} catch (LlmUnavailableException e) {
			throw new com.smarthome.assistant.tools.ToolTypes.ToolArgumentException("The search service is unavailable right now: " + e.getMessage());
		}
	}

	/** Query preprocessing: stemmed, stop-word-free tokens plus their synonyms ("temp" also searches "temperature"). */
	static List<String> terms(String question) {
		Set<String> out = new LinkedHashSet<>();
		for (String t : LocalHashEmbedding.tokens(question)) {
			if (t.length() >= 2) {
				out.add(t);
			}
			String syn = LocalHashEmbedding.synonym(t);
			if (syn != null) {
				out.add(LocalHashEmbedding.stem(syn));
			}
		}
		return new ArrayList<>(out);
	}

	/** Okapi BM25 over the candidates; scores are normalised to [0, 1] and returned best first. */
	static List<Hit> bm25(List<String> terms, List<Hit> candidates, long corpusSize, int limit) {
		if (terms.isEmpty() || candidates.isEmpty()) {
			return List.of();
		}
		List<List<String>> docs = new ArrayList<>();
		Map<String, Integer> df = new HashMap<>();
		double totalLen = 0;
		for (Hit h : candidates) {
			List<String> toks = LocalHashEmbedding.tokens(h.content());
			docs.add(toks);
			totalLen += toks.size();
			for (String t : new HashSet<>(toks)) {
				df.merge(t, 1, Integer::sum);
			}
		}
		double avgdl = Math.max(1, totalLen / candidates.size());
		long n = Math.max(corpusSize, candidates.size());
		double[] scores = new double[candidates.size()];
		double best = 0;
		for (int i = 0; i < candidates.size(); i++) {
			List<String> toks = docs.get(i);
			Map<String, Integer> tf = new HashMap<>();
			toks.forEach(t -> tf.merge(t, 1, Integer::sum));
			double s = 0;
			for (String term : terms) {
				int f = tf.getOrDefault(term, 0);
				if (f == 0) {
					continue;
				}
				int d = df.getOrDefault(term, 0);
				double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
				s += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * toks.size() / avgdl));
			}
			scores[i] = s;
			best = Math.max(best, s);
		}
		List<Hit> out = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			if (scores[i] > 0) {
				Hit h = candidates.get(i);
				out.add(new Hit(h.id(), h.ownerUserId(), h.deviceUuid(), h.sourceType(), h.sourceKey(), h.chunkIndex(), h.title(), h.content(),
						h.asOf(), scores[i] / best));
			}
		}
		out.sort(Comparator.comparingDouble(Hit::score).reversed());
		return out.size() > limit ? out.subList(0, limit) : out;
	}

	/**
	 * Reciprocal-rank fusion of the vector and keyword lists, plus the reading boosts. The displayed score of a chunk found
	 * by both lists is the higher of its two (each is in [0, 1]).
	 */
	static List<Hit> fuse(String question, Map<UUID, String> deviceNames, List<Hit> vector, List<Hit> keyword, List<Hit> extra, int topK, boolean readings) {
		Map<UUID, Hit> byId = new LinkedHashMap<>();
		Map<UUID, Double> rrf = new HashMap<>();
		addRanks(vector, byId, rrf);
		addRanks(keyword, byId, rrf);
		for (Hit h : extra) {
			if (h.deviceUuid() != null && !byId.containsKey(h.id())) {
				byId.put(h.id(), h);
				rrf.put(h.id(), 0.0);
			}
		}
		String q = question.toLowerCase(Locale.ROOT);
		Set<UUID> mentioned = new HashSet<>();
		deviceNames.forEach((id, name) -> {
			if (name.length() >= 3 && q.contains(name.toLowerCase(Locale.ROOT))) {
				mentioned.add(id);
			}
		});
		double unit = 1.0 / (RRF_K + 1);                  // the fused score of a first-ranked chunk in one list
		List<Ranked> ranked = new ArrayList<>();
		byId.forEach((id, h) -> {
			double r = rrf.getOrDefault(id, 0.0);
			if (h.deviceUuid() != null && mentioned.contains(h.deviceUuid())) {
				r += unit * (LiveFacts.DEVICE_STATUS.equals(h.sourceType()) ? 0.6 : 0.4);
			}
			ranked.add(new Ranked(h, r));
		});
		Comparator<Ranked> byRank = Comparator.comparingDouble((Ranked x) -> -x.rank());
		Comparator<Ranked> order = readings
				? Comparator.comparingInt((Ranked x) -> LiveFacts.DEVICE_STATUS.equals(x.hit().sourceType()) && x.hit().deviceUuid() != null ? 0 : 1).thenComparing(byRank)
				: byRank;
		return ranked.stream().sorted(order).limit(topK).map(Ranked::hit).toList();
	}

	private static void addRanks(List<Hit> list, Map<UUID, Hit> byId, Map<UUID, Double> rrf) {
		for (int i = 0; i < list.size(); i++) {
			Hit h = list.get(i);
			rrf.merge(h.id(), 1.0 / (RRF_K + i + 1), Double::sum);
			Hit prev = byId.get(h.id());
			if (prev == null || h.score() > prev.score()) {
				byId.put(h.id(), h);
			}
		}
	}
}
