package com.smarthome.assistant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import com.smarthome.assistant.AssistantExceptions.InvalidChatRequestException;
import com.smarthome.assistant.AssistantProperties.Rag;
import com.smarthome.assistant.RagChunkStore.Existing;
import com.smarthome.assistant.RagChunkStore.NewChunk;
import com.smarthome.assistant.RagChunkStore.SourceSummary;

/**
 * Ingestion: chunk text, embed it and keep the vector store in step with the source records.
 * <ul>
 * <li><b>Shared help text</b> (resources under assistant/knowledge) is indexed once per embedding model.</li>
 * <li><b>Live facts</b> (devices, readings, anomalies, rules) are rebuilt from the database before a question is answered
 * (at most every {@code refreshSeconds} per user). Unchanged text is recognised by its hash and not embedded again, and
 * facts whose source no longer exists are deleted, so the index never keeps describing a removed rule or device.</li>
 * <li><b>Documents</b> are notes a user adds on purpose.</li>
 * </ul>
 */
@Service
public class RagIndexer {

	public static final String KNOWLEDGE = "KNOWLEDGE";
	public static final String DOCUMENT = "DOCUMENT";
	private static final int MAX_CONTENT = 3900;
	private static final Object SHARED_LOCK = new Object();

	private final RagChunkStore store;
	private final EmbeddingClient embedder;
	private final LiveFacts liveFacts;
	private final Rag rag;
	private final Clock clock;
	private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
	private final Map<UUID, Instant> lastRefresh = new ConcurrentHashMap<>();
	private volatile String knowledgeIndexedFor = "";

	public RagIndexer(RagChunkStore store, EmbeddingClient embedder, LiveFacts liveFacts, AssistantProperties props, Clock clock) {
		this.store = store;
		this.embedder = embedder;
		this.liveFacts = liveFacts;
		this.rag = props.rag();
		this.clock = clock;
	}

	// ---- live facts ----------------------------------------------------------------------------

	/** Brings the user's live facts up to date (no-op if refreshed very recently). */
	public void refreshLive(UUID ownerId) throws LlmUnavailableException {
		synchronized (locks.computeIfAbsent(ownerId, k -> new Object())) {
			Instant now = clock.instant();
			Instant last = lastRefresh.get(ownerId);
			if (rag.refreshSeconds() > 0 && last != null && now.isBefore(last.plusSeconds(rag.refreshSeconds()))) {
				return;
			}
			for (Map.Entry<String, List<FactDoc>> e : liveFacts.build(ownerId).entrySet()) {
				sync(ownerId, e.getKey(), e.getValue());
			}
			lastRefresh.put(ownerId, now);
		}
	}

	// ---- shared help text ----------------------------------------------------------------------

	public void ensureKnowledge() throws LlmUnavailableException {
		String model = embedder.modelId();
		if (model.equals(knowledgeIndexedFor)) {
			return;
		}
		synchronized (SHARED_LOCK) {
			if (model.equals(knowledgeIndexedFor)) {
				return;
			}
			sync(null, KNOWLEDGE, loadKnowledge());
			knowledgeIndexedFor = model;
		}
	}

