package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.smarthome.assistant.LiveFacts;
import com.smarthome.assistant.RagChunkStore.Hit;
import com.smarthome.assistant.RagIndexer;

import tools.jackson.databind.JsonNode;

/**
 * Retrieval against the real database layer (H2 in tests; the pgvector path is exercised against PostgreSQL in the
 * manual integration check). Device data goes in through the real ingest; nothing is stubbed except the language model.
 */
class AssistantRagTests extends AssistantTestBase {

	private static int seq = 50_000;

	private List<Hit> search(UUID user, String question) throws Exception {
		return ragStore.search(user, embedder.embedOne(question), embedder.modelId(), 6, 0.10);
	}

	private String texts(List<Hit> hits) {
		return hits.stream().map(Hit::content).collect(Collectors.joining("\n---\n"));
	}

	private Paired deviceWithReadings(String token, String name, double temp, double hum, int gas) throws Exception {
		Paired p = pairNamed(token, name);
		reading(p, ++seq, temp, hum, true, gas);
		return p;
	}

	@Test
	void liveFactsAboutTheOwnersDevicesAreIndexedAndFoundBySimilarity() throws Exception {
		String token = newUserToken();
		Paired p = deviceWithReadings(token, "Zebra Greenhouse", 23.4, 51.2, 1510);
		ragIndexer.refreshLive(p.userId());

		List<Hit> hits = search(p.userId(), "what is the temperature of the Zebra Greenhouse");
		assertFalse(hits.isEmpty());
		// a word-based embedder ranks several chunks that name the device close together; the status chunk must be among them
		Hit best = hits.stream().filter(h -> h.sourceType().equals(LiveFacts.DEVICE_STATUS) && h.content().contains("23.4")).findFirst()
				.orElseThrow(() -> new AssertionError("no status chunk in " + texts(hits)));
		assertTrue(best.content().contains("Zebra Greenhouse"), best.content());
		assertTrue(best.content().contains("23.4"), best.content());
		assertTrue(best.content().contains("humidity 51.2"));
		assertEquals(p.uuid(), best.deviceUuid());
		assertEquals(p.userId(), best.ownerUserId());
		assertTrue(best.score() > 0.3, "score " + best.score());
		for (int i = 1; i < hits.size(); i++) {
			assertTrue(hits.get(i - 1).score() >= hits.get(i).score(), "results are best first");
		}
	}

	@Test
	void theBestMatchChangesWithTheQuestion() throws Exception {
		String token = newUserToken();
		Paired p = deviceWithReadings(token, "Okapi Loft", 19.0, 40.0, 1400);
		createRule(p, ruleJson("Okapi night light", p.uuid(), null, TELEMETRY, "AND", List.of(cond("motion", "==", "true")), "set_pir_led", "on", 10, 60));
		ragIndexer.refreshLive(p.userId());

		List<Hit> statusHits = search(p.userId(), "is the Okapi Loft device online and when was it last heard");
		List<Hit> ruleHits = search(p.userId(), "which automation rule turns the PIR LED on");
		assertTrue(statusHits.stream().anyMatch(h -> h.sourceType().equals(LiveFacts.DEVICE_STATUS) && h.content().contains("Okapi Loft")), texts(statusHits));
		Hit rule = ruleHits.stream().filter(h -> h.sourceType().equals(LiveFacts.AUTOMATION) && h.content().contains("Okapi night light")).findFirst()
				.orElseThrow(() -> new AssertionError("rule not retrieved: " + texts(ruleHits)));
		assertTrue(rule.content().contains("set_pir_led"));
		// the rule question must not be answered with the unrelated status question's best chunk first
		assertNotEquals(statusHits.get(0).id(), ruleHits.get(0).id());
	}

