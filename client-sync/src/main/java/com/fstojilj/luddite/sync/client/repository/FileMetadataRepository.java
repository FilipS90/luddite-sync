package com.fstojilj.luddite.sync.client.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Tracks which files have been successfully synced to the local mirror disk.
 *
 * <p>Maps to the {@code file_metadata} table in the client SQLite database.
 */
@Repository
@RequiredArgsConstructor
public class FileMetadataRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Records a file as successfully synced to disk.
     * Safe to call repeatedly — duplicate entries are silently ignored.
     *
     * @param dirName      server-side directory name (first path component)
     * @param relativePath qualified relative path including the dir name prefix
     */
    public void upsert(String dirName, String relativePath) {
        jdbcTemplate.update("""
                INSERT INTO file_metadata (dir_name, relative_path)
                VALUES (?, ?)
                ON CONFLICT(dir_name, relative_path) DO NOTHING
                """, dirName, relativePath);
    }

    /**
     * Removes a file record when the server delivers a delete event,
     * or when the file is found missing from disk during the startup audit.
     *
     * @param dirName      server-side directory name
     * @param relativePath qualified relative path to remove
     */
    public void delete(String dirName, String relativePath) {
        jdbcTemplate.update("""
                DELETE FROM file_metadata WHERE dir_name = ? AND relative_path = ?
                """, dirName, relativePath);
    }

    /**
     * Returns all relative paths recorded for a given directory.
     * Used during the startup audit to detect files missing from disk.
     *
     * @param dirName server-side directory name
     * @return list of relative paths previously confirmed as synced
     */
    public List<String> findAllByDir(String dirName) {
        return jdbcTemplate.queryForList(
                "SELECT relative_path FROM file_metadata WHERE dir_name = ?",
                String.class,
                dirName);
    }
}

