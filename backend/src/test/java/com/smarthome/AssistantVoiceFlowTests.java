package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.smarthome.ScriptedLlm.Call;
import com.smarthome.command.CommandIngestService;
import com.smarthome.command.CommandIngestService.Result;

import tools.jackson.databind.JsonNode;

/**
 * The backend side of the dual-mode (text + voice) assistant page. Voice mode has no API of its own: the browser turns
 * speech into text and calls the same chat endpoint, and device commands go through the same command API. These tests
 * pin that contract down. The "model" is the scripted fake, so they prove what the application sends to and does with a
 * model, not how well Groq answers; the "device" is the ack ingest path, not a real ESP32.
 */
class AssistantVoiceFlowTests extends AssistantTestBase {

	private static int seq = 120_000;

	@Autowired CommandIngestService commandIngest;

	private static String refContaining(Call call, String needle) {
		Matcher m = Pattern.compile("\\[(S\\d+)\\] \\([A-Z_]+, as of [^)]*\\)\\n(.*?)\\n\\n", Pattern.DOTALL).matcher(call.lastUserMessage());
		while (m.find()) {
			if (m.group(2).contains(needle)) {
				return m.group(1);
			}
		}
		throw new AssertionError("no source contains '" + needle + "' in:\n" + call.lastUserMessage());
	}

	private Result ack(Paired p, UUID commandId, String status, String detail) {
		String json = "{\"id\":\"" + commandId + "\",\"status\":\"" + status + "\""
				+ (detail == null ? "" : ",\"detail\":" + mapper.writeValueAsString(detail)) + "}";
		return commandIngest.handle("home/devices/" + p.deviceId() + "/acks", json.getBytes(StandardCharsets.UTF_8), false);
	}

	private UUID sendCommand(Paired p, String action, String value) throws Exception {
		JsonNode r = json(postAs(p.token(), "/api/devices/" + p.uuid() + "/commands", "{\"action\":\"" + action + "\",\"value\":\"" + value + "\"}")
				.andExpect(status().isAccepted()));
		assertEquals("SENT", r.get("status").asString(), "accepted for delivery is not the same as done");
		return UUID.fromString(r.get("id").asString());
	}

	@Test
	void aTranscribedQuestionIsAnsweredFromTheLatestStoredReadingWithItsCitation() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Quokka Lounge");
		reading(p, ++seq, 21.0, 40.0, false, 900);
		reading(p, ++seq, 24.6, 47.5, true, 1320);   // the newest reading
		llm.replyWith(c -> "Quokka Lounge is at 24.6 C right now [" + refContaining(c, "temperature 24.6") + "].");

		// What browser speech recognition typically produces: lower case, no punctuation.
		JsonNode r = ask(token, "what is the current temperature in quokka lounge");