	List<FactDoc> loadKnowledge() {
		List<FactDoc> docs = new ArrayList<>();
		try {
			Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:assistant/knowledge/*.md");
			Arrays.sort(files, Comparator.comparing(Resource::getFilename));
			Instant now = clock.instant();
			for (Resource f : files) {
				String text = new String(f.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
				String docTitle = f.getFilename();
				String[] sections = text.split("(?m)^(?=#{1,2} )");
				int n = 0;
				for (String section : sections) {
					String s = section.strip();
					if (s.isEmpty()) {
						continue;
					}
					String heading = s.lines().findFirst().orElse("").replaceFirst("^#+\\s*", "").trim();
					String body = s.lines().skip(1).reduce("", (a, b) -> a + b + "\n").strip();
					if (s.startsWith("# ")) {
						docTitle = heading;
						if (body.isEmpty()) {
							continue;
						}
					}
					if (body.isEmpty()) {
						continue;
					}
					docs.add(new FactDoc(KNOWLEDGE, "kb:" + f.getFilename() + ":" + n++, null, "Help: " + docTitle + " - " + heading, body, now));
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not read the bundled help text", e);
		}
		return docs;
	}

	// ---- user documents ------------------------------------------------------------------------

	public record DocumentInfo(String id, String title, Instant addedAt, int chunks) {
	}

	/** Adds a note or document for this user only. Nothing is stored if embedding fails. */
	public DocumentInfo addDocument(UUID ownerId, String title, String text) throws LlmUnavailableException {
		String t = Text.oneLine(title, 100);
		String body = Text.multiLine(text, rag.maxDocumentChars() + 1);
		if (t.isEmpty()) {
			throw new InvalidChatRequestException("title", "A title is required");
		}
		if (body.length() < 20) {
			throw new InvalidChatRequestException("text", "Write at least 20 characters");
		}
		if (body.length() > rag.maxDocumentChars()) {
			throw new InvalidChatRequestException("text", "At most " + rag.maxDocumentChars() + " characters");
		}
		synchronized (locks.computeIfAbsent(ownerId, k -> new Object())) {
			if (store.sources(ownerId, DOCUMENT).size() >= rag.maxDocumentsPerUser()) {
				throw new InvalidChatRequestException("text", "You can keep at most " + rag.maxDocumentsPerUser() + " documents; delete one first");
			}
			String key = "doc:" + UUID.randomUUID();
			Instant now = clock.instant();
			// Documents are only added or deleted, never rebuilt: syncing one new document must not delete the others.
			sync(ownerId, DOCUMENT, List.of(new FactDoc(DOCUMENT, key, null, t, body, now)), false);
			return new DocumentInfo(key, t, now, Chunker.split(body, rag.chunkChars(), rag.overlapChars()).size());
		}
	}

	public List<SourceSummary> documents(UUID ownerId) {
		return store.sources(ownerId, DOCUMENT);
	}

	/** @return false if the user has no such document. */
	public boolean deleteDocument(UUID ownerId, String key) {
		return store.deleteSource(ownerId, DOCUMENT, key) > 0;
	}

	// ---- shared machinery ----------------------------------------------------------------------

	private void sync(UUID ownerOrNull, String type, List<FactDoc> docs) throws LlmUnavailableException {
		sync(ownerOrNull, type, docs, true);
	}

	/**
	 * Makes the stored chunks of one source type match {@code docs}. With {@code deleteMissing} (live facts, help text)
	 * chunks whose source is gone are removed; without it (adding one document) existing chunks are left alone.
	 */
	private void sync(UUID ownerOrNull, String type, List<FactDoc> docs, boolean deleteMissing) throws LlmUnavailableException {
		String model = embedder.modelId();
		Map<String, Existing> existing = new HashMap<>();
		for (Existing e : store.existing(ownerOrNull, type)) {
			existing.put(e.sourceKey() + "#" + e.chunkIndex(), e);
		}
		List<NewChunk> wanted = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		List<UUID> deletes = new ArrayList<>();
		Map<String, Boolean> keep = new HashMap<>();
		for (FactDoc d : docs) {
			List<String> pieces = Chunker.split(d.text(), rag.chunkChars(), rag.overlapChars());
			for (int i = 0; i < pieces.size(); i++) {
				String content = Text.oneLine(d.title(), 120) + ": " + pieces.get(i);
				if (content.length() > MAX_CONTENT) {
					content = content.substring(0, MAX_CONTENT);
				}
				String k = d.sourceKey() + "#" + i;
				keep.put(k, true);
				Existing old = existing.get(k);
				if (old != null && old.hash().equals(Hashes.sha256(content)) && old.model().equals(model)) {
					continue;                                  // unchanged: neither deleted nor embedded again
				}
				if (old != null) {
					deletes.add(old.id());
				}
				wanted.add(new NewChunk(d.deviceUuid(), type, d.sourceKey(), i, Text.oneLine(d.title(), 160), content, d.asOf(), null));
				texts.add(content);
			}
		}
		if (deleteMissing) {
			existing.forEach((k, e) -> {
				if (!keep.containsKey(k)) {
					deletes.add(e.id());
				}
			});
		}
		if (wanted.isEmpty() && deletes.isEmpty()) {
			return;
		}
		List<float[]> vectors = texts.isEmpty() ? List.of() : embedder.embed(texts);     // before touching the database
		if (vectors.size() != wanted.size()) {
			throw new LlmUnavailableException(LlmUnavailableException.Reason.BAD_RESPONSE, "The embedding service returned the wrong number of vectors.");
		}
		List<NewChunk> inserts = new ArrayList<>(wanted.size());
		for (int i = 0; i < wanted.size(); i++) {
			NewChunk c = wanted.get(i);
			inserts.add(new NewChunk(c.deviceUuid(), c.sourceType(), c.sourceKey(), c.chunkIndex(), c.title(), c.content(), c.asOf(), vectors.get(i)));
		}
		store.apply(ownerOrNull, deletes, inserts, model, clock.instant());
	}
}