	@Test
	void aUserNeverRetrievesAnotherUsersChunks() throws Exception {
		Paired a = deviceWithReadings(newUserToken(), "Zebra Atrium", 23.4, 51.2, 1510);
		Paired b = deviceWithReadings(newUserToken(), "Quokka Cellar", 9.7, 88.1, 1620);
		ragIndexer.refreshLive(a.userId());
		ragIndexer.refreshLive(b.userId());

		List<Hit> forA = search(a.userId(), "Quokka Cellar temperature humidity 9.7 88.1");
		List<Hit> forB = search(b.userId(), "Zebra Atrium temperature humidity 23.4 51.2");
		assertFalse(texts(forA).contains("Quokka"), texts(forA));
		assertFalse(texts(forA).contains(b.uuid().toString()));
		assertFalse(texts(forB).contains("Zebra"), texts(forB));
		for (Hit h : forA) {
			assertTrue(h.ownerUserId() == null || h.ownerUserId().equals(a.userId()));
		}
		for (Hit h : forB) {
			assertTrue(h.ownerUserId() == null || h.ownerUserId().equals(b.userId()));
		}
		assertTrue(search(a.userId(), "Zebra Atrium temperature").stream().anyMatch(h -> h.content().contains("Zebra Atrium")), "A still finds their own");
	}

	@Test
	void aSearchWithoutAnOwnerIsRefusedOutright() {
		RuntimeException e = assertThrows(RuntimeException.class, () -> ragStore.search(null, new float[] { 1f }, "m", 3, 0.0));
		assertTrue(e.getMessage().contains("needs the caller's user id"), e.getMessage());
	}

	@Test
	void vectorsOfADifferentModelAreNeverCompared() throws Exception {
		Paired p = deviceWithReadings(newUserToken(), "Ibis Porch", 20.0, 45.0, 1300);
		ragIndexer.refreshLive(p.userId());
		assertTrue(ragStore.search(p.userId(), embedder.embedOne("Ibis Porch temperature"), "some-other-model:v2", 6, 0.0).isEmpty());
	}

	@Test
	void aMissingSensorIsDescribedAsNoValueNeverAsZero() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Lynx Shed");
		sendTelemetry(p, "{\"seq\":" + (++seq) + ",\"motion\":true}");       // PIR only, no temperature, humidity or gas
		ragIndexer.refreshLive(p.userId());

