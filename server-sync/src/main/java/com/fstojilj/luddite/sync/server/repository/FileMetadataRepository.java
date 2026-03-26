package com.fstojilj.luddite.sync.server.repository;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Slf4j
public class FileMetadataRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<FileMetadata> rowMapper = (rs, _) -> FileMetadata.builder()
            .id(rs.getLong("id"))
            .filename(rs.getString("filename"))
            .rootDirId(rs.getLong("root_dir_id"))
            .relativePath(rs.getString("relative_path"))
            .checksum(rs.getString("checksum"))
            .fileSize(rs.getLong("file_size"))
            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null)
            .modifiedAt(rs.getTimestamp("modified_at") != null ? rs.getTimestamp("modified_at").toInstant() : null)
            .syncVersion(rs.getObject("sync_version") instanceof Number n ? n.longValue() : null)
            .deleted(rs.getBoolean("deleted"))
            .clientIds(rs.getString("client_ids"))
            .build();


    public long add(FileMetadata fileMetadata) {
        String sql = """
                INSERT INTO file_metadata (filename, root_dir_id, relative_path, checksum, file_size, created_at, modified_at, sync_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, fileMetadata.filename());
            ps.setLong(2, fileMetadata.rootDirId());
            ps.setString(3, fileMetadata.relativePath());
            ps.setString(4, fileMetadata.checksum());
            ps.setLong(5, fileMetadata.fileSize());
            ps.setTimestamp(6, fileMetadata.createdAt() != null ? Timestamp.from(fileMetadata.createdAt()) : Timestamp.from(Instant.now()));
            ps.setTimestamp(7, fileMetadata.modifiedAt() != null ? Timestamp.from(fileMetadata.modifiedAt()) : Timestamp.from(Instant.now()));
            ps.setObject(8, fileMetadata.syncVersion());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated key for FileMetadata");
        }

        log.debug("Inserted FileMetadata with id: {}", key.longValue());
        return key.longValue();
    }


    public void updateSyncVersion(long rootDirId, String relativePath, long syncVersion) {
        jdbcTemplate.update(
                "UPDATE file_metadata SET sync_version = MAX(COALESCE(sync_version, 0), ?) WHERE root_dir_id = ? AND relative_path = ?",
                syncVersion, rootDirId, relativePath);
    }

    /**
     * Hard-deletes a live (non-soft-deleted) file metadata row.
     * Use {@link #softDelete} for deletion events that must be replicated to clients.
     */
    public void delete(Long rootDirId, String relativePath) {
        String sql = "DELETE FROM file_metadata WHERE root_dir_id = ? AND relative_path = ?";
        int rowsAffected = jdbcTemplate.update(sql, rootDirId, relativePath);
        if (rowsAffected > 0) {
            log.debug("Deleted FileMetadata for rootDirId: {}, relativePath: {}", rootDirId, relativePath);
        } else {
            log.warn("No FileMetadata found for deletion with rootDirId: {}, relativePath: {}", rootDirId, relativePath);
        }
    }

    public void deleteAllByRootDirId(long rootDirId) {
        jdbcTemplate.update("DELETE FROM file_metadata WHERE root_dir_id = ?", rootDirId);
        log.debug("Deleted all FileMetadata for rootDirId: {}", rootDirId);
    }

    /**
     * Returns all rows (live and soft-deleted) whose {@code sync_version} is greater than
     * {@code lastSyncVersion}, or whose {@code sync_version} is {@code NULL} (never delivered).
     * Used by the poll handler to compute what a client needs to catch up on.
     *
     * @param rootDirId       root directory ID
     * @param lastSyncVersion last version the client acknowledged
     * @return list of changed/deleted records since that version
     */
    public List<FileMetadata> findChangedSince(long rootDirId, Long lastSyncVersion) {
        return jdbcTemplate.query("""
                        SELECT * FROM file_metadata
                        WHERE root_dir_id = ?
                          AND (sync_version IS NULL OR sync_version > ?)
                        """,
                rowMapper, rootDirId, lastSyncVersion);
    }

    /**
     * Marks a file as soft-deleted and sets the {@code sync_version} and
     * {@code client_ids} so the deletion can be replicated to all current clients.
     *
     * @param rootDirId    root directory ID
     * @param relativePath relative file path
     * @param syncVersion  new monotonic sync version minted for this deletion event
     * @param clientIds    comma-separated hardware IDs of all currently connected clients
     */
    public void softDelete(long rootDirId, String relativePath, long syncVersion, String clientIds) {
        int rows = jdbcTemplate.update("""
                        UPDATE file_metadata
                        SET deleted = TRUE, sync_version = ?, client_ids = ?,
                            file_size = 0, checksum = ''
                        WHERE root_dir_id = ? AND relative_path = ?
                        """,
                syncVersion, clientIds, rootDirId, relativePath);
        if (rows == 0) {
            log.warn("softDelete: no row found for rootDirId={} relativePath='{}'", rootDirId, relativePath);
        } else {
            log.debug("Soft-deleted (v{}) rootDirId={} '{}'", syncVersion, rootDirId, relativePath);
        }
    }

    /**
     * Removes {@code hardwareId} from the {@code client_ids} list of a soft-deleted row.
     * When the list becomes empty (all clients have acknowledged the delete) the row is
     * hard-deleted from the database.
     *
     * @param rootDirId    root directory ID
     * @param relativePath relative file path
     * @param hardwareId   the client hardware ID to remove
     */
    public void acknowledgeDelete(long rootDirId, String relativePath, String hardwareId) {
        var results = jdbcTemplate.queryForList(
                "SELECT client_ids FROM file_metadata WHERE root_dir_id = ? AND relative_path = ? AND deleted = TRUE",
                rootDirId, relativePath);

        if (results.isEmpty()) {
            log.warn("acknowledgeDelete: no soft-deleted row for rootDirId={} '{}'", rootDirId, relativePath);
            return;
        }

        String raw = (String) results.getFirst().get("client_ids");
        if (raw == null || raw.isBlank()) {
            // No more clients pending — hard-delete immediately
            jdbcTemplate.update("DELETE FROM file_metadata WHERE root_dir_id = ? AND relative_path = ?",
                    rootDirId, relativePath);
            log.debug("acknowledgeDelete: client_ids already empty, hard-deleted rootDirId={} '{}'", rootDirId, relativePath);
            return;
        }

        // Remove this client's ID from the comma-separated list
        String updated = java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(id -> !id.equals(hardwareId))
                .collect(java.util.stream.Collectors.joining(","));

        if (updated.isEmpty()) {
            jdbcTemplate.update("DELETE FROM file_metadata WHERE root_dir_id = ? AND relative_path = ?",
                    rootDirId, relativePath);
            log.debug("acknowledgeDelete: last client acked delete, hard-deleted rootDirId={} '{}'", rootDirId, relativePath);
        } else {
            jdbcTemplate.update(
                    "UPDATE file_metadata SET client_ids = ? WHERE root_dir_id = ? AND relative_path = ?",
                    updated, rootDirId, relativePath);
            log.debug("acknowledgeDelete: removed '{}' from pending list for rootDirId={} '{}', remaining: {}",
                    hardwareId, rootDirId, relativePath, updated);
        }
    }
}
