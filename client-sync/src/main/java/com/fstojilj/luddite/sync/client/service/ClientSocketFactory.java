package com.fstojilj.luddite.sync.client.service;

import java.net.Socket;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

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

    @Value("${sync.socket.keystore:classpath:client-keystore.p12}")
    private Resource keystoreResource;

    @Value("${sync.socket.truststore:classpath:truststore.p12}")
    private Resource truststoreResource;

    @Value("${sync.socket.password}")
    private String keystorePassword;

    @Value("${sync.socket.tls-enabled:true}")
    private boolean tlsEnabled;

    /**
     * Opens a new connection to the configured server.
     * When {@code sync.socket.tls-enabled} is {@code true} (default), builds a
     * mutually-authenticated TLS socket; otherwise builds a plain TCP socket.
     *
     * @return a connected {@link Socket}
     * @throws Exception if the connection fails
     */
    public Socket connect() throws Exception {
        if (!tlsEnabled) {
            return new Socket(serverHost, serverPort);
        }

        char[] password = keystorePassword.toCharArray();

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var is = keystoreResource.getInputStream()) {
            keyStore.load(is, password);
        }

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (var is = truststoreResource.getInputStream()) {
            trustStore.load(is, password);
        }

        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);

        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        var ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        return ctx.getSocketFactory().createSocket(serverHost, serverPort);
    }

}
