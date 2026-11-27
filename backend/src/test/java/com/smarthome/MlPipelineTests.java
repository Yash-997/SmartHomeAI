package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.smarthome.device.Device;
import com.smarthome.device.DeviceStatusRecorder;
import com.smarthome.ml.AnomalyKind;
import com.smarthome.ml.DataCollectionService;
import com.smarthome.ml.MlModel;
import com.smarthome.ml.MlTrainingRunRepository;

import tools.jackson.databind.JsonNode;

/** Connectivity history through the real API and database. */
class MlPipelineTests extends MlTestBase {

	@Autowired JdbcTemplate jdbc;
	@Autowired MlTrainingRunRepository runRepo;
	@Autowired DeviceStatusRecorder statusRecorder;
	@Autowired DataCollectionService collection;

	private String url(Paired p, String path) {
		return "/api/devices/" + p.uuid() + "/ml" + path;
	}

	private JsonNode getJson(Paired p, String path) throws Exception {
		return mapper.readTree(mvc.perform(get(url(p, path)).header("Authorization", p.auth())).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
	}


	@Test
	void onlineAndOfflineChangesAreRecordedOncePerChange() throws Exception {
		Paired p = onlineDevice(newUserToken());
		Device d = devices.findById(p.uuid()).orElseThrow();
		Instant now = Instant.now();
		assertTrue(statusRecorder.record(d, now).isPresent());
		assertTrue(statusRecorder.record(d, now.plusSeconds(60)).isEmpty(), "no change, no row");

		jdbc.update("update devices set last_seen_at = ? where id = ?", Timestamp.from(now.minus(Duration.ofMinutes(10))), p.uuid());
		Device silent = devices.findById(p.uuid()).orElseThrow();
		assertEquals("OFFLINE", statusRecorder.record(silent, now).orElseThrow().getState());

		JsonNode c = getJson(p, "/connectivity");
		assertEquals("OFFLINE", c.get("currentState").asString());
		assertEquals(1, c.get("offlineEventsLast7Days").asLong());
		assertEquals(2, c.get("events").size());
		assertEquals("OFFLINE", c.get("events").get(0).get("state").asString(), "newest first");
		assertTrue(c.get("events").get(0).get("reason").asString().contains("silent"));
		mvc.perform(get(url(p, "/connectivity")).header("Authorization", "Bearer " + newUserToken())).andExpect(status().isNotFound());
	}

	@Test
	void aDeviceThatNeverConnectedHasNoHistory() throws Exception {
		Paired p = onlineDevice(newUserToken());
		jdbc.update("update devices set last_seen_at = null where id = ?", p.uuid());
		assertTrue(statusRecorder.record(devices.findById(p.uuid()).orElseThrow(), Instant.now()).isEmpty());
		assertEquals(0, getJson(p, "/connectivity").get("events").size());
	}
}
