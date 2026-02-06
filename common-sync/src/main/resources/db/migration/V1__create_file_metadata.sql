CREATE TABLE IF NOT EXISTS root_dir (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    absolute_path TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    modified_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS file_metadata (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    filename TEXT NOT NULL,
    root_dir_id INTEGER NOT NULL REFERENCES root_dir(id) ON DELETE CASCADE,
    relative_path TEXT NOT NULL,
    checksum TEXT NOT NULL UNIQUE,
    file_size INTEGER NOT NULL,
    mime_type TEXT,
    created_at TEXT NOT NULL,
    modified_at TEXT NOT NULL,
    sync_version INTEGER
);