package com.fstojilj.luddite.sync.client.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.Socket;

/**
 * Builds outbound connections to the server's file socket, shared by the persistent
 * sync connection ({@link ClientSyncService}) and one-off downloads ({@link DownloadService}).
 *
 * <p>Centralizes the mTLS/plain-TCP decision and keystore/truststore loading so both
 * callers stay in sync with the {@code sync.socket.*} configuration.
 */
@Component
public class ClientSocketFactory {

    @Getter
    @Value("${sync.server.host}")
    private String serverHost;

    @Getter
    @Value("${sync.server.port:8888}")
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

}
