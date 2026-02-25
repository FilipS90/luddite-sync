package com.fstojilj.luddite.sync.common.model;

/**
 * Sent by the client immediately after connecting.
 * Each entry represents one root directory the client wants synced,
 * along with the highest sync_version it already has locally.
 * A lastSyncVersion of -1 means the client has nothing and wants a full sync.
 * <p>
 * Wire format (binary, written with DataOutputStream):
 * [4 bytes] number of entries
 * per entry:
 * [4 bytes] dir name length
 * [N bytes] dir name (UTF-8)
 * [8 bytes] lastSyncVersion (long)
 */
public record SyncHandshakeEntry(String dirName, long lastSyncVersion) {
}

