package com.smarthome;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.smarthome.config.SecurityConfig;
import com.smarthome.user.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class AuthTests {

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper mapper;
	@Autowired UserRepository users;
	@Autowired JwtEncoder encoder;

	private static String register(String username, String email, String password) {
		return """
				{"username":"%s","email":"%s","password":"%s"}""".formatted(username, email, password);
	}

	private MvcResult doRegister(String username, String email) throws Exception {
		return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(register(username, email, "password123"))).andReturn();
	}

	private String tokenOf(MvcResult r) throws Exception {
		JsonNode n = mapper.readTree(r.getResponse().getContentAsString());
		return n.get("token").asString();
	}

	@Test
	void registerReturnsTokenAndNeverExposesPasswordHash() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(register("alice", "Alice@Example.com", "password123")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.token").isNotEmpty())
				.andExpect(jsonPath("$.user.username").value("alice"))
				.andExpect(jsonPath("$.user.email").value("alice@example.com"))
				.andExpect(content().string(not(containsString("password"))));
		// stored as a BCrypt hash, not plain text
		org.junit.jupiter.api.Assertions.assertTrue(
				users.findByUsernameIgnoreCase("alice").orElseThrow().getPasswordHash().startsWith("$2"));
	}

	@Test
	void registerRejectsDuplicates() throws Exception {
		doRegister("bob", "bob@example.com");
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(register("BOB", "other@example.com", "password123")))
				.andExpect(status().isConflict());
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(register("bob2", "bob@example.com", "password123")))
				.andExpect(status().isConflict());
	}

	@Test
	void registerValidatesInput() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(register("a", "not-an-email", "short")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.username").exists())
				.andExpect(jsonPath("$.fieldErrors.email").exists())
				.andExpect(jsonPath("$.fieldErrors.password").exists());
	}

	@Test
	void loginSucceedsAndFailsWithGenericError() throws Exception {
		doRegister("carol", "carol@example.com");
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"carol\",\"password\":\"password123\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty());
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"carol\",\"password\":\"wrong-password\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid username or password"));
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"nobody\",\"password\":\"password123\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid username or password"));
	}

	@Test
	void meReturnsProfileForValidToken() throws Exception {
		String token = tokenOf(doRegister("dave", "dave@example.com"));
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("dave"))
				.andExpect(jsonPath("$.email").value("dave@example.com"))
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(content().string(not(containsString("password"))));
	}

	@Test
	void meRejectsMissingTamperedAndExpiredTokens() throws Exception {
		mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());

		String token = tokenOf(doRegister("erin", "erin@example.com"));
		String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered))
				.andExpect(status().isUnauthorized());

		String sub = users.findByUsernameIgnoreCase("erin").orElseThrow().getId().toString();
		Instant past = Instant.now().minusSeconds(3600);
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer(SecurityConfig.ISSUER).subject(sub)
				.issuedAt(past.minusSeconds(60)).expiresAt(past).build();
		String expired = encoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
		mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expired))
				.andExpect(status().isUnauthorized());
	}
}
