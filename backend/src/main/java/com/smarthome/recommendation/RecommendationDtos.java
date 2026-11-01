package com.smarthome.recommendation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.smarthome.automation.AutomationDtos.RuleRequest;

import tools.jackson.databind.JsonNode;

public final class RecommendationDtos {

	private RecommendationDtos() {
	}

	/**
	 * {@code evidence} holds the real numbers behind the suggestion. For CREATE_RULE, {@code rule} is exactly what will be
	 * created on approval and {@code description} reads it in plain words; for DISABLE_RULE, {@code ruleId} names the rule.
	 * status: PENDING, APPLIED, DISMISSED or STALE.
	 */
	public record RecommendationResponse(UUID id, UUID deviceId, String actionType, String title, String rationale, JsonNode evidence,
			String description, RuleRequest rule, UUID ruleId, String status, Instant createdAt, Instant decidedAt, UUID createdRuleId) {
	}

	/** {@code notes} explains, in words, what could not be suggested and why (not enough data, model not ready...). */
	public record RecommendationList(List<RecommendationResponse> recommendations, List<String> notes, Instant generatedAt) {
	}
}
