package com.fstojilj.luddite.sync.server.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the SQLite schema on startup using CREATE TABLE IF NOT EXISTS.
 * Runs before WatcherStartupRunner thanks to @Order(1).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(1)
public class SchemaInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Initializing SQLite schema...");

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS root_dir (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    absolute_path TEXT NOT NULL UNIQUE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    modified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS file_metadata (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    filename TEXT NOT NULL,
                    root_dir_id INTEGER NOT NULL REFERENCES root_dir(id) ON DELETE CASCADE,
                    relative_path TEXT NOT NULL,
                    checksum TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    modified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    sync_version INTEGER,
                    UNIQUE (root_dir_id, relative_path)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sync_log (
                    version INTEGER PRIMARY KEY AUTOINCREMENT,
                    status TEXT NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS deleted_files (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    root_dir_id   INTEGER NOT NULL REFERENCES root_dir(id) ON DELETE CASCADE,
                    relative_path TEXT    NOT NULL,
                    sync_version  INTEGER NOT NULL,
                    deleted_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (root_dir_id, relative_path)
                )
                """);

        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_deleted_files_root_dir_sync_version
                    ON deleted_files (root_dir_id, sync_version)
                """);

        createTriggerIfNotExists("update_root_dir_modified_at", """
                CREATE TRIGGER update_root_dir_modified_at
                AFTER UPDATE ON root_dir
                FOR EACH ROW
                BEGIN
                    UPDATE root_dir SET modified_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
                END
                """);

        createTriggerIfNotExists("update_file_metadata_modified_at", """
                CREATE TRIGGER update_file_metadata_modified_at
                AFTER UPDATE ON file_metadata
                FOR EACH ROW
                BEGIN
                    UPDATE file_metadata SET modified_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
                END
                """);

        log.info("SQLite schema initialized successfully");
    }

    private void createTriggerIfNotExists(String triggerName, String createSql) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name=?",
                Integer.class, triggerName);
        if (count == null || count == 0) {
            jdbcTemplate.execute(createSql);
        }
    }
}