		assertEquals("ANSWERED", statusOf(r));
		assertTrue(answerText(r).contains("24.6"));
		Call call = llm.lastCall();
		assertTrue(call.lastUserMessage().contains("temperature 24.6"), "the retrieved context with the stored reading reaches the model");
		assertTrue(call.lastUserMessage().contains("User question: what is the current temperature in quokka lounge"));
		JsonNode cited = null;
		for (JsonNode s : r.get("assistantMessage").get("sources")) {
			if (s.get("cited").asBoolean()) {
				cited = s;
			}
		}
		assertTrue(cited != null && cited.get("snippet").asString().contains("24.6"), "the cited source is the stored reading");
		assertEquals(p.uuid().toString(), cited.get("deviceId").asString());
	}

	@Test
	void aGenericCurrentTemperatureQuestionStillReachesTheLatestStoredReading() throws Exception {
		// Regression: with word-overlap embeddings "what is the current temperature" reduces to the single word
		// "temperature", and short help passages outranked the device's long status chunk, so the model never saw the
		// latest reading and (honestly) said it had none. Found in the live Docker run with Groq.
		String token = newUserToken();
		Paired p = pairNamed(token, "Numbat Nook");
		reading(p, ++seq, 19.5, 41.0, false, 950);
		reading(p, ++seq, 26.8, 49.1, false, 1262);
		llm.replyWith(c -> "It is 26.8 C [" + refContaining(c, "temperature 26.8") + "].");

		JsonNode r = ask(token, "What is the current temperature?");

		assertEquals("ANSWERED", statusOf(r));
		String context = llm.lastCall().lastUserMessage();
		Matcher first = Pattern.compile("\\[S1\\] \\(([A-Z_]+),").matcher(context);
		assertTrue(first.find());
		assertEquals("DEVICE_STATUS", first.group(1), "the device status (latest reading) is the first source");
		assertTrue(context.contains("temperature 26.8"));
		assertEquals(0, r.get("assistantMessage").get("warnings").size(), r.get("assistantMessage").get("warnings").toString());
	}

	@Test
	void aQuestionThatIsNotAboutReadingsIsRankedAsBefore() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Bilby Burrow");
		reading(p, ++seq, 20.0, 40.0, false, 900);
		llm.replyWith("Pair it from the Devices page.");

		ask(token, "How do I pair a new device?");

		Matcher first = Pattern.compile("\\[S1\\] \\(([A-Z_]+),").matcher(llm.lastCall().lastUserMessage());
		assertTrue(first.find());
		assertEquals("KNOWLEDGE", first.group(1), "help text still wins for a how-to question");
	}

	@Test
	void aSpokenFollowUpAfterATypedQuestionContinuesTheSameConversation() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Wombat Den");
		reading(p, ++seq, 22.2, 55.0, false, 1000);

		llm.replyWith("Wombat Den is at 22.2 C.");
		JsonNode typed = ask(token, "What is the temperature at Wombat Den?");
		UUID conv = UUID.fromString(typed.get("conversationId").asString());

		llm.replyWith("Humidity there is 55 %.");
		JsonNode spoken = json(chat(token, conv, "and the humidity").andExpect(status().isOk()));

		assertEquals(conv.toString(), spoken.get("conversationId").asString());
		assertEquals(3, llm.lastCall().messages().size(), "earlier question, earlier answer, the follow-up");
		assertEquals("What is the temperature at Wombat Den?", llm.lastCall().messages().get(0).content());
		assertEquals("Wombat Den is at 22.2 C.", llm.lastCall().messages().get(1).content());
		getAs(token, "/api/assistant/conversations/" + conv + "/messages").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
	}

	@Test
	void askingTheAssistantToSwitchAnLedNeverSendsACommand() throws Exception {
		String token = newUserToken();
		Paired p = onlineDevice(token);
		llm.replyWith("I can't switch outputs from here.");

		ask(token, "turn on the pir indicator led");

		assertEquals(0, publisher.publishedTo(p.deviceId()).size(), "only the confirmed command API publishes commands");
	}

	@Test
	void aVoiceCommandIsOnlyDoneOnceTheDeviceAcknowledgesIt() throws Exception {
		String token = newUserToken();
		Paired p = onlineDevice(token);

		UUID id = sendCommand(p, "set_pir_led", "on");
		String wire = publisher.publishedTo(p.deviceId()).get(0).json();
		assertTrue(wire.contains("\"action\":\"set_pir_led\"") && wire.contains("\"value\":\"on\"") && wire.contains("\"id\":\"" + id + "\""));
		getAs(token, "/api/devices/" + p.uuid() + "/commands/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));

		assertEquals(Result.ACK_APPLIED, ack(p, id, "applied", null));
		getAs(token, "/api/devices/" + p.uuid() + "/commands/" + id).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
	}

	@Test
	void aRejectedAckIsReportedAsAFailureNotASuccess() throws Exception {
		String token = newUserToken();
		Paired p = onlineDevice(token);
		UUID id = sendCommand(p, "set_pir_led", "auto");

		ack(p, id, "rejected", "clock not set");

		JsonNode c = json(getAs(token, "/api/devices/" + p.uuid() + "/commands/" + id).andExpect(status().isOk()));
		assertEquals("FAILED", c.get("status").asString());
		assertTrue(c.get("detail").asString().contains("clock not set"));
	}

	@Test
	void someoneElsesDeviceCannotBeCommandedOrPolledAndNothingIsPublished() throws Exception {
		Paired owner = onlineDevice(newUserToken());
		UUID id = sendCommand(owner, "set_pir_led", "on");
		String intruder = newUserToken();
		int before = publisher.publishedTo(owner.deviceId()).size();

		postAs(intruder, "/api/devices/" + owner.uuid() + "/commands", "{\"action\":\"set_pir_led\",\"value\":\"off\"}").andExpect(status().isNotFound());
		getAs(intruder, "/api/devices/" + owner.uuid() + "/commands/" + id).andExpect(status().isNotFound());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/devices/" + owner.uuid() + "/commands")
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"action\":\"set_pir_led\",\"value\":\"off\"}"))
				.andExpect(status().isUnauthorized());

		assertEquals(before, publisher.publishedTo(owner.deviceId()).size());
		assertFalse(publisher.publishedTo(owner.deviceId()).stream().anyMatch(x -> x.json().contains("\"value\":\"off\"")));
	}
}
