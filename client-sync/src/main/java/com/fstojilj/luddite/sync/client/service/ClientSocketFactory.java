package com.fstojilj.luddite.sync.client.service;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.Socket;

/**
 * Builds outbound connections to the server's file socket, shared by the persistent
 * sync connection ({@link ClientSyncService}) and one-off downloads ({@link DownloadService}).
 */
@Component
public class ClientSocketFactory {

    /**
     * The server host and port to connect to. Neither has an {@code @Value} default —
     * both are always set by {@code HostSettingsInitializer} at startup, from the
     * client's {@code host} DB table (the single source of truth for both;
     * {@code application.yml} plays no role). The port is additionally kept current by
     * {@code ClientSyncService}, which re-discovers it over REST on every (re)connect.
     */
    @Getter
    private String serverHost;

    @Getter
    private int serverPort;

    /**
     * Opens a new connection to the configured server.
     *
     * @return a connected {@link Socket}
     * @throws IOException if the connection fails
     */
    public Socket connect() throws IOException {
        return new Socket(serverHost, serverPort);
    }

    /**
     * Points future {@link #connect()} calls at a different server host.
     *
     * @param host the new server host
     */
    public synchronized void setServerHost(String host) {
        this.serverHost = host;
    }

    /**
     * Points future {@link #connect()} calls at a different server port.
     *
     * @param port the new server port
     */
    public synchronized void setServerPort(int port) {
        this.serverPort = port;
    }

}
