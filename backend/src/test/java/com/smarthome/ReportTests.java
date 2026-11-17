package com.smarthome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** Reports through the real API: generation from stored readings, history, PDF download and owner isolation. */
class ReportTests extends AssistantTestBase {

	private static final AtomicInteger SEQ = new AtomicInteger(80_000);

	@Test
	void aDailyReportSummarisesTheStoredReadingsAndRecommendsFromThem() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Report Room");
		reading(p, SEQ.incrementAndGet(), 22.0, 70.0, true, 800);
		reading(p, SEQ.incrementAndGet(), 24.0, 72.0, false, 900);

		JsonNode r = json(postAs(token, "/api/reports", "{\"kind\":\"daily\",\"timeZone\":\"UTC\"}").andExpect(status().isCreated()));

		assertEquals("DAILY", r.get("kind").asString());
		String summary = r.get("summary").asString();
		assertTrue(summary.contains("2 readings"), summary);
		assertTrue(summary.contains("Temperature averaged 23.0 °C"), summary);
		JsonNode sections = r.get("content").get("sections");
		assertEquals("Executive summary", sections.get(0).get("heading").asString());
		assertEquals("Recommendations", sections.get(sections.size() - 1).get("heading").asString());
		assertTrue(sections.get(sections.size() - 1).toString().contains("Humidity averaged 71.0 %"), "a recommendation quotes its number");
		assertTrue(sections.toString().contains("\"kind\":\"table\""));
	}

	@Test
	void reportsAreListedDownloadableAsPdfAndDeletable() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Pdf Parlour");
		reading(p, SEQ.incrementAndGet(), 21.0, 50.0, false, 700);
		String id = json(postAs(token, "/api/reports", "{\"kind\":\"weekly\"}").andExpect(status().isCreated())).get("id").asString();

		JsonNode list = json(getAs(token, "/api/reports").andExpect(status().isOk()));
		assertEquals(1, list.size());
		assertEquals(id, list.get(0).get("id").asString());

		byte[] pdf = getAs(token, "/api/reports/" + id + "/pdf").andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		String s = new String(pdf, StandardCharsets.ISO_8859_1);
		assertTrue(s.startsWith("%PDF-"));
		assertTrue(s.contains("Executive summary"));

		deleteAs(token, "/api/reports/" + id).andExpect(status().isNoContent());
		getAs(token, "/api/reports/" + id).andExpect(status().isNotFound());
	}

	@Test
	void anotherUsersReportLooksLikeItDoesNotExist() throws Exception {
		String ta = newUserToken();
		String tb = newUserToken();
		pairNamed(ta, "Private Place");
		String id = json(postAs(ta, "/api/reports", "{\"kind\":\"monthly\"}").andExpect(status().isCreated())).get("id").asString();

		getAs(tb, "/api/reports/" + id).andExpect(status().isNotFound());
		getAs(tb, "/api/reports/" + id + "/pdf").andExpect(status().isNotFound());
		deleteAs(tb, "/api/reports/" + id).andExpect(status().isNotFound());
		assertEquals(0, json(getAs(tb, "/api/reports")).size());
	}

	@Test
	void invalidRequestsAreRejected() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Strict Space");
		postAs(token, "/api/reports", "{\"kind\":\"hourly\"}").andExpect(status().isBadRequest());
		postAs(token, "/api/reports", "{\"kind\":\"custom\"}").andExpect(status().isBadRequest());
		postAs(token, "/api/reports", "{\"kind\":\"custom\",\"from\":\"2026-01-05\",\"to\":\"2026-01-01\"}").andExpect(status().isBadRequest());
		postAs(token, "/api/reports", "{\"kind\":\"daily\",\"timeZone\":\"Not/AZone\"}").andExpect(status().isBadRequest());
		postAs(token, "/api/reports", "{\"kind\":\"daily\",\"deviceId\":\"" + java.util.UUID.randomUUID() + "\"}").andExpect(status().isBadRequest());
		postAs(token, "/api/reports", "{\"kind\":\"daily\",\"deviceId\":\"" + p.uuid() + "\"}").andExpect(status().isCreated());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/reports")).andExpect(status().isUnauthorized());
	}

	@Test
	void anEmptyPeriodSaysThereIsNoDataInsteadOfInventingIt() throws Exception {
		String token = newUserToken();
		pairNamed(token, "Silent Cellar");
		JsonNode r = json(postAs(token, "/api/reports", "{\"kind\":\"custom\",\"from\":\"2021-03-01\",\"to\":\"2021-03-02\"}").andExpect(status().isCreated()));
		assertTrue(r.get("summary").asString().contains("No sensor readings were stored"), r.get("summary").asString());
		assertFalse(r.toString().contains("averaged 0"));
		assertTrue(r.get("content").get("sections").toString().contains("sent no readings in this period"));
	}

	@Test
	void theAssistantCanCreateAReportOnRequest() throws Exception {
		String token = newUserToken();
		Paired p = pairNamed(token, "Chat Chamber");
		reading(p, SEQ.incrementAndGet(), 21.0, 50.0, false, 700);
		llm.replyWith("Your weekly report is ready.");

		JsonNode r = ask(token, "Generate a weekly report");

		assertEquals(1, r.get("assistantMessage").get("extras").get("reports").size());
		assertEquals(1, json(getAs(token, "/api/reports")).size());
	}
}
