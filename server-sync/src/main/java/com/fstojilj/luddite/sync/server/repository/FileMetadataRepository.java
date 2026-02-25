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
import java.util.Optional;

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
            .syncVersion(rs.getLong("sync_version"))
            .build();


    public long add(FileMetadata fileMetadata) {
        String sql = """
                INSERT INTO file_metadata (filename, root_dir_id, relative_path, checksum, file_size, created_at, modified_at, sync_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, fileMetadata.getFilename());
            ps.setLong(2, fileMetadata.getRootDirId());
            ps.setString(3, fileMetadata.getRelativePath());
            ps.setString(4, fileMetadata.getChecksum());
            ps.setLong(5, fileMetadata.getFileSize());
            ps.setTimestamp(6, fileMetadata.getCreatedAt() != null ? Timestamp.from(fileMetadata.getCreatedAt()) : Timestamp.from(Instant.now()));
            ps.setTimestamp(7, fileMetadata.getModifiedAt() != null ? Timestamp.from(fileMetadata.getModifiedAt()) : Timestamp.from(Instant.now()));
            ps.setObject(8, fileMetadata.getSyncVersion());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated key for FileMetadata");
        }

        log.debug("Inserted FileMetadata with id: {}", key.longValue());
        return key.longValue();
    }

    public FileMetadata findByRootDirIdAndRelativePath(Long rootDirId, String relativePath) {
        String sql = "SELECT * FROM file_metadata WHERE root_dir_id = ? AND relative_path = ?";
        List<FileMetadata> results = jdbcTemplate.query(sql, rowMapper, rootDirId, relativePath);
        if (results.isEmpty()) {
            throw new IllegalArgumentException("No FileMetadata found for rootDirId: " + rootDirId + " and relativePath: " + relativePath);
        }
        return results.getFirst();
    }

    public Optional<FileMetadata> findOptionalByRootDirIdAndRelativePath(Long rootDirId, String relativePath) {
        String sql = "SELECT * FROM file_metadata WHERE root_dir_id = ? AND relative_path = ?";
        List<FileMetadata> results = jdbcTemplate.query(sql, rowMapper, rootDirId, relativePath);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public void update(FileMetadata fileMetadata) {
        String sql = """
                UPDATE file_metadata
                SET filename = ?, root_dir_id = ?, relative_path = ?, checksum = ?,
                    file_size = ?, modified_at = ?, sync_version = ?
                WHERE id = ?
                """;

        int rowsAffected = jdbcTemplate.update(sql,
                fileMetadata.getFilename(),
                fileMetadata.getRootDirId(),
                fileMetadata.getRelativePath(),
                fileMetadata.getChecksum(),
                fileMetadata.getFileSize(),
                Timestamp.from(Instant.now()),
                fileMetadata.getSyncVersion(),
                fileMetadata.getId()
        );

        if (rowsAffected == 0) {
            log.warn("No FileMetadata found with id: {}", fileMetadata.getId());
        } else {
            log.debug("Updated FileMetadata for rootDirId: {}, relativePath: {}", fileMetadata.getRootDirId(), fileMetadata.getRelativePath());
        }
    }

    public List<FileMetadata> findByRootDirIdWithSyncVersionAfter(long rootDirId, long lastSyncVersion) {
        String sql = "SELECT * FROM file_metadata WHERE root_dir_id = ? AND sync_version IS NOT NULL AND sync_version > ?";
        return jdbcTemplate.query(sql, rowMapper, rootDirId, lastSyncVersion);
    }

    public void updateSyncVersion(long rootDirId, String relativePath, long syncVersion) {
        jdbcTemplate.update(
                "UPDATE file_metadata SET sync_version = ? WHERE root_dir_id = ? AND relative_path = ?",
                syncVersion, rootDirId, relativePath);
    }

    public void delete(Long rootDirId, String relativePath) {
        String sql = "DELETE FROM file_metadata WHERE root_dir_id = ? AND relative_path = ?";
        int rowsAffected = jdbcTemplate.update(sql, rootDirId, relativePath);
        if (rowsAffected > 0) {
            log.debug("Deleted FileMetadata for rootDirId: {}, relativePath: {}", rootDirId, relativePath);
        } else {
            log.warn("No FileMetadata found for deletion with rootDirId: {}, relativePath: {}", rootDirId, relativePath);
        }
    }


}
