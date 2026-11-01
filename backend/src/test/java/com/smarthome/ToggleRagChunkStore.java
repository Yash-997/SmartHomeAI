package com.smarthome;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.smarthome.assistant.RagChunkStore;

/** The real chunk store, except that a test can make it fail the way an unreachable database does. */
@Component
@Primary
public class ToggleRagChunkStore extends RagChunkStore {

	private volatile boolean down;

	public ToggleRagChunkStore(JdbcTemplate jdbc, DataSource dataSource) {
		super(jdbc, dataSource);
	}

	public void setDown(boolean down) {
		this.down = down;
	}

	private void check() {
		if (down) {
			throw new DataAccessResourceFailureException("simulated database outage (secret-host:5432)");
		}
	}

	@Override
	public List<Existing> existing(UUID ownerOrNull, String sourceType) {
		check();
		return super.existing(ownerOrNull, sourceType);
	}

	@Override
	public List<Hit> search(UUID callerId, float[] query, String model, int topK, double minScore) {
		check();
		return super.search(callerId, query, model, topK, minScore);
	}

	@Override
	public long countOwned(UUID ownerId) {
		check();
		return super.countOwned(ownerId);
	}
}
