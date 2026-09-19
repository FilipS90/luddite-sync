package com.fstojilj.luddite.sync.server.repository;

import com.fstojilj.luddite.sync.common.dto.TreeEntry;
import com.fstojilj.luddite.sync.common.dto.TreeResponse;
import com.fstojilj.luddite.sync.common.model.FileMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
@RequiredArgsConstructor
@Slf4j
public class FileMetadataRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<FileMetadata> rowMapper = (rs, _) -> FileMetadata.builder()
            .id(rs.getLong("id"))
            .filename(rs.getString("filename"))
            .rootDirId(rs.getInt("root_dir_id"))
            .relativePath(rs.getString("relative_path"))
            .checksum(rs.getString("checksum"))
            .fileSize(rs.getLong("file_size"))
            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null)
            .modifiedAt(rs.getTimestamp("modified_at") != null ? rs.getTimestamp("modified_at").toInstant() : null)
            .syncVersion(rs.getObject("sync_version") instanceof Number n ? n.longValue() : null)
            .deleted(rs.getBoolean("deleted"))
            .clientIds(rs.getString("client_ids"))
            .build();

    /**
     * Inserts a live file record, or refreshes the existing row at that path in place (live or
     * soft-deleted): marks it live, updates checksum, size and modification time, and resets
     * {@code sync_version} to {@code NULL} so the next poll re-delivers the file.
     * {@code client_ids} is preserved so clients holding a previous copy remain tracked.
     *
     * <p>Must be used instead of {@link #addAll} for create/modify events: a soft-deleted row still
     * occupies the {@code UNIQUE (root_dir_id, relative_path, filename)} key until all clients
     * have acknowledged the delete.
     *
     * @param fileMetadata the record to insert or refresh
     */
    public void upsert(FileMetadata fileMetadata) {
        String sql = """
                INSERT INTO file_metadata (filename, root_dir_id, relative_path, checksum, file_size,
                                           created_at, modified_at, sync_version, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, FALSE)
                ON CONFLICT (root_dir_id, relative_path, filename) DO UPDATE SET
                    checksum     = excluded.checksum,
                    file_size    = excluded.file_size,
                    modified_at  = excluded.modified_at,
                    sync_version = NULL,
                    deleted      = FALSE
                """;
        jdbcTemplate.update(sql,
                fileMetadata.filename(),
                fileMetadata.rootDirId(),
                fileMetadata.relativePath(),
                fileMetadata.checksum(),
                fileMetadata.fileSize(),
                fileMetadata.createdAt() != null ? Timestamp.from(fileMetadata.createdAt()) : Timestamp.from(Instant.now()),
                fileMetadata.modifiedAt() != null ? Timestamp.from(fileMetadata.modifiedAt()) : Timestamp.from(Instant.now()));
        log.debug("Upserted FileMetadata for rootDirId: {}, relativePath: {}",
                fileMetadata.rootDirId(), fileMetadata.relativePath());
    }

    public void addAll(List<FileMetadata> metadataList) {
        String sql = """
                INSERT INTO file_metadata (filename, root_dir_id, relative_path, checksum, file_size, created_at, modified_at, sync_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
                FileMetadata fileMetadata = metadataList.get(i);

                ps.setString(1, fileMetadata.filename());
                ps.setLong(2, fileMetadata.rootDirId());
                ps.setString(3, fileMetadata.relativePath());
                ps.setString(4, fileMetadata.checksum());
                ps.setLong(5, fileMetadata.fileSize());

                ps.setTimestamp(6, fileMetadata.createdAt() != null ?
                        Timestamp.from(fileMetadata.createdAt()) : Timestamp.from(Instant.now()));
                ps.setTimestamp(7, fileMetadata.modifiedAt() != null ?
                        Timestamp.from(fileMetadata.modifiedAt()) : Timestamp.from(Instant.now()));

                ps.setObject(8, fileMetadata.syncVersion());
            }

            @Override
            public int getBatchSize() {
                return metadataList.size();
            }
        });

        log.debug("Batch inserted {} FileMetadata records successfully.", metadataList.size());
    }

    public ConcurrentMap<Integer, Long> getMaxSyncVersionByRootDir() {
        return jdbcTemplate.query(
                "SELECT root_dir_id, MAX(sync_version) as max_sync_version FROM file_metadata GROUP BY root_dir_id",
                rs -> {
                    ConcurrentMap<Integer, Long> map = new ConcurrentHashMap<>();
                    while (rs.next()) {
                        long val = rs.getLong("max_sync_version");
                        map.put(rs.getInt("root_dir_id"), rs.wasNull() ? null : val);
                    }
                    return map;
                }
        );
    }


    public void updateSyncVersion(int rootDirId, String relativePath, long syncVersion) {
        jdbcTemplate.update(
                "UPDATE file_metadata SET sync_version = MAX(COALESCE(sync_version, 0), ?) WHERE root_dir_id = ? AND relative_path = ?",
                syncVersion, rootDirId, relativePath);
    }

    /**
     * Hard-deletes a live (non-soft-deleted) file metadata row.
     * Use {@link #softDelete} for deletion events that must be replicated to clients.
     */
    public void delete(Integer rootDirId, String relativePath) {
        String sql = "DELETE FROM file_metadata WHERE root_dir_id = ? AND relative_path = ?";
        int rowsAffected = jdbcTemplate.update(sql, rootDirId, relativePath);
        if (rowsAffected > 0) {
            log.debug("Deleted FileMetadata for rootDirId: {}, relativePath: {}", rootDirId, relativePath);
        } else {
            log.warn("No FileMetadata found for deletion with rootDirId: {}, relativePath: {}", rootDirId, relativePath);
        }
    }

    public void deleteAllByRootDirId(int rootDirId) {
        jdbcTemplate.update("DELETE FROM file_metadata WHERE root_dir_id = ?", rootDirId);
        log.debug("Deleted all FileMetadata for rootDirId: {}", rootDirId);
    }

    /**
     * Returns all rows (live and soft-deleted) whose {@code sync_version} is greater than
     * {@code lastSyncVersion}, or whose {@code sync_version} is {@code NULL} (never delivered).
     * Used by the poll handler to compute what a client needs to catch up on.
     *
     * <p>Ordered by ascending version with {@code NULL} rows last. The client advances its
     * cursor to the highest version in a batch, and {@code NULL} rows are minted above every
     * existing version when sent, so this ordering guarantees a capped batch never contains a
     * version higher than one still unsent.
     *
     * @param rootDirId       root directory ID
     * @param lastSyncVersion last version the client acknowledged
     * @return list of changed/deleted records since that version
     */
    public List<FileMetadata> findChangedSince(long rootDirId, Long lastSyncVersion, long limit) {
        return jdbcTemplate.query("""
                        SELECT * FROM file_metadata
                        WHERE root_dir_id = ?
                          AND (sync_version IS NULL OR sync_version > ?)
                        ORDER BY sync_version IS NULL, sync_version ASC
                        LIMIT ?
                        """,
                rowMapper, rootDirId, lastSyncVersion, limit);
    }

    /**
     * Marks a file as soft-deleted and sets the {@code sync_version}. The existing
     * {@code client_ids} (clients holding a copy) is left untouched — each of them must
     * acknowledge the delete via {@link #acknowledgeDelete} before the row is hard-deleted.
     *
     * @param rootDirId    root directory ID
     * @param relativePath relative file path
     * @param syncVersion  new monotonic sync version minted for this deletion event
     */
    public void softDelete(long rootDirId, String relativePath, long syncVersion) {
        int rows = jdbcTemplate.update("""
                        UPDATE file_metadata
                        SET deleted = TRUE, sync_version = ?,
                            file_size = 0, checksum = ''
                        WHERE root_dir_id = ? AND relative_path = ?
                        """,
                syncVersion, rootDirId, relativePath);
        if (rows == 0) {
            log.warn("softDelete: no row found for rootDirId={} relativePath='{}'", rootDirId, relativePath);
        } else {
            log.debug("Soft-deleted (v{}) rootDirId={} '{}'", syncVersion, rootDirId, relativePath);
        }
    }

    /**
     * Appends {@code clientId} to the {@code client_ids} list of every given row in one batch.
     * Called after a client has received the files. The append is done in SQL so it is atomic
     * per row and idempotent (a client already in the list is not added twice).
     *
     * @param rootDirId     root directory ID
     * @param relativePaths relative paths of the files the client received
     * @param clientId      the receiving client's stable ID
     */
    public void addClientIdToAll(int rootDirId, List<String> relativePaths, String clientId) {
        if (relativePaths.isEmpty()) {
            return;
        }
        String sql = """
                UPDATE file_metadata
                SET client_ids = CASE
                    WHEN client_ids IS NULL OR client_ids = '' THEN ?
                    WHEN instr(',' || client_ids || ',', ',' || ? || ',') > 0 THEN client_ids
                    ELSE client_ids || ',' || ?
                END
                WHERE root_dir_id = ? AND relative_path = ?
                """;

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(@NonNull PreparedStatement ps, int i) throws SQLException {
                ps.setString(1, clientId);
                ps.setString(2, clientId);
                ps.setString(3, clientId);
                ps.setInt(4, rootDirId);
                ps.setString(5, relativePaths.get(i));
            }

            @Override
            public int getBatchSize() {
                return relativePaths.size();
            }
        });

        log.debug("Added client '{}' to client_ids of {} record(s) in rootDirId={}",
                clientId, relativePaths.size(), rootDirId);
    }

    /**
     * Removes {@code clientId} from the {@code client_ids} list of a soft-deleted row.
     * When the list becomes empty (all clients have acknowledged the delete) the row is
     * hard-deleted from the database.
     *
     * <p>The removal is a single UPDATE so that acknowledgements from several clients
     * arriving at the same time never overwrite each other's edit of the list.
     *
     * @param rootDirId    root directory ID
     * @param relativePath relative file path
     * @param clientId     the client ID to remove
     */
    public void acknowledgeDelete(int rootDirId, String relativePath, String clientId) {
        int rows = jdbcTemplate.update("""
                        UPDATE file_metadata
                        SET client_ids = trim(replace(',' || coalesce(client_ids, '') || ',', ',' || ? || ',', ','), ',')
                        WHERE root_dir_id = ? AND relative_path = ? AND deleted = TRUE
                        """,
                clientId, rootDirId, relativePath);
        if (rows == 0) {
            log.warn("acknowledgeDelete: no soft-deleted row for rootDirId={} '{}'", rootDirId, relativePath);
            return;
        }

        int purged = jdbcTemplate.update("""
                        DELETE FROM file_metadata
                        WHERE root_dir_id = ? AND relative_path = ? AND deleted = TRUE AND client_ids = ''
                        """,
                rootDirId, relativePath);
        if (purged > 0) {
            log.debug("acknowledgeDelete: last client acked delete, hard-deleted rootDirId={} '{}'", rootDirId, relativePath);
        } else {
            log.debug("acknowledgeDelete: removed '{}' from pending list for rootDirId={} '{}'", clientId, rootDirId, relativePath);
        }
    }

    /**
     * Returns all active (non-deleted) file metadata records for a given root directory.
     * Used by the periodic subdir scanner to compare DB state against the filesystem.
     *
     * @param rootDirId root directory ID
     * @return list of active FileMetadata records
     */
    public List<FileMetadata> findAllActiveByRootDirId(int rootDirId) {
        return jdbcTemplate.query(
                "SELECT * FROM file_metadata WHERE root_dir_id = ? AND (deleted IS NULL OR deleted = FALSE)",
                rowMapper, rootDirId);
    }

    /**
     * Returns the current maximum sync version for a single root directory.
     * Returns {@code null} if no versioned records exist yet.
     *
     * @param rootDirId root directory ID
     * @return max sync_version, or null
     */
    public Long getMaxSyncVersionForDir(int rootDirId) {
        return jdbcTemplate.queryForObject(
                "SELECT MAX(sync_version) FROM file_metadata WHERE root_dir_id = ?",
                Long.class, rootDirId);
    }

    /**
     * Returns the immediate children under {@code parentRelPath} for a root directory:
     * subdirectories (derived from file paths — the schema has no directory rows) with the
     * summed size of every file beneath them, and files with their own size.
     *
     * @param rootDirId     root directory ID
     * @param parentRelPath the path to look under; {@code ""} or {@code null} for the root level
     * @return children sorted by name
     */
    public TreeResponse findImmediateChildren(int rootDirId, String parentRelPath) {
        String prefix = parentRelPath == null ? "" : parentRelPath.replace('\\', '/').replaceAll("^/+|/+$", "");

        String sql = "SELECT relative_path, file_size FROM file_metadata WHERE root_dir_id = ? AND deleted = FALSE";
        Object[] args = {rootDirId};
        if (!prefix.isEmpty()) {
            // '0' is the code point after '/', so this range is exactly "prefix/…"
            sql += " AND relative_path >= ? AND relative_path < ?";
            args = new Object[]{rootDirId, prefix + "/", prefix + "0"};
        }

        Map<String, Long> dirs = new TreeMap<>();
        Map<String, Long> files = new TreeMap<>();
        int skip = prefix.isEmpty() ? 0 : prefix.length() + 1;
        jdbcTemplate.query(sql, rs -> {
            String rest = rs.getString("relative_path").substring(skip);
            long size = rs.getLong("file_size");
            int slash = rest.indexOf('/');
            if (slash < 0) {
                files.put(rest, size);
            } else {
                dirs.merge(rest.substring(0, slash), size, Long::sum);
            }
        }, args);

        return new TreeResponse(toEntries(dirs), toEntries(files));
    }

    private static List<TreeEntry> toEntries(Map<String, Long> byName) {
        return byName.entrySet().stream().map(e -> new TreeEntry(e.getKey(), e.getValue())).toList();
    }

    /**
     * Returns the total size of live files per root directory.
     *
     * @return map of root_dir_id to summed file_size; roots with no live files are absent
     */
    public Map<Integer, Long> sumFileSizeByRootDir() {
        return jdbcTemplate.query(
                "SELECT root_dir_id, SUM(file_size) AS total FROM file_metadata WHERE deleted = FALSE GROUP BY root_dir_id",
                rs -> {
                    Map<Integer, Long> map = new HashMap<>();
                    while (rs.next()) {
                        map.put(rs.getInt("root_dir_id"), rs.getLong("total"));
                    }
                    return map;
                });
    }

    /**
     * Removes {@code clientId} from the {@code client_ids} list of every row that contains it,
     * across all root directories. Soft-deleted rows left with no clients are hard-deleted,
     * since nobody remains to acknowledge the delete.
     *
     * @param clientId the client ID to remove
     */
    public void removeAllClientIdUsage(String clientId) {
        String sql = """
                UPDATE file_metadata
                SET client_ids = NULLIF(TRIM(REPLACE(',' || client_ids || ',', ',' || ? || ',', ','), ','), '')
                WHERE instr(',' || client_ids || ',', ',' || ? || ',') > 0
                """;
        int updated = jdbcTemplate.update(sql, clientId, clientId);
        int purged = jdbcTemplate.update(
                "DELETE FROM file_metadata WHERE deleted = TRUE AND (client_ids IS NULL OR client_ids = '')");

        log.debug("Removed client '{}' from client_ids of {} record(s), hard-deleted {} orphaned soft-deleted row(s)",
                clientId, updated, purged);
    }
}
