package com.fstojilj.luddite.sync.server.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Duration;
import java.time.Instant;

import static com.fstojilj.luddite.sync.server.config.SchemaConstants.SYNC_TIME_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests SyncTimeRepository against a real in-memory SQLite database.
 */
class SyncTimeRepositoryTest {

    private static final long SEVEN_MONTHS_AGO = Instant.now().minus(Duration.ofDays(7 * 30)).toEpochMilli();

    private SyncTimeRepository repository;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        jdbcTemplate = new JdbcTemplate(ds);
        jdbcTemplate.execute(SYNC_TIME_TABLE);
        repository = new SyncTimeRepository(jdbcTemplate);
    }

    private Long syncTimeOf(String clientId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_sync_time_millis FROM sync_time WHERE client_id = ?", Long.class, clientId);
    }

    // ── upsert ────────────────────────────────────────────────────────────────

    @Test
    void upsert_newClient_insertsRow() {
        repository.upsert("hw-id-1", 1000L);
        assertThat(syncTimeOf("hw-id-1")).isEqualTo(1000L);
    }

    @Test
    void upsert_existingClient_overwritesSyncTime() {
        repository.upsert("hw-id-1", 1000L);
        repository.upsert("hw-id-1", 2000L);

        assertThat(syncTimeOf("hw-id-1")).isEqualTo(2000L);
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sync_time", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_removesOnlyThatClient() {
        repository.upsert("hw-id-1", 1000L);
        repository.upsert("hw-id-2", 1000L);

        repository.delete("hw-id-1");

        assertThat(jdbcTemplate.queryForList("SELECT client_id FROM sync_time", String.class))
                .containsExactly("hw-id-2");
    }

    // ── getAllStaleClientIds ──────────────────────────────────────────────────

    @Test
    void getAllStaleClientIds_returnsOnlyClientsOlderThanSixMonths() {
        repository.upsert("stale-1", SEVEN_MONTHS_AGO);
        repository.upsert("stale-2", 0L);
        repository.upsert("fresh", System.currentTimeMillis());

        assertThat(repository.getAllStaleClientIds()).containsExactlyInAnyOrder("stale-1", "stale-2");
    }

    @Test
    void getAllStaleClientIds_noRows_returnsEmptySet() {
        assertThat(repository.getAllStaleClientIds()).isEmpty();
    }
}
