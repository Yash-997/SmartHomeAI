package com.smarthome.predict;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.smarthome.ml.SyntheticGenerator;

import tools.jackson.databind.ObjectMapper;

/**
 * Owns the primary SYNTHETIC dataset and the model trained on it. This class reads and writes only synthetic_* and
 * predictive_models; live ESP32 readings never enter it. Training happens when there is no saved model, or on request;
 * inference uses the loaded model.
 */
@Service
public class PredictiveModelService {

	private static final Logger log = LoggerFactory.getLogger(PredictiveModelService.class);
	static final String DEVICE_IDENTIFIER = "synthetic-home-01";
	static final String GENERATOR = "primary-1";
	private static final UUID SYSTEM_USER = new UUID(0, 0);

	public record DatasetInfo(UUID id, String dataSource, String deviceIdentifier, long seed, int days, String timeZone,
			Instant from, Instant to, int records, int offlineMinutes, int arrivals, int departures, String generator) {
	}

	/** The loaded model with its dataset. */
	public record Ready(DatasetInfo dataset, PredictiveModel model, int version, Instant trainedAt) {
	}

	public record ModelInfo(String state, String dataLabel, int version, Instant trainedAt, int trainingRows, int trainingDays,
			DatasetInfo dataset, java.util.Map<String, String> algorithms, String message) {
	}

	/** A prediction at the current time of day for a generated synthetic "today". */
	public record Now(String dataLabel, LocalDateTime at, String timeZone, PredictiveModel.Presence presence, int modelVersion) {
	}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transaction;
	private final ObjectMapper mapper;
	private final Clock clock;
	private final ZoneId zone;
	private final long seed;
	private final boolean warmUp;
	private volatile Ready ready;
	private volatile String failure;

