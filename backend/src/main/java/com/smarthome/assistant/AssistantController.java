package com.smarthome.assistant;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.assistant.AssistantDtos.ChatRequest;
import com.smarthome.assistant.AssistantDtos.ChatResponse;
import com.smarthome.assistant.AssistantDtos.ConversationDto;
import com.smarthome.assistant.AssistantDtos.DocumentDto;
import com.smarthome.assistant.AssistantDtos.DocumentRequest;
import com.smarthome.assistant.AssistantDtos.MessageDto;
import com.smarthome.assistant.AssistantDtos.StatusResponse;
import com.smarthome.assistant.AssistantDtos.SuggestionDto;
import com.smarthome.automation.AutomationDtos.RuleResponse;

import jakarta.validation.Valid;

/** User JWT required (user security chain). The owner always comes from the token, never from the request body. */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

	private final AssistantService service;

	public AssistantController(AssistantService service) {
		this.service = service;
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	@GetMapping("/status")
	public StatusResponse status(@AuthenticationPrincipal Jwt jwt) {
		return service.status(userId(jwt));
	}

	@PostMapping("/chat")
	public ChatResponse chat(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChatRequest req) {
		return service.chat(userId(jwt), req.conversationId(), req.message(), req.timeZone(), AssistantService.Progress.NONE);
	}

	/**
	 * The same chat as {@code /chat}, as server-sent events: {@code progress} events ({"stage","detail"}) while the answer is
	 * prepared, then one {@code done} event with the full {@link ChatResponse}, or one {@code error} event
	 * ({"status","message","fieldErrors"}). The answer itself is sent whole, after the grounding check, never unchecked.
	 */
	@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public org.springframework.http.ResponseEntity<SseEmitter> chatStream(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChatRequest req) {
		UUID owner = userId(jwt);                        // read here: the worker thread has no security context
		SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
		STREAMS.execute(() -> {
			try {
				ChatResponse r = service.chat(owner, req.conversationId(), req.message(), req.timeZone(), (stage, detail) -> send(emitter, "progress",
						Map.of("stage", stage, "detail", detail)));
				send(emitter, "done", r);
				emitter.complete();
			} catch (RuntimeException e) {
				ApiErrorView err = errorView(e);
				send(emitter, "error", err);
				emitter.complete();
			}
		});
		// nginx (the frontend container) buffers proxied responses by default, which would hold every progress event
		// back until the answer is complete; this header turns buffering off for this response only.
		return org.springframework.http.ResponseEntity.ok().header("X-Accel-Buffering", "no").header("Cache-Control", "no-cache").body(emitter);
	}

	private static final long STREAM_TIMEOUT_MS = 180_000;
	private static final java.util.concurrent.ExecutorService STREAMS = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

	record ApiErrorView(int status, String message, Map<String, String> fieldErrors) {
	}

	private static ApiErrorView errorView(RuntimeException e) {
		if (e instanceof AssistantExceptions.InvalidChatRequestException i) {
			return new ApiErrorView(400, "Invalid request", i.fieldErrors());
		}
		if (e instanceof AssistantExceptions.TooManyChatRequestsException) {
			return new ApiErrorView(429, e.getMessage(), Map.of());
		}
		if (e instanceof AssistantExceptions.ConversationNotFoundException) {
			return new ApiErrorView(404, e.getMessage(), Map.of());
		}
		if (e instanceof AssistantExceptions.AssistantUnavailableException) {
			return new ApiErrorView(503, e.getMessage(), Map.of());
		}
		org.slf4j.LoggerFactory.getLogger(AssistantController.class).error("Streaming chat failed: {}", e.getClass().getSimpleName());
		return new ApiErrorView(500, "Something went wrong. Please try again.", Map.of());
	}

	private static void send(SseEmitter emitter, String event, Object data) {
		try {
			emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
		} catch (java.io.IOException | IllegalStateException e) {
			// the client went away; the answer is still stored and appears in the conversation
		}
	}

	@GetMapping("/conversations")
	public List<ConversationDto> conversations(@AuthenticationPrincipal Jwt jwt) {
		return service.conversations(userId(jwt));
	}

	@GetMapping("/conversations/{id}/messages")
	public List<MessageDto> messages(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return service.messages(userId(jwt), id);
	}

	@DeleteMapping("/conversations/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteConversation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		service.deleteConversation(userId(jwt), id);
	}

	// ---- knowledge documents -------------------------------------------------------------------

	@GetMapping("/documents")
	public List<DocumentDto> documents(@AuthenticationPrincipal Jwt jwt) {
		return service.documents(userId(jwt));
	}

	@PostMapping("/documents")
	@ResponseStatus(HttpStatus.CREATED)
	public DocumentDto addDocument(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DocumentRequest req) {
		return service.addDocument(userId(jwt), req.title(), req.text());
	}

	@DeleteMapping("/documents/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteDocument(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
		service.deleteDocument(userId(jwt), id);
	}

	// ---- suggestions (nothing is created until the owner approves) -----------------------------

	@GetMapping("/suggestions")
	public List<SuggestionDto> suggestions(@AuthenticationPrincipal Jwt jwt) {
		return service.pendingSuggestions(userId(jwt));
	}

	@PostMapping("/suggestions/{id}/approve")
	public RuleResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return service.approve(userId(jwt), id);
	}

	@PostMapping("/suggestions/{id}/reject")
	public SuggestionDto reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return service.reject(userId(jwt), id);
	}
}
