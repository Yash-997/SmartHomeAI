package com.smarthome.ml;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.smarthome.device.Device;
import com.smarthome.device.DeviceRepository;
import com.smarthome.device.DeviceService;
import com.smarthome.device.DeviceStatus;
import com.smarthome.ml.MlDtos.CountsResponse;
import com.smarthome.ml.MlDtos.DataSummary;
import com.smarthome.ml.MlDtos.ForecastResponse;
import com.smarthome.ml.MlDtos.MetricsResponse;
import com.smarthome.ml.MlDtos.OccupancyStatusResponse;
import com.smarthome.ml.MlDtos.PatternCell;
import com.smarthome.ml.MlDtos.PatternResponse;
import com.smarthome.ml.MlDtos.PredictionResponse;
import com.smarthome.ml.MlDtos.RequirementsResponse;
import com.smarthome.ml.MlDtos.TrainingRunResponse;
import com.smarthome.ml.MlExceptions.InvalidMlRequestException;
import com.smarthome.ml.MlExceptions.ModelNotReadyException;
import com.smarthome.ml.MlExceptions.TooFrequentException;
import com.smarthome.ml.MlExceptions.TrainingBusyException;
import com.smarthome.ml.MlTrainingRun.Trigger;
import com.smarthome.ml.ModelMetrics.Metrics;
import com.smarthome.ml.OccupancyTrainer.Status;

import tools.jackson.databind.ObjectMapper;

/**
 * Occupancy model lifecycle: train, store, version, report status, predict.
 *
 * <p>Training happens only (a) when the owner asks, and (b) from a slow scheduled check that retrains a model about
 * once a day if enough new real data has arrived. Individual telemetry messages never trigger training. Every run is
 * kept in {@code ml_training_runs}; a run that produces a model gets the next version number and becomes current. A
 * run without enough data never replaces a working model.
 */
@Service
public class OccupancyService {

	private static final Logger log = LoggerFactory.getLogger(OccupancyService.class);
	static final String METHOD = "Logistic regression (Newton / IRLS, L2-regularised) on time of day, day of week, weekend rhythm and "
			+ "each hour's past motion frequency, trained on motion activity per 15-minute slot, evaluated on the most recent slots";
	static final String COLLECTING = "Collecting historical data.";

	private final DeviceService deviceService;
	private final DeviceRepository devices;
	private final MlModelRepository models;
	private final MlTrainingRunRepository runs;
	private final MlSampleRepository samples;
	private final DataCollectionService collection;
	private final MlProperties props;
	private final ObjectMapper mapper;
	private final Clock clock;
	private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
	private final Map<UUID, Instant> lastManualTraining = new ConcurrentHashMap<>();

	public OccupancyService(DeviceService deviceService, DeviceRepository devices, MlModelRepository models, MlTrainingRunRepository runs,
			MlSampleRepository samples, DataCollectionService collection, MlProperties props, ObjectMapper mapper, Clock clock) {
		this.deviceService = deviceService;
		this.devices = devices;
		this.models = models;
		this.runs = runs;
		this.samples = samples;
		this.collection = collection;
		this.props = props;
		this.mapper = mapper;
		this.clock = clock;
	}

	// ---- status ----------------------------------------------------------------------------------

	public OccupancyStatusResponse status(UUID ownerId, UUID deviceUuid) {
		Device d = deviceService.requireOwned(ownerId, deviceUuid);
		return toStatus(d, models.findByDeviceUuidAndModelType(d.getId(), MlModel.OCCUPANCY).orElse(null), null);
	}

