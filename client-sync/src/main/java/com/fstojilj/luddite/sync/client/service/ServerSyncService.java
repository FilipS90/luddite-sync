package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.client.config.SyncClientProperties;
import com.fstojilj.luddite.sync.client.repository.SyncStateRepository;
import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class ServerSyncService {

    private static final byte EVENT_WRITE = 1;
    private static final byte EVENT_DELETE = 2;

    private final SyncStateRepository syncStateRepository;
    private final SyncClientProperties clientProperties;

    @Value("${sync.server.host:localhost}")
    private String serverHost;

    @Value("${sync.server.port:8888}")
    private int serverPort;


    @Value("${sync.socket.keystore:classpath:client-keystore.p12}")
    private Resource keystoreResource;

    @Value("${sync.socket.truststore:classpath:truststore.p12}")
    private Resource truststoreResource;

    @Value("${sync.socket.password:fichony123!}")
    private String keystorePassword;

    private volatile boolean running = false;
    private SSLSocket socket;

    @PostConstruct
    public void start() {
        running = true;
        Thread.ofVirtual().name("server-sync-receiver").start(this::connectAndReceive);
    }

    @PreDestroy
    public void stop() {
        running = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            log.warn("Error closing socket", e);
        }
    }

    private void connectAndReceive() {
        while (running) {
            try {
                socket = buildSslSocket();
                log.info("Connected to server {}:{}", serverHost, serverPort);

                var out = new DataOutputStream(socket.getOutputStream());
                var in = new DataInputStream(socket.getInputStream());

                registerConfiguredDirs();
                sendHandshake(out);
                receiveLoop(in);

            } catch (IOException e) {
                if (running) {
                    log.warn("Connection lost: {}. Reconnecting in 5s...", e.getMessage());
                    sleep(5_000);
                }
            } catch (Exception e) {
                log.error("Fatal error in sync receiver", e);
                break;
            }
        }
    }

    /**
     * Ensures every dir listed in config exists in sync_state.
     * New dirs get lastSyncVersion = -1 (full sync).
     * Existing dirs keep their current version.
     * Called on every connect so config changes take effect on reconnect.
     */
    private void registerConfiguredDirs() {
        for (String dirName : clientProperties.getDirs()) {
            syncStateRepository.registerIfAbsent(dirName);
        }
    }

    /**
     * Writes the handshake to the server:
     * [4 bytes] number of dirs
     * per dir:
     * [4 bytes] dir name length
     * [N bytes] dir name (UTF-8)
     * [8 bytes] lastSyncVersion
     */
    private void sendHandshake(DataOutputStream out) throws IOException {
        Set<String> configuredDirs = new HashSet<>(clientProperties.getDirs());
        List<SyncHandshakeEntry> entries = syncStateRepository.findAll().stream()
                .filter(e -> configuredDirs.contains(e.dirName()))
                .toList();

        out.writeInt(entries.size());
        for (SyncHandshakeEntry entry : entries) {
            byte[] nameBytes = entry.dirName().getBytes(StandardCharsets.UTF_8);
            out.writeInt(nameBytes.length);
            out.write(nameBytes);
            out.writeLong(entry.lastSyncVersion());
        }
        out.flush();
        log.info("Handshake sent: {} dir(s)", entries.size());
    }

    private void receiveLoop(DataInputStream in) throws IOException {
        while (running) {
            byte eventType = in.readByte();
            int pathLen = in.readInt();
            String relPath = new String(in.readNBytes(pathLen), StandardCharsets.UTF_8);
            long syncVersion = in.readLong();
            long fileSize = in.readLong();

            Path target = Path.of(clientProperties.getMirrorDir()).resolve(relPath).normalize();
            // first component of relPath is the dir name (e.g. "photos" from "photos/img.jpg")
            String dirName = Path.of(relPath).getName(0).toString();

            if (eventType == EVENT_DELETE) {
                Files.deleteIfExists(target);
                log.info("Deleted: {}", relPath);
            } else {
                byte[] fileBytes = readExactly(in, fileSize);
                Files.createDirectories(target.getParent());
                Files.write(target, fileBytes);
                log.info("Written: {} ({} bytes, v{})", relPath, fileSize, syncVersion);
            }

            syncStateRepository.upsert(dirName, syncVersion);
        }
    }

    private SSLSocket buildSslSocket() throws Exception {
        char[] password = keystorePassword.toCharArray();

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var in = keystoreResource.getInputStream()) {
            keyStore.load(in, password);
        }

        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (var in = truststoreResource.getInputStream()) {
            trustStore.load(in, password);
        }

        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);

        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        var ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        return (SSLSocket) ctx.getSocketFactory().createSocket(serverHost, serverPort);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private byte[] readExactly(DataInputStream in, long size) throws IOException {
        var baos = new java.io.ByteArrayOutputStream();
        long remaining = size;
        byte[] buf = new byte[8192];
        while (remaining > 0) {
            int read = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (read == -1) throw new IOException("Unexpected end of stream reading file bytes");
            baos.write(buf, 0, read);
            remaining -= read;
        }
        return baos.toByteArray();
    }
}
