package com.smarthome.predict;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.command.CommandDtos.CommandResponse;
import com.smarthome.predict.LightingDecisionEngine.Override;
import com.smarthome.predict.LightingDecisionEngine.Settings;

/** User JWT required. Simulation endpoints touch no device; only the two explicitly named ones below can. */
@RestController
@RequestMapping("/api/predictive")
public class PredictiveController {

	private final Map<UUID, SimulationSession> sessions = new ConcurrentHashMap<>();
	private final LivePredictionService live;
	private final PredictiveModelService models;

	public PredictiveController(LivePredictionService live, PredictiveModelService models) {
		this.live = live;
		this.models = models;
	}

	public record Regenerate(Long seed) {
	}

	/** The saved/loaded model and its dataset. Never trains. */
	@GetMapping("/model")
	public PredictiveModelService.ModelInfo model() {
		return models.info();
	}

	/** Trains a new version on the stored synthetic dataset. */
	@PostMapping("/model/train")
	public PredictiveModelService.ModelInfo train() {
		models.retrain();
		return models.info();
	}

	/** Replaces the shared synthetic dataset (same seed = same routine and noise) and retrains. */
	@PostMapping("/dataset/regenerate")
	public PredictiveModelService.ModelInfo regenerate(@RequestBody Regenerate req) {
		models.regenerate(need(req.seed(), "seed"));
		return models.info();
	}

	@GetMapping("/now")
	public PredictiveModelService.Now now() {
		return models.now();
	}

	@GetMapping("/evaluation")
	public PredictiveModel.Evaluation evaluation() {
		return models.current().model().stored().evaluation();
	}

	/** Only the fields that the action uses need to be set. */
	public record SimAction(String action, SimulationSession.Scenario scenario, Integer minute, Integer minutes, Integer wake,
			Integer leave, Integer back, Integer sustainDays, Settings settings, Override override) {
	}

	public record LiveAction(Boolean armed, Override override, Settings settings) {
	}

	public record SendRequest(UUID deviceId, String value) {
	}

	private SimulationSession session(Jwt jwt) {
		PredictiveModelService.Ready ready = models.current();
		// A new model version (retrain or new dataset) starts a fresh session on it.
		return sessions.compute(UUID.fromString(jwt.getSubject()),
				(id, old) -> old != null && old.modelVersion() == ready.version() ? old : new SimulationSession(ready));
	}

	@GetMapping("/simulation")
	public SimulationSession.View simulation(@AuthenticationPrincipal Jwt jwt) {
		return session(jwt).view();
	}

	@PostMapping("/simulation")
	public SimulationSession.View act(@AuthenticationPrincipal Jwt jwt, @RequestBody SimAction a) {
		SimulationSession s = session(jwt);
		switch (String.valueOf(a.action())) {
			case "scenario" -> s.select(need(a.scenario(), "scenario"));
			case "time" -> s.setTime(need(a.minute(), "minute"));
			case "advance" -> s.advance(need(a.minutes(), "minutes"));
			case "motion" -> s.triggerMotion();
			case "today" -> s.changeToday(need(a.wake(), "wake"), need(a.leave(), "leave"), need(a.back(), "back"),
					a.sustainDays() == null ? 0 : a.sustainDays());
			case "settings" -> s.setSettings(need(a.settings(), "settings"));
			case "override" -> s.setOverride(need(a.override(), "override"));
			case "reset" -> s.reset();
			default -> throw new IllegalArgumentException("Unknown simulation action");
		}
		return s.view();
	}

	/** Sends a real command to the user's own device. The acknowledgement comes from the normal command endpoints. */
	@PostMapping("/simulation/send-to-device")
	public CommandResponse send(@AuthenticationPrincipal Jwt jwt, @RequestBody SendRequest req) {
		return live.sendSimulatedDecision(UUID.fromString(jwt.getSubject()), need(req.deviceId(), "deviceId"),
				need(req.value(), "value"));
	}

	@GetMapping("/live/{deviceId}")
	public LivePredictionService.View live(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
		return live.view(UUID.fromString(jwt.getSubject()), deviceId);
	}

	@PostMapping("/live/{deviceId}")
	public LivePredictionService.View configure(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId,
			@RequestBody LiveAction a) {
		return live.configure(UUID.fromString(jwt.getSubject()), deviceId, a.armed(), a.override(), a.settings());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
	}

	private static <T> T need(T value, String name) {
		if (value == null) {
			throw new IllegalArgumentException("Missing field: " + name);
		}
		return value;
	}
}
