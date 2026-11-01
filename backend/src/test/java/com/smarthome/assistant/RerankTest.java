package com.smarthome.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.smarthome.assistant.RagChunkStore.Hit;

class RerankTest {

	private static final UUID FOX = UUID.randomUUID();
	private static final UUID OWL = UUID.randomUUID();
	private static final Map<UUID, String> NAMES = Map.of(FOX, "Fox Den", OWL, "Owl Loft");

	private static Hit hit(String type, UUID device, double score, String content) {
		return new Hit(UUID.randomUUID(), UUID.randomUUID(), device, type, "k", 0, "t", content, Instant.EPOCH, score);
	}

	@Test
	void aDeviceNamedInTheQuestionMovesItsChunksAheadOfSlightlyBetterMatches() {
		Hit help = hit("KNOWLEDGE", null, 0.50, "help");
		Hit other = hit("DEVICE_STATUS", OWL, 0.45, "owl status");
		Hit history = hit("SENSOR_HISTORY", FOX, 0.40, "fox history");
		Hit status = hit("DEVICE_STATUS", FOX, 0.36, "fox status");

		List<Hit> out = AssistantService.rerank("what is the gas value of fox den?", NAMES, List.of(help, other, history, status), 3);

		assertEquals(List.of(status, history, help), out, "the named device's status first, then its other chunks");
	}

	@Test
	void withoutAMentionTheOrderIsPlainSimilarityAndNothingIsAdded() {
		List<Hit> in = new ArrayList<>(List.of(hit("DEVICE_STATUS", OWL, 0.30, "a"), hit("KNOWLEDGE", null, 0.50, "b"), hit("ANOMALY", FOX, 0.40, "c")));
		List<Hit> out = AssistantService.rerank("how do I pair a device", NAMES, in, 10);
		assertEquals(List.of(in.get(1), in.get(2), in.get(0)), out);
		assertTrue(in.containsAll(out) && out.size() == in.size());
	}

	@Test
	void aReadingQuestionPutsDeviceStatusFirstAndStillPrefersTheNamedDevice() {
		Hit help = hit("KNOWLEDGE", null, 0.50, "temperature range help");
		Hit history = hit("SENSOR_HISTORY", FOX, 0.40, "fox history");
		Hit owl = hit("DEVICE_STATUS", OWL, 0.09, "owl status");
		Hit fox = hit("DEVICE_STATUS", FOX, 0.05, "fox status");
		Hit overview = hit("DEVICE_STATUS", null, 0.30, "overview");

		List<Hit> generic = AssistantService.rerank("what is the current temperature?", NAMES, List.of(help, history, owl, fox, overview), 3, true);
		assertEquals(List.of(owl, fox, help), generic, "per-device status first (by score), then the rest; the overview is not a reading");

		List<Hit> named = AssistantService.rerank("what is the temperature in fox den?", NAMES, List.of(help, history, owl, fox), 2, true);
		assertEquals(List.of(fox, owl), named);
	}

	@Test
	void readingQuestionsAreRecognised() {
		assertTrue(AssistantService.asksAboutReadings("What is the current temperature?"));
		assertTrue(AssistantService.asksAboutReadings("Explain the latest gas sensor readings."));
		assertTrue(AssistantService.asksAboutReadings("what is the humidity"));
		assertTrue(!AssistantService.asksAboutReadings("How do I pair a new device?"));
		assertTrue(!AssistantService.asksAboutReadings("Which automation rules do I have?"));
	}

	@Test
	void theResultIsCutToTopK() {
		List<Hit> in = List.of(hit("KNOWLEDGE", null, 0.9, "a"), hit("KNOWLEDGE", null, 0.8, "b"), hit("KNOWLEDGE", null, 0.7, "c"));
		assertEquals(2, AssistantService.rerank("x", NAMES, in, 2).size());
	}
}
