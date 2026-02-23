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
            .mimeType(rs.getString("mime_type"))
            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null)
            .modifiedAt(rs.getTimestamp("modified_at") != null ? rs.getTimestamp("modified_at").toInstant() : null)
            .syncVersion(rs.getLong("sync_version"))
            .build();


    public long add(FileMetadata fileMetadata) {
        String sql = """
                INSERT INTO file_metadata (filename, root_dir_id, relative_path, checksum, file_size, mime_type, created_at, modified_at, sync_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, fileMetadata.getFilename());
            ps.setLong(2, fileMetadata.getRootDirId());
            ps.setString(3, fileMetadata.getRelativePath());
            ps.setString(4, fileMetadata.getChecksum());
            ps.setLong(5, fileMetadata.getFileSize());
            ps.setString(6, fileMetadata.getMimeType());
            ps.setTimestamp(7, fileMetadata.getCreatedAt() != null ? Timestamp.from(fileMetadata.getCreatedAt()) : Timestamp.from(Instant.now()));
            ps.setTimestamp(8, fileMetadata.getModifiedAt() != null ? Timestamp.from(fileMetadata.getModifiedAt()) : Timestamp.from(Instant.now()));
            ps.setObject(9, fileMetadata.getSyncVersion());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated key for FileMetadata");
        }

        log.debug("Inserted FileMetadata with id: {}", fileMetadata.getId());
        return key.longValue();
    }

    private void update(FileMetadata fileMetadata) {
        String sql = """
                UPDATE file_metadata
                SET filename = ?, root_dir_id = ?, relative_path = ?, checksum = ?,
                    file_size = ?, mime_type = ?, modified_at = ?, sync_version = ?
                WHERE id = ?
                """;

        int rowsAffected = jdbcTemplate.update(sql,
                fileMetadata.getFilename(),
                fileMetadata.getRootDirId(),
                fileMetadata.getRelativePath(),
                fileMetadata.getChecksum(),
                fileMetadata.getFileSize(),
                fileMetadata.getMimeType(),
                Timestamp.from(Instant.now()),
                fileMetadata.getSyncVersion(),
                fileMetadata.getId()
        );

        if (rowsAffected == 0) {
            log.warn("No FileMetadata found with id: {}", fileMetadata.getId());
        } else {
            log.debug("Updated FileMetadata with id: {}", fileMetadata.getId());
        }
    }

    public Optional<FileMetadata> findById(Long id) {
        String sql = "SELECT * FROM file_metadata WHERE id = ?";
        List<FileMetadata> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<FileMetadata> findByRootDirId(Long rootDirId) {
        String sql = "SELECT * FROM file_metadata WHERE root_dir_id = ?";
        return jdbcTemplate.query(sql, rowMapper, rootDirId);
    }

    public List<FileMetadata> findBySyncVersionGreaterThan(Long syncVersion) {
        String sql = "SELECT * FROM file_metadata WHERE sync_version > ?";
        return jdbcTemplate.query(sql, rowMapper, syncVersion);
    }

    public void deleteById(Long id) {
        String sql = "DELETE FROM file_metadata WHERE id = ?";
        int rowsAffected = jdbcTemplate.update(sql, id);
        if (rowsAffected > 0) {
            log.debug("Deleted FileMetadata with id: {}", id);
        } else {
            log.warn("No FileMetadata found with id: {}", id);
        }
    }

    public void deleteByChecksum(String checksum) {
        String sql = "DELETE FROM file_metadata WHERE checksum = ?";
        int rowsAffected = jdbcTemplate.update(sql, checksum);
        if (rowsAffected > 0) {
            log.debug("Deleted FileMetadata with checksum: {}", checksum);
        } else {
            log.warn("No FileMetadata found with checksum: {}", checksum);
        }
    }

    public boolean existsByChecksum(String checksum) {
        String sql = "SELECT COUNT(*) FROM file_metadata WHERE checksum = ?";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, checksum);
        return count != null && count > 0;
    }

    public long count() {
        String sql = "SELECT COUNT(*) FROM file_metadata";
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count : 0L;
    }
}
