package com.fstojilj.luddite.sync.server.config;

public interface SchemaConstants {

    String ROOT_DIR_TABLE = """
            CREATE TABLE IF NOT EXISTS root_dir (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                is_private BOOLEAN NOT NULL DEFAULT FALSE,
                password TEXT,
                absolute_path TEXT NOT NULL UNIQUE,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                modified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """;

    String FILE_METADATA_TABLE = """
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
            """;

    String UPDATE_ROOT_DIR_MODIFIED_AT_TRIGGER = """
            CREATE TRIGGER update_root_dir_modified_at
            AFTER UPDATE ON root_dir
            FOR EACH ROW
            BEGIN
                UPDATE root_dir SET modified_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
            END
            """;

    String UPDATE_FILE_METADATA_MODIFIED_AT_TRIGGER = """
            CREATE TRIGGER update_file_metadata_modified_at
            AFTER UPDATE ON file_metadata
            FOR EACH ROW
            BEGIN
                UPDATE file_metadata SET modified_at = CURRENT_TIMESTAMP WHERE id = NEW.id;
            END
            """;
}
