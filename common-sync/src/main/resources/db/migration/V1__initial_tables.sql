CREATE TABLE IF NOT EXISTS root_dir (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    absolute_path TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS file_metadata (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    filename TEXT NOT NULL,
    root_dir_id INTEGER NOT NULL REFERENCES root_dir(id) ON DELETE CASCADE,
    relative_path TEXT NOT NULL,
    checksum TEXT NOT NULL UNIQUE,
    file_size INTEGER NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    modified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    sync_version INTEGER
);

CREATE TABLE IF NOT EXISTS sync_log (
    version PRIMARY KEY INTEGER NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER update_root_dir_modified_at
AFTER UPDATE ON root_dir
FOR EACH ROW
BEGIN
    UPDATE root_dir
    SET modified_at = CURRENT_TIMESTAMP
    WHERE id = NEW.id;
END;

CREATE TRIGGER update_file_metadata_modified_at
AFTER UPDATE ON file_metadata
FOR EACH ROW
BEGIN
    UPDATE file_metadata
    SET modified_at = CURRENT_TIMESTAMP
    WHERE id = NEW.id;
END;

CREATE TRIGGER update_folder_metadata_modified_at
AFTER UPDATE ON folder_metadata
FOR EACH ROW
BEGIN
    UPDATE folder_metadata
    SET modified_at = CURRENT_TIMESTAMP
    WHERE id = NEW.id;
END;