package com.smarthome.ml;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.smarthome.device.Device;
import com.smarthome.device.DeviceService;
import com.smarthome.ml.MlDtos.MetricsResponse;
import com.smarthome.ml.MlDtos.PredictionResponse;
import com.smarthome.ml.MlExceptions.InvalidMlRequestException;
import com.smarthome.ml.MlExceptions.ModelNotReadyException;
import com.smarthome.ml.MlExceptions.TooFrequentException;
import com.smarthome.ml.MlTrainingRun.DataSource;
import com.smarthome.ml.MlTrainingRun.Trigger;
import com.smarthome.ml.SyntheticDtos.DatasetInfo;
import com.smarthome.ml.SyntheticDtos.ModelInfo;
import com.smarthome.ml.SyntheticDtos.ScenarioInfo;
import com.smarthome.ml.SyntheticDtos.StoredEvaluation;
import com.smarthome.ml.SyntheticDtos.SyntheticForecastResponse;
import com.smarthome.ml.SyntheticDtos.SyntheticStatusResponse;
import com.smarthome.ml.SyntheticDtos.TrainingDetails;
import com.smarthome.ml.SyntheticGenerator.Reading;
import com.smarthome.ml.SyntheticGenerator.Scenario;
import com.smarthome.ml.SyntheticGenerator.ScenarioWindow;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates, stores and trains on SYNTHETIC datasets. Only ever runs when the owner asks (never at startup, never on
 * telemetry). Synthetic readings live in their own tables; this class never reads or writes telemetry_readings, and the
 * model it trains is stored as {@link MlModel#OCCUPANCY_SYNTHETIC}, so it can never replace or be mistaken for the
 * device's real model.
 */
@Service
public class SyntheticService {

	private static final Logger log = LoggerFactory.getLogger(SyntheticService.class);
	static final int DEFAULT_DAYS = 30;
	static final long DEFAULT_SEED = 42;

	private final DeviceService deviceService;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final MlModelRepository models;
	private final MlTrainingRunRepository runs;
	private final MlProperties props;
	private final ObjectMapper mapper;
	private final Clock clock;
	private final Map<UUID, Instant> lastTraining = new ConcurrentHashMap<>();

	public SyntheticService(DeviceService deviceService, JdbcTemplate jdbc, TransactionTemplate tx, MlModelRepository models,
			MlTrainingRunRepository runs, MlProperties props, ObjectMapper mapper, Clock clock) {
		this.deviceService = deviceService;
		this.jdbc = jdbc;
		this.tx = tx;
		this.models = models;
		this.runs = runs;
		this.props = props;
		this.mapper = mapper;
		this.clock = clock;
	}

	private record Row(UUID id, long seed, int days, String zone, Instant startsAt, Instant endsAt, int recordCount, int offlineMinutes,
			Instant createdAt, String scenariosJson, String evaluationJson, String version) {
	}

	// ---- generation ------------------------------------------------------------------------------

	/**
	 * Generates the dataset unless one with the same settings already exists for this device, in which case that one is
	 * returned unchanged (idempotent: no duplicate records, existing data is never modified).
	 */
	public SyntheticStatusResponse generate(UUID ownerId, UUID deviceUuid, Long seedParam, Integer daysParam) {
		Device d = deviceService.requireOwned(ownerId, deviceUuid);
		long seed = seedParam == null ? DEFAULT_SEED : seedParam;
		int days = daysParam == null ? DEFAULT_DAYS : daysParam;
		if (days < 14 || days > 60) {
			throw new InvalidMlRequestException("days", "Between 14 and 60");
		}
		if (find(d.getId(), seed, days).isPresent()) {
			return status(d, false);
		}
		ZoneId zone = props.zone();
		// The dataset ends at the start of today (local) and covers the preceding days, so it never claims a future time.
		Instant start = LocalDate.now(clock.withZone(zone)).minusDays(days).atStartOfDay(zone).toInstant();
		SyntheticGenerator.Dataset ds = SyntheticGenerator.generate(start, days, zone, seed);
		UUID id = UUID.randomUUID();
		try {
			tx.executeWithoutResult(status -> insert(id, d, ownerId, ds));
			log.info("Synthetic dataset {} generated for device {}: {} readings, {} offline minutes", id, d.getDeviceId(), ds.readings().size(),
					ds.offlineMinutes());
		} catch (DuplicateKeyException e) {
			// Two requests at once: the other one won. Its dataset is the same, so use it.
		}
		return status(d, true);
	}

	private void insert(UUID id, Device d, UUID ownerId, SyntheticGenerator.Dataset ds) {
		List<ScenarioInfo> scenarios = ds.scenarios().stream()
				.map(w -> new ScenarioInfo(w.scenario().name(), w.description(), w.from(), w.to())).toList();
		jdbc.update("insert into synthetic_datasets (id, device_uuid, created_by, generator_version, seed, days, time_zone, starts_at, ends_at, "
				+ "record_count, offline_minutes, created_at, scenarios_json, evaluation_json) values (?,?,?,?,?,?,?,?,?,?,?,?,?,null)",
				id, d.getId(), ownerId, SyntheticGenerator.VERSION, ds.seed(), ds.days(), ds.zone().getId(), odt(ds.start()), odt(ds.end()),
				ds.readings().size(), ds.offlineMinutes(), odt(clock.instant()), mapper.writeValueAsString(scenarios));
		List<Reading> rows = ds.readings();
		jdbc.batchUpdate("insert into synthetic_readings (dataset_id, recorded_at, temperature_c, humidity_pct, motion, gas_raw, occupancy, scenario) "
				+ "values (?,?,?,?,?,?,?,?)", rows, 1000, (PreparedStatement ps, Reading r) -> {
					ps.setObject(1, id);
					ps.setObject(2, odt(r.at()));
					setDouble(ps, 3, r.temperature());
					setDouble(ps, 4, r.humidity());
					if (r.motion() == null) {
						ps.setNull(5, Types.BOOLEAN);
					} else {
						ps.setBoolean(5, r.motion());
					}
					if (r.gasRaw() == null) {
						ps.setNull(6, Types.INTEGER);
					} else {
						ps.setInt(6, r.gasRaw());
					}
					ps.setString(7, r.occupied() ? "OCCUPIED" : "UNOCCUPIED");
					ps.setString(8, r.scenario().name());
				});
		jdbc.batchUpdate("insert into synthetic_connectivity_events (dataset_id, changed_at, state, reason) values (?,?,?,?)", ds.connectivity(), 500,
				(ps, e) -> {
					ps.setObject(1, id);
					ps.setObject(2, odt(e.at()));
					ps.setString(3, e.state());
					ps.setString(4, e.reason());
				});
	}

	// ---- training --------------------------------------------------------------------------------

	/** Trains the demonstration model on the device's synthetic dataset and replays the anomaly scenarios. */
	public SyntheticStatusResponse train(UUID ownerId, UUID deviceUuid) {
		Device d = deviceService.requireOwned(ownerId, deviceUuid);
		Row row = latest(d.getId()).orElseThrow(() -> new ModelNotReadyException("Generate a synthetic dataset first."));
		Instant now = clock.instant();
		Instant last = lastTraining.get(d.getId());
		if (last != null && Duration.between(last, now).getSeconds() < props.training().minManualIntervalSeconds()) {
			throw new TooFrequentException("Training was requested a moment ago. Wait a little between runs.");
		}
		lastTraining.put(d.getId(), now);

		long started = System.nanoTime();
		SyntheticGenerator.Dataset ds = load(row);
		SyntheticTrainer.Result r = SyntheticTrainer.train(ds, props.occupancy());
		SyntheticAnomalyEvaluator.Evaluation eval = SyntheticAnomalyEvaluator.evaluate(ds, r.model(), props);
		long durationMs = (System.nanoTime() - started) / 1_000_000;

		MetricsResponse metrics = r.metrics() == null ? null : new MetricsResponse(r.train().slots() + r.validation().slots(), r.metrics().n(),
				r.metrics().positives(), r.metrics().accuracy(), r.metrics().precision(), r.metrics().recall(), r.metrics().f1(), r.metrics().auc(),
				r.metrics().logLoss(), r.metrics().brier(), r.metrics().baselineAccuracy(), r.metrics().baselineLogLoss(),
				"SYNTHETIC data. Chronological split: train " + r.train().slots() + " / validation " + r.validation().slots() + " / test "
						+ r.test().slots() + " slots, 1-hour embargo between parts; regularisation chosen on validation; scored once on the test part.");
		String metricsJson = metrics == null ? null : mapper.writeValueAsString(metrics);
		OccupancyTrainer.Result asResult = new OccupancyTrainer.Result(r.status(), r.message(), r.counts(), r.model(), r.metrics(),
				r.train().slots() + r.validation().slots(), r.test().slots());
		MlModel model = models.findByDeviceUuidAndModelType(d.getId(), MlModel.OCCUPANCY_SYNTHETIC).orElseGet(() -> new MlModel(d.getId(), MlModel.OCCUPANCY_SYNTHETIC));
		String modelJson = r.model() == null ? null : mapper.writeValueAsString(r.model());
		if (modelJson != null || !model.hasModel()) {
			model.record(asResult, ds.zone().getId(), props.occupancy().bucketMinutes(), modelJson, metricsJson, now);
			model = models.save(model);
		}
		runs.save(new MlTrainingRun(d.getId(), MlModel.OCCUPANCY_SYNTHETIC, DataSource.SYNTHETIC, row.id(), modelJson == null ? null : model.getVersion(),
				Trigger.MANUAL, asResult, modelJson != null, ds.zone().getId(), OccupancyFeatures.FEATURE_SET, now, durationMs, metricsJson));

		TrainingDetails details = new TrainingDetails(r.lambda(), r.validationLogLoss(), r.train(), r.validation(), r.test(),
				SyntheticTrainer.EMBARGO.toMinutes(), r.embargoDropped(), r.scenarioSlotsExcluded(), r.agreement());
		jdbc.update("update synthetic_datasets set evaluation_json = ? where id = ?", mapper.writeValueAsString(new StoredEvaluation(details, eval)), row.id());
		return status(d, false);
	}

	// ---- reading ---------------------------------------------------------------------------------

	public SyntheticStatusResponse status(UUID ownerId, UUID deviceUuid) {
		return status(deviceService.requireOwned(ownerId, deviceUuid), false);
	}

	private SyntheticStatusResponse status(Device d, boolean generated) {
		Optional<Row> row = latest(d.getId());
		if (row.isEmpty()) {
			return new SyntheticStatusResponse(SyntheticDtos.SOURCE, SyntheticDtos.NOTICE, null, null, null, null, false);
		}
		Row r = row.get();
		List<ScenarioInfo> scenarios = mapper.readValue(r.scenariosJson(), new TypeReference<List<ScenarioInfo>>() { });
		Integer events = jdbc.queryForObject("select count(*) from synthetic_connectivity_events where dataset_id = ?", Integer.class, r.id());
		Double occupied = jdbc.queryForObject("select avg(case when occupancy = 'OCCUPIED' then 1.0 else 0.0 end) from synthetic_readings where dataset_id = ?",
				Double.class, r.id());
		Double motion = jdbc.queryForObject("select avg(case when motion then 1.0 else 0.0 end) from synthetic_readings where dataset_id = ?",
				Double.class, r.id());
		DatasetInfo info = new DatasetInfo(r.id(), r.version(), r.seed(), r.days(), r.zone(), r.startsAt(), r.endsAt(), r.recordCount(),
				r.offlineMinutes(), events == null ? 0 : events, occupied == null ? 0 : occupied, motion == null ? 0 : motion, r.createdAt(), scenarios);
		ModelInfo model = models.findByDeviceUuidAndModelType(d.getId(), MlModel.OCCUPANCY_SYNTHETIC)
				.map(m -> new ModelInfo(m.getStatus().name(), m.getVersion(), m.getTrainedAt(), m.getTimeZone(), m.getReason(),
						m.getMetricsJson() == null ? null : mapper.readValue(m.getMetricsJson(), MetricsResponse.class)))
				.orElse(null);
		StoredEvaluation stored = r.evaluationJson() == null ? null : mapper.readValue(r.evaluationJson(), StoredEvaluation.class);
		return new SyntheticStatusResponse(SyntheticDtos.SOURCE, SyntheticDtos.NOTICE, info, model, stored == null ? null : stored.training(),
				stored == null ? null : stored.anomalies(), generated);
	}

	/** Predictions of the SYNTHETIC demonstration model. Labelled as such; never used for the device's real predictions. */
	public SyntheticForecastResponse forecast(UUID ownerId, UUID deviceUuid, int hours) {
		if (hours < 1 || hours > 72) {
			throw new InvalidMlRequestException("hours", "Between 1 and 72");
		}
		Device d = deviceService.requireOwned(ownerId, deviceUuid);
		MlModel row = models.findByDeviceUuidAndModelType(d.getId(), MlModel.OCCUPANCY_SYNTHETIC).filter(MlModel::hasModel)
				.orElseThrow(() -> new ModelNotReadyException("No synthetic demonstration model yet. Generate the dataset and train it first."));
		OccupancyModel model = mapper.readValue(row.getModelJson(), OccupancyModel.class);
		ZoneId zone = ZoneId.of(model.timeZone());
		Instant start = clock.instant().truncatedTo(ChronoUnit.HOURS);
		List<PredictionResponse> points = new ArrayList<>();
		for (int i = 0; i < hours; i++) {
			Instant at = start.plus(Duration.ofHours(i));
			ZonedDateTime local = at.atZone(zone);
			double p = model.probability(local);
			int support = model.support(local);
			points.add(new PredictionResponse(at, local.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).toString(),
					local.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH), local.getHour(), p, p >= props.occupancy().decisionThreshold(),
					Math.max(p, 1 - p), support, support >= props.occupancy().minSupport(), model.observedRate(local), "SYNTHETIC"));
		}
		return new SyntheticForecastResponse(SyntheticDtos.SOURCE, SyntheticDtos.NOTICE, row.getVersion(), zone.getId(), points);
	}

	// ---- persistence helpers ---------------------------------------------------------------------

	private Optional<Row> find(UUID device, long seed, int days) {
		return jdbc.query("select * from synthetic_datasets where device_uuid = ? and generator_version = ? and seed = ? and days = ?",
				(rs, i) -> row(rs), device, SyntheticGenerator.VERSION, seed, days).stream().findFirst();
	}

	private Optional<Row> latest(UUID device) {
		return jdbc.query("select * from synthetic_datasets where device_uuid = ? order by created_at desc", (rs, i) -> row(rs), device).stream()
				.findFirst();
	}

	private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Row(rs.getObject("id", UUID.class), rs.getLong("seed"), rs.getInt("days"), rs.getString("time_zone"),
				rs.getObject("starts_at", OffsetDateTime.class).toInstant(), rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
				rs.getInt("record_count"), rs.getInt("offline_minutes"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				rs.getString("scenarios_json"), rs.getString("evaluation_json"), rs.getString("generator_version"));
	}

	/** Reads a stored dataset back exactly as generated (the labels included). */
	private SyntheticGenerator.Dataset load(Row r) {
		List<Reading> readings = jdbc.query("select recorded_at, temperature_c, humidity_pct, motion, gas_raw, occupancy, scenario from synthetic_readings "
				+ "where dataset_id = ? order by recorded_at", (rs, i) -> new Reading(rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
						(Double) rs.getObject("temperature_c"), (Double) rs.getObject("humidity_pct"), (Boolean) rs.getObject("motion"),
						(Integer) rs.getObject("gas_raw"), "OCCUPIED".equals(rs.getString("occupancy")), Scenario.valueOf(rs.getString("scenario"))),
				r.id());
		List<SyntheticGenerator.ConnectivityEvent> events = jdbc.query("select changed_at, state, reason from synthetic_connectivity_events "
				+ "where dataset_id = ? order by changed_at, id", (rs, i) -> new SyntheticGenerator.ConnectivityEvent(
						rs.getObject("changed_at", OffsetDateTime.class).toInstant(), rs.getString("state"), rs.getString("reason")), r.id());
		List<ScenarioWindow> windows = mapper.readValue(r.scenariosJson(), new TypeReference<List<ScenarioInfo>>() { }).stream()
				.map(s -> new ScenarioWindow(Scenario.valueOf(s.scenario()), s.from(), s.to(), s.description())).toList();
		return new SyntheticGenerator.Dataset(r.startsAt(), r.endsAt(), ZoneId.of(r.zone()), r.seed(), r.days(), readings, events, windows, r.offlineMinutes());
	}

	private static OffsetDateTime odt(Instant at) {
		return at.atOffset(ZoneOffset.UTC);
	}

	private static void setDouble(PreparedStatement ps, int i, Double v) throws java.sql.SQLException {
		if (v == null) {
			ps.setNull(i, Types.DOUBLE);
		} else {
			ps.setDouble(i, v);
		}
	}

	/** Tests only. */
	public void forgetRateLimits() {
		lastTraining.clear();
	}
}
