package com.smarthome.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChunkerTest {

	@Test
	void emptyAndBlankTextGiveNoChunks() {
		assertTrue(Chunker.split(null, 200, 20).isEmpty());
		assertTrue(Chunker.split("   \n\n  ", 200, 20).isEmpty());
	}

	@Test
	void shortTextIsOneChunk() {
		assertEquals(List.of("Temperature is 22.4 C."), Chunker.split("Temperature is 22.4 C.", 200, 20));
	}

	@Test
	void everyChunkRespectsTheSizeLimitAndNoTextIsLost() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 60; i++) {
			sb.append("Sentence number ").append(i).append(" talks about the sensor in room ").append(i % 7).append(". ");
		}
		List<String> chunks = Chunker.split(sb.toString(), 300, 50);
		assertTrue(chunks.size() > 4);
		chunks.forEach(c -> assertTrue(c.length() <= 300, "chunk too long: " + c.length()));
		for (int i = 0; i < 60; i++) {
			String needle = "Sentence number " + i + " ";
			assertTrue(chunks.stream().anyMatch(c -> c.contains(needle)), "lost: " + needle);
		}
	}

	@Test
	void consecutiveChunksOverlapSoABoundarySentenceIsFoundWhole() {
		String text = "Alpha one two three four five six seven eight nine ten. Bravo one two three four five six seven eight nine ten. "
				+ "Charlie one two three four five six seven eight nine ten. Delta one two three four five six seven eight nine ten.";
		List<String> chunks = Chunker.split(text, 130, 60);
		assertTrue(chunks.size() >= 3);
		for (int i = 0; i + 1 < chunks.size(); i++) {
			String prev = chunks.get(i);
			String lastWords = prev.substring(prev.lastIndexOf(' ', prev.lastIndexOf(' ') - 1) + 1);   // the last two words
			assertTrue(chunks.get(i + 1).contains(lastWords), "no overlap between " + i + " and " + (i + 1) + ": '" + lastWords + "'");
		}
		assertTrue(chunks.stream().anyMatch(c -> c.contains("Bravo")));
	}

	@Test
	void aSingleHugeWordIsStillSplitInsteadOfLoopingForever() {
		List<String> chunks = Chunker.split("x".repeat(1000), 100, 10);
		assertTrue(chunks.size() >= 10);
		chunks.forEach(c -> assertTrue(c.length() <= 100));
	}

	@Test
	void windowsLineEndingsAreNormalised() {
		List<String> chunks = Chunker.split("one\r\n\r\ntwo", 200, 0);
		assertEquals(1, chunks.size());
		assertTrue(!chunks.get(0).contains("\r"));
	}

	@Test
	void tooSmallALimitIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> Chunker.split("text", 10, 0));
	}
}
