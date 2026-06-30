package com.fstojilj.luddite.sync.common.model;

/**
 * Describes a single operation to execute over the binary TCP socket.
 * The client builds a {@code List<SocketOperation>} via HTTP negotiation
 * and hands it to the socket executor after the connection is established.
 *
 * <p>Operations are executed in declaration order: all {@link Download} operations
 * complete before any {@link Sync} polling begins.
 */
public sealed interface SocketOperation permits SocketOperation.Sync, SocketOperation.Download {

    /**
     * Continuous sync of a root directory.
     * {@code lastSyncVersion} is fetched from the local database on each poll cycle
     * and is therefore not stored here.
     *
     * @param dirName root directory name to sync
     */
    record Sync(String dirName) implements SocketOperation {}

    /**
     * One-time download of a single file identified by its qualified path.
     *
     * @param qualifiedPath qualified path in the form {@code "dirName/relativePath"}
     */
    record Download(String qualifiedPath) implements SocketOperation {}
}
