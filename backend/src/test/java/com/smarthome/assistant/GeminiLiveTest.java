package com.smarthome.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.smarthome.assistant.AssistantProperties.Gemini;
import com.smarthome.assistant.AssistantProperties.Groq;
import com.smarthome.assistant.AssistantProperties.Llm;
import com.smarthome.assistant.LlmClient.Message;

import tools.jackson.databind.json.JsonMapper;

/**
 * One real request to Google Gemini. Runs only when both GEMINI_API_KEY and GEMINI_LIVE_TEST=true are set, so a normal
 * build never spends quota: {@code GEMINI_LIVE_TEST=true GEMINI_API_KEY=... ./mvnw test -Dtest=GeminiLiveTest}
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_LIVE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiLiveTest {

	@Test
	void oneRealGenerateContentCall() throws Exception {
		String model = System.getenv().getOrDefault("GEMINI_MODEL", GeminiLlmProvider.DEFAULT_MODEL);
		Llm llm = new Llm("gemini", "", "", "", 30, 200, 0.2, "gemini", "none", 60, new Gemini(System.getenv("GEMINI_API_KEY"), model, "", 1024, "low"),
				new Groq("", "openai/gpt-oss-120b", ""));
		String text = new GeminiLlmProvider(llm, JsonMapper.builder().build()).complete("Reply with exactly one word.",
				List.of(Message.user("Say OK.")));
		assertFalse(text.isBlank());
		assertTrue(text.length() < 200, text);
	}
}