	public PredictiveModelService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper, Clock clock,
			@Value("${app.ml.default-time-zone:Asia/Kolkata}") String zone, @Value("${app.predictive.seed:42}") long seed,
			@Value("${app.predictive.warm-up:true}") boolean warmUp) {
		this.jdbc = jdbc;
		this.transaction = transaction;
		this.mapper = mapper;
		this.clock = clock;
		this.zone = ZoneId.of(zone);
		this.seed = seed;
		this.warmUp = warmUp;
	}

	public ZoneId zone() {
		return zone;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void prepareInBackground() {
		if (warmUp) {
			Thread.ofVirtual().name("predictive-warm-up").start(() -> {
				try {
					current();
				} catch (RuntimeException e) {
					log.warn("Predictive model could not be prepared: {}", e.getMessage());
				}
			});
		}
	}

	/** The loaded model; loads it, or generates the dataset and trains, the first time. */
	public Ready current() {
		Ready r = ready;
		return r != null ? r : prepare();
	}

	private synchronized Ready prepare() {
		if (ready != null) {
			return ready;
		}
		try {
			DatasetInfo dataset = storedDataset();
			if (dataset == null) {
				dataset = generate(seed);
			}
			Ready loaded = load(dataset);
			ready = loaded != null ? loaded : trainOn(dataset);
			failure = null;
			return ready;
		} catch (RuntimeException e) {
			failure = e.getMessage();
			throw e;
		}
	}

	/** Trains a new model version on the stored synthetic dataset. */
	public synchronized Ready retrain() {
		ready = trainOn(current().dataset());
		return ready;
	}

	/** Replaces the primary dataset (30 days ending yesterday) and trains on it. */
	public synchronized Ready regenerate(long newSeed) {
		ready = null;
		ready = trainOn(generate(newSeed));
		return ready;
	}

	/** Reports what is loaded or saved. Never triggers training. */
	public ModelInfo info() {
		Ready r = ready;
		if (r == null) {
			String state = failure == null ? "PREPARING" : "UNAVAILABLE";
			return new ModelInfo(state, DemoData.LABEL, 0, null, 0, 0, null, java.util.Map.of(), failure == null
					? "The synthetic dataset and model are being prepared."
					: failure);
		}
		var evaluation = r.model().stored().evaluation();
		var algorithms = new java.util.LinkedHashMap<String, String>();
		evaluation.results().forEach(c -> algorithms.put(c.target(), c.chosen().name()));
		return new ModelInfo("READY", DemoData.LABEL, r.version(), r.trainedAt(), r.model().stored().trainingRows(),
				r.dataset().days(), r.dataset(), algorithms,
				"Trained on " + r.dataset().days() + " days of synthetic data. Real-world accuracy has not been measured.");
	}

	public Now now() {
		Ready r = current();
		ZonedDateTime local = clock.instant().atZone(zone);
		int minute = local.getHour() * 60 + local.getMinute();
		// A synthetic "today" from the same generator; its seed depends on the date so it is stable within the day.
		var plan = DemoData.plan(local.toLocalDate(), DemoData.Routine.DEFAULT,
				new java.util.Random(r.dataset().seed() * 31 + local.toLocalDate().toEpochDay())).times();
		DayTrace today = DemoData.scripted(local.toLocalDate(), plan.wake(), plan.leave(), plan.back(), r.dataset().seed(), zone);
		return new Now(DemoData.LABEL, local.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES), zone.getId(),
				r.model().presence(today, minute), r.version());
	}

	// ---- storage ------------------------------------------------------------------------------------------------

	private DatasetInfo storedDataset() {
		return jdbc.query("""
				SELECT d.*, (SELECT COUNT(*) FROM synthetic_events e WHERE e.dataset_id = d.id AND e.kind = 'ARRIVAL') AS arrivals,
				       (SELECT COUNT(*) FROM synthetic_events e WHERE e.dataset_id = d.id AND e.kind = 'DEPARTURE') AS departures
				FROM synthetic_datasets d WHERE d.is_primary = TRUE ORDER BY d.created_at DESC""",
				(rs, i) -> new DatasetInfo(rs.getObject("id", UUID.class), rs.getString("data_source"), rs.getString("device_identifier"),
						rs.getLong("seed"), rs.getInt("days"), rs.getString("time_zone"), rs.getTimestamp("starts_at").toInstant(),
						rs.getTimestamp("ends_at").toInstant(), rs.getInt("record_count"), rs.getInt("offline_minutes"),
						rs.getInt("arrivals"), rs.getInt("departures"), rs.getString("generator_version")))
				.stream().findFirst().orElse(null);
	}

	private DatasetInfo generate(long newSeed) {
		LocalDate today = clock.instant().atZone(zone).toLocalDate();
		DemoData.Generated g = DemoData.generate(today, DemoData.Routine.DEFAULT, newSeed, zone);
		SyntheticGenerator.Dataset data = g.data();
		UUID id = UUID.randomUUID();
		transaction.executeWithoutResult(status -> {
			jdbc.update("DELETE FROM synthetic_datasets WHERE is_primary = TRUE");
			jdbc.update("""
					INSERT INTO synthetic_datasets (id, device_uuid, created_by, generator_version, seed, days, time_zone, starts_at,
					  ends_at, record_count, offline_minutes, created_at, scenarios_json, data_source, device_identifier, is_primary)
					VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SYNTHETIC', ?, TRUE)""",
					id, SYSTEM_USER, GENERATOR, newSeed, DemoData.HISTORY_DAYS, zone.getId(), Timestamp.from(data.start()),
					Timestamp.from(data.end()), data.readings().size(), data.offlineMinutes(), Timestamp.from(clock.instant()),
					mapper.writeValueAsString(g.plan().stream().map(p -> p.date() + " " + p.variation()).toList()), DEVICE_IDENTIFIER);
			jdbc.batchUpdate("""
					INSERT INTO synthetic_readings (dataset_id, recorded_at, temperature_c, humidity_pct, motion, gas_raw, occupancy, scenario)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", data.readings(), 2000, (ps, r) -> {
				ps.setObject(1, id);
				ps.setTimestamp(2, Timestamp.from(r.at()));
				ps.setObject(3, r.temperature());
				ps.setObject(4, r.humidity());
				ps.setObject(5, r.motion());
				ps.setObject(6, r.gasRaw());
				ps.setString(7, r.occupied() ? "OCCUPIED" : "UNOCCUPIED");
				ps.setString(8, r.scenario().name());
			});
			List<Object[]> events = new ArrayList<>();
			for (DemoData.PlannedDay day : g.plan()) {
				if (day.times().leave() >= 0) {
					var midnight = day.date().atStartOfDay(zone);
					events.add(new Object[] { id, Timestamp.from(midnight.plusMinutes(day.times().leave()).toInstant()), "DEPARTURE" });
					events.add(new Object[] { id, Timestamp.from(midnight.plusMinutes(day.times().back()).toInstant()), "ARRIVAL" });
				}
			}
			jdbc.batchUpdate("INSERT INTO synthetic_events (dataset_id, occurred_at, kind) VALUES (?, ?, ?)", events);
			for (var c : data.connectivity()) {
				jdbc.update("INSERT INTO synthetic_connectivity_events (dataset_id, changed_at, state, reason) VALUES (?, ?, ?, ?)", id,
						Timestamp.from(c.at()), c.state(), c.reason());
			}
		});
		log.info("Primary synthetic dataset generated: seed {}, {} readings", newSeed, data.readings().size());
		return storedDataset();
	}

	private List<DayTrace> traces(DatasetInfo d) {
		List<SyntheticGenerator.Reading> readings = jdbc.query(
				"SELECT recorded_at, temperature_c, humidity_pct, motion, gas_raw, occupancy FROM synthetic_readings "
						+ "WHERE dataset_id = ? ORDER BY recorded_at",
				(rs, i) -> new SyntheticGenerator.Reading(rs.getTimestamp(1).toInstant(), rs.getObject(2, Double.class),
						rs.getObject(3, Double.class), rs.getObject(4, Boolean.class), rs.getObject(5, Integer.class),
						"OCCUPIED".equals(rs.getString(6)), SyntheticGenerator.Scenario.NORMAL),
				d.id());
		return DemoData.traces(readings, ZoneId.of(d.timeZone()));
	}

	private Ready load(DatasetInfo d) {
		record Row(int version, Instant trainedAt, String json) {
		}
		Row row = jdbc.query("SELECT version, trained_at, model_json FROM predictive_models WHERE dataset_id = ? ORDER BY version DESC",
				(rs, i) -> new Row(rs.getInt(1), rs.getTimestamp(2).toInstant(), rs.getString(3)), d.id()).stream().findFirst()
				.orElse(null);
		if (row == null) {
			return null;
		}
		try {
			PredictiveModel.Stored stored = mapper.readValue(row.json(), PredictiveModel.Stored.class);
			return new Ready(d, PredictiveModel.restore(traces(d), stored), row.version(), row.trainedAt());
		} catch (RuntimeException e) {
			log.warn("The saved predictive model could not be read and will be retrained: {}", e.getClass().getSimpleName());
			return null;
		}
	}

	private Ready trainOn(DatasetInfo d) {
		long started = System.nanoTime();
		PredictiveModel model = PredictiveModel.train(traces(d));
		Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
		int version = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM predictive_models", Integer.class) + 1;
		String json = mapper.writeValueAsString(model.stored());
		transaction.executeWithoutResult(status -> {
			jdbc.update("INSERT INTO predictive_models (version, dataset_id, trained_at, training_rows, model_json) VALUES (?, ?, ?, ?, ?)",
					version, d.id(), Timestamp.from(now), model.stored().trainingRows(), json);
			jdbc.update("DELETE FROM predictive_models WHERE version < ?", version);
		});
		log.info("Predictive model v{} trained on synthetic data in {} ms", version, (System.nanoTime() - started) / 1_000_000);
		return new Ready(d, model, version, now);
	}
}
