package com.fstojilj.luddite.sync.server.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;

import static com.fstojilj.luddite.sync.server.config.SchemaConstants.FILE_METADATA_TABLE;
import static com.fstojilj.luddite.sync.server.config.SchemaConstants.ROOT_DIR_TABLE;
import static com.fstojilj.luddite.sync.server.config.SchemaConstants.UPDATE_FILE_METADATA_MODIFIED_AT_TRIGGER;
import static com.fstojilj.luddite.sync.server.config.SchemaConstants.UPDATE_ROOT_DIR_MODIFIED_AT_TRIGGER;

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

    private final SyncServerProperties serverProperties;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Initializing SQLite schema...");

        jdbcTemplate.execute(ROOT_DIR_TABLE);
        jdbcTemplate.execute(FILE_METADATA_TABLE);
        createTriggerIfNotExists("update_root_dir_modified_at", UPDATE_ROOT_DIR_MODIFIED_AT_TRIGGER);
        createTriggerIfNotExists("update_file_metadata_modified_at", UPDATE_FILE_METADATA_MODIFIED_AT_TRIGGER);

        for (String rootDirAbsolutePath : serverProperties.getRootDirs()) {
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

