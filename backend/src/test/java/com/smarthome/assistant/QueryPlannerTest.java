package com.smarthome.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.smarthome.assistant.QueryPlanner.Plan;
import com.smarthome.assistant.tools.ToolTypes.Focus;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class QueryPlannerTest {

	private final ObjectMapper mapper = JsonMapper.builder().build();
	private final List<String> devices = List.of("Kitchen", "Bedroom");

	private Plan plan(String q, Focus f) {
		return QueryPlanner.plan(q, devices, f, mapper);
	}

	private static String tools(Plan p) {
		return p.calls().toString();
	}

	@Test
	void onlyAnActualRequestCreatesAReport() {
		assertTrue(tools(plan("Generate a weekly report", Focus.EMPTY)).contains("create_report"));
		assertTrue(tools(plan("Please create a monthly report for the kitchen", Focus.EMPTY)).contains("create_report"));
		// a question about reports, or a refusal, must not store one
		assertTrue(!tools(plan("How do I create a weekly report?", Focus.EMPTY)).contains("create_report"));
		assertTrue(!tools(plan("Don't generate a report, just tell me the average temperature today", Focus.EMPTY)).contains("create_report"));
	}

	@Test
	void currentQuestionsReadTheLatestValues() {
		Plan p = plan("What is the current temperature in the kitchen?", Focus.EMPTY);
		assertEquals("get_current_readings", p.calls().get(0).tool());
		assertTrue(p.calls().get(0).argumentsJson().contains("Kitchen"));
	}

	@Test
	void historicalStatisticsUseTheDatabaseAggregates() {
		Plan p = plan("What was the average humidity last week?", Focus.EMPTY);
		assertEquals("get_sensor_statistics", p.calls().get(0).tool());
		assertTrue(p.calls().get(0).argumentsJson().contains("\"period\":\"last_week\""));
		assertTrue(p.calls().get(0).argumentsJson().contains("humidity"));
	}

	@Test
	void trendsAndExplicitDates() {
		Plan p = plan("show the temperature trend from 2026-11-01 to 2026-11-07", Focus.EMPTY);
		assertEquals("get_sensor_trend", p.calls().get(0).tool());
		String a = p.calls().get(0).argumentsJson();
		assertTrue(a.contains("\"from\":\"2026-11-01\"") && a.contains("\"to\":\"2026-11-07\"") && a.contains("custom"), a);
	}

	@Test
	void twoPeriodsMakeAComparisonInTheOrderMentioned() {
		Plan p = plan("Was it warmer today than yesterday?", Focus.EMPTY);
		String a = p.calls().get(0).argumentsJson();
		assertEquals("compare_periods", p.calls().get(0).tool());
		assertTrue(a.contains("\"period\":\"today\"") && a.contains("\"compare_with\":\"yesterday\""), a);
		assertTrue(a.contains("temperature"));
	}

	@Test
	void theDayBeforeMeansThePreviousPeriod() {
		// Found in the end-to-end run: this used to plan plain statistics for yesterday only.
		Plan p = plan("How did the humidity compare between yesterday and the day before?", Focus.EMPTY);
		String a = p.calls().get(0).argumentsJson();
		assertEquals("compare_periods", p.calls().get(0).tool());
		assertTrue(a.contains("\"period\":\"yesterday\"") && a.contains("\"compare_with\":\"previous\"") && a.contains("humidity"), a);
		Plan h = plan("Was it warmer during the last 24 hours than in the 24 hours before?", Focus.EMPTY);
		assertEquals("compare_periods", h.calls().get(0).tool());
		assertTrue(h.calls().get(0).argumentsJson().contains("\"period\":\"last_24h\""), h.calls().toString());
	}

	@Test
	void compareItWithYesterdayResolvesTheMetricAndPeriodFromTheFocus() {
		Focus f = new Focus("gas", "today", null, null, "Bedroom");
		Plan p = plan("compare it with yesterday", f);
		String a = p.calls().get(0).argumentsJson();
		assertEquals("compare_periods", p.calls().get(0).tool());
		assertTrue(a.contains("\"metric\":\"gas\""), a);
		assertTrue(a.contains("\"period\":\"today\"") && a.contains("\"compare_with\":\"yesterday\""), a);
		assertTrue(a.contains("Bedroom"), "the device carries over: " + a);
		assertTrue(p.followUp());
	}

	@Test
	void whatAboutHumiditySwitchesTheMetricButKeepsThePeriod() {
		Plan p = plan("what about humidity?", new Focus("temperature", "last_week", null, null, null));
		String a = p.calls().get(0).argumentsJson();
		assertEquals("get_sensor_statistics", p.calls().get(0).tool());
		assertTrue(a.contains("humidity") && a.contains("last_week"), a);
	}

	@Test
	void motionSecurityAnomaliesHealthAndReports() {
		assertTrue(tools(plan("Was there any motion last night or today?", Focus.EMPTY)).contains("get_motion_activity"));
		assertTrue(tools(plan("Any security alerts this week?", Focus.EMPTY)).contains("get_security_events"));
		assertTrue(tools(plan("Were there any anomalies?", Focus.EMPTY)).contains("get_anomalies"));
		assertTrue(tools(plan("Is the system healthy?", Focus.EMPTY)).contains("get_system_health"));
		assertTrue(tools(plan("Which devices are offline?", Focus.EMPTY)).contains("get_device_status"));
		Plan r = plan("Generate a weekly report", Focus.EMPTY);
		assertEquals("create_report", r.calls().get(0).tool());
		assertTrue(r.calls().get(0).argumentsJson().contains("weekly"));
	}

	@Test
	void gasIncidentQuestionsReadTheIncidentRecordsNotAveragesOrTheSecurityLog() {
		Plan last = plan("When was the last gas incident?", Focus.EMPTY);
		assertEquals("[PlannedCall[tool=get_gas_incidents, argumentsJson={\"limit\":1}]]", tools(last));
		assertEquals("[PlannedCall[tool=get_gas_incidents, argumentsJson={\"limit\":1}]]",
				tools(plan("What was the gas reading during the last incident?", Focus.EMPTY)), "not the current reading");
		assertEquals("[PlannedCall[tool=get_gas_incidents, argumentsJson={\"limit\":10,\"period\":\"last_24h\"}]]",
				tools(plan("How many gas incidents occurred in the last 24 hours?", Focus.EMPTY)));
		assertEquals("[PlannedCall[tool=get_gas_incidents, argumentsJson={\"limit\":5}]]", tools(plan("Show me the last five gas incidents.", Focus.EMPTY)));
		assertEquals("[PlannedCall[tool=get_gas_incidents, argumentsJson={\"limit\":3}]]", tools(plan("list the latest 3 gas alerts", Focus.EMPTY)));
		// security alerts and motion are still the security log
		assertTrue(tools(plan("Any security alerts this week?", Focus.EMPTY)).contains("get_security_events"));
		assertFalse(tools(plan("Any security incidents this week?", Focus.EMPTY)).contains("get_gas_incidents"));
	}

	@Test
	void ledHistoryQuestionsReadTheCommandLogButCommandsDoNot() {
		Plan p = plan("When was the gas LED last turned on?", Focus.EMPTY);
		assertEquals("get_command_history", p.calls().get(0).tool());
		String a = p.calls().get(0).argumentsJson();
		assertTrue(a.contains("\"action\":\"set_gas_led\"") && a.contains("\"value\":\"on\""), a);
		assertEquals(1, p.calls().size(), "no current readings for an LED history question");
		assertFalse(tools(plan("Turn on the gas LED", Focus.EMPTY)).contains("get_command_history"));
	}

	@Test
	void connectivityAndSummaries() {
		Plan online = plan("Which devices are currently online?", Focus.EMPTY);
		assertEquals("get_device_status", online.calls().get(0).tool());
		assertEquals(1, online.calls().size());
		Plan sum = plan("Summarize the recent activity of the kitchen.", Focus.EMPTY);
		assertEquals(List.of("get_sensor_statistics", "get_motion_activity", "get_security_events"), sum.calls().stream().map(c -> c.tool()).toList());
		assertTrue(sum.calls().get(0).argumentsJson().contains("Kitchen") && sum.calls().get(0).argumentsJson().contains("last_24h"));
	}

	@Test
	void howToQuestionsAndSmallTalkPlanNothing() {
		assertTrue(plan("How do I pair a new device?", Focus.EMPTY).isEmpty());
		assertTrue(plan("tell me a joke", Focus.EMPTY).isEmpty());
	}

	@Test
	void neverMoreThanThreeCalls() {
		Plan p = plan("security anomalies automation health motion devices today", Focus.EMPTY);
		assertTrue(p.calls().size() <= QueryPlanner.MAX_CALLS);
	}

	@Test
	void periodsAreFoundInOrderWithoutDoubleMatching() {
		assertEquals(List.of("last_7_days"), QueryPlanner.periodsIn(" over the last 7 days "));
		assertEquals(List.of("this_week", "last_week"), QueryPlanner.periodsIn(" this week vs last week "));
	}
}
