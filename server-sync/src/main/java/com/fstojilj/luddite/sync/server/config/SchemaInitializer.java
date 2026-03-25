package com.fstojilj.luddite.sync.server.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;

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

    private final RootDirs rootDirs;

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
                    id            INTEGER  PRIMARY KEY AUTOINCREMENT,
                    filename      TEXT     NOT NULL,
                    root_dir_id   INTEGER  NOT NULL REFERENCES root_dir(id) ON DELETE CASCADE,
                    relative_path TEXT     NOT NULL,
                    checksum      TEXT     NOT NULL,
                    file_size     INTEGER  NOT NULL,
                    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    modified_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    sync_version  BIGINT,
                    deleted       BOOLEAN  NOT NULL DEFAULT FALSE,
                    client_ids    TEXT,
                    UNIQUE (root_dir_id, relative_path, filename)
                )
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

        for (String rootDirAbsolutePath : rootDirs.rootDirAbsolutePaths()) {
            String name = Paths.get(rootDirAbsolutePath).getFileName().toString();
            jdbcTemplate.update(
                    "INSERT OR IGNORE INTO root_dir (name, absolute_path) VALUES (?, ?)",
                    name, rootDirAbsolutePath
            );
        }

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

