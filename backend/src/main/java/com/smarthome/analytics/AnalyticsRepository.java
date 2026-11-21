package com.smarthome.analytics;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only aggregate queries for the assistant and the reports. Every query:
 * <ul>
 * <li>joins to {@code devices} and filters on {@code owner_user_id = ?} (or filters the owner column directly), so it can
 * only ever see the caller's own data;</li>
 * <li>is a fixed SQL string with bound parameters: nothing a user or a language model types is ever put into SQL text;</li>
 * <li>is bounded by a time range and, for row lists, a LIMIT.</li>
 * </ul>
 * Aggregation happens in SQL (count, sum, min, max per 15-minute window), so a month of readings is summarised without
 * loading the readings themselves. 15 minutes divides every real-world UTC offset, so windows can be regrouped into local
 * hours and days exactly.
 */
@Repository
public class AnalyticsRepository {

	/** Aggregation window. A constant (never user input), so it is safe to inline in the SQL text. */
	public static final int BUCKET_SECONDS = 900;
	public static final int MAX_ROWS = 200;

	private final JdbcTemplate jdbc;

	public AnalyticsRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** count / sum / min / max of the values that exist. A sensor with no values has count 0 and null min/max (never 0). */
	public record Agg(long count, double sum, Double min, Double max) {
		public static final Agg EMPTY = new Agg(0, 0, null, null);

		public Double avg() {
			return count == 0 ? null : sum / count;
		}

		public Agg plus(Agg o) {
			if (o.count == 0) {
				return this;
			}
			if (count == 0) {
				return o;
			}
			return new Agg(count + o.count, sum + o.sum, Math.min(min, o.min), Math.max(max, o.max));
		}
	}

	/** One 15-minute window of one device. */
	public record Bucket(UUID device, Instant start, long readings, Agg temperature, Agg humidity, Agg gas, long gasOverThreshold,
			long motionReadings, long motionDetected) {
	}

	private static final String BUCKET_EXPR = "floor(extract(epoch from t.received_at) / " + BUCKET_SECONDS + ")";

	public List<Bucket> buckets(UUID owner, UUID deviceOrNull, Instant from, Instant to, int gasThreshold) {
		String sql = "select t.device_uuid as device, " + BUCKET_EXPR + " as bucket, count(*) as n, "
				+ "count(t.temperature_c) as tc, sum(t.temperature_c) as ts, min(t.temperature_c) as tmin, max(t.temperature_c) as tmax, "
				+ "count(t.humidity_pct) as hc, sum(t.humidity_pct) as hs, min(t.humidity_pct) as hmin, max(t.humidity_pct) as hmax, "
				+ "count(t.gas_raw) as gc, sum(t.gas_raw) as gs, min(t.gas_raw) as gmin, max(t.gas_raw) as gmax, "
				+ "sum(case when t.gas_raw >= ? then 1 else 0 end) as gover, "
				+ "count(t.motion) as mc, sum(case when t.motion = true then 1 else 0 end) as mt "
				+ "from telemetry_readings t join devices d on d.id = t.device_uuid "
				+ "where d.owner_user_id = ? and t.received_at >= ? and t.received_at < ?"
				+ (deviceOrNull == null ? "" : " and t.device_uuid = ?")
				+ " group by t.device_uuid, " + BUCKET_EXPR + " order by 2";
		List<Object> args = new ArrayList<>(List.of(gasThreshold, owner, ts(from), ts(to)));
		if (deviceOrNull != null) {
			args.add(deviceOrNull);
		}
		return jdbc.query(sql, (rs, i) -> new Bucket(rs.getObject("device", UUID.class), Instant.ofEpochSecond(rs.getLong("bucket") * BUCKET_SECONDS),
				rs.getLong("n"), agg(rs, "tc", "ts", "tmin", "tmax"), agg(rs, "hc", "hs", "hmin", "hmax"), agg(rs, "gc", "gs", "gmin", "gmax"),
				rs.getLong("gover"), rs.getLong("mc"), rs.getLong("mt")), args.toArray());
	}

	private static Agg agg(ResultSet rs, String c, String s, String mn, String mx) throws SQLException {
		long n = rs.getLong(c);
		if (n == 0) {
			return Agg.EMPTY;
		}
		return new Agg(n, rs.getDouble(s), rs.getDouble(mn), rs.getDouble(mx));
	}

