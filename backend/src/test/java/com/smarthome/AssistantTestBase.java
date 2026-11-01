package com.smarthome;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.smarthome.assistant.AssistantSuggestionRepository;
import com.smarthome.assistant.ChatConversationRepository;
import com.smarthome.assistant.RagIndexer;
import com.smarthome.device.Device;

import tools.jackson.databind.JsonNode;

/**
 * Helpers for the assistant tests. The language model is the scripted fake (simulated answers); the database is the
 * in-memory H2 test database; all device data is created through the real ingest and services, never invented.
 */
abstract class AssistantTestBase extends AutomationTestBase {

	@Autowired ScriptedLlm llm;
	@Autowired ToggleRagChunkStore ragStore;
	@Autowired ToggleEmbedding embedder;
	@Autowired RagIndexer ragIndexer;
	@Autowired AssistantSuggestionRepository suggestionRepo;
	@Autowired ChatConversationRepository conversationRepo;

	@BeforeEach
	void resetAssistantFakes() {
		llm.reset();
		ragStore.setDown(false);
		embedder.setDown(false);
	}

	/** Pairs a device whose name is {@code name}. */
	Paired pairNamed(String token, String name) throws Exception {
		String hw = "esp32-" + UUID.randomUUID().toString().substring(0, 12);
		String code = mapper.readTree(mvc.perform(post("/api/devices/pairing-codes").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
				.andReturn().getResponse().getContentAsString()).get("code").asString();
		mvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON)
				.content("{\"pairingCode\":\"" + code + "\",\"deviceId\":\"" + hw + "\"}")).andExpect(status().isOk());
		Device d = devices.findByDeviceId(hw).orElseThrow();
		return new Paired(token, d.getId(), hw, d.getOwnerUserId());
	}

	ResultActions chat(String token, UUID conversationId, String message) throws Exception {
		String body = "{" + (conversationId == null ? "" : "\"conversationId\":\"" + conversationId + "\",")
				+ "\"message\":" + mapper.writeValueAsString(message) + "}";
		return mvc.perform(post("/api/assistant/chat").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	JsonNode ask(String token, String message) throws Exception {
		return json(chat(token, null, message).andExpect(status().isOk()));
	}

	JsonNode json(ResultActions r) throws Exception {
		return mapper.readTree(r.andReturn().getResponse().getContentAsString());
	}

	ResultActions getAs(String token, String path) throws Exception {
		return mvc.perform(get(path).header("Authorization", "Bearer " + token));
	}

	ResultActions postAs(String token, String path, String body) throws Exception {
		return mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body == null ? "" : body));
	}

	ResultActions deleteAs(String token, String path) throws Exception {
		return mvc.perform(delete(path).header("Authorization", "Bearer " + token));
	}

	/** A reading through the real ingest path. */
	void reading(Paired p, int seq, double temp, double hum, boolean motion, int gas) {
		sendTelemetry(p, "{\"seq\":" + seq + ",\"temperature\":" + temp + ",\"humidity\":" + hum + ",\"motion\":" + motion + ",\"gasRaw\":" + gas + "}");
	}

	static String answerText(JsonNode chatResponse) {
		return chatResponse.get("assistantMessage").get("content").asString();
	}

	static String statusOf(JsonNode chatResponse) {
		return chatResponse.get("assistantMessage").get("status").asString();
	}
}
