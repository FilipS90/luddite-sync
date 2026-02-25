CREATE TABLE IF NOT EXISTS deleted_files (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    root_dir_id   INTEGER NOT NULL REFERENCES root_dir(id) ON DELETE CASCADE,
    relative_path TEXT    NOT NULL,
    sync_version  INTEGER NOT NULL,
    deleted_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (root_dir_id, relative_path)
);

CREATE INDEX IF NOT EXISTS idx_deleted_files_root_dir_sync_version
    ON deleted_files (root_dir_id, sync_version);

