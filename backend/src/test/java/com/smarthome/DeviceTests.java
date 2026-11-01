package com.smarthome;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.smarthome.device.Device;
import com.smarthome.device.DeviceRepository;
import com.smarthome.device.DeviceStatus;
import com.smarthome.device.PairingCode;
import com.smarthome.device.PairingCodeRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class DeviceTests {

	private static final AtomicInteger SEQ = new AtomicInteger();

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper mapper;
	@Autowired DeviceRepository devices;
	@Autowired PairingCodeRepository codes;

	// ---- helpers -------------------------------------------------------------------------------

	/** Registers a fresh user and returns their JWT. */
	private String newUserToken() throws Exception {
		int n = SEQ.incrementAndGet();
		String body = """
				{"username":"devuser%d","email":"devuser%d@example.com","password":"password123"}""".formatted(n, n);
		String res = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return mapper.readTree(res).get("token").asString();
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private static String hwId() {
		return "esp32-" + UUID.randomUUID().toString().substring(0, 12);
	}

	private String createCode(String token, String name) throws Exception {
		String res = mvc.perform(post("/api/devices/pairing-codes").header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andReturn().getResponse().getContentAsString();
		return mapper.readTree(res).get("code").asString();
	}

	private ResultActions devicePair(String code, String deviceId) throws Exception {
		return mvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON)
				.content("{\"pairingCode\":\"" + code + "\",\"deviceId\":\"" + deviceId + "\"}"));
	}

	/** Full pairing: returns {id (uuid), credential}. */
	private record Paired(String id, String deviceId, String credential) {
	}

	private Paired pairNewDevice(String userToken, String name) throws Exception {
		String hw = hwId();
		String code = createCode(userToken, name);
		String res = devicePair(code, hw).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String credential = mapper.readTree(res).get("credential").asString();
		String list = mvc.perform(get("/api/devices").header("Authorization", bearer(userToken)))
				.andReturn().getResponse().getContentAsString();
		for (JsonNode d : mapper.readTree(list)) {
			if (d.get("deviceId").asString().equals(hw)) {
				return new Paired(d.get("id").asString(), hw, credential);
			}
		}
		throw new AssertionError("paired device not listed");
	}

	private ResultActions deviceMe(Paired p, String credential) throws Exception {
		return mvc.perform(get("/api/device/me").header("X-Device-Id", p.deviceId()).header("X-Device-Key", credential));
	}

	// ---- pairing -------------------------------------------------------------------------------

	@Test
	void pairingCreatesDeviceOwnedByCodeCreatorWithUnconnectedStatus() throws Exception {
		String token = newUserToken();
		String hw = hwId();
		String code = createCode(token, "Living room");

		devicePair(code, hw).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(jsonPath("$.credential").isNotEmpty())
				.andExpect(jsonPath("$.name").value("Living room"));

		mvc.perform(get("/api/devices").header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].deviceId").value(hw))
				.andExpect(jsonPath("$[0].name").value("Living room"))
				.andExpect(jsonPath("$[0].status").value("ACTIVE"))
				// never claim a device is online before real connectivity exists
				.andExpect(jsonPath("$[0].connection").value("NEVER_CONNECTED"))
				.andExpect(jsonPath("$[0].lastSeenAt").doesNotExist())
				.andExpect(jsonPath("$[0].latestTelemetry").doesNotExist())
				.andExpect(content().string(not(containsString("credential"))))
				.andExpect(content().string(not(containsString("Hash"))));
	}

	@Test
	void ownerComesFromTheCodeCreatorsJwtNotFromTheRequest() throws Exception {
		String alice = newUserToken();
		String bob = newUserToken();
		String code = createCode(alice, "Alice device");
		// the device (or an attacker) tries to smuggle an owner id in; it is ignored
		mvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON)
				.content("{\"pairingCode\":\"" + code + "\",\"deviceId\":\"" + hwId() + "\",\"ownerUserId\":\"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isOk());
		mvc.perform(get("/api/devices").header("Authorization", bearer(alice))).andExpect(jsonPath("$", hasSize(1)));
		mvc.perform(get("/api/devices").header("Authorization", bearer(bob))).andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void deviceIdAloneCannotClaimADevice() throws Exception {
		devicePair("", hwId()).andExpect(status().isBadRequest());
		devicePair("ZZZZ-ZZZZ", hwId()).andExpect(status().isBadRequest());
		mvc.perform(post("/api/device/pair").contentType(MediaType.APPLICATION_JSON)
				.content("{\"deviceId\":\"" + hwId() + "\"}")).andExpect(status().isBadRequest());
	}

	@Test
	void pairingRequiresAuthenticationToCreateACode() throws Exception {
		mvc.perform(post("/api/devices/pairing-codes").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"x\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void codeIsSingleUse() throws Exception {
		String token = newUserToken();
		String code = createCode(token, "One shot");
		devicePair(code, hwId()).andExpect(status().isOk());
		devicePair(code, hwId()).andExpect(status().isBadRequest());
	}

	@Test
	void codeIsAcceptedInAnyCaseAndWithoutDash() throws Exception {
		String token = newUserToken();
		String code = createCode(token, "Typed by hand");
		devicePair(code.toLowerCase().replace("-", " "), hwId()).andExpect(status().isOk());
	}

	@Test
	void expiredCodeIsRejected() throws Exception {
		String token = newUserToken();
		createCode(token, "Will expire");
		PairingCode only = codes.findAll().stream().filter(c -> c.getUsedAt() == null)
				.filter(c -> c.getDeviceName().equals("Will expire")).findFirst().orElseThrow();
		// replace with an already-expired code for a known plaintext
		codes.delete(only);
		String plain = "EXPIRED2";
		codes.saveAndFlush(new PairingCode(only.getUserId(), sha256(plain), "Expired", Instant.now().minusSeconds(900),
				Instant.now().minusSeconds(300)));
		devicePair(plain, hwId()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Invalid or expired pairing code"));
	}

	@Test
	void creatingANewCodeInvalidatesThePreviousUnusedOne() throws Exception {
		String token = newUserToken();
		String first = createCode(token, "First");
		createCode(token, "Second");
		devicePair(first, hwId()).andExpect(status().isBadRequest());
	}

	@Test
	void duplicateClaimIsRejectedAndDoesNotBurnTheSecondUsersCode() throws Exception {
		String alice = newUserToken();
		String bob = newUserToken();
		String hw = hwId();
		devicePair(createCode(alice, "Alice's"), hw).andExpect(status().isOk());

		String bobCode = createCode(bob, "Bob wants it");
		devicePair(bobCode, hw).andExpect(status().isConflict());
		// Bob's code was rolled back to unused, so it still works for a different device
		devicePair(bobCode, hwId()).andExpect(status().isOk());

		// the original owner is unchanged
		mvc.perform(get("/api/devices").header("Authorization", bearer(alice))).andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void repeatedBadCodesAreRateLimited() throws Exception {
		for (int i = 0; i < 10; i++) {
			mvc.perform(post("/api/device/pair").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"pairingCode\":\"BADCODE" + i + "\",\"deviceId\":\"" + hwId() + "\"}"))
					.andExpect(status().isBadRequest());
		}
		mvc.perform(post("/api/device/pair").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; })
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"pairingCode\":\"BADCODEX\",\"deviceId\":\"" + hwId() + "\"}"))
				.andExpect(status().isTooManyRequests());
	}

	// ---- ownership / access control ------------------------------------------------------------

	@Test
	void anotherUsersDeviceIsInvisibleAndImmutable() throws Exception {
		String alice = newUserToken();
		String mallory = newUserToken();
		Paired p = pairNewDevice(alice, "Alice's sensor");

		mvc.perform(get("/api/devices/" + p.id()).header("Authorization", bearer(mallory))).andExpect(status().isNotFound());
		mvc.perform(patch("/api/devices/" + p.id()).header("Authorization", bearer(mallory))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"pwned\"}")).andExpect(status().isNotFound());
		mvc.perform(delete("/api/devices/" + p.id()).header("Authorization", bearer(mallory))).andExpect(status().isNotFound());

		// untouched for the real owner
		mvc.perform(get("/api/devices/" + p.id()).header("Authorization", bearer(alice)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Alice's sensor"));
		deviceMe(p, p.credential()).andExpect(status().isOk());
	}

	@Test
	void deviceEndpointsRequireAuthentication() throws Exception {
		mvc.perform(get("/api/devices")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/devices/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/device/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void renameValidatesAndPersists() throws Exception {
		String token = newUserToken();
		Paired p = pairNewDevice(token, "Old name");
		mvc.perform(patch("/api/devices/" + p.id()).header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  Kitchen  \"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Kitchen"));
		mvc.perform(patch("/api/devices/" + p.id()).header("Authorization", bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.name").exists());
	}

	@Test
	void malformedDeviceIdInUrlIsABadRequest() throws Exception {
		String token = newUserToken();
		mvc.perform(get("/api/devices/not-a-uuid").header("Authorization", bearer(token))).andExpect(status().isBadRequest());
	}

	// ---- separate device vs user authentication ------------------------------------------------

	@Test
	void deviceCredentialsAndUserJwtsAreNotInterchangeable() throws Exception {
		String token = newUserToken();
		Paired p = pairNewDevice(token, "Thing");

		deviceMe(p, p.credential()).andExpect(status().isOk())
				.andExpect(jsonPath("$.deviceId").value(p.deviceId()))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		// a user JWT does not authenticate a device
		mvc.perform(get("/api/device/me").header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
		// device credentials do not authenticate a user
		mvc.perform(get("/api/devices").header("X-Device-Id", p.deviceId()).header("X-Device-Key", p.credential()))
				.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/auth/me").header("X-Device-Id", p.deviceId()).header("X-Device-Key", p.credential()))
				.andExpect(status().isUnauthorized());
		// wrong key / someone else's device id
		deviceMe(p, "not-the-credential").andExpect(status().isUnauthorized());
	}

	// ---- disown / revocation -------------------------------------------------------------------

	@Test
	void disownRevokesCredentialsAndRequiresFreshPairing() throws Exception {
		String alice = newUserToken();
		Paired p = pairNewDevice(alice, "Doomed");
		deviceMe(p, p.credential()).andExpect(status().isOk());

		mvc.perform(delete("/api/devices/" + p.id()).header("Authorization", bearer(alice))).andExpect(status().isNoContent());

		// credential no longer works, owner no longer sees the device
		deviceMe(p, p.credential()).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/devices/" + p.id()).header("Authorization", bearer(alice))).andExpect(status().isNotFound());
		mvc.perform(get("/api/devices").header("Authorization", bearer(alice))).andExpect(jsonPath("$", hasSize(0)));

		Device row = devices.findByDeviceId(p.deviceId()).orElseThrow();
		assertEquals(DeviceStatus.DISOWNED, row.getStatus());
		assertNull(row.getOwnerUserId());
		assertNull(row.getCredentialHash());
		assertNotNull(row.getDisownedAt());

		// pairing again needs a brand-new code; the old credential stays dead, the new one works
		String bob = newUserToken();
		String res = devicePair(createCode(bob, "Second life"), p.deviceId()).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String newCredential = mapper.readTree(res).get("credential").asString();
		deviceMe(p, p.credential()).andExpect(status().isUnauthorized());
		deviceMe(p, newCredential).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Second life"));
		mvc.perform(get("/api/devices").header("Authorization", bearer(bob))).andExpect(jsonPath("$", hasSize(1)));
		// the previous owner still has no access
		mvc.perform(get("/api/devices").header("Authorization", bearer(alice))).andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void credentialIsStoredOnlyAsAHash() throws Exception {
		String token = newUserToken();
		Paired p = pairNewDevice(token, "Hashed");
		Device row = devices.findByDeviceId(p.deviceId()).orElseThrow();
		assertEquals(64, row.getCredentialHash().length());
		org.junit.jupiter.api.Assertions.assertNotEquals(p.credential(), row.getCredentialHash());
	}

	private static String sha256(String value) {
		try {
			return java.util.HexFormat.of().formatHex(
					java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
