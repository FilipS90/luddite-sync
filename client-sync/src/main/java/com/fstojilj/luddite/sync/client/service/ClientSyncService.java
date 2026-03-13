package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.client.config.SyncClientProperties;
import com.fstojilj.luddite.sync.client.repository.SyncStateRepository;
import com.fstojilj.luddite.sync.client.repository.SyncedFileRepository;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClientSyncService {

    private static final byte EVENT_WRITE = 1;
    private static final byte EVENT_DELETE = 2;
    private static final byte ACK = 3;
    private static final byte SHUTDOWN = 4;
    private static final byte RESUME_SERVER_MODE = 5;

    private final SyncStateRepository syncStateRepository;
    private final SyncedFileRepository syncedFileRepository;
    private final SyncClientProperties clientProperties;

    @Value("${sync.server.host:localhost}")
    private String serverHost;

    @Value("${sync.server.port:8888}")
    private int serverPort;


    @Value("${sync.socket.keystore:classpath:client-keystore.p12}")
    private Resource keystoreResource;

    @Value("${sync.socket.truststore:classpath:truststore.p12}")
    private Resource truststoreResource;

    @Value("${sync.socket.password}")
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
        closeSocket();
    }

    /**
     * Drops the current connection so connectAndReceive() reconnects immediately,
     * re-reading available dirs from the server. Called by the CLI's 'refresh' command.
     */
    public void reconnect() {
        log.info("Reconnect requested — dropping current connection to re-poll server dirs");
        closeSocket();
    }

    /**
     * Sends a SHUTDOWN signal to the server over the existing mTLS socket.
     * The server will log the request and call System.exit(0).
     * Use this when you are at the client site and need to remotely stop the server.
     */
    public void sendShutdown() {
        if (socket == null || socket.isClosed()) {
            log.warn("Cannot send shutdown — not connected to server");
            return;
        }
        try {
            var out = new DataOutputStream(socket.getOutputStream());
            out.writeByte(SHUTDOWN);
            out.flush();
            log.info("Shutdown signal sent to server");
        } catch (IOException e) {
            log.warn("Failed to send shutdown signal: {}", e.getMessage());
        }
    }

    private void closeSocket() {
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

                // Step 1: read available dirs advertised by the server
                List<String> availableDirs = readAvailableDirs(in);
                log.info("Server advertises {} dir(s): {}", availableDirs.size(), availableDirs);

                // Step 2: decide which dirs to subscribe to.
                // If client has no dirs configured, print what the server offers and wait
                // for the user to subscribe via CLI — without dropping the connection.
                List<String> configuredDirs = clientProperties.getDirs();
                if (configuredDirs.isEmpty()) {
                    printAvailableDirs(availableDirs);
                    while (running && clientProperties.getDirs().isEmpty()) {
                        sleep(2_000);
                    }
                    configuredDirs = clientProperties.getDirs();
                }

                List<String> dirsToSync = availableDirs.stream()
                        .filter(configuredDirs::contains)
                        .toList();

                if (dirsToSync.isEmpty()) {
                    log.warn("No matching dirs between server and client config. Server has: {}, client wants: {}",
                            availableDirs, configuredDirs);
                }

                // Step 3: register dirs locally, audit for missing files, then send handshake
                registerDirs(dirsToSync);
                auditMissingFiles(dirsToSync);
                sendHandshake(out, dirsToSync);
                receiveLoop(in, out);

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

    private void printAvailableDirs(List<String> availableDirs) {
        System.out.println();
        System.out.println("  Server has the following directories available:");
        System.out.println("  -----------------------------------------------");
        if (availableDirs.isEmpty()) {
            System.out.println("  (none)");
        } else {
            availableDirs.forEach(d -> System.out.println("  - " + d));
        }
        System.out.println();
        System.out.println("  Use 'add <name>' in the CLI to subscribe, then sync will begin automatically.");
        System.out.println();
    }

    /**
     * Reads the list of available root dir names advertised by the server.
     * Wire format:
     * [4 bytes] number of dirs (int)
     * per dir:
     * [4 bytes] name length (int)
     * [N bytes] name (UTF-8)
     */
    private List<String> readAvailableDirs(DataInputStream in) throws IOException {
        int count = in.readInt();
        List<String> dirs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int len = in.readInt();
            dirs.add(new String(in.readNBytes(len), StandardCharsets.UTF_8));
        }
        return dirs;
    }

    /**
     * Registers dirs in local sync_state if not already present.
     * Called on every connect so newly discovered dirs get a -1 starting version.
     */
    private void registerDirs(List<String> dirs) {
        for (String dirName : dirs) {
            syncStateRepository.registerIfAbsent(dirName);
        }
    }

    /**
     * Audits the mirror directory against the DB records of synced files.
     * If any previously-synced file is missing from disk, the dir's last_sync_version
     * is reset to -1 so the server re-sends those files. Only the missing file records
     * are removed — files still on disk keep their records.
     */
    private void auditMissingFiles(List<String> dirs) {
        Path mirrorRoot = Path.of(clientProperties.getMirrorDir());
        for (String dirName : dirs) {
            List<String> recorded = syncedFileRepository.findAllByDir(dirName);
            List<String> missing = recorded.stream()
                    .filter(rel -> !Files.exists(mirrorRoot.resolve(rel)))
                    .toList();
            if (!missing.isEmpty()) {
                log.warn("Dir '{}': {} file(s) missing from disk — resetting sync version to force re-sync: {}",
                        dirName, missing.size(), missing);
                syncStateRepository.reset(dirName);
                missing.forEach(rel -> syncedFileRepository.delete(dirName, rel));
            }
        }
    }

    /**
     * Writes the handshake to the server:
     * [4 bytes] number of dirs
     * per dir:
     * [4 bytes] dir name length (int)
     * [N bytes] dir name (UTF-8)
     * [8 bytes] lastSyncVersion
     */
    private void sendHandshake(DataOutputStream out, List<String> dirsToSync) throws IOException {
        Set<String> syncSet = new HashSet<>(dirsToSync);
        List<SyncHandshakeEntry> entries = syncStateRepository.findAll().stream()
                .filter(e -> syncSet.contains(e.dirName()))
                .toList();

        out.writeInt(entries.size());
        for (SyncHandshakeEntry entry : entries) {
            byte[] nameBytes = entry.dirName().getBytes(StandardCharsets.UTF_8);
            out.writeInt(nameBytes.length);
            out.write(nameBytes);
            out.writeLong(entry.lastSyncVersion());
        }
        out.flush();
        log.info("Handshake sent: {} dir(s): {}", entries.size(), dirsToSync);
    }

    private void receiveLoop(DataInputStream in, DataOutputStream out) throws IOException {
        while (running) {
            byte eventType = in.readByte();

            if (eventType == RESUME_SERVER_MODE) {
                log.info("Resume-server-mode signal received from server — exiting with code 2 to restart as server");
                System.exit(2);
            }

            int pathLen = in.readInt();
            String relPath = new String(in.readNBytes(pathLen), StandardCharsets.UTF_8);
            long syncVersion = in.readLong();
            long fileSize = in.readLong();

            Path target = Path.of(clientProperties.getMirrorDir()).resolve(relPath).normalize();
            // first component of relPath is the dir name (e.g. "photos" from "photos/img.jpg")
            String dirName = Path.of(relPath).getName(0).toString();

            if (eventType == EVENT_DELETE) {
                Files.deleteIfExists(target);
                syncedFileRepository.delete(dirName, relPath);
                log.info("Deleted: {}", relPath);
            } else {
                byte[] fileBytes = in.readNBytes((int) fileSize);
                Files.createDirectories(target.getParent());
                Files.write(target, fileBytes);
                syncedFileRepository.upsert(dirName, relPath);
                log.info("Written: {} ({} bytes, v{})", relPath, fileSize, syncVersion);
            }

            // Persist local state first, then ACK the server
            syncStateRepository.upsert(dirName, syncVersion);

            out.writeByte(ACK);
            out.writeLong(syncVersion);
            out.flush();
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
}