	/** When motion was last detected (any time), or null. */
	public Instant lastMotion(UUID owner, UUID deviceOrNull) {
		String sql = "select max(t.received_at) from telemetry_readings t join devices d on d.id = t.device_uuid "
				+ "where d.owner_user_id = ? and t.motion = true" + (deviceOrNull == null ? "" : " and t.device_uuid = ?");
		Timestamp t = deviceOrNull == null ? jdbc.queryForObject(sql, Timestamp.class, owner) : jdbc.queryForObject(sql, Timestamp.class, owner, deviceOrNull);
		return t == null ? null : t.toInstant();
	}

	// ---- events ----------------------------------------------------------------------------------

	/**
	 * {@code details} is the detector's JSON (the numbers behind the explanation); {@code deviceId} is the hardware id;
	 * {@code emailStatus} is null when no email was due.
	 */
	public record AnomalyRow(UUID id, UUID device, String deviceName, String kind, String scope, String severity, String status, String explanation,
			Instant detectedAt, Instant lastSeenAt, int occurrences, String deviceId, String details, String emailStatus) {
	}

	/** Anomalies that were active at some point in [from, to), newest first. */
	public List<AnomalyRow> anomalies(UUID owner, UUID deviceOrNull, Instant from, Instant to, int limit) {
		String sql = "select a.id, a.device_uuid, d.name, a.kind, a.scope, a.severity, a.status, a.explanation, a.detected_at, a.last_seen_at, a.occurrences, "
				+ "d.device_id, a.details, a.email_status "
				+ "from ml_anomalies a join devices d on d.id = a.device_uuid where d.owner_user_id = ? and a.last_seen_at >= ? and a.detected_at < ?"
				+ (deviceOrNull == null ? "" : " and a.device_uuid = ?") + " order by a.last_seen_at desc limit ?";
		return jdbc.query(sql, (rs, i) -> new AnomalyRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
				rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), inst(rs, 9), inst(rs, 10), rs.getInt(11), rs.getString(12),
				rs.getString(13), rs.getString(14)),
				args(owner, deviceOrNull, from, to, cap(limit)));
	}

	public record ExecutionRow(String ruleName, String outcome, String reason, String action, String value, Instant at, int repeats) {
	}

	public List<ExecutionRow> automationRuns(UUID owner, UUID deviceOrNull, Instant from, Instant to, int limit) {
		String sql = "select e.rule_name, e.outcome, e.reason, e.action, e.action_value, e.last_at, e.repeat_count from automation_executions e "
				+ "where e.owner_user_id = ? and e.last_at >= ? and e.created_at < ?" + (deviceOrNull == null ? "" : " and e.device_uuid = ?")
				+ " order by e.last_at desc limit ?";
		return jdbc.query(sql, (rs, i) -> new ExecutionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				inst(rs, 6), rs.getInt(7)), args(owner, deviceOrNull, from, to, cap(limit)));
	}

	/** outcome -> number of evaluations (repeats included). */
	public Map<String, Long> automationOutcomes(UUID owner, UUID deviceOrNull, Instant from, Instant to) {
		String sql = "select e.outcome, sum(e.repeat_count) from automation_executions e where e.owner_user_id = ? and e.last_at >= ? and e.created_at < ?"
				+ (deviceOrNull == null ? "" : " and e.device_uuid = ?") + " group by e.outcome order by 2 desc";
		return counts(sql, args(owner, deviceOrNull, from, to, null));
	}

	public record SecurityRow(String kind, String severity, String mode, String message, Instant at, boolean acknowledged) {
	}

	public List<SecurityRow> securityEvents(UUID owner, UUID deviceOrNull, Instant from, Instant to, int limit) {
		String sql = "select s.kind, s.severity, s.mode, s.message, s.created_at, s.acknowledged_at from security_events s "
				+ "where s.owner_user_id = ? and s.created_at >= ? and s.created_at < ?" + (deviceOrNull == null ? "" : " and s.device_uuid = ?")
				+ " order by s.created_at desc limit ?";
		return jdbc.query(sql, (rs, i) -> new SecurityRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), inst(rs, 5),
				rs.getTimestamp(6) != null), args(owner, deviceOrNull, from, to, cap(limit)));
	}

	public Map<String, Long> securityCounts(UUID owner, UUID deviceOrNull, Instant from, Instant to) {
		String sql = "select s.kind, count(*) from security_events s where s.owner_user_id = ? and s.created_at >= ? and s.created_at < ?"
				+ (deviceOrNull == null ? "" : " and s.device_uuid = ?") + " group by s.kind order by 2 desc";
		return counts(sql, args(owner, deviceOrNull, from, to, null));
	}

	public record GasAlertRow(String deviceName, String state, int threshold, int trigger, int peak, Instant startedAt, Instant recoveredAt) {
	}

	/** Gas incidents that overlap [from, to). */
	public List<GasAlertRow> gasAlerts(UUID owner, UUID deviceOrNull, Instant from, Instant to, int limit) {
		String sql = "select g.device_name, g.state, g.threshold, g.trigger_gas_raw, g.peak_gas_raw, g.started_at, g.recovered_at from gas_alerts g "
				+ "where g.owner_user_id = ? and (g.recovered_at is null or g.recovered_at >= ?) and g.started_at < ?"
				+ (deviceOrNull == null ? "" : " and g.device_uuid = ?") + " order by g.started_at desc limit ?";
		return jdbc.query(sql, (rs, i) -> new GasAlertRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), inst(rs, 6),
				inst(rs, 7)), args(owner, deviceOrNull, from, to, cap(limit)));
	}

	/** Gas incidents that are active right now (independent of any period). */
	public List<GasAlertRow> activeGasAlerts(UUID owner) {
		return jdbc.query("select g.device_name, g.state, g.threshold, g.trigger_gas_raw, g.peak_gas_raw, g.started_at, g.recovered_at from gas_alerts g "
				+ "where g.owner_user_id = ? and g.state = 'ACTIVE' order by g.started_at desc limit 20",
				(rs, i) -> new GasAlertRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), inst(rs, 6), inst(rs, 7)), owner);
	}

	public record ConnectivityRow(UUID device, String deviceName, String state, String reason, Instant at) {
	}

	public List<ConnectivityRow> connectivity(UUID owner, UUID deviceOrNull, Instant from, Instant to, int limit) {
		String sql = "select e.device_uuid, d.name, e.state, e.reason, e.changed_at from device_status_events e join devices d on d.id = e.device_uuid "
				+ "where d.owner_user_id = ? and e.changed_at >= ? and e.changed_at < ?" + (deviceOrNull == null ? "" : " and e.device_uuid = ?")
				+ " order by e.changed_at desc limit ?";
		return jdbc.query(sql, (rs, i) -> new ConnectivityRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), inst(rs, 5)),
				args(owner, deviceOrNull, from, to, cap(limit)));
	}

	/** One stored gas incident (a gas_alerts row): the alert system's record of the raw value reaching the threshold. */
	public record GasIncidentRow(String deviceName, String state, int threshold, int recoveryThreshold, int trigger, int peak, int last,
			Instant startedAt, Instant recoveredAt) {
	}

	private static final String GAS_INCIDENT_WHERE = " from gas_alerts g where g.owner_user_id = ?";

	/**
	 * Gas incidents of this owner that STARTED in [from, to), newest first. {@code from}/{@code to} null = all stored history.
	 * Rows stay with the user who owned the device when the incident happened, so a re-paired device never leaks another user's incidents.
	 */
	public List<GasIncidentRow> gasIncidents(UUID owner, UUID deviceOrNull, Instant fromOrNull, Instant toOrNull, int limit) {
		List<Object> a = new ArrayList<>();
		String where = gasIncidentWhere(a, owner, deviceOrNull, fromOrNull, toOrNull);
		a.add(cap(limit));
		return jdbc.query("select g.device_name, g.state, g.threshold, g.recovery_threshold, g.trigger_gas_raw, g.peak_gas_raw, g.last_gas_raw, "
				+ "g.started_at, g.recovered_at" + where + " order by g.started_at desc limit ?",
				(rs, i) -> new GasIncidentRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
						inst(rs, 8), inst(rs, 9)), a.toArray());
	}

	/** How many gas incidents started in [from, to) (null = unbounded). */
	public long countGasIncidents(UUID owner, UUID deviceOrNull, Instant fromOrNull, Instant toOrNull) {
		List<Object> a = new ArrayList<>();
		String where = gasIncidentWhere(a, owner, deviceOrNull, fromOrNull, toOrNull);
		Long n = jdbc.queryForObject("select count(*)" + where, Long.class, a.toArray());
		return n == null ? 0 : n;
	}

	private static String gasIncidentWhere(List<Object> a, UUID owner, UUID deviceOrNull, Instant fromOrNull, Instant toOrNull) {
		StringBuilder sb = new StringBuilder(GAS_INCIDENT_WHERE);
		a.add(owner);
		if (deviceOrNull != null) {
			sb.append(" and g.device_uuid = ?");
			a.add(deviceOrNull);
		}
		if (fromOrNull != null) {
			sb.append(" and g.started_at >= ?");
			a.add(ts(fromOrNull));
		}
		if (toOrNull != null) {
			sb.append(" and g.started_at < ?");
			a.add(ts(toOrNull));
		}
		return sb.toString();
	}

	/** One remote output command (device_commands row) sent from the app or an automation. */
	public record CommandRow(String deviceName, String action, String value, String status, String origin, String detail, Instant createdAt,
			Instant completedAt) {
	}

	/**
	 * Commands this owner requested for their own devices, newest first; every filter is optional (null = any, from/to null = all history).
	 * Both the device's current owner and the requester must be the caller, so commands of a previous owner are never shown.
	 */
	public List<CommandRow> commands(UUID owner, UUID deviceOrNull, String actionOrNull, String valueOrNull, Instant fromOrNull, Instant toOrNull, int limit) {
		List<Object> a = new ArrayList<>(List.of(owner, owner));
		StringBuilder sb = new StringBuilder("select d.name, c.action, c.action_value, c.status, c.origin, c.detail, c.created_at, c.completed_at "
				+ "from device_commands c join devices d on d.id = c.device_uuid where d.owner_user_id = ? and c.requested_by = ?");
		if (deviceOrNull != null) {
			sb.append(" and c.device_uuid = ?");
			a.add(deviceOrNull);
		}
		if (actionOrNull != null) {
			sb.append(" and c.action = ?");
			a.add(actionOrNull);
		}
		if (valueOrNull != null) {
			sb.append(" and c.action_value = ?");
			a.add(valueOrNull);
		}
		if (fromOrNull != null) {
			sb.append(" and c.created_at >= ?");
			a.add(ts(fromOrNull));
		}
		if (toOrNull != null) {
			sb.append(" and c.created_at < ?");
			a.add(ts(toOrNull));
		}
		sb.append(" order by c.created_at desc limit ?");
		a.add(cap(limit));
		return jdbc.query(sb.toString(), (rs, i) -> new CommandRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				rs.getString(6), inst(rs, 7), inst(rs, 8)), a.toArray());
	}

	/** command status -> count, for commands created in [from, to). */
	public Map<String, Long> commandCounts(UUID owner, UUID deviceOrNull, Instant from, Instant to) {
		String sql = "select c.status, count(*) from device_commands c join devices d on d.id = c.device_uuid "
				+ "where d.owner_user_id = ? and c.created_at >= ? and c.created_at < ?" + (deviceOrNull == null ? "" : " and c.device_uuid = ?")
				+ " group by c.status order by 2 desc";
		return counts(sql, args(owner, deviceOrNull, from, to, null));
	}

	// ---- helpers ---------------------------------------------------------------------------------

	private Map<String, Long> counts(String sql, Object[] args) {
		Map<String, Long> out = new LinkedHashMap<>();
		jdbc.query(sql, rs -> {
			out.put(rs.getString(1), rs.getLong(2));
		}, args);
		return out;
	}

	private static Object[] args(UUID owner, UUID deviceOrNull, Instant from, Instant to, Integer limit) {
		List<Object> a = new ArrayList<>(List.of(owner, ts(from), ts(to)));
		if (deviceOrNull != null) {
			a.add(deviceOrNull);
		}
		if (limit != null) {
			a.add(limit);
		}
		return a.toArray();
	}

	private static int cap(int limit) {
		return Math.max(1, Math.min(MAX_ROWS, limit));
	}

	private static Timestamp ts(Instant i) {
		return Timestamp.from(i);
	}

	private static Instant inst(ResultSet rs, int col) throws SQLException {
		Timestamp t = rs.getTimestamp(col);
		return t == null ? null : t.toInstant();
	}
}
