package com.smarthome.ml;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smarthome.ml.MlDtos.AnomalyResponse;
import com.smarthome.ml.MlDtos.ForecastResponse;
import com.smarthome.ml.MlDtos.MlStatusResponse;
import com.smarthome.ml.MlDtos.OccupancyStatusResponse;
import com.smarthome.ml.MlDtos.PredictionResponse;
import com.smarthome.ml.MlDtos.ResolveRequest;
import com.smarthome.ml.MlDtos.ScanResponse;

import jakarta.validation.Valid;

/** User JWT required (user security chain). The owner always comes from the token; the device is checked against it. */
@RestController
@RequestMapping("/api/devices/{deviceId}/ml")
public class MlController {

	static final String NOTICE = "ML insights are advisory. They are computed from stored readings and never change a device or "
			+ "an automation. The device's own gas warning works independently of all of this.";

	private final OccupancyService occupancy;
	private final AnomalyService anomalies;
	private final AnomalyScanner scanner;

	public MlController(OccupancyService occupancy, AnomalyService anomalies, AnomalyScanner scanner) {
		this.occupancy = occupancy;
		this.anomalies = anomalies;
		this.scanner = scanner;
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	@GetMapping("/status")
	public MlStatusResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
		OccupancyStatusResponse o = occupancy.status(userId(jwt), deviceId);   // also checks ownership
		return new MlStatusResponse(o, anomalies.summary(deviceId, scanner.lastScanAt(deviceId)), NOTICE);
	}

	/** Trains now, on stored history (this is the only way a person starts training). Optional IANA time zone for "time of day". */
	@PostMapping("/occupancy/train")
	public OccupancyStatusResponse train(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId,
			@RequestParam(required = false) String timeZone) {
		return occupancy.trainManually(userId(jwt), deviceId, timeZone);
	}

	@GetMapping("/occupancy/predict")
	public PredictionResponse predict(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
		return occupancy.predict(userId(jwt), deviceId, at);
	}

	@GetMapping("/occupancy/forecast")
	public ForecastResponse forecast(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(defaultValue = "24") int hours, @RequestParam(defaultValue = "60") int stepMinutes) {
		return occupancy.forecast(userId(jwt), deviceId, from, hours, stepMinutes);
	}

	/** status=active (default) or all. */
	@GetMapping("/anomalies")
	public List<AnomalyResponse> anomalies(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId,
			@RequestParam(defaultValue = "active") String status, @RequestParam(defaultValue = "50") int limit) {
		return anomalies.list(userId(jwt), deviceId, !"all".equalsIgnoreCase(status), limit);
	}

	@PatchMapping("/anomalies/{anomalyId}")
	public AnomalyResponse resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId, @PathVariable UUID anomalyId,
			@Valid @RequestBody ResolveRequest req) {
		return anomalies.resolve(userId(jwt), deviceId, anomalyId, req.status(), req.note());
	}

	/** Runs an anomaly check over the last hour right now. */
	@PostMapping("/anomalies/scan")
	public ScanResponse scan(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
		return scanner.scanManually(userId(jwt), deviceId);
	}
}
