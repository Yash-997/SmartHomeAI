package com.smarthome.recommendation;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.recommendation.RecommendationDtos.RecommendationList;
import com.smarthome.recommendation.RecommendationDtos.RecommendationResponse;

/** User JWT required. Approving is the only way a recommendation changes anything. */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

	private final RecommendationService service;

	public RecommendationController(RecommendationService service) {
		this.service = service;
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	@GetMapping
	public RecommendationList list(@AuthenticationPrincipal Jwt jwt) {
		return service.list(userId(jwt));
	}

	@PostMapping("/refresh")
	public RecommendationList refresh(@AuthenticationPrincipal Jwt jwt) {
		return service.refresh(userId(jwt));
	}

	@PostMapping("/{id}/approve")
	public RecommendationResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return service.approve(userId(jwt), id);
	}

	@PostMapping("/{id}/dismiss")
	public RecommendationResponse dismiss(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return service.dismiss(userId(jwt), id);
	}
}
