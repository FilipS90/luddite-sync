package com.fstojilj.luddite.sync.client.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the SQLite schema on startup using CREATE TABLE IF NOT EXISTS.
 * Runs before other ApplicationRunners thanks to @Order(1).
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
                CREATE TABLE IF NOT EXISTS sync_state (
                    dir_name          TEXT    PRIMARY KEY NOT NULL,
                    last_sync_version INTEGER NOT NULL DEFAULT -1
                )
                """);

        log.info("SQLite schema initialized successfully");
    }
}