	private OccupancyStatusResponse toStatus(Device d, MlModel m, String extraMessage) {
		MlProperties.Occupancy cfg = props.occupancy();
		RequirementsResponse req = new RequirementsResponse(cfg.bucketMinutes(), cfg.minReadingsPerBucket(), cfg.minLabeledBuckets(),
				cfg.minDistinctDays(), cfg.minClassBuckets());
		DataSummary data = collection.summary(d.getId());
		long runCount = runs.countByDeviceUuidAndModelType(d.getId(), MlModel.OCCUPANCY);
		Instant lastSuccess = runs.findFirstByDeviceUuidAndModelTypeAndBecameCurrentTrueOrderByTrainedAtDesc(d.getId(), MlModel.OCCUPANCY)
				.map(MlTrainingRun::getTrainedAt).orElse(m != null && m.hasModel() ? m.getTrainedAt() : null);
		boolean auto = props.training().autoEnabled();
		if (m == null) {
			String msg = COLLECTING + " " + (data.dataset().enoughForTraining()
					? "There is now enough history; the next automatic training check will build the first model (or press Train now)."
					: "Nothing is predicted until there is enough real history to learn from.");
			return new OccupancyStatusResponse("NOT_TRAINED", false, msg, null, null, null, req, null, data, METHOD, true, 0, null, runCount,
					OccupancyFeatures.FEATURE_SET, auto);
		}
		CountsResponse counts = new CountsResponse(m.getSamples(), m.getLabeledBuckets(), m.getPositiveBuckets(),
				m.getLabeledBuckets() - m.getPositiveBuckets(), 0, m.getDistinctDays(), m.getDataFrom(), m.getDataTo());
		MetricsResponse metrics = m.getMetricsJson() == null ? null : mapper.readValue(m.getMetricsJson(), MetricsResponse.class);
		boolean collecting = !m.hasModel();
		String reason = m.getReason() == null ? "" : m.getReason();
		String message = collecting ? COLLECTING + " " + reason : reason;
		if (extraMessage != null) {
			message = extraMessage + " " + message;
		}
		return new OccupancyStatusResponse(m.getStatus().name(), m.hasModel(), message.trim(), m.getTrainedAt(), m.getTimeZone(), counts, req,
				metrics, data, METHOD, collecting, m.getVersion(), lastSuccess, runCount, OccupancyFeatures.FEATURE_SET, auto);
	}

	// ---- training --------------------------------------------------------------------------------

	/** Owner-requested training. Rate limited, and refuses to run twice at once for the same device. */
	public OccupancyStatusResponse trainManually(UUID ownerId, UUID deviceUuid, String timeZone) {
		Device d = deviceService.requireOwned(ownerId, deviceUuid);
		ZoneId zone = resolveZone(d, timeZone);
		Instant now = clock.instant();
		Instant last = lastManualTraining.get(d.getId());
		int min = props.training().minManualIntervalSeconds();
		if (last != null && Duration.between(last, now).getSeconds() < min) {
			throw new TooFrequentException("Training was requested a moment ago. Wait about " + min + " seconds between runs.");
		}
		lastManualTraining.put(d.getId(), now);
		MlModel before = models.findByDeviceUuidAndModelType(d.getId(), MlModel.OCCUPANCY).orElse(null);
		boolean hadModel = before != null && before.hasModel();
		Instant beforeTrainedAt = before == null ? null : before.getTrainedAt();
		MlModel after = trainDevice(d, zone, now, Trigger.MANUAL);
		String note = hadModel && after.getTrainedAt().equals(beforeTrainedAt)
				? "Training did not replace the existing model because the data is currently insufficient."
				: null;
		return toStatus(d, after, note);
	}

	/** Called by the slow scheduler. Retrains only when it is worthwhile; errors on one device never affect the others. */
	public void autoTrainAll(Instant now) {
		if (!props.training().autoEnabled()) {
			return;
		}
		for (Device d : devices.findByStatus(DeviceStatus.ACTIVE)) {
			try {
				if (shouldAutoTrain(d, now)) {
					MlModel existing = models.findByDeviceUuidAndModelType(d.getId(), MlModel.OCCUPANCY).orElse(null);
					ZoneId zone = existing != null ? ZoneId.of(existing.getTimeZone()) : props.zone();
					trainDevice(d, zone, now, Trigger.AUTO);
				}
			} catch (RuntimeException e) {
				log.warn("Automatic training failed for device {}: {}", d.getDeviceId(), e.getClass().getSimpleName());
			}
		}
	}

	/**
	 * Avoids pointless work: no training without recent real data; the first attempt as soon as data exists; after "not
	 * enough data" only every few hours; and an existing model only after the retrain interval AND enough new readings.
	 */
	boolean shouldAutoTrain(Device d, Instant now) {
		Instant lastMotion = samples.lastMotionAt(d.getId());
		if (lastMotion == null || lastMotion.isBefore(now.minus(2, ChronoUnit.DAYS))) {
			return false;   // no recent data: nothing new to learn from
		}
		MlModel m = models.findByDeviceUuidAndModelType(d.getId(), MlModel.OCCUPANCY).orElse(null);
		MlProperties.Training t = props.training();
		if (m == null) {
			return true;
		}
		if (!m.hasModel()) {
			return m.getTrainedAt().isBefore(now.minus(t.insufficientRetryHours(), ChronoUnit.HOURS));
		}
		return m.getTrainedAt().isBefore(now.minus(t.retrainIntervalHours(), ChronoUnit.HOURS))
				&& samples.countMotionSamplesAfter(d.getId(), m.getTrainedAt()) >= t.minNewSamplesForRetrain();
	}

