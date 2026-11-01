package com.smarthome.assistant;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.smarthome.automation.AutomationDtos.RuleRequest;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Separates a model reply into the text to show and an optional proposed rule. The proposal is only parsed here; it is
 * validated and stored as PENDING by {@link AssistantService}, and becomes a rule only if the owner approves it.
 * Every suggestion block is removed from the visible text, so a model (or injected text) cannot smuggle one in.
 */
@Component
public class SuggestionParser {

	private static final Pattern BLOCK = Pattern.compile(Pattern.quote(PromptBuilder.SUGGESTION_OPEN) + "(.*?)" + Pattern.quote(PromptBuilder.SUGGESTION_CLOSE),
			Pattern.DOTALL);
	/** An unterminated block (the reply was cut off) is dropped too. */
	private static final Pattern DANGLING = Pattern.compile(Pattern.quote(PromptBuilder.SUGGESTION_OPEN) + ".*", Pattern.DOTALL);

	public record Proposal(String summary, String rationale, RuleRequest rule) {
	}

	/** @param proposal null if there was none; @param problem why a block was present but unusable (null otherwise) */
	public record Parsed(String visibleText, Proposal proposal, String problem) {
	}

	private final ObjectMapper mapper;

	public SuggestionParser(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public Parsed parse(String reply) {
		Matcher m = BLOCK.matcher(reply);
		String first = null;
		int blocks = 0;
		while (m.find()) {
			if (first == null) {
				first = m.group(1);
			}
			blocks++;
		}
		String visible = DANGLING.matcher(BLOCK.matcher(reply).replaceAll("")).replaceAll("").strip();
		boolean dangling = reply.contains(PromptBuilder.SUGGESTION_OPEN) && first == null;
		if (first == null) {
			return new Parsed(visible, null, dangling ? "the proposed rule was incomplete" : null);
		}
		try {
			JsonNode root = mapper.readTree(first.strip());
			JsonNode rule = root.path("rule");
			if (!rule.isObject()) {
				return new Parsed(visible, null, "the proposed rule had no rule definition");
			}
			// Unknown fields (an "owner", "admin"...) are ignored, never applied: RuleRequest simply has no place for them.
			RuleRequest req = mapper.readerFor(RuleRequest.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(rule);
			String summary = Text.oneLine(root.path("summary").asString(""), 200);
			if (summary.isEmpty()) {
				summary = "Proposed rule: " + Text.oneLine(req.name(), 60);
			}
			String problem = blocks > 1 ? "only the first of several proposed rules was kept" : null;
			return new Parsed(visible, new Proposal(summary, Text.oneLine(root.path("rationale").asString(""), 1000), req), problem);
		} catch (RuntimeException e) {
			return new Parsed(visible, null, "the proposed rule could not be read");
		}
	}
}
