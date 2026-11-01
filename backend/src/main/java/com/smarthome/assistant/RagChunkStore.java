package com.smarthome.assistant;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage and similarity search for text chunks. On PostgreSQL the vectors live in a pgvector column and the nearest
 * chunks are found with the {@code <=>} (cosine distance) operator; on the H2 test database vectors are text and the
 * same cosine similarity is computed in Java. Plain JDBC is used because the vector column is not a JPA type.
 *
 * <b>Ownership:</b> {@link #search} is the only read of chunk content and it always restricts to
 * {@code owner_user_id = :caller OR owner_user_id IS NULL} (shared product help). There is no way to search without an owner.
 */
@Repository
public class RagChunkStore {

	public record NewChunk(UUID deviceUuid, String sourceType, String sourceKey, int chunkIndex, String title, String content,
			Instant asOf, float[] vector) {
	}

	public record Existing(UUID id, String sourceKey, int chunkIndex, String hash, String model) {
	}

	public record Hit(UUID id, UUID ownerUserId, UUID deviceUuid, String sourceType, String sourceKey, int chunkIndex, String title,
			String content, Instant asOf, double score) {
	}

	public record SourceSummary(String sourceKey, String title, Instant asOf, int chunks) {
	}

	private final JdbcTemplate jdbc;
	private final DataSource dataSource;
	private volatile Boolean postgres;

	public RagChunkStore(JdbcTemplate jdbc, DataSource dataSource) {
		this.jdbc = jdbc;
		this.dataSource = dataSource;
	}

	boolean postgres() {
		Boolean p = postgres;
		if (p == null) {
			try (Connection c = dataSource.getConnection()) {
				p = c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
			} catch (SQLException e) {
				throw new org.springframework.jdbc.CannotGetJdbcConnectionException("Could not inspect the database", e);
			}
			postgres = p;
		}
		return p;
	}

	// ---- writes --------------------------------------------------------------------------------

	@Transactional
	public void insert(UUID ownerOrNull, NewChunk c, String model, Instant now) {
		String vec = literal(c.vector());
		String sql = "insert into rag_chunks (id, owner_user_id, device_uuid, source_type, source_key, chunk_index, title, content, "
				+ "content_hash, embedding_model, embedding_dims, as_of, created_at, embedding) values (?,?,?,?,?,?,?,?,?,?,?,?,?,"
				+ (postgres() ? "?::vector)" : "?)");
		jdbc.update(sql, UUID.randomUUID(), ownerOrNull, c.deviceUuid(), c.sourceType(), c.sourceKey(), c.chunkIndex(), c.title(),
				c.content(), Hashes.sha256(c.content()), model, c.vector().length, Timestamp.from(c.asOf()), Timestamp.from(now), vec);
	}

	@Transactional
	public void delete(List<UUID> ids) {
		for (UUID id : ids) {
			jdbc.update("delete from rag_chunks where id = ?", id);
		}
	}

	/** Deletes and inserts together, so a reader never sees a source half replaced. */
	@Transactional
	public void apply(UUID ownerOrNull, List<UUID> deletes, List<NewChunk> inserts, String model, Instant now) {
		delete(deletes);
		for (NewChunk c : inserts) {
			insert(ownerOrNull, c, model, now);
		}
	}

	/** Removes one source (all its chunks) of one owner. {@code ownerOrNull} null means the shared help text. */
	@Transactional
	public int deleteSource(UUID ownerOrNull, String sourceType, String sourceKey) {
		return ownerOrNull == null
				? jdbc.update("delete from rag_chunks where owner_user_id is null and source_type = ? and source_key = ?", sourceType, sourceKey)
				: jdbc.update("delete from rag_chunks where owner_user_id = ? and source_type = ? and source_key = ?", ownerOrNull, sourceType, sourceKey);
	}

	/** Everything derived from a device (used when it is disowned, so a later owner can never be shown it). */
	@Transactional
	public int deleteForDevice(UUID deviceUuid) {
		return jdbc.update("delete from rag_chunks where device_uuid = ?", deviceUuid);
	}

	// ---- reads ---------------------------------------------------------------------------------

	public List<Existing> existing(UUID ownerOrNull, String sourceType) {
		String where = ownerOrNull == null ? "owner_user_id is null" : "owner_user_id = ?";
		String sql = "select id, source_key, chunk_index, content_hash, embedding_model from rag_chunks where " + where + " and source_type = ?";
		Object[] args = ownerOrNull == null ? new Object[] { sourceType } : new Object[] { ownerOrNull, sourceType };
		return jdbc.query(sql, (rs, i) -> new Existing(rs.getObject("id", UUID.class), rs.getString("source_key"), rs.getInt("chunk_index"),
				rs.getString("content_hash"), rs.getString("embedding_model")), args);
	}

	public long countOwned(UUID ownerId) {
		Long n = jdbc.queryForObject("select count(*) from rag_chunks where owner_user_id = ?", Long.class, ownerId);
		return n == null ? 0 : n;
	}

	public long countShared() {
		Long n = jdbc.queryForObject("select count(*) from rag_chunks where owner_user_id is null", Long.class);
		return n == null ? 0 : n;
	}

	public List<SourceSummary> sources(UUID ownerId, String sourceType) {
		return jdbc.query("select source_key, min(title) as title, min(as_of) as as_of, count(*) as n from rag_chunks "
				+ "where owner_user_id = ? and source_type = ? group by source_key order by min(as_of) desc",
				(rs, i) -> new SourceSummary(rs.getString("source_key"), rs.getString("title"), rs.getTimestamp("as_of").toInstant(), rs.getInt("n")),
				ownerId, sourceType);
	}

	/**
	 * The {@code topK} chunks closest to {@code query} (cosine similarity) among the caller's own chunks and the shared
	 * help text, restricted to vectors of the same model and size, keeping only those scoring at least {@code minScore}.
	 */
	public List<Hit> search(UUID callerId, float[] query, String model, int topK, double minScore) {
		if (callerId == null) {
			throw new IllegalArgumentException("A search always needs the caller's user id");
		}
		if (norm(query) == 0) {
			return List.of();
		}
		List<Hit> hits = postgres() ? searchPostgres(callerId, query, model, topK) : searchInJava(callerId, query, model, topK);
		return hits.stream().filter(h -> h.score() >= minScore).toList();
	}

	/**
	 * The caller's OWN chunks of one source type (never shared help text, never another user's), scored against the query
	 * but without a minimum score. Used for questions about the present readings: a device's status chunk lists many
	 * fields, so its word-overlap score can be low even though it is exactly the record that answers the question.
	 */
	public List<Hit> ownedOfType(UUID callerId, String sourceType, float[] query, String model, int limit) {
		if (callerId == null) {
			throw new IllegalArgumentException("A search always needs the caller's user id");
		}
		if (norm(query) == 0) {
			return List.of();
		}
		record Scored(Hit hit, double score) {
		}
		List<Scored> all = jdbc.query("select id, owner_user_id, device_uuid, source_type, source_key, chunk_index, title, content, as_of, "
				+ "cast(embedding as varchar) as embedding from rag_chunks where owner_user_id = ? and source_type = ? and embedding_model = ? "
				+ "and embedding_dims = ? and embedding is not null",
				(rs, i) -> {
					double s = cosine(query, parse(rs.getString("embedding")));
					return new Scored(hit(rs, s), s);
				}, callerId, sourceType, model, query.length);
		return all.stream().sorted(Comparator.comparingDouble(Scored::score).reversed()).limit(limit).map(Scored::hit).toList();
	}

	private List<Hit> searchPostgres(UUID caller, float[] q, String model, int topK) {
		String vec = literal(q);
		return jdbc.query("select id, owner_user_id, device_uuid, source_type, source_key, chunk_index, title, content, as_of, "
				+ "1 - (embedding <=> ?::vector) as score from rag_chunks "
				+ "where (owner_user_id = ? or owner_user_id is null) and embedding_model = ? and embedding_dims = ? and embedding is not null "
				+ "order by embedding <=> ?::vector limit ?",
				(rs, i) -> hit(rs, rs.getDouble("score")), vec, caller, model, q.length, vec, topK);
	}

	private List<Hit> searchInJava(UUID caller, float[] q, String model, int topK) {
		record Scored(Hit hit, double score) {
		}
		List<Scored> all = jdbc.query("select id, owner_user_id, device_uuid, source_type, source_key, chunk_index, title, content, as_of, embedding "
				+ "from rag_chunks where (owner_user_id = ? or owner_user_id is null) and embedding_model = ? and embedding_dims = ? and embedding is not null",
				(rs, i) -> {
					double s = cosine(q, parse(rs.getString("embedding")));
					return new Scored(hit(rs, s), s);
				}, caller, model, q.length);
		return all.stream().sorted(Comparator.comparingDouble(Scored::score).reversed()).limit(topK).map(Scored::hit).toList();
	}

	private static Hit hit(java.sql.ResultSet rs, double score) throws SQLException {
		return new Hit(rs.getObject("id", UUID.class), rs.getObject("owner_user_id", UUID.class), rs.getObject("device_uuid", UUID.class),
				rs.getString("source_type"), rs.getString("source_key"), rs.getInt("chunk_index"), rs.getString("title"),
				rs.getString("content"), rs.getTimestamp("as_of").toInstant(), score);
	}

	// ---- vector helpers ------------------------------------------------------------------------

	static String literal(float[] v) {
		StringBuilder sb = new StringBuilder(v.length * 9).append('[');
		for (int i = 0; i < v.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(v[i]);
		}
		return sb.append(']').toString();
	}

	static float[] parse(String s) {
		String body = s.substring(1, s.length() - 1);
		if (body.isEmpty()) {
			return new float[0];
		}
		String[] parts = body.split(",");
		float[] v = new float[parts.length];
		for (int i = 0; i < parts.length; i++) {
			v[i] = Float.parseFloat(parts[i]);
		}
		return v;
	}

	static double norm(float[] v) {
		double n = 0;
		for (float x : v) {
			n += (double) x * x;
		}
		return Math.sqrt(n);
	}

	static double cosine(float[] a, float[] b) {
		if (a.length != b.length) {
			return -1;
		}
		double dot = 0;
		for (int i = 0; i < a.length; i++) {
			dot += (double) a[i] * b[i];
		}
		double d = norm(a) * norm(b);
		return d == 0 ? 0 : dot / d;
	}

	/** Used by tests that need the list of chunk ids for an owner. */
	List<UUID> idsOwnedBy(UUID ownerId) {
		return new ArrayList<>(jdbc.queryForList("select id from rag_chunks where owner_user_id = ?", UUID.class, ownerId));
	}
}
