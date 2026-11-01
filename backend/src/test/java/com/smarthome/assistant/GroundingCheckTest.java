package com.smarthome.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.smarthome.assistant.GroundingCheck.Result;

class GroundingCheckTest {

	private static final String SOURCE = "Latest reading: temperature 23.4 C, humidity 51.2 %, gas raw 1510. 1280 readings are stored. Last heard 12 seconds ago.";

	@Test
	void numbersFoundInTheSourcesPass() {
		Result r = GroundingCheck.check("The temperature is 23.4 C and humidity is 51.2 % [S1].", 1, SOURCE, "how warm is it");
		assertTrue(r.grounded(), r.warnings().toString());
		assertEquals(List.of(1), r.cited());
	}

	@Test
	void anInventedNumberIsReportedAsUnverified() {
		Result r = GroundingCheck.check("The temperature is 41.5 C [S1].", 1, SOURCE, "how warm is it");
		assertFalse(r.grounded());
		assertEquals(List.of("41.5"), r.unverifiedNumbers());
		assertTrue(r.warnings().get(0).contains("41.5"));
	}

	@Test
	void roundingToFewerDecimalsIsAccepted() {
		assertTrue(GroundingCheck.check("About 23.4 C, roughly 23 C [S1].", 1, SOURCE, "").grounded());
		assertTrue(GroundingCheck.check("Humidity is 51 % [S1].", 1, SOURCE, "").grounded());
	}

	@Test
	void citationsToSourcesThatDoNotExistAreRemoved() {
		Result r = GroundingCheck.check("Gas raw is 1510 [S1] and also [S7] [S0].", 2, SOURCE, "");
		assertEquals(List.of(1), r.cited());
		assertFalse(r.text().contains("S7"));
		assertFalse(r.text().contains("S0"));
	}

	@Test
	void smallWholeNumbersAreExemptButNotWhenTheyCarryAUnit() {
		assertTrue(GroundingCheck.check("There are 3 devices [S1].", 1, SOURCE, "").grounded());
		Result r = GroundingCheck.check("It is 7 C outside [S1].", 1, SOURCE, "");
		assertEquals(List.of("7"), r.unverifiedNumbers());
	}

	@Test
	void anAnswerWithNumbersButNoCitationIsWarnedAbout() {
		Result r = GroundingCheck.check("Gas raw is 1510.", 1, SOURCE, "");
		assertTrue(r.warnings().stream().anyMatch(w -> w.contains("does not cite")));
	}

	@Test
	void aPlainRefusalWithNoNumbersNeedsNoCitation() {
		assertTrue(GroundingCheck.check("I do not have that information.", 1, SOURCE, "").grounded());
	}

	@Test
	void aNumberTheUserTypedInTheQuestionIsNotCountedAsInvented() {
		assertTrue(GroundingCheck.check("You asked about 300 seconds; the last message was 12 seconds ago [S1].", 1, SOURCE, "what happened in the last 300 seconds").grounded());
	}

	@Test
	void fractionalSecondsOfATimestampCannotConfirmAnInventedReading() {
		String source = "Latest stored reading (received 3 seconds ago at 2026-10-25T01:19:41.512Z): temperature 23.4 C.";
		Result r = GroundingCheck.check("The temperature is 41.5 C [S1].", 1, source, "");
		assertEquals(List.of("41.5"), r.unverifiedNumbers());
		// the whole-number parts of the timestamp are still quotable
		assertTrue(GroundingCheck.check("It was received at 01:19:41 on 2026-10-25 [S1].", 1, source, "").grounded());
	}

	@Test
	void numbersInsideIdentifiersAreNotTreatedAsReadings() {
		Result r = GroundingCheck.check("Device esp32-ab12 reported gas raw 1510 [S1].", 1, SOURCE, "");
		assertTrue(r.grounded(), r.warnings().toString());
	}
}
