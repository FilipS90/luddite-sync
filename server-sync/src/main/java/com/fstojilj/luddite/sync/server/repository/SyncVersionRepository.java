package com.fstojilj.luddite.sync.server.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;

/**
 * Mints monotonically increasing sync version numbers using the sync_log table.
 * Each call to next() inserts a row and returns its auto-generated version (primary key).
 */
@Repository
@RequiredArgsConstructor
public class SyncVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    public long next() {
        var keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> connection.prepareStatement(
                "INSERT INTO sync_log (status) VALUES ('PENDING')",
                Statement.RETURN_GENERATED_KEYS), keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to generate sync version");
        }
        return key.longValue();
    }

    public void markSynced(long version) {
        jdbcTemplate.update(
                "UPDATE sync_log SET status = 'SYNCED' WHERE version = ?", version);
    }
}

