package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.smarthome.command.CommandIngestService;
import com.smarthome.predict.LivePredictionService;

class PredictiveApiTests extends AutomationTestBase {

	@Autowired LivePredictionService live;
	@Autowired CommandIngestService commandIngest;

	private ResultActions call(String auth, String path, String body) throws Exception {
		return mvc.perform(post(path).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void simulationNeedsAuthenticationAndNeverPublishesACommand() throws Exception {
		mvc.perform(get("/api/predictive/simulation")).andExpect(status().isUnauthorized());
		String auth = "Bearer " + newUserToken();
		int before = publisher.published().size();
		call(auth, "/api/predictive/simulation", "{\"action\":\"scenario\",\"scenario\":\"PREDICTIVE_ARRIVAL\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("SIMULATION"))
				.andExpect(jsonPath("$.decision.type").value("LIGHT_ON"));
		assertEquals(before, publisher.published().size());
		call(auth, "/api/predictive/simulation", "{\"action\":\"nonsense\"}").andExpect(status().isBadRequest());
		call(auth, "/api/predictive/simulation", "{\"action\":\"time\",\"minute\":5000}").andExpect(status().isBadRequest());
	}

	@Test
	void anotherUsersDeviceCannotBeControlled() throws Exception {
		Paired p = onlineDevice(newUserToken());
		String stranger = "Bearer " + newUserToken();
		call(stranger, "/api/predictive/live/" + p.uuid(), "{\"armed\":true}").andExpect(status().isNotFound());
		call(stranger, "/api/predictive/simulation/send-to-device",
				"{\"deviceId\":\"" + p.uuid() + "\",\"value\":\"on\"}").andExpect(status().isNotFound());
		assertEquals(0, published(p));
	}

	@Test
	void liveModeSendsOnlyWhenArmedAndShowsTheRealAcknowledgement() throws Exception {
		Paired p = onlineDevice(newUserToken());
		String path = "/api/predictive/live/" + p.uuid();
		call(p.auth(), path, "{\"override\":\"ON\"}").andExpect(status().isOk()).andExpect(jsonPath("$.armed").value(false));
		assertEquals(0, published(p), "disarmed: preview only");

		call(p.auth(), path, "{\"armed\":true}").andExpect(jsonPath("$.mode").value("LIVE_ESP32"))
				.andExpect(jsonPath("$.commands[0].status").value("SENT"));
		assertEquals(1, published(p));
		live.runArmed();
		assertEquals(1, published(p), "no duplicate while the command is outstanding");

		String id = mapper.readTree(publisher.publishedTo(p.deviceId()).get(0).json()).get("id").asString();
		commandIngest.handle("home/devices/" + p.deviceId() + "/acks",
				("{\"id\":\"" + id + "\",\"status\":\"applied\"}").getBytes(StandardCharsets.UTF_8), false);
		mvc.perform(get(path).header("Authorization", p.auth()))
				.andExpect(jsonPath("$.commands[0].status").value("ACKNOWLEDGED"));

		finishAllCommands(p);
		call(p.auth(), path, "{\"armed\":false}").andExpect(jsonPath("$.override").value("NONE"));
		assertTrue(publisher.publishedTo(p.deviceId()).get(published(p) - 1).json().contains("\"auto\""), "released on disarm");
	}

	@Test
	void disconnectedDeviceGetsNoCommand() throws Exception {
		Paired p = onlineDevice(newUserToken());
		markOffline(p);
		call(p.auth(), "/api/predictive/live/" + p.uuid(), "{\"armed\":true,\"override\":\"ON\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.decision.type").value("NO_ACTION"));
		assertEquals(0, published(p));
		call(p.auth(), "/api/predictive/live/" + p.uuid(), "{\"armed\":false}");
	}

	@Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
	@Autowired com.smarthome.predict.PredictiveModelService predictive;

	private int count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

	@Test
	void theModelTrainsOnlyOnTheStoredSyntheticDatasetAndLiveReadingsStayOut() throws Exception {
		String auth = "Bearer " + newUserToken();
		var ready = predictive.current();
		assertEquals("SYNTHETIC", ready.dataset().dataSource());
		assertEquals(30, ready.dataset().days());
		assertTrue(ready.dataset().arrivals() > 10 && ready.dataset().departures() == ready.dataset().arrivals());
		int readings = count("synthetic_readings");
		int rows = ready.model().stored().trainingRows();

		Paired p = onlineDevice(newUserToken());
		sendTelemetry(p, "{\"seq\":999001,\"motion\":true,\"temperatureC\":25.0}");
		var retrained = predictive.retrain();
		assertEquals(readings, count("synthetic_readings"), "live readings never enter the dataset");
		assertEquals(rows, retrained.model().stored().trainingRows());
		assertEquals(ready.version() + 1, retrained.version());
		assertEquals(1, count("predictive_models"));

		mvc.perform(get("/api/predictive/model").header("Authorization", auth)).andExpect(jsonPath("$.state").value("READY"))
				.andExpect(jsonPath("$.dataLabel").value("DATA SOURCE: SYNTHETIC DEMONSTRATION DATA"))
				.andExpect(jsonPath("$.version").value(retrained.version()))
				.andExpect(jsonPath("$.trainingRows").value(rows));
		mvc.perform(get("/api/predictive/now").header("Authorization", auth)).andExpect(status().isOk())
				.andExpect(jsonPath("$.presence.state").exists());
		mvc.perform(get("/api/predictive/evaluation").header("Authorization", auth))
				.andExpect(jsonPath("$.data").value("SYNTHETIC DATA EVALUATION - this is not real-world accuracy"));
		mvc.perform(get("/api/devices/" + p.uuid() + "/ml/status").header("Authorization", p.auth()))
				.andExpect(jsonPath("$.occupancy.state").value("READY")).andExpect(jsonPath("$.occupancy.requirements").doesNotExist())
				.andExpect(jsonPath("$.occupancy.data").doesNotExist());
		mvc.perform(get("/api/devices/" + p.uuid() + "/ml/status").header("Authorization", auth)).andExpect(status().isNotFound());
		call(p.auth(), "/api/devices/" + p.uuid() + "/ml/occupancy/train", "{}").andExpect(status().is4xxClientError());
	}
}
