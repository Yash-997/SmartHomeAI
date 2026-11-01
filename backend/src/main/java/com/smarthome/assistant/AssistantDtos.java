package com.smarthome.assistant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.smarthome.automation.AutomationDtos.RuleRequest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response shapes of the assistant API. Responses never contain keys, other users' data or internal ids of other users. */
public final class AssistantDtos {

	private AssistantDtos() {
	}

	/** How an assistant message came about. */
	public enum AnswerStatus {
		/** The model answered from the retrieved sources. */
		ANSWERED,
		/** Nothing relevant was found, so the model was not asked. */
		NO_CONTEXT,
		/** No language model is configured; only the references are shown. */
		NOT_CONFIGURED,
		/** The language model could not be reached or refused; the references are still shown. */
		LLM_UNAVAILABLE,
		/** The embedding service needed to search was not available. */
		RETRIEVAL_UNAVAILABLE
	}

	public record ChatRequest(UUID conversationId, @NotBlank @Size(max = 4000) String message) {
	}

	/** A reference shown under an answer. {@code cited} = the answer pointed at it. {@code snippet} is the stored text, shortened. */
	public record SourceDto(String ref, String type, String title, String snippet, double score, Instant asOf, UUID deviceId, boolean cited) {
	}

	/**
	 * A proposed rule. {@code description} is a plain-words reading of {@code rule}. Nothing exists until it is approved.
	 * status: PENDING, APPROVED, REJECTED or EXPIRED.
	 */
	public record SuggestionDto(UUID id, String summary, String rationale, String description, RuleRequest rule, String status,
			Instant createdAt, Instant expiresAt, UUID createdRuleId) {
	}

	public record MessageDto(UUID id, String role, String content, AnswerStatus status, List<SourceDto> sources, List<String> warnings,
			List<SuggestionDto> suggestions, Instant createdAt) {
	}

	public record ChatResponse(UUID conversationId, String conversationTitle, MessageDto userMessage, MessageDto assistantMessage) {
	}

	public record ConversationDto(UUID id, String title, Instant createdAt, Instant updatedAt) {
	}

	public record DocumentRequest(@NotBlank @Size(max = 100) String title, @NotBlank String text) {
	}

	public record DocumentDto(String id, String title, Instant addedAt, int chunks) {
	}

	public record ModelInfo(boolean configured, String provider, String model, String note) {
	}

	public record IndexInfo(long yourChunks, long sharedChunks, int documents) {
	}

	public record LimitsInfo(int maxMessageChars, int maxDocumentChars, int maxDocuments, int topK) {
	}

	public record StatusResponse(ModelInfo llm, ModelInfo embedding, IndexInfo index, LimitsInfo limits, String notice) {
	}
}
