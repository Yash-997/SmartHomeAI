package com.smarthome.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.smarthome.assistant.SuggestionParser.Parsed;

import tools.jackson.databind.json.JsonMapper;

class SuggestionParserTest {

	private final SuggestionParser parser = new SuggestionParser(JsonMapper.builder().build());

	private static final String GOOD = "<<<RULE_SUGGESTION\n{\"summary\":\"Night light\",\"rationale\":\"You asked for it\","
			+ "\"rule\":{\"name\":\"Night light\",\"deviceId\":\"7f3e2d1c-0000-4000-8000-000000000001\",\"trigger\":{\"type\":\"TELEMETRY\"},"
			+ "\"conditions\":[{\"sensor\":\"motion\",\"operator\":\"==\",\"value\":true}],\"action\":{\"action\":\"set_pir_led\",\"value\":\"on\"}}}\n>>>";

	@Test
	void aReplyWithoutABlockIsJustText() {
		Parsed p = parser.parse("Your temperature is 23.4 C [S1].");
		assertEquals("Your temperature is 23.4 C [S1].", p.visibleText());
		assertNull(p.proposal());
		assertNull(p.problem());
	}

	@Test
	void aValidBlockIsParsedAndRemovedFromTheVisibleText() {
		Parsed p = parser.parse("Here is a rule you can approve.\n" + GOOD);
		assertEquals("Here is a rule you can approve.", p.visibleText());
		assertNotNull(p.proposal());
		assertEquals("Night light", p.proposal().summary());
		assertEquals("set_pir_led", p.proposal().rule().action().action());
		assertEquals("motion", p.proposal().rule().conditions().get(0).sensor());
		assertNull(p.problem());
	}

	@Test
	void extraFieldsInTheRuleAreIgnoredNotObeyed() {
		String sneaky = GOOD.replace("\"name\":\"Night light\",", "\"name\":\"Night light\",\"ownerUserId\":\"someone-else\",\"admin\":true,");
		Parsed p = parser.parse(sneaky);
		assertNotNull(p.proposal());
	}

	@Test
	void malformedJsonGivesNoProposalAndAProblemNote() {
		Parsed p = parser.parse("Sure.\n<<<RULE_SUGGESTION\n{not json}\n>>>");
		assertNull(p.proposal());
		assertNotNull(p.problem());
		assertEquals("Sure.", p.visibleText());
	}

	@Test
	void aBlockWithoutARuleObjectGivesNoProposal() {
		Parsed p = parser.parse("<<<RULE_SUGGESTION {\"summary\":\"x\"} >>>");
		assertNull(p.proposal());
		assertTrue(p.problem().contains("no rule"));
	}

	@Test
	void anUnterminatedBlockIsDroppedFromTheTextAndReported() {
		Parsed p = parser.parse("Answer.\n<<<RULE_SUGGESTION {\"rule\":{");
		assertNull(p.proposal());
		assertEquals("Answer.", p.visibleText());
		assertNotNull(p.problem());
	}

	@Test
	void onlyTheFirstOfSeveralBlocksIsKeptAndAllAreHidden() {
		Parsed p = parser.parse("Two rules.\n" + GOOD + "\n" + GOOD.replace("Night light", "Second"));
		assertNotNull(p.proposal());
		assertEquals("Night light", p.proposal().summary());
		assertFalse(p.visibleText().contains("RULE_SUGGESTION"));
		assertTrue(p.problem().contains("first"));
	}
}
