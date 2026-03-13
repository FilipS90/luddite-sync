package com.fstojilj.luddite.sync.server.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class DeletedFilesRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insert(long rootDirId, String relativePath, long syncVersion) {
        jdbcTemplate.update("""
                INSERT INTO deleted_files (root_dir_id, relative_path, sync_version)
                VALUES (?, ?, ?)
                ON CONFLICT(root_dir_id, relative_path)
                    DO UPDATE SET sync_version = MAX(excluded.sync_version, sync_version),
                                  deleted_at   = CURRENT_TIMESTAMP
                """, rootDirId, relativePath, syncVersion);
    }

    /**
     * Returns relative_path + sync_version of files deleted after the given syncVersion.
     */
    public List<Map<String, Object>> findByRootDirIdWithSyncVersionAfter(long rootDirId, long lastSyncVersion) {
        return jdbcTemplate.queryForList(
                "SELECT relative_path, sync_version FROM deleted_files WHERE root_dir_id = ? AND sync_version > ?",
                rootDirId, lastSyncVersion);
    }

    public void deleteAllByRootDirId(long rootDirId) {
        jdbcTemplate.update("DELETE FROM deleted_files WHERE root_dir_id = ?", rootDirId);
    }
}

