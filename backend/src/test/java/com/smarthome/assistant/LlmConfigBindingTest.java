package com.smarthome.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import tools.jackson.databind.json.JsonMapper;

/**
 * The real {@code src/main/resources/application.properties} with fake environment variables: proves the documented
 * variable names (GEMINI_API_KEY, LLM_PRIMARY_PROVIDER, ...) bind, and that ModelConfig builds the right providers.
 */
class LlmConfigBindingTest {

	private static AssistantProperties bind(Map<String, Object> env) throws Exception {
		StandardEnvironment e = new StandardEnvironment();
		e.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		e.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		e.getPropertySources().addFirst(new MapPropertySource("fakeEnv", env));
		new PropertiesPropertySourceLoader().load("main", new FileSystemResource(Path.of("src/main/resources/application.properties")))
				.forEach(e.getPropertySources()::addLast);
		return Binder.get(e).bind("app.assistant", AssistantProperties.class).get();
	}

	private static LlmClient client(AssistantProperties p) {
		return new ModelConfig().llmClient(p, JsonMapper.builder().build());
	}

	@Test
	void documentedVariablesBind() throws Exception {
		AssistantProperties p = bind(Map.of("LLM_PROVIDER", "gemini", "LLM_PRIMARY_PROVIDER", "gemini", "LLM_FALLBACK_PROVIDER", "groq",
				"LLM_TIMEOUT_SECONDS", "17", "GEMINI_API_KEY", "g-key", "GEMINI_MODEL", "gemini-3-flash-preview", "GROQ_API_KEY", "q-key",
				"GROQ_MODEL", "openai/gpt-oss-120b"));
		assertEquals("gemini", p.llm().primaryProvider());
		assertEquals("groq", p.llm().fallbackProvider());
		assertEquals(17, p.llm().timeoutSeconds());
		assertEquals("g-key", p.llm().gemini().apiKey());
		assertEquals("gemini-3-flash-preview", p.llm().gemini().model());
		assertEquals("q-key", p.llm().groq().apiKey());
		assertEquals("openai/gpt-oss-120b", p.llm().groq().model());

		LlmProviderOrchestrator o = assertInstanceOf(LlmProviderOrchestrator.class, client(p));
		assertInstanceOf(GeminiLlmProvider.class, o.primary());
		assertInstanceOf(GroqLlmProvider.class, o.fallback());
		assertEquals("gemini", o.provider());
		assertTrue(o.configured());
	}

	@Test
	void defaultsAreGeminiThenGroqWithTheDocumentedModels() throws Exception {
		AssistantProperties p = bind(Map.of("GROQ_API_KEY", "q-key"));
		assertEquals("gemini-3-flash-preview", p.llm().gemini().model());
		assertEquals("openai/gpt-oss-120b", p.llm().groq().model());
		assertEquals(30, p.llm().timeoutSeconds());
		LlmClient c = client(p);
		assertEquals("groq", c.provider(), "without GEMINI_API_KEY, Groq answers alone");
		assertTrue(c.configured());
	}

	@Test
	void anExistingSingleProviderEnvKeepsWorking() throws Exception {
		// an older .env: LLM_PROVIDER=groq and no LLM_PRIMARY_PROVIDER
		AssistantProperties p = bind(Map.of("LLM_PROVIDER", "groq", "LLM_BASE_URL", "https://api.groq.com/openai/v1", "LLM_MODEL",
				"openai/gpt-oss-120b", "GROQ_API_KEY", "q-key"));
		LlmProviderOrchestrator o = assertInstanceOf(LlmProviderOrchestrator.class, client(p));
		assertInstanceOf(GroqLlmProvider.class, o.primary());
		assertNull(o.fallback(), "groq as primary and fallback is just groq");
		assertEquals("groq", o.provider());
	}

	@Test
	void noneAndUnknownProviders() throws Exception {
		assertFalse(client(bind(Map.of("LLM_PROVIDER", "none"))).configured());
		LlmClient unknown = client(bind(Map.of("LLM_PRIMARY_PROVIDER", "bogus")));
		assertFalse(unknown.configured());
		assertTrue(unknown.notConfiguredReason().contains("Unknown"));
		LlmClient nothing = client(bind(Map.of()));
		assertFalse(nothing.configured());
		assertTrue(nothing.notConfiguredReason().contains("GEMINI_API_KEY") && nothing.notConfiguredReason().contains("GROQ_API_KEY"));
	}
}
