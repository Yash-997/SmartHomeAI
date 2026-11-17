package com.smarthome.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.smarthome.assistant.RagChunkStore.Hit;

class HybridRetrieverTest {

	private static Hit hit(String type, UUID device, String content, double score) {
		return new Hit(UUID.randomUUID(), UUID.randomUUID(), device, type, "k", 0, "t", content, Instant.EPOCH, score);
	}

	@Test
	void queryTermsAreStemmedAndExpandedWithSynonyms() {
		List<String> t = HybridRetriever.terms("How hot was the kitchen?");
		assertTrue(t.contains("hot"));
		assertTrue(t.contains(LocalHashEmbedding.stem("temperature")), t.toString());
		assertTrue(t.contains("kitchen"));
	}

	@Test
	void bm25PrefersRareTermsAndNormalises() {
		Hit common = hit("KNOWLEDGE", null, "device device device status", 0);
		Hit rare = hit("ANOMALY", null, "stuck value anomaly on humidity of device", 0);
		List<Hit> out = HybridRetriever.bm25(List.of("stuck", "devic"), List.of(common, rare), 100, 10);
		assertEquals(rare.id(), out.get(0).id(), "the rare word decides");
		assertEquals(1.0, out.get(0).score(), 1e-9);
	}

	@Test
	void fusionRewardsChunksFoundByBothSearches() {
		Hit a = hit("KNOWLEDGE", null, "a", 0.9);
		Hit b = hit("KNOWLEDGE", null, "b", 0.8);
		Hit bKeyword = new Hit(b.id(), b.ownerUserId(), null, "KNOWLEDGE", "k", 0, "t", "b", Instant.EPOCH, 1.0);
		List<Hit> out = HybridRetriever.fuse("q", Map.of(), List.of(a, b), List.of(bKeyword), List.of(), 2, false);
		assertEquals(b.id(), out.get(0).id(), "found by vector and keyword search");
		assertEquals(1.0, out.get(0).score(), 1e-9, "shows the better of its two scores");
	}

	@Test
	void namedDevicesAndReadingQuestionsAreBoosted() {
		UUID kitchen = UUID.randomUUID();
		Hit help = hit("KNOWLEDGE", null, "help", 0.9);
		Hit status = hit(LiveFacts.DEVICE_STATUS, kitchen, "status", 0.2);
		List<Hit> readings = HybridRetriever.fuse("temperature", Map.of(kitchen, "Kitchen"), List.of(help), List.of(), List.of(status), 2, true);
		assertEquals(status.id(), readings.get(0).id(), "device status first for a readings question");
		Hit history = hit(LiveFacts.SENSOR_HISTORY, kitchen, "history", 0.5);
		List<Hit> named = HybridRetriever.fuse("kitchen history", Map.of(kitchen, "Kitchen"), List.of(help, history), List.of(), List.of(), 2, false);
		assertEquals(history.id(), named.get(0).id(), "a chunk about the named device is boosted");
	}
}
