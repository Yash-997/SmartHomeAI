package com.smarthome.assistant;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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
		return service.chat(userId(jwt), req.conversationId(), req.message());
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
