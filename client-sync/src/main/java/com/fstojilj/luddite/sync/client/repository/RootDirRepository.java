package com.fstojilj.luddite.sync.client.repository;

import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Persists the set of server root-directories the client is tracking, together with
 * each directory's last acknowledged sync version.
 *
 * <p>Maps to the {@code root_dirs} table in the client SQLite database.
 */
@Repository
@RequiredArgsConstructor
public class RootDirRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<SyncHandshakeEntry> rowMapper =
            (rs, _) -> new SyncHandshakeEntry(
                    rs.getString("dir_name"),
                    rs.getLong("last_sync_version")
            );

    /**
     * Returns all tracked directories and their last known sync versions.
     */
    public List<SyncHandshakeEntry> findAll() {
        return jdbcTemplate.query("SELECT * FROM root_dirs", rowMapper);
    }

    /**
     * Registers a directory with {@code last_sync_version = -1} if not already present.
     * Existing entries are left untouched so their progress is preserved.
     *
     * @param dirName server-side directory name
     */
    public void registerIfAbsent(String dirName) {
        jdbcTemplate.update("""
                INSERT INTO root_dirs (dir_name, last_sync_version)
                VALUES (?, -1)
                ON CONFLICT(dir_name) DO NOTHING
                """, dirName);
    }

    /**
     * Registers a private directory with its hashed password.
     * Updates the password_hash if the entry already exists.
     *
     * @param dirName      server-side directory name
     * @param passwordHash SHA-256 hex hash of the user-supplied password
     */
    public void registerPrivateDir(String dirName, String passwordHash) {
        jdbcTemplate.update("""
                INSERT INTO root_dirs (dir_name, last_sync_version, password_hash, is_private)
                VALUES (?, -1, ?, TRUE)
                ON CONFLICT(dir_name) DO UPDATE SET password_hash = excluded.password_hash,
                                                    is_private = TRUE
                """, dirName, passwordHash);
    }

    /**
     * Returns the stored SHA-256 password hash for a private directory, or {@code null}
     * if the directory is not registered or has no stored hash.
     *
     * @param dirName directory name
     * @return stored hash or null
     */
    public String getPasswordHash(String dirName) {
        List<String> results = jdbcTemplate.query(
                "SELECT password_hash FROM root_dirs WHERE dir_name = ?",
                (rs, _) -> rs.getString("password_hash"),
                dirName);
        return results.isEmpty() ? null : results.getFirst();
    }

    /**
     * Returns all locally registered private directories with their stored password hashes.
     * Used during reconnect to automatically re-authenticate.
     *
     * @return list of (dirName, passwordHash) pairs as String arrays
     */
    public List<String[]> findAllPrivate() {
        return jdbcTemplate.query(
                "SELECT dir_name, password_hash FROM root_dirs WHERE is_private = TRUE",
                (rs, _) -> new String[]{rs.getString("dir_name"), rs.getString("password_hash")});
    }

    /**
     * Removes a directory entry from the local state.
     *
     * @param dirName directory name to remove
     */
    public void remove(String dirName) {
        jdbcTemplate.update("DELETE FROM root_dirs WHERE dir_name = ?", dirName);
    }

    /**
     * Resets the last sync version for a directory back to {@code -1}, forcing a full
     * re-sync on the next handshake.
     *
     * @param dirName directory name to reset
     */
    public void reset(String dirName) {
        jdbcTemplate.update(
                "UPDATE root_dirs SET last_sync_version = -1 WHERE dir_name = ?", dirName);
    }

    /**
     * Inserts or advances the last sync version for a directory.
     * Never regresses — if the stored version is already higher, it is kept.
     *
     * @param dirName         directory name
     * @param lastSyncVersion new sync version to record
     */
    public void updateSyncVersion(String dirName, long lastSyncVersion) {
        jdbcTemplate.update("""
                UPDATE root_dirs
                SET last_sync_version = ?
                WHERE dir_name = ?
                """, lastSyncVersion, dirName);
    }
}

