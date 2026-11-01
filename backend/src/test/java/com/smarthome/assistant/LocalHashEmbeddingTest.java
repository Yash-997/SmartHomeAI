package com.smarthome.assistant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class LocalHashEmbeddingTest {

	private static double sim(String a, String b) {
		return RagChunkStore.cosine(LocalHashEmbedding.vector(a), LocalHashEmbedding.vector(b));
	}

	@Test
	void vectorsAreUnitLengthAndDeterministic() {
		float[] v = LocalHashEmbedding.vector("What is the temperature in the living room?");
		assertEquals(LocalHashEmbedding.DIMS, v.length);
		assertEquals(1.0, RagChunkStore.norm(v), 1e-5);
		assertArrayEquals(v, LocalHashEmbedding.vector("What is the temperature in the living room?"));
	}

	@Test
	void textWithNoUsableWordsGivesAZeroVectorSoNothingMatchesIt() {
		float[] v = LocalHashEmbedding.vector("the is of ???");
		assertEquals(0.0, RagChunkStore.norm(v), 0.0);
	}

	@Test
	void relatedTextScoresFarHigherThanUnrelatedText() {
		String chunk = "Status of device Kitchen: latest reading temperature 23.1 C, humidity 48 %, gas raw 1500.";
		double related = sim("what is the temperature in the kitchen", chunk);
		double unrelated = sim("how do I pair a new device with a pairing code", chunk);
		assertTrue(related > unrelated + 0.15, related + " vs " + unrelated);
	}

	@Test
	void pluralsAndSimpleSynonymsStillMatch() {
		assertTrue(sim("sensor readings", "A sensor reading") > 0.6);
		// "hot" is mapped next to "temperature"
		assertTrue(sim("is it hot", "temperature 31 C") > sim("is it hot", "pairing code expires"));
	}

	@Test
	void randomIdentifiersDoNotChangeTheVector() {
		String a = "Device \"Fox Den\". Connection: ONLINE. Device identifiers: hardware id esp32-5f3a9c01b2d4, id 7f3e2d1c-0000-4000-8000-000000000001. temperature 23.4 C";
		String b = "Device \"Fox Den\". Connection: ONLINE. Device identifiers: hardware id esp32-0a1b2c3d4e5f, id 11111111-2222-4333-8444-555555555555. temperature 23.4 C";
		assertArrayEquals(LocalHashEmbedding.vector(a), LocalHashEmbedding.vector(b));
	}

	@Test
	void embedKeepsTheOrderOfTheInputs() {
		LocalHashEmbedding e = new LocalHashEmbedding();
		List<float[]> out = e.embed(List.of("alpha beta", "gamma delta"));
		assertArrayEquals(LocalHashEmbedding.vector("alpha beta"), out.get(0));
		assertArrayEquals(LocalHashEmbedding.vector("gamma delta"), out.get(1));
		assertTrue(e.local());
	}
}