	/** Trains, records the run, and stores the model. A failed attempt never overwrites a working model. */
	MlModel trainDevice(Device d, ZoneId zone, Instant now, Trigger trigger) {
		ReentrantLock lock = locks.computeIfAbsent(d.getId(), k -> new ReentrantLock());
		if (!lock.tryLock()) {
			throw new TrainingBusyException();
		}
		try {
			long started = System.nanoTime();
			MlProperties.Occupancy cfg = props.occupancy();
			List<MotionSample> data = samples.findMotionSamples(d.getId(), now.minus(cfg.windowDays(), ChronoUnit.DAYS),
					PageRequest.of(0, cfg.maxSamples()));
			OccupancyTrainer.Result result = OccupancyTrainer.train(data, zone, cfg);
			long durationMs = (System.nanoTime() - started) / 1_000_000;
			String metricsJson = result.metrics() == null ? null : mapper.writeValueAsString(metricsResponse(result));

			MlModel existing = models.findByDeviceUuidAndModelType(d.getId(), MlModel.OCCUPANCY).orElse(null);
			if (result.status() == Status.INSUFFICIENT_DATA && existing != null && existing.hasModel()) {
				runs.save(new MlTrainingRun(d.getId(), null, trigger, result, false, zone.getId(), OccupancyFeatures.FEATURE_SET, now, durationMs, null));
				return existing;   // keep the working model; the caller is told why nothing changed
			}
			MlModel row = existing != null ? existing : new MlModel(d.getId());
			String modelJson = result.model() == null ? null : mapper.writeValueAsString(result.model());
			row.record(result, zone.getId(), cfg.bucketMinutes(), modelJson, metricsJson, now);
			MlModel saved = models.save(row);
			runs.save(new MlTrainingRun(d.getId(), modelJson == null ? null : saved.getVersion(), trigger, result, modelJson != null, zone.getId(),
					OccupancyFeatures.FEATURE_SET, now, durationMs, metricsJson));
			if (modelJson != null) {
				log.info("Occupancy model v{} for device {}: {} ({} slots, {} days, {} trigger)", saved.getVersion(), d.getDeviceId(),
						result.status(), result.counts().labeledBuckets(), result.counts().distinctDays(), trigger);
			}
			return saved;
		} finally {
			lock.unlock();
		}
	}

	private static MetricsResponse metricsResponse(OccupancyTrainer.Result r) {
		Metrics m = r.metrics();
		return new MetricsResponse(r.trainBuckets(), m.n(), m.positives(), m.accuracy(), m.precision(), m.recall(), m.f1(), m.auc(),
				m.logLoss(), m.brier(), m.baselineAccuracy(), m.baselineLogLoss(),
				"Fitted on the earliest " + r.trainBuckets() + " slots and scored on the latest " + m.n()
						+ " slots it had not seen. The stored model is then refitted on all slots.");
	}

	private ZoneId resolveZone(Device d, String requested) {
		if (requested != null && !requested.isBlank()) {
			if (!ZoneId.getAvailableZoneIds().contains(requested)) {
				throw new InvalidMlRequestException("timeZone", "Use an IANA time zone name such as Asia/Kolkata or UTC");
			}
			return ZoneId.of(requested);
		}
		return models.findByDeviceUuidAndModelType(d.getId(), MlModel.OCCUPANCY).map(m -> ZoneId.of(m.getTimeZone())).orElse(props.zone());
	}

	/** Tests only: forget the manual-training rate limit. */
	public void forgetRateLimits() {
		lastManualTraining.clear();
	}

	// ---- history and learned pattern --------------------------------------------------------------

	public List<TrainingRunResponse> runs(UUID ownerId, UUID deviceUuid, int limit) {
		Device d = deviceService.requireOwned(ownerId, deviceUuid);
		return runs.findByDeviceUuidAndModelTypeOrderByTrainedAtDesc(d.getId(), MlModel.OCCUPANCY, PageRequest.of(0, Math.max(1, Math.min(limit, 100))))
				.stream().map(r -> new TrainingRunResponse(r.getId(), r.getVersion(), r.getTrigger().name(), r.getStatus().name(), r.getReason(),
						r.isBecameCurrent(), r.getTrainedAt(), r.getDurationMs(), r.getTimeZone(), r.getFeatureSet(), r.getSamples(),
						r.getLabeledBuckets(), r.getPositiveBuckets(), r.getDistinctDays(), r.getDataFrom(), r.getDataTo(),
						r.getMetricsJson() == null ? null : mapper.readValue(r.getMetricsJson(), MetricsResponse.class)))
				.toList();
	}

