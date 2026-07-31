package com.fstojilj.luddite.sync.client.service;

/**
 * State of the client's persistent sync connection to the server, as tracked explicitly
 * by {@link ClientSyncService} (set at well-defined transition points rather than derived
 * from raw socket introspection, which was prone to flakiness).
 */
public enum ConnectionState {
    /** No active socket connection — initial state, HTTP negotiation phase, or a lost connection awaiting reconnect. */
    DISCONNECTED,
    /** Socket connected and the poll loop is running with no file transfer in progress. */
    IDLE,
    /** Socket connected and the poll loop is actively writing or deleting files received from the server. */
    TRANSFERRING
}