		String all = texts(ragStore.search(p.userId(), embedder.embedOne("Lynx Shed temperature humidity gas readings"), embedder.modelId(), 10, 0.0));
		assertTrue(all.contains("no temperature value"), all);
		assertTrue(all.contains("No temperature values"), all);
		assertFalse(all.contains("temperature 0"), all);
		assertFalse(all.contains("min 0.0"), all);
		assertFalse(all.contains("gas raw 0"), all);
	}

	@Test
	void sensorSummariesAreComputedFromTheStoredReadings() throws Exception {
		Paired p = pairNamed(newUserToken(), "Heron Annex");
		reading(p, ++seq, 20.0, 40.0, false, 1000);
		reading(p, ++seq, 24.0, 60.0, true, 2000);
		ragIndexer.refreshLive(p.userId());

		String all = texts(ragStore.search(p.userId(), embedder.embedOne("Heron Annex last 24 hours temperature summary"), embedder.modelId(), 10, 0.0));
		assertTrue(all.contains("temperature min 20.0, average 22.0, max 24.0"), all);
		assertTrue(all.contains("humidity min 40.0, average 50.0, max 60.0"), all);
		assertTrue(all.contains("Motion was detected in 1 of 2"), all);
	}

	@Test
	void unchangedFactsAreKeptAndNotReplaced() throws Exception {
		Paired p = pairNamed(newUserToken(), "Crane Hall");
		ragIndexer.refreshLive(p.userId());
		Set<UUID> first = ragStore.existing(p.userId(), LiveFacts.ANOMALY).stream().map(e -> e.id()).collect(Collectors.toSet());
		assertFalse(first.isEmpty());
		ragIndexer.refreshLive(p.userId());
		Set<UUID> second = ragStore.existing(p.userId(), LiveFacts.ANOMALY).stream().map(e -> e.id()).collect(Collectors.toSet());
		assertEquals(first, second, "an unchanged fact must not be deleted and re-created");
	}

	@Test
	void factsWhoseSourceWasDeletedDisappearFromTheIndex() throws Exception {
		String token = newUserToken();
		Paired p = deviceWithReadings(token, "Newt Study", 21.0, 50.0, 1200);
		UUID rule = createRule(p, ruleJson("Newt temporary rule", p.uuid(), null, TELEMETRY, "AND", List.of(cond("temperature", ">", "30")), "set_pir_led", "on", 10, 60));
		ragIndexer.refreshLive(p.userId());
		assertTrue(texts(search(p.userId(), "Newt temporary rule automation")).contains("Newt temporary rule"));

		deleteAs(token, "/api/automations/" + rule).andExpect(status().isNoContent());
		ragIndexer.refreshLive(p.userId());
		assertFalse(texts(search(p.userId(), "Newt temporary rule automation")).contains("Newt temporary rule"));
	}

	@Test
	void disowningADeviceRemovesWhatWasIndexedAboutItAtOnce() throws Exception {
		String token = newUserToken();
		Paired p = deviceWithReadings(token, "Marten Garage", 18.2, 55.5, 1111);
		ragIndexer.refreshLive(p.userId());
		assertTrue(texts(search(p.userId(), "Marten Garage temperature")).contains("Marten Garage"));

		deleteAs(token, "/api/devices/" + p.uuid()).andExpect(status().isNoContent());
		// no refresh in between: the removal itself must have cleared it
		assertFalse(texts(search(p.userId(), "Marten Garage temperature")).contains("Marten Garage"));
		assertTrue(ragStore.existing(p.userId(), LiveFacts.DEVICE_STATUS).stream().noneMatch(e -> e.sourceKey().contains(p.uuid().toString())));
	}

	@Test
	void sharedHelpTextIsIndexedOnceAndAvailableToEveryone() throws Exception {
		Paired a = deviceWithReadings(newUserToken(), "Dove Den", 20.0, 50.0, 1000);
		Paired b = deviceWithReadings(newUserToken(), "Finch Nook", 20.0, 50.0, 1000);
		ragIndexer.ensureKnowledge();
		ragIndexer.ensureKnowledge();

		for (UUID user : List.of(a.userId(), b.userId())) {
			List<Hit> hits = search(user, "can the gas warning LED be turned off");
			assertFalse(hits.isEmpty());
			assertEquals(RagIndexer.KNOWLEDGE, hits.get(0).sourceType());
			assertEquals(null, hits.get(0).ownerUserId());
			assertTrue(hits.get(0).title().startsWith("Help:"));
		}
		long once = ragStore.existing(null, RagIndexer.KNOWLEDGE).size();
		ragIndexer.ensureKnowledge();
		assertEquals(once, ragStore.existing(null, RagIndexer.KNOWLEDGE).size());
		assertTrue(once >= 15, "help text chunks: " + once);
	}

	@Test
	void helpTextExplainsRawGasValuesAndAnomalies() throws Exception {
		UUID user = deviceWithReadings(newUserToken(), "Wren Loft", 20.0, 50.0, 1000).userId();
		ragIndexer.ensureKnowledge();
		assertTrue(texts(search(user, "is the MQ-2 gas value in ppm")).contains("not converted to parts per million"));
		assertTrue(texts(search(user, "how are anomalies detected robust z-score")).contains("median absolute deviation"));
	}

	// ---- documents -----------------------------------------------------------------------------

	@Test
	void aDocumentIsChunkedEmbeddedAndOnlyVisibleToItsOwner() throws Exception {
		String ta = newUserToken();
		String tb = newUserToken();
		Paired a = pairNamed(ta, "Doc Device A");
		Paired b = pairNamed(tb, "Doc Device B");
		StringBuilder text = new StringBuilder("Tomatillo seedlings need 21 C at night. ");
		for (int i = 0; i < 40; i++) {
			text.append("Watering note ").append(i).append(": check the tomatillo trays every morning and keep the soil damp but not wet. ");
		}
		JsonNode doc = json(postAs(ta, "/api/assistant/documents", "{\"title\":\"Greenhouse notes\",\"text\":" + mapper.writeValueAsString(text.toString()) + "}")
				.andExpect(status().isCreated()));
		assertTrue(doc.get("chunks").asInt() > 3, "long text is split: " + doc.get("chunks"));

		List<Hit> mine = search(a.userId(), "when should I water the tomatillo trays");
		assertEquals(RagIndexer.DOCUMENT, mine.get(0).sourceType());
		assertTrue(mine.get(0).content().toLowerCase().contains("tomatillo"));
		assertEquals(a.userId(), mine.get(0).ownerUserId());
		assertFalse(texts(search(b.userId(), "when should I water the tomatillo trays")).toLowerCase().contains("tomatillo"));

		assertEquals(1, json(getAs(ta, "/api/assistant/documents")).size());
		assertEquals(0, json(getAs(tb, "/api/assistant/documents")).size());
		deleteAs(tb, "/api/assistant/documents/" + doc.get("id").asString()).andExpect(status().isNotFound());
		assertEquals(1, json(getAs(ta, "/api/assistant/documents")).size());
		deleteAs(ta, "/api/assistant/documents/" + doc.get("id").asString()).andExpect(status().isNoContent());
		assertFalse(texts(search(a.userId(), "when should I water the tomatillo trays")).toLowerCase().contains("tomatillo"));
	}

	@Test
	void documentInputIsValidated() throws Exception {
		String token = newUserToken();
		postAs(token, "/api/assistant/documents", "{\"title\":\"\",\"text\":\"long enough text for a document\"}").andExpect(status().isBadRequest());
		postAs(token, "/api/assistant/documents", "{\"title\":\"Short\",\"text\":\"too short\"}").andExpect(status().isBadRequest());
		postAs(token, "/api/assistant/documents", "{\"title\":\"Huge\",\"text\":" + mapper.writeValueAsString("x ".repeat(11_000)) + "}").andExpect(status().isBadRequest());
	}

	@Test
	void aUserCanKeepOnlyALimitedNumberOfDocuments() throws Exception {
		String token = newUserToken();
		for (int i = 0; i < 20; i++) {
			postAs(token, "/api/assistant/documents", "{\"title\":\"Note " + i + "\",\"text\":\"This is document number " + i + " about the garden shed.\"}")
					.andExpect(status().isCreated());
		}
		postAs(token, "/api/assistant/documents", "{\"title\":\"One too many\",\"text\":\"This one should be refused by the limit.\"}").andExpect(status().isBadRequest());
	}

	@Test
	void aDocumentIsNotSavedWhenTheEmbeddingServiceIsDown() throws Exception {
		String token = newUserToken();
		embedder.setDown(true);
		postAs(token, "/api/assistant/documents", "{\"title\":\"Never stored\",\"text\":\"This text cannot be indexed right now.\"}")
				.andExpect(status().isServiceUnavailable());
		embedder.setDown(false);
		assertEquals(0, json(getAs(token, "/api/assistant/documents")).size());
	}

	@Test
	void freshFactsReplaceOldOnesSoAnOldReadingIsNotServedAsCurrent() throws Exception {
		Paired p = deviceWithReadings(newUserToken(), "Stoat Pantry", 20.0, 50.0, 1000);
		ragIndexer.refreshLive(p.userId());
		assertTrue(texts(search(p.userId(), "Stoat Pantry temperature")).contains("temperature 20.0"));
		reading(p, ++seq, 27.8, 50.0, false, 1000);
		ragIndexer.refreshLive(p.userId());
		String now = texts(search(p.userId(), "Stoat Pantry temperature latest reading"));
		assertTrue(now.contains("temperature 27.8"), now);
		assertFalse(now.contains("temperature 20.0 C"), "the old reading must have been replaced, not kept beside the new one");
	}
}
