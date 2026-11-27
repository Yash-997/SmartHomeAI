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

import com.smarthome.ml.MlDtos.AnomalyModelStatusResponse;
import com.smarthome.ml.MlDtos.AnomalyResponse;
import com.smarthome.ml.MlDtos.ConnectivityResponse;
import com.smarthome.ml.MlDtos.MlStatusResponse;
import com.smarthome.ml.MlDtos.ResolveRequest;
import com.smarthome.ml.MlDtos.ScanResponse;

import com.smarthome.device.DeviceService;
import com.smarthome.predict.PredictiveModelService;

import jakarta.validation.Valid;

/** User JWT required (user security chain). The owner always comes from the token; the device is checked against it. */
@RestController
@RequestMapping("/api/devices/{deviceId}/ml")
public class MlController {

	static final String NOTICE = "ML insights are advisory. They are computed from stored readings and never change a device or "
			+ "an automation. The device's own gas warning works independently of all of this.";

	private final PredictiveModelService predictive;
	private final DeviceService devices;
	private final AnomalyService anomalies;
	private final AnomalyScanner scanner;
	private final DataCollectionService collection;
	private final AnomalyModelService anomalyModels;

	public MlController(PredictiveModelService predictive, DeviceService devices, AnomalyService anomalies, AnomalyScanner scanner, DataCollectionService collection,
			AnomalyModelService anomalyModels) {
		this.anomalyModels = anomalyModels;
		this.predictive = predictive;
		this.devices = devices;
		this.anomalies = anomalies;
		this.scanner = scanner;
		this.collection = collection;
	}

	private static UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

	@GetMapping("/status")
	public MlStatusResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
		devices.requireOwned(userId(jwt), deviceId);
		return new MlStatusResponse(predictive.info(), anomalies.summary(deviceId, scanner.lastScanAt(deviceId)), NOTICE);
	}

	@GetMapping("/connectivity")
	public ConnectivityResponse connectivity(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId,
			@RequestParam(defaultValue = "20") int limit) {
		return collection.connectivity(userId(jwt), deviceId, limit);
	}

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

	/** The Isolation Forest anomaly models (real-data and synthetic), with their evaluation. */
	@GetMapping("/anomaly-model")
	public AnomalyModelStatusResponse anomalyModel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
		return anomalyModels.status(userId(jwt), deviceId);
	}

	/** Retrains the Isolation Forest on this device's real telemetry now (it also retrains itself about once a day). */
	@PostMapping("/anomaly-model/train")
	public AnomalyModelStatusResponse trainAnomalyModel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
		return anomalyModels.trainManually(userId(jwt), deviceId);
	}

	/** Runs an anomaly check over the last hour right now. */
	@PostMapping("/anomalies/scan")
	public ScanResponse scan(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID deviceId) {
		return scanner.scanManually(userId(jwt), deviceId);
	}
}
