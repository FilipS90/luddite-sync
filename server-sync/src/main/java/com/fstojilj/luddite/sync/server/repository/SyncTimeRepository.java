package com.fstojilj.luddite.sync.server.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SyncTimeRepository {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void upsert(String clientId, long syncTime) {
        String sql = """
                INSERT INTO sync_time (client_id, last_sync_time_millis)
                VALUES (?, ?)
                ON CONFLICT(client_id) DO UPDATE SET last_sync_time_millis=excluded.last_sync_time_millis
                """;

        jdbcTemplate.update(sql, clientId, syncTime);
    }

    @Transactional
    public void delete(String clientId) {
        jdbcTemplate.update("DELETE FROM sync_time WHERE client_id = ?", clientId);
    }

    @Transactional
    public Set<String> getAllStaleClientIds() {
        long staleThresholdMillis = ZonedDateTime.now(ZoneOffset.UTC)
                .minusMonths(6)
                .toInstant()
                .toEpochMilli();

        String sql = """
                SELECT client_id
                FROM sync_time
                WHERE last_sync_time_millis < ?
                """;

        return new HashSet<>(jdbcTemplate.queryForList(sql, String.class, staleThresholdMillis));
    }
}
