package com.smarthome.ml;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.smarthome.device.Device;
import com.smarthome.device.DeviceRepository;
import com.smarthome.device.DeviceService;
import com.smarthome.device.DeviceStatus;
import com.smarthome.ml.MlDtos.AnomalyModelInfo;
import com.smarthome.ml.MlDtos.AnomalyModelStatusResponse;
import com.smarthome.ml.MlExceptions.InvalidMlRequestException;
import com.smarthome.ml.MlExceptions.TooFrequentException;
import com.smarthome.ml.MlTrainingRun.DataSource;
import com.smarthome.ml.MlTrainingRun.Trigger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Trains, stores and serves the Isolation Forest anomaly models.
 *
 * <ul>
 *   <li>{@link MlModel#ANOMALY_IF}: trained on the device's real telemetry. Only a READY one is used by the live scanner.</li>
 *   <li>{@link MlModel#ANOMALY_IF_SYNTHETIC}: trained and evaluated on the device's SYNTHETIC dataset, with labels. Shown
 *       for evaluation only; never used for live detection.</li>
 * </ul>
 * Every run is kept in ml_training_runs. Training never happens on the telemetry path: on request, or from the hourly
 * job on the ML worker thread.
 */
@Service
public class AnomalyModelService {

	private static final Logger log = LoggerFactory.getLogger(AnomalyModelService.class);
	static final String NOTICE = "The Isolation Forest only adds anomaly findings next to the statistical rules; it never removes, "
			+ "closes or delays one, and the device's gas warning and the backend gas alert do not depend on it.";

	private record Cached(UUID modelId, int version, AnomalyModel model) {
	}

	private final MlModelRepository models;
	private final MlTrainingRunRepository runs;
	private final MlSampleRepository samples;
	private final MlAnomalyRepository anomalies;
	private final DeviceRepository devices;
	private final DeviceService deviceService;
	private final MlProperties props;
	private final ObjectMapper mapper;
	private final Clock clock;
	private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
	private final Map<UUID, Instant> lastManual = new ConcurrentHashMap<>();

	public AnomalyModelService(MlModelRepository models, MlTrainingRunRepository runs, MlSampleRepository samples, MlAnomalyRepository anomalies,
			DeviceRepository devices, DeviceService deviceService, MlProperties props, ObjectMapper mapper, Clock clock) {
		this.models = models;
		this.runs = runs;
		this.samples = samples;
		this.anomalies = anomalies;
		this.devices = devices;
		this.deviceService = deviceService;
		this.props = props;
		this.mapper = mapper;
		this.clock = clock;
	}

	// ---- live use ---------------------------------------------------------------------------------

	/** The device's READY real-data model, parsed once per version. Empty if there is none. */
	public Optional<AnomalyModel> readyModel(UUID deviceUuid) {
		Optional<MlModel> row = models.findByDeviceUuidAndModelType(deviceUuid, MlModel.ANOMALY_IF)
				.filter(m -> m.getStatus() == OccupancyTrainer.Status.READY && m.getArtifactJson() != null);
		if (row.isEmpty()) {
			cache.remove(deviceUuid);
			return Optional.empty();
		}
		MlModel m = row.get();
		Cached c = cache.get(deviceUuid);
		if (c == null || !c.modelId().equals(m.getId()) || c.version() != m.getVersion()) {
			c = new Cached(m.getId(), m.getVersion(), mapper.readValue(m.getArtifactJson(), AnomalyModel.class));
			cache.put(deviceUuid, c);
		}
		return Optional.of(c.model());
	}

	public void forget(UUID deviceUuid) {
		cache.remove(deviceUuid);
		lastManual.remove(deviceUuid);
	}

	@org.springframework.beans.factory.annotation.Value("${app.ml.forest.real-training-enabled:false}")
	private boolean realTrainingEnabled;

	// ---- training on real telemetry ---------------------------------------------------------------

	public AnomalyModelStatusResponse trainManually(UUID ownerId, UUID deviceUuid) {
		Device d = deviceService.requireOwned(ownerId, deviceUuid);
		if (!realTrainingEnabled) {
			throw new InvalidMlRequestException("model",
					"Training on live device readings is switched off: this installation trains only on synthetic data.");
		}
		Instant now = clock.instant();
		Instant last = lastManual.get(d.getId());
		if (last != null && Duration.between(last, now).getSeconds() < props.training().minManualIntervalSeconds()) {
			throw new TooFrequentException("Training was requested a moment ago. Wait a little between runs.");
		}
		lastManual.put(d.getId(), now);
		trainReal(d.getId(), now, Trigger.MANUAL);
		return status(d);
	}

	/** Hourly job: retrains each active device about once a day; after "not enough data" only every few hours. */
	public void autoTrainAll(Instant now) {
		if (!realTrainingEnabled || !props.training().autoEnabled()) {
			return;
		}
		for (Device d : devices.findByStatus(DeviceStatus.ACTIVE)) {
			try {
				MlModel m = models.findByDeviceUuidAndModelType(d.getId(), MlModel.ANOMALY_IF).orElse(null);
				int hours = m == null ? 0 : m.hasModel() ? props.forest().retrainIntervalHours() : props.training().insufficientRetryHours();
				if (m == null || m.getTrainedAt().isBefore(now.minus(hours, ChronoUnit.HOURS))) {
					trainReal(d.getId(), now, Trigger.AUTO);
				}
			} catch (RuntimeException e) {
				log.warn("Automatic anomaly-model training failed for device {}: {}", d.getDeviceId(), e.getClass().getSimpleName());
			}
		}
	}

	/** Trains on the last {@code trainingDays} of real telemetry and stores the outcome (even "not enough data"). */
	public AnomalyModelTrainer.Result trainReal(UUID deviceUuid, Instant now, Trigger trigger) {
		long started = System.nanoTime();
		MlProperties.Forest cfg = props.forest();
		ZoneId zone = props.zone();
		Instant from = now.minus(cfg.trainingDays(), ChronoUnit.DAYS);
		List<TelemetryPoint> points = samples.findTelemetryPoints(deviceUuid, from, now, PageRequest.of(0, cfg.maxSamples()));
		List<MinuteFrame> frames = MinuteFrame.resample(points);
		AnomalyModelTrainer.Result r = AnomalyModelTrainer.trainUnlabelled(frames, zone, props, recordedAnomalies(deviceUuid, from));
		long durationMs = (System.nanoTime() - started) / 1_000_000;

		MlModel row = models.findByDeviceUuidAndModelType(deviceUuid, MlModel.ANOMALY_IF).orElseGet(() -> new MlModel(deviceUuid, MlModel.ANOMALY_IF));
		String artifact = r.model() == null ? null : mapper.writeValueAsString(r.model());
		String metrics = r.report() == null ? null : mapper.writeValueAsString(r.report());
		int trainingMinutes = r.report() == null ? 0 : r.report().trainFrames();
		int excluded = r.report() == null ? 0 : r.report().excludedFrames();
		// A failed attempt never replaces a working model: it is recorded in the run history only.
		if (artifact != null || !row.hasModel()) {
			row.recordAnomalyModel(status(r.status()), r.message(), zone.getId(), r.dataFrom(), r.dataTo(), points.size(), trainingMinutes, excluded,
					r.distinctDays(), summary(r.model()), artifact, metrics, now);
			row = models.save(row);
		}
		runs.save(new MlTrainingRun(deviceUuid, MlModel.ANOMALY_IF, DataSource.REAL, null, artifact == null ? null : row.getVersion(), trigger,
				status(r.status()), r.message(), artifact != null, zone.getId(), AnomalyFeatures.FEATURE_SET, now, durationMs, r.dataFrom(), r.dataTo(),
				points.size(), trainingMinutes, excluded, r.distinctDays(), metrics));
		cache.remove(deviceUuid);
		log.info("Anomaly model for device {}: {} ({} minutes, {} ms)", deviceUuid, r.status(), r.frames(), durationMs);
		return r;
	}

	/** Minutes inside recorded anomalies, except dismissed ones (the owner said those were false positives). */
	private Predicate<Instant> recordedAnomalies(UUID deviceUuid, Instant since) {
		List<Instant[]> spans = new ArrayList<>();
		for (MlAnomaly a : anomalies.findByDeviceUuidAndLastSeenAtAfter(deviceUuid, since)) {
			if (a.getStatus() == AnomalyStatus.DISMISSED) {
				continue;
			}
			Instant start = a.getReadingAt() != null && a.getReadingAt().isBefore(a.getDetectedAt()) ? a.getReadingAt() : a.getDetectedAt();
			spans.add(new Instant[] { start.minus(5, ChronoUnit.MINUTES), a.getLastSeenAt().plus(5, ChronoUnit.MINUTES) });
		}
		return t -> spans.stream().anyMatch(s -> !t.isBefore(s[0]) && !t.isAfter(s[1]));
	}

	// ---- training on SYNTHETIC data ---------------------------------------------------------------

	/** Trains and evaluates on a labelled synthetic dataset; stores it as the synthetic model (never used live). */
	public AnomalyBenchmark.Report trainSynthetic(UUID deviceUuid, UUID datasetId, SyntheticGenerator.Dataset ds, OccupancyModel occupancy,
			Instant now) {
		long started = System.nanoTime();
		AnomalyBenchmark.Outcome o = AnomalyBenchmark.run(ds, occupancy, props);
		long durationMs = (System.nanoTime() - started) / 1_000_000;
		String artifact = mapper.writeValueAsString(o.model());
		String metrics = mapper.writeValueAsString(o.report());
		AnomalyBenchmark.DetectorResult hybrid = o.report().test().get(o.report().test().size() - 1);
		String message = String.format(java.util.Locale.ROOT, "SYNTHETIC data. Test period, rules + Isolation Forest: precision %s, recall %s, F1 %s, "
				+ "%.3f false alarms per hour, %d of %d anomaly windows detected.", hybrid.precision(), hybrid.recall(), hybrid.f1(),
				hybrid.falseAlarmsPerHour(), hybrid.windowsDetected(), hybrid.windows());
		MlModel row = models.findByDeviceUuidAndModelType(deviceUuid, MlModel.ANOMALY_IF_SYNTHETIC)
				.orElseGet(() -> new MlModel(deviceUuid, MlModel.ANOMALY_IF_SYNTHETIC));
		row.recordAnomalyModel(OccupancyTrainer.Status.READY, message, ds.zone().getId(), ds.start(), ds.end(), ds.readings().size(),
				o.report().split().trainFrames(), 0, ds.days(), summary(o.model()), artifact, metrics, now);
		row = models.save(row);
		runs.save(new MlTrainingRun(deviceUuid, MlModel.ANOMALY_IF_SYNTHETIC, DataSource.SYNTHETIC, datasetId, row.getVersion(), Trigger.MANUAL,
				OccupancyTrainer.Status.READY, message, true, ds.zone().getId(), AnomalyFeatures.FEATURE_SET, now, durationMs, ds.start(), ds.end(),
				ds.readings().size(), o.report().split().trainFrames(), 0, ds.days(), metrics));
		return o.report();
	}

	// ---- reading ----------------------------------------------------------------------------------

	public AnomalyModelStatusResponse status(UUID ownerId, UUID deviceUuid) {
		return status(deviceService.requireOwned(ownerId, deviceUuid));
	}

	private AnomalyModelStatusResponse status(Device d) {
		AnomalyModelInfo real = models.findByDeviceUuidAndModelType(d.getId(), MlModel.ANOMALY_IF).map(m -> info(m, "REAL")).orElse(null);
		AnomalyModelInfo synth = models.findByDeviceUuidAndModelType(d.getId(), MlModel.ANOMALY_IF_SYNTHETIC).map(m -> info(m, "SYNTHETIC")).orElse(null);
		boolean live = props.forest().enabled() && real != null && "READY".equals(real.state()) && real.version() > 0;
		return new AnomalyModelStatusResponse(real, synth, live, NOTICE);
	}

	private AnomalyModelInfo info(MlModel m, String source) {
		JsonNode metrics = m.getMetricsJson() == null ? null : mapper.readTree(m.getMetricsJson());
		JsonNode summary = m.getModelJson() == null ? null : mapper.readTree(m.getModelJson());
		return new AnomalyModelInfo(m.getModelType(), source, m.getStatus().name(), m.getVersion(), m.getTrainedAt(), m.getReason(), m.getTimeZone(),
				m.getDataFrom(), m.getDataTo(), m.getLabeledBuckets(), summary, metrics);
	}

	/** Short description of a model for the API (the full model stays in artifact_json). */
	private String summary(AnomalyModel m) {
		if (m == null) {
			return null;
		}
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("version", m.version());
		s.put("dataSource", m.dataSource());
		s.put("featureSet", m.featureSet());
		s.put("features", m.featureNames().size());
		s.put("groups", m.forests().stream().map(AnomalyModel.Subspace::group).toList());
		s.put("treesPerGroup", m.forests().get(0).forest().trees().size());
		s.put("subsample", m.forests().get(0).forest().subsample());
		s.put("seed", m.forests().get(0).forest().seed());
		s.put("quantile", m.quantile());
		s.put("threshold", AnomalyModelTrainer.round(m.threshold()));
		s.put("criticalThreshold", AnomalyModelTrainer.round(m.criticalThreshold()));
		s.put("consecutiveMinutes", m.consecutive());
		s.put("windowMinutes", m.preprocessing().windowMinutes());
		s.put("trainedFrom", m.trainedFrom().toString());
		s.put("trainedTo", m.trainedTo().toString());
		s.put("trainingMinutes", m.trainingFrames());
		return mapper.writeValueAsString(s);
	}

	private static OccupancyTrainer.Status status(AnomalyModelTrainer.Status s) {
		return OccupancyTrainer.Status.valueOf(s.name());
	}
}
