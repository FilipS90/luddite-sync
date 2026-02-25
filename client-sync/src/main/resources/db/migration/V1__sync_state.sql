CREATE TABLE IF NOT EXISTS sync_state (
    dir_name     TEXT    PRIMARY KEY NOT NULL,
    last_sync_version INTEGER NOT NULL DEFAULT -1
);

