package com.fstojilj.luddite.sync.client.repository;

import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class SyncStateRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<SyncHandshakeEntry> rowMapper =
            (rs, _) -> new SyncHandshakeEntry(
                    rs.getString("dir_name"),
                    rs.getLong("last_sync_version")
            );

    public List<SyncHandshakeEntry> findAll() {
        return jdbcTemplate.query("SELECT * FROM sync_state", rowMapper);
    }

    /**
     * Registers a dir with lastSyncVersion = -1 if not already present.
     * Existing entries are left untouched so their progress is preserved.
     */
    public void registerIfAbsent(String dirName) {
        jdbcTemplate.update("""
                INSERT INTO sync_state (dir_name, last_sync_version)
                VALUES (?, -1)
                ON CONFLICT(dir_name) DO NOTHING
                """, dirName);
    }

    /**
     * Resets the last sync version for a dir back to -1, forcing a full re-sync
     * on the next handshake with the server.
     */
    public void reset(String dirName) {
        jdbcTemplate.update("""
                UPDATE sync_state SET last_sync_version = -1 WHERE dir_name = ?
                """, dirName);
    }

    /**
     * Inserts or advances the last sync version for a given dir.
     * Never regresses — if the stored version is already higher, it is kept.
     */
    public void upsert(String dirName, long lastSyncVersion) {
        jdbcTemplate.update("""
                INSERT INTO sync_state (dir_name, last_sync_version)
                VALUES (?, ?)
                ON CONFLICT(dir_name) DO UPDATE
                    SET last_sync_version = MAX(excluded.last_sync_version, last_sync_version)
                """, dirName, lastSyncVersion);
    }
}

