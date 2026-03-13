package com.fstojilj.luddite.sync.client.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class SyncedFileRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Records a file as successfully synced to disk.
     */
    public void upsert(String dirName, String relativePath) {
        jdbcTemplate.update("""
                INSERT INTO synced_files (dir_name, relative_path)
                VALUES (?, ?)
                ON CONFLICT(dir_name, relative_path) DO NOTHING
                """, dirName, relativePath);
    }

    /**
     * Removes a file record when the server sends a DELETE event,
     * or when the file is found missing during a startup audit.
     */
    public void delete(String dirName, String relativePath) {
        jdbcTemplate.update("""
                DELETE FROM synced_files WHERE dir_name = ? AND relative_path = ?
                """, dirName, relativePath);
    }

    /**
     * Returns all relative paths recorded for a given dir.
     */
    public List<String> findAllByDir(String dirName) {
        return jdbcTemplate.queryForList(
                "SELECT relative_path FROM synced_files WHERE dir_name = ?",
                String.class,
                dirName
        );
    }
}
