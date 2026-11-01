package com.smarthome.config;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.smarthome.assistant.AssistantExceptions.AssistantUnavailableException;
import com.smarthome.assistant.AssistantExceptions.ConversationNotFoundException;
import com.smarthome.assistant.AssistantExceptions.DocumentNotFoundException;
import com.smarthome.assistant.AssistantExceptions.InvalidChatRequestException;
import com.smarthome.assistant.AssistantExceptions.SuggestionNotFoundException;
import com.smarthome.assistant.AssistantExceptions.SuggestionNotPendingException;
import com.smarthome.assistant.AssistantExceptions.TooManyChatRequestsException;
import com.smarthome.recommendation.RecommendationExceptions.RecommendationNotFoundException;
import com.smarthome.recommendation.RecommendationExceptions.RecommendationNotPendingException;
import com.smarthome.security.SecurityExceptions.InvalidSecurityRequestException;
import com.smarthome.security.SecurityExceptions.SecurityEventNotFoundException;
import com.smarthome.auth.ConflictException;
import com.smarthome.auth.InvalidCredentialsException;
import com.smarthome.automation.AutomationExceptions.InvalidRuleException;
import com.smarthome.automation.AutomationExceptions.RuleNotFoundException;
import com.smarthome.command.CommandExceptions.CommandNotFoundException;
import com.smarthome.command.CommandExceptions.DeviceOfflineException;
import com.smarthome.command.CommandExceptions.InvalidCommandException;
import com.smarthome.command.CommandExceptions.TooManyCommandsException;
import com.smarthome.device.DeviceExceptions.DeviceNotFoundException;
import com.smarthome.device.DeviceExceptions.InvalidPairingCodeException;
import com.smarthome.device.DeviceExceptions.TooManyAttemptsException;
import com.smarthome.ml.MlExceptions.AnomalyNotFoundException;
import com.smarthome.ml.MlExceptions.InvalidAnomalyTransitionException;
import com.smarthome.ml.MlExceptions.InvalidMlRequestException;
import com.smarthome.ml.MlExceptions.ModelNotReadyException;
import com.smarthome.ml.MlExceptions.TooFrequentException;
import com.smarthome.ml.MlExceptions.TrainingBusyException;
import com.smarthome.mqtt.BrokerAccess.BrokerUnavailableException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
		Map<String, String> fields = new LinkedHashMap<>();
		e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
		return ResponseEntity.badRequest()
				.body(new ApiError(Instant.now(), 400, "Bad Request", "Validation failed", fields));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
		return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request", "Malformed request body"));
	}

	@ExceptionHandler(ConflictException.class)
	ResponseEntity<ApiError> conflict(ConflictException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(409, "Conflict", e.getMessage()));
	}

	@ExceptionHandler(DeviceNotFoundException.class)
	ResponseEntity<ApiError> deviceNotFound(DeviceNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not Found", e.getMessage()));
	}

	@ExceptionHandler(InvalidPairingCodeException.class)
	ResponseEntity<ApiError> invalidPairingCode(InvalidPairingCodeException e) {
		return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request", e.getMessage()));
	}

	@ExceptionHandler(TooManyAttemptsException.class)
	ResponseEntity<ApiError> tooManyAttempts(TooManyAttemptsException e) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiError.of(429, "Too Many Requests", e.getMessage()));
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ApiError> badPathVariable(MethodArgumentTypeMismatchException e) {
		return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request", "Invalid value for '" + e.getName() + "'"));
	}

	@ExceptionHandler(InvalidCommandException.class)
	ResponseEntity<ApiError> invalidCommand(InvalidCommandException e) {
		return ResponseEntity.badRequest()
				.body(new ApiError(Instant.now(), 400, "Bad Request", e.getMessage(), e.fieldErrors()));
	}

	@ExceptionHandler(DeviceOfflineException.class)
	ResponseEntity<ApiError> deviceOffline(DeviceOfflineException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(409, "Conflict", e.getMessage()));
	}

	@ExceptionHandler(TooManyCommandsException.class)
	ResponseEntity<ApiError> tooManyCommands(TooManyCommandsException e) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiError.of(429, "Too Many Requests", e.getMessage()));
	}

	@ExceptionHandler({ ModelNotReadyException.class, TrainingBusyException.class, InvalidAnomalyTransitionException.class })
	ResponseEntity<ApiError> mlConflict(RuntimeException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(409, "Conflict", e.getMessage()));
	}

	@ExceptionHandler(TooFrequentException.class)
	ResponseEntity<ApiError> tooFrequent(TooFrequentException e) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiError.of(429, "Too Many Requests", e.getMessage()));
	}

	@ExceptionHandler(InvalidMlRequestException.class)
	ResponseEntity<ApiError> invalidMlRequest(InvalidMlRequestException e) {
		return ResponseEntity.badRequest().body(new ApiError(Instant.now(), 400, "Bad Request", e.getMessage(), e.fieldErrors()));
	}

	@ExceptionHandler(AnomalyNotFoundException.class)
	ResponseEntity<ApiError> anomalyNotFound(AnomalyNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not Found", e.getMessage()));
	}

	@ExceptionHandler(InvalidChatRequestException.class)
	ResponseEntity<ApiError> invalidChatRequest(InvalidChatRequestException e) {
		return ResponseEntity.badRequest().body(new ApiError(Instant.now(), 400, "Bad Request", e.getMessage(), e.fieldErrors()));
	}

	@ExceptionHandler({ ConversationNotFoundException.class, SuggestionNotFoundException.class, DocumentNotFoundException.class })
	ResponseEntity<ApiError> assistantNotFound(RuntimeException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not Found", e.getMessage()));
	}

	@ExceptionHandler(SuggestionNotPendingException.class)
	ResponseEntity<ApiError> suggestionNotPending(SuggestionNotPendingException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(409, "Conflict", e.getMessage()));
	}

	@ExceptionHandler(TooManyChatRequestsException.class)
	ResponseEntity<ApiError> tooManyChat(TooManyChatRequestsException e) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiError.of(429, "Too Many Requests", e.getMessage()));
	}

	@ExceptionHandler(AssistantUnavailableException.class)
	ResponseEntity<ApiError> assistantUnavailable(AssistantUnavailableException e) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiError.of(503, "Service Unavailable", e.getMessage()));
	}

	@ExceptionHandler(InvalidSecurityRequestException.class)
	ResponseEntity<ApiError> invalidSecurityRequest(InvalidSecurityRequestException e) {
		return ResponseEntity.badRequest().body(new ApiError(Instant.now(), 400, "Bad Request", e.getMessage(), e.fieldErrors()));
	}

	@ExceptionHandler({ SecurityEventNotFoundException.class, RecommendationNotFoundException.class })
	ResponseEntity<ApiError> securityNotFound(RuntimeException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not Found", e.getMessage()));
	}

	@ExceptionHandler(RecommendationNotPendingException.class)
	ResponseEntity<ApiError> recommendationNotPending(RecommendationNotPendingException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(409, "Conflict", e.getMessage()));
	}

	@ExceptionHandler(InvalidRuleException.class)
	ResponseEntity<ApiError> invalidRule(InvalidRuleException e) {
		return ResponseEntity.badRequest()
				.body(new ApiError(Instant.now(), 400, "Bad Request", e.getMessage(), e.fieldErrors()));
	}

	@ExceptionHandler(RuleNotFoundException.class)
	ResponseEntity<ApiError> ruleNotFound(RuleNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not Found", e.getMessage()));
	}

	@ExceptionHandler(CommandNotFoundException.class)
	ResponseEntity<ApiError> commandNotFound(CommandNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not Found", e.getMessage()));
	}

	@ExceptionHandler(BrokerUnavailableException.class)
	ResponseEntity<ApiError> brokerUnavailable(BrokerUnavailableException e) {
		// The cause may name internal hosts; the client only needs to know it is temporary and nothing changed.
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiError.of(503, "Service Unavailable",
				"The device messaging service is unavailable. Nothing was changed; please try again shortly."));
	}

	@ExceptionHandler(InvalidCredentialsException.class)
	ResponseEntity<ApiError> invalidCredentials(InvalidCredentialsException e) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of(401, "Unauthorized", e.getMessage()));
	}
}