	/**
	 * The learned weekly pattern: for each of the 168 hours of the week, what the training history showed and what the
	 * current model predicts. Only available once a model exists.
	 */
	public PatternResponse pattern(UUID ownerId, UUID deviceUuid) {
		Device d = deviceService.requireOwned(ownerId, deviceUuid);
		Loaded l = load(d);
		ZoneId zone = ZoneId.of(l.model.timeZone());
		// Any fixed week works: features depend only on weekday and time. Use the current week in the model's zone.
		ZonedDateTime monday = clock.instant().atZone(zone).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
				.truncatedTo(ChronoUnit.DAYS);
		List<PatternCell> cells = new ArrayList<>(168);
		for (int day = 0; day < 7; day++) {
			for (int hour = 0; hour < 24; hour++) {
				ZonedDateTime mid = monday.plusDays(day).withHour(hour).withMinute(30);
				int c = OccupancyFeatures.cell(mid);
				int n = l.model.cellN()[c];
				cells.add(new PatternCell(day + 1, hour, n, n == 0 ? null : l.model.cellPos()[c] / (double) n, l.model.probability(mid)));
			}
		}
		return new PatternResponse(zone.getId(), l.row.getVersion(), l.row.getStatus().name(), l.row.getTrainedAt(), l.model.baseRate(), cells);
	}

	/** The current model if it is good enough to judge behaviour by (READY), for the unusual-activity check. */
	Optional<OccupancyModel> readyModel(UUID deviceUuid) {
		return models.findByDeviceUuidAndModelType(deviceUuid, MlModel.OCCUPANCY)
				.filter(m -> m.hasModel() && m.getStatus() == Status.READY)
				.map(m -> mapper.readValue(m.getModelJson(), OccupancyModel.class));
	}

	// ---- prediction ------------------------------------------------------------------------------

	public PredictionResponse predict(UUID ownerId, UUID deviceUuid, Instant at) {
		Device d = deviceService.requireOwned(ownerId, deviceUuid);
		Loaded loaded = load(d);
		return point(loaded, at == null ? clock.instant() : at);
	}

	public ForecastResponse forecast(UUID ownerId, UUID deviceUuid, Instant from, int hours, int stepMinutes) {
		if (hours < 1 || hours > 72) {
			throw new InvalidMlRequestException("hours", "Between 1 and 72");
		}
		if (stepMinutes != 15 && stepMinutes != 30 && stepMinutes != 60) {
			throw new InvalidMlRequestException("stepMinutes", "Must be 15, 30 or 60");
		}
		Device d = deviceService.requireOwned(ownerId, deviceUuid);
		Loaded loaded = load(d);
		Instant start = (from == null ? clock.instant() : from).truncatedTo(ChronoUnit.MINUTES);
		List<PredictionResponse> points = new ArrayList<>();
		for (int i = 0; i < hours * 60 / stepMinutes; i++) {
			points.add(point(loaded, start.plus((long) i * stepMinutes, ChronoUnit.MINUTES)));
		}
		return new ForecastResponse(loaded.model.timeZone(), loaded.row.getStatus().name(), points);
	}

	private record Loaded(MlModel row, OccupancyModel model) {
	}

	private Loaded load(Device d) {
		MlModel row = models.findByDeviceUuidAndModelType(d.getId(), MlModel.OCCUPANCY).orElse(null);
		if (row == null) {
			throw new ModelNotReadyException(COLLECTING + " There is no occupancy model yet; predictions are never made without one.");
		}
		if (!row.hasModel()) {
			throw new ModelNotReadyException(COLLECTING + " There is no occupancy model because there is not enough data yet. " + row.getReason());
		}
		return new Loaded(row, mapper.readValue(row.getModelJson(), OccupancyModel.class));
	}

	private PredictionResponse point(Loaded l, Instant at) {
		ZonedDateTime local = at.atZone(ZoneId.of(l.model.timeZone()));
		double p = l.model.probability(local);
		int support = l.model.support(local);
		boolean lowQuality = l.row.getStatus() == Status.LOW_QUALITY;
		return new PredictionResponse(at, local.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).toString(),
				local.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), local.getHour(), p,
				p >= props.occupancy().decisionThreshold(), Math.max(p, 1 - p), support,
				support >= props.occupancy().minSupport() && !lowQuality, l.model.observedRate(local), l.row.getStatus().name());
	}
}
