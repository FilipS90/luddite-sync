package com.fstojilj.luddite.sync.client.model;

/**
 * A server host:port pair. Used to bundle the two together wherever the client resolves
 * "which server am I talking to" — restoring from the {@code host} DB table, or
 * falling back to the one-time bootstrap default when that table is empty.
 */
public record HostEndpoint(String host, int port) {

    /** One-time bootstrap host, used only when {@code host} is empty. */
    public static final String DEFAULT_HOST = "luddite-server";

    /** One-time bootstrap port, used only when {@code host} has no entry for a host. */
    public static final int DEFAULT_PORT = 8888;

    public static HostEndpoint bootstrapDefault() {
        return new HostEndpoint(DEFAULT_HOST, DEFAULT_PORT);
    }
}
