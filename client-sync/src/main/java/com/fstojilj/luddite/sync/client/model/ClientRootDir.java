package com.fstojilj.luddite.sync.client.model;

/**
 * Client-side row model for the {@code root_dirs} SQLite table.
 * Represents one server root directory the client is tracking, together with the
 * last acknowledged sync version and an optional custom local mirror path.
 * <p>
 * Unlike {@link com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry} (the shared
 * wire-protocol record), this type is client-only and carries fields that never cross
 * the network.
 *
 * @param dirName         server-side directory name
 * @param lastSyncVersion highest sync_version acknowledged by this client; {@code -1}
 *                        means nothing has been synced yet
 * @param customPath      absolute local path chosen by the user at subscribe time, or
 *                        {@code null} to use the default {@code mirrorDir/dirName} location
 */
public record ClientRootDir(String dirName, long lastSyncVersion, String customPath) {
}
