package com.fstojilj.luddite.sync.client.service;

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

/**
 * Establishes and maintains a persistent mTLS connection to the sync server,
 * receiving file-change events and applying them to the local mirror directory.
 *
 * <p>On startup the service:
 * <ol>
 *   <li>Reads the list of root directories advertised by the server.</li>
 *   <li>Waits (polling every 7 s) for the user to subscribe to at least one dir via the CLI
 *       if no dirs are configured yet.</li>
 *   <li>Purges stale local dirs, registers new ones, audits the mirror for missing files,
 *       and sends the handshake with the last-known sync versions.</li>
 *   <li>Enters a receive loop that processes {@code EVENT_WRITE} and {@code EVENT_DELETE}
 *       events, writes files to disk, and ACKs each event back to the server.</li>
 * </ol>
 *
 * <p>If the connection is lost the service automatically reconnects after a 5-second
 * back-off, resuming from the last persisted sync version.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ClientSyncService {

    private static final byte EVENT_WRITE = 1;
    private static final byte EVENT_DELETE = 2;
    private static final byte ACK = 3;
    private static final byte SHUTDOWN = 4;
    private static final byte RESUME_SERVER_MODE = 5;

    private final SyncStateService syncStateService;
    private final SyncedFileRepository syncedFileRepository;

    public static List<String> serverDirs = new ArrayList<>();

    @Value("${sync.server.host:localhost}")
    private String serverHost;

    @Value("${sync.server.port:8888}")
    private int serverPort;

    @Value("${sync.client.mirror-dir}")
    private String mirrorDir;

    @Value("${sync.socket.keystore:classpath:client-keystore.p12}")
    private Resource keystoreResource;

    @Value("${sync.socket.truststore:classpath:truststore.p12}")
    private Resource truststoreResource;

    @Value("${sync.socket.password}")
    private String keystorePassword;

    private volatile boolean running = false;
    private SSLSocket socket;

    /**
     * Starts the virtual thread that drives the connect-and-sync loop.
     * Invoked automatically by Spring after dependency injection.
     */
    @PostConstruct
    public void start() {
        running = true;
        Thread.ofVirtual().name("server-sync-receiver").start(this::connectAndSync);
    }

    /**
     * Signals the receive loop to stop and closes the underlying SSL socket.
     * Invoked automatically by Spring during application shutdown.
     */
    @PreDestroy
    public void stop() {
        running = false;
        closeSocket();
    }

    /**
     * Drops the current connection so the sync loop reconnects immediately,
     * re-reading the list of available directories from the server.
     * Intended to be called by the CLI {@code refresh} command.
     */
    public void reconnect() {
        log.info("Reconnect requested — dropping current connection to re-poll server dirs");
        running = false;
        closeSocket();
    }

    /**
     * Sends a {@code SHUTDOWN} signal to the server over the existing mTLS socket,
     * asking it to terminate. Use this from the client site when you need to remotely
     * stop the server process.
     *
     * <p>If the socket is not currently connected, the call is a no-op and a warning
     * is logged.
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

    /**
     * Closes the SSL socket, suppressing any {@link IOException} that may occur
     * during the close operation.
     */
    private void closeSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            log.warn("Error closing socket", e);
        }
    }

    /**
     * Main sync loop: connects to the server, performs the handshake, and then
     * delegates to {@link #receiveLoop} to process incoming events.
     * Reconnects automatically on {@link IOException} with a 5-second back-off.
     */
    private void connectAndSync() {
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
                List<String> configuredDirs = syncStateService.retrieveAllInSyncDirs();
                if (configuredDirs.isEmpty()) {
                    printAvailableDirs(availableDirs);
                    while (running) {
                        sleep(7_000);

                        configuredDirs = syncStateService.retrieveAllInSyncDirs();
                        if (!configuredDirs.isEmpty()) {
                            break;
                        }
                    }
                }

                List<String> staleDirs = getStaleDirs(availableDirs, configuredDirs);
                syncStateService.removeStaleDirs(staleDirs);

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

    /**
     * Prints the list of directories available on the server to stdout, along with
     * instructions for subscribing via the CLI.
     *
     * @param availableDirs directory names advertised by the server
     */
    private static void printAvailableDirs(List<String> availableDirs) {
        System.out.println();
        System.out.println("  Server has the following directories available:");
        System.out.println("  -----------------------------------------------");
        if (availableDirs.isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (int i = 1; i <= availableDirs.size(); i++) {
                System.out.printf("  %d. %s%n", i, availableDirs.get(i - 1));
            }
            serverDirs = availableDirs;
        }
        System.out.println();
        System.out.println("  Use add command with dir indices");
        System.out.println("  e.g. 'add 1' or 'add 2,3' to subscribe to the first dir, or the second and third dirs.");
        System.out.println();
    }

    /**
     * Reads the list of available root directory names advertised by the server.
     *
     * <p>Wire format:
     * <pre>
     * [4 bytes] number of dirs (int)
     * per dir:
     *   [4 bytes] name length (int)
     *   [N bytes] name (UTF-8)
     * </pre>
     *
     * @param in the data input stream connected to the server
     * @return list of directory names
     * @throws IOException if reading from the stream fails
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
     * Registers each directory in the local sync state (with a {@code -1} starting version)
     * if it is not already present. Safe to call on every connect.
     *
     * @param dirs list of directory names to register
     */
    private void registerDirs(List<String> dirs) {
        for (String dirName : dirs) {
            syncStateService.registerIfAbsent(dirName);
        }
    }

    /**
     * Audits the local mirror against the database of synced-file records.
     * Any file that was previously acknowledged but is no longer present on disk causes the
     * entire directory's sync version to be reset to {@code -1}, so the server re-sends the
     * missing files on the next connection. The stale DB records for the missing files are
     * also removed.
     *
     * @param dirs list of directory names to audit
     */
    private void auditMissingFiles(List<String> dirs) {
        Path mirrorRoot = Path.of(mirrorDir);
        for (String dirName : dirs) {
            List<String> recorded = syncedFileRepository.findAllByDir(dirName);
            List<String> missing = recorded.stream()
                    .filter(rel -> !Files.exists(mirrorRoot.resolve(rel)))
                    .toList();
            if (!missing.isEmpty()) {
                log.warn("Dir '{}': {} file(s) missing from disk — resetting sync version to force re-sync: {}",
                        dirName, missing.size(), missing);
                syncStateService.resetSyncVersionForDir(dirName);
                missing.forEach(rel -> syncedFileRepository.delete(dirName, rel));
            }
        }
    }

    /**
     * Sends the subscription handshake to the server.
     *
     * <p>Wire format:
     * <pre>
     * [4 bytes] number of entries (int)
     * per entry:
     *   [4 bytes] dir name length (int)
     *   [N bytes] dir name (UTF-8)
     *   [8 bytes] lastSyncVersion (long)
     * </pre>
     *
     * @param out        the data output stream connected to the server
     * @param dirsToSync the directories the client wants to subscribe to
     * @throws IOException if writing to the stream fails
     */
    private void sendHandshake(DataOutputStream out, List<String> dirsToSync) throws IOException {
        Set<String> syncSet = new HashSet<>(dirsToSync);
        List<SyncHandshakeEntry> entries = syncStateService.findAll().stream()
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

    /**
     * Processes incoming events from the server until the connection is lost or
     * {@link #running} is set to {@code false}.
     *
     * <p>For each event the method:
     * <ol>
     *   <li>Reads the event type, path, sync version, and file-size header.</li>
     *   <li>Writes or deletes the file in the mirror directory.</li>
     *   <li>Persists the new sync version locally.</li>
     *   <li>Sends an {@code ACK} with the sync version back to the server.</li>
     * </ol>
     *
     * @param in  the data input stream connected to the server
     * @param out the data output stream connected to the server
     * @throws IOException if reading from or writing to the stream fails
     */
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

            Path target = Path.of(mirrorDir).resolve(relPath).normalize();
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
            syncStateService.updateSyncVersion(dirName, syncVersion);

            out.writeByte(ACK);
            out.writeLong(syncVersion);
            out.flush();
        }
    }

    /**
     * Builds a mutually-authenticated TLS socket connected to the configured server host and port.
     * Loads both the client keystore (for client-auth) and the truststore (for server verification)
     * from the configured resources.
     *
     * @return a connected {@link SSLSocket}
     * @throws Exception if the SSL context cannot be built or the connection fails
     */
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

    /**
     * Sleeps for the given number of milliseconds, restoring the interrupt flag if interrupted.
     *
     * @param millis time to sleep in milliseconds
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the directories that the client is configured to track but that are
     * no longer offered by the server. These should be purged from the local mirror.
     *
     * @param availableServerDirs directories currently advertised by the server
     * @param clientListeningDirs directories the client is currently tracking
     * @return list of directory names present on the client but absent from the server
     */
    private List<String> getStaleDirs(List<String> availableServerDirs, List<String> clientListeningDirs) {
        return clientListeningDirs.stream()
                .filter(dir -> !availableServerDirs.contains(dir))
                .toList();
    }
}
