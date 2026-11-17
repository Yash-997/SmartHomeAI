package com.smarthome.report;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.config.ApiError;
import com.smarthome.report.ReportDtos.ReportDetail;
import com.smarthome.report.ReportDtos.ReportRequest;
import com.smarthome.report.ReportDtos.ReportSummary;
import com.smarthome.report.ReportExceptions.ReportNotFoundException;
import com.smarthome.report.ReportExceptions.ReportUnavailableException;
import com.smarthome.report.ReportExceptions.TooManyReportsException;

import jakarta.validation.Valid;

/** Reports of the signed-in user (JWT). The owner always comes from the token. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

	private final ReportService service;

	public ReportController(ReportService service) {
		this.service = service;
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ReportDetail create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReportRequest req) {
		return service.generate(userId(jwt), req.kind(), req.from(), req.to(), req.timeZone(), req.deviceId());
	}

	@GetMapping
	public List<ReportSummary> list(@AuthenticationPrincipal Jwt jwt) {
		return service.list(userId(jwt));
	}

	@GetMapping("/{id}")
	public ReportDetail get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return service.get(userId(jwt), id);
	}

	@GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
	public ResponseEntity<byte[]> pdf(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		byte[] pdf = service.pdf(userId(jwt), id);
		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("smarthome-report-" + id + ".pdf").build().toString())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.contentType(MediaType.APPLICATION_PDF).body(pdf);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		service.delete(userId(jwt), id);
	}

	// ---- errors (only for this controller) ---------------------------------------------------------

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<ApiError> invalid(IllegalArgumentException e) {
		return ResponseEntity.badRequest().body(new ApiError(java.time.Instant.now(), 400, "Bad Request", e.getMessage(), Map.of()));
	}

	@ExceptionHandler(ReportNotFoundException.class)
	ResponseEntity<ApiError> notFound(ReportNotFoundException e) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(404, "Not Found", e.getMessage()));
	}

	@ExceptionHandler(TooManyReportsException.class)
	ResponseEntity<ApiError> tooMany(TooManyReportsException e) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiError.of(429, "Too Many Requests", e.getMessage()));
	}

	@ExceptionHandler(ReportUnavailableException.class)
	ResponseEntity<ApiError> unavailable(ReportUnavailableException e) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiError.of(503, "Service Unavailable", e.getMessage()));
	}
}
