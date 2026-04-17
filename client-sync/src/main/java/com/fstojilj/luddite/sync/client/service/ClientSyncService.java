package com.fstojilj.luddite.sync.client.service;

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

/**
 * Establishes and maintains a persistent mTLS connection to the sync server,
 * polling for file-change events every 2 seconds and applying them to the local
 * mirror directory.
 *
 * <h2>Connection lifecycle</h2>
 * <ol>
 *   <li>Reads the list of root directories advertised by the server.</li>
 *   <li>Waits (polling every 7 s) for the user to subscribe to at least one dir
 *       via the CLI if no dirs are configured yet.</li>
 *   <li>Purges stale local dirs, registers new ones, audits the mirror for missing
 *       files, then sends the stable {@code hardwareId} followed by the subscription
 *       handshake with last-known sync versions.</li>
 *   <li>Enters a poll loop that every 2 s sends a {@code POLL} request per subscribed
 *       directory, reads the response, writes/deletes files on disk, and sends a
 *       {@code DELETE_ACK} for each soft-deleted file received.</li>
 * </ol>
 *
 * <p>If the connection is lost the service automatically reconnects after a 5-second
 * back-off, resuming from the last persisted sync version.
 *
 * <h2>Poll response wire format</h2>
 * <pre>
 * [4 bytes] record count (int)
 * per record:
 *   [1 byte]  flags  — bit 0 = deleted
 *   [4 bytes] path length (int)
 *   [N bytes] qualified path (UTF-8, "dirName/relativePath")
 *   [8 bytes] syncVersion (long)
 *   [8 bytes] file size in bytes (long; 0 for deletes)
 *   [M bytes] file content (absent for deletes)
 * </pre>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ClientSyncService {

    private static final byte POLL = 1;
    private static final byte DELETE_ACK = 2;
    private static final byte SHUTDOWN = 3;
    private static final byte RESUME_SERVER_MODE = 4;

    private static final byte FLAG_DELETED = 0x01;

    private static final long POLL_INTERVAL_MS = 2_000;
    private static final long MAX_FILE_SIZE = Integer.MAX_VALUE; // ~2 GB

    private final RootDirService rootDirService;
    private final FileMetadataService fileMetadataService;
    private final HardwareIdService hardwareIdService;

    /**
     * Dirs currently advertised by the server — exposed for the CLI {@code add} command.
     */
    public static List<String> serverDirs = new ArrayList<>();

    @Value("${sync.server.host}")
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
        Thread.ofPlatform().name("server-sync-receiver").daemon(false).start(this::connectAndSync);
    }

    /**
     * Signals the poll loop to stop and closes the underlying SSL socket.
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
        // Do NOT set running=false — that exits the loop entirely.
        // Closing the socket causes an IOException in connectAndSync which triggers a reconnect.
        closeSocket();
    }

    /**
     * Sends a {@code SHUTDOWN} signal to the server over the existing mTLS socket,
     * asking it to terminate.
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
     * Main sync loop: connects to the server, performs the handshake, and then
     * enters the poll loop. Reconnects automatically on {@link IOException} with
     * a 5-second back-off.
     */
    private void connectAndSync() {
        while (running) {
            try {
                socket = buildSslSocket();
                log.info("Connected to server {}:{}", serverHost, serverPort);

                var out = new DataOutputStream(socket.getOutputStream());
                var in = new DataInputStream(socket.getInputStream());

                // 1 — read available dirs advertised by the server
                List<String> serverServedDirs = readAvailableDirs(in);
                log.info("Server advertises {} dir(s): {}", serverServedDirs.size(), serverServedDirs);

                // 2 — wait for the user to subscribe if nothing is configured yet
                List<String> clientListeningDirs = rootDirService.retrieveAllInSyncDirs();
                if (clientListeningDirs.isEmpty()) {
                    printAvailableDirs(serverServedDirs);
                    while (running) {
                        sleep(7_000);
                        clientListeningDirs = rootDirService.retrieveAllInSyncDirs();
                        if (!clientListeningDirs.isEmpty()) break;
                    }
                }

                removeStaleDirectories(serverServedDirs, clientListeningDirs);
                List<String> dirsToSync = serverServedDirs.stream()
                        .filter(clientListeningDirs::contains)
                        .toList();

                if (dirsToSync.isEmpty()) {
                    log.warn("No matching dirs between server and client config. Server: {}, client wants: {}",
                            serverServedDirs, clientListeningDirs);
                    continue;
                }

                registerDirs(dirsToSync);
                auditMissingFiles(dirsToSync);
                sendHardwareId(out);
                sendHandshake(out, dirsToSync);
                pollLoop(in, out, dirsToSync);

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
     * Reads the list of root directory names advertised by the server.
     *
     * <p>Wire format:
     * <pre>
     * [4 bytes] count
     * per dir:
     *   [4 bytes] name length
     *   [N bytes] name (UTF-8)
     * </pre>
     *
     * @param in the server input stream
     * @return list of directory names
     * @throws IOException if reading fails
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

    private void removeStaleDirectories(List<String> serverServedDirs, List<String> clientListeningDirs) {
        List<String> staleDirs = getStaleDirs(serverServedDirs, clientListeningDirs);
        rootDirService.removeStaleDirs(staleDirs);
    }

    /**
     * Sends the client's stable hardware ID to the server immediately after the
     * available-dirs advertisement.
     *
     * <p>Wire format: {@code [4 bytes] id length, [N bytes] id (UTF-8)}
     *
     * @param out the server output stream
     * @throws IOException if writing fails
     */
    private void sendHardwareId(DataOutputStream out) throws IOException {
        byte[] idBytes = hardwareIdService.getHardwareId().getBytes(StandardCharsets.UTF_8);
        out.writeInt(idBytes.length);
        out.write(idBytes);
        out.flush();
        log.info("Sent hardwareId to server: {}", hardwareIdService.getHardwareId());
    }

    /**
     * Registers each directory in the local sync state with a {@code -1} starting version
     * if it is not already present. Safe to call on every connect.
     *
     * @param dirs list of directory names to register
     */
    private void registerDirs(List<String> dirs) {
        dirs.forEach(rootDirService::registerIfAbsent);
    }

    /**
     * Audits the local mirror against the database of synced-file records.
     * Any file previously acknowledged but no longer present on disk causes the
     * directory's sync version to be reset to {@code -1} so the server re-sends it.
     *
     * @param dirs list of directory names to audit
     */
    private void auditMissingFiles(List<String> dirs) {
        Path mirrorRoot = Path.of(mirrorDir);
        for (String dirName : dirs) {
            Path dirPath = mirrorRoot.resolve(dirName);

            // If the entire directory is absent, reset everything for this dir
            if (!Files.exists(dirPath)) {
                log.warn("Dir '{}': mirror directory missing entirely — resetting sync version", dirName);
                rootDirService.resetSyncVersionForDir(dirName);
                fileMetadataService.findAllByDir(dirName)
                        .forEach(rel -> fileMetadataService.purgeRecord(dirName, rel));
                continue;
            }

            // Walk all recorded paths (includes subdirectory files) and find missing ones
            List<String> recorded = fileMetadataService.findAllByDir(dirName);
            List<String> missing = recorded.stream()
                    .filter(rel -> {
                        // rel is a qualified path like "dirName/subdir/file.txt"
                        // resolve it under mirrorRoot to get the full path
                        Path filePath = mirrorRoot.resolve(rel).normalize();
                        return !Files.exists(filePath);
                    })
                    .toList();

            if (!missing.isEmpty()) {
                log.warn("Dir '{}': {} file(s) missing from disk (including subdirs) — resetting sync version: {}",
                        dirName, missing.size(), missing);
                rootDirService.resetSyncVersionForDir(dirName);
                missing.forEach(rel -> fileMetadataService.purgeRecord(dirName, rel));
            }
        }
    }

    /**
     * Sends the subscription handshake to the server.
     *
     * <p>Wire format:
     * <pre>
     * [4 bytes] count
     * per entry:
     *   [4 bytes] name length
     *   [N bytes] name (UTF-8)
     *   [8 bytes] lastSyncVersion (long)
     * </pre>
     *
     * @param out        the server output stream
     * @param dirsToSync directories to subscribe to
     * @throws IOException if writing fails
     */
    private void sendHandshake(DataOutputStream out, List<String> dirsToSync) throws IOException {
        var syncSet = new HashSet<>(dirsToSync);
        List<SyncHandshakeEntry> entries = rootDirService.findAll().stream()
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
     * Polls the server every {@value #POLL_INTERVAL_MS} ms for each subscribed directory.
     * For each directory:
     * <ol>
     *   <li>Sends a {@code POLL} request with the last known sync version.</li>
     *   <li>Reads the response records.</li>
     *   <li>Writes live files to disk or deletes soft-deleted files.</li>
     *   <li>Sends a {@code DELETE_ACK} for each deleted file.</li>
     *   <li>Persists the highest received {@code syncVersion}.</li>
     * </ol>
     *
     * @param in         the server input stream
     * @param out        the server output stream
     * @param dirsToSync the directories to poll
     * @throws IOException if reading or writing fails (triggers reconnect)
     */
    private void pollLoop(DataInputStream in, DataOutputStream out,
                          List<String> dirsToSync) throws IOException {
        while (running) {
            for (String dirName : dirsToSync) {
                long lastVersion = rootDirService.findAll().stream()
                        .filter(e -> e.dirName().equals(dirName))
                        .mapToLong(SyncHandshakeEntry::lastSyncVersion)
                        .findFirst()
                        .orElse(-1L);

                // Send poll request
                byte[] nameBytes = dirName.getBytes(StandardCharsets.UTF_8);
                out.writeByte(POLL);
                out.writeInt(nameBytes.length);
                out.write(nameBytes);
                out.writeLong(lastVersion);
                out.flush();

                // Read response
                int count = in.readInt();
                long highestVersion = lastVersion;

                Path mirrorRoot = Path.of(mirrorDir).toAbsolutePath().normalize();

                for (int i = 0; i < count; i++) {
                    byte flags = in.readByte();
                    int pathLen = in.readInt();
                    String relPath = new String(in.readNBytes(pathLen), StandardCharsets.UTF_8);
                    relPath = adjustFilePathToClientOS(relPath);
                    long syncVersion = in.readLong();
                    long fileSize = in.readLong();

                    boolean deleted = (flags & FLAG_DELETED) != 0;

                    Path target = mirrorRoot.resolve(relPath).normalize();

                    // Reject any path whose canonical form is not a descendant of mirrorRoot.
                    if (!target.startsWith(mirrorRoot)) {
                        log.error("Path traversal blocked — server sent path outside mirror dir: '{}'", relPath);
                        // Must drain bytes from stream to keep it in sync before continuing
                        if (!deleted && fileSize > 0) {
                            in.skipNBytes(fileSize);
                        }
                        continue;
                    }

                    // dirName is the first component of relPath
                    String dir = Path.of(relPath).getName(0).toString();

                    if (deleted) {
                        // Delete from disk first, then purge DB record (disk-only delete already done here,
                        // so use purgeRecord not removeRecord to avoid a second disk delete attempt)
                        Files.deleteIfExists(target);
                        fileMetadataService.purgeRecord(dir, relPath);
                        log.info("Deleted: {}", relPath);

                        // ACK the delete so server can remove from client_ids
                        byte[] ackPathBytes = relPath.getBytes(StandardCharsets.UTF_8);
                        out.writeByte(DELETE_ACK);
                        out.writeInt(ackPathBytes.length);
                        out.write(ackPathBytes);
                        out.flush();
                    } else {
                        if (fileSize < 0 || fileSize > MAX_FILE_SIZE) {
                            throw new IOException("Unreasonable file size from server (" + fileSize + " bytes) for: " + relPath);
                        }
                        byte[] fileBytes = in.readNBytes((int) fileSize);
                        Files.createDirectories(target.getParent());
                        Files.write(target, fileBytes);
                        fileMetadataService.recordSynced(dir, relPath);
                        log.info("Written: {} ({} bytes, v{})", relPath, fileSize, syncVersion);
                    }

                    if (syncVersion > highestVersion) highestVersion = syncVersion;
                }

                if (highestVersion > lastVersion) {
                    rootDirService.updateSyncVersion(dirName, highestVersion);
                }
            }

            // Check for server-initiated signals (non-blocking: peek at available bytes)
            if (in.available() > 0) {
                byte signal = in.readByte();
                if (signal == RESUME_SERVER_MODE) {
                    log.info("RESUME_SERVER_MODE received — exiting with code 2 to restart as server");
                    System.exit(2);
                } else {
                    log.warn("Unexpected byte from server outside poll: {}", signal);
                }
            }

            sleep(POLL_INTERVAL_MS);
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
        System.out.println("  Use the 'add' command with dir indices, e.g. 'add 1' or 'add 2,3'");
        System.out.println();
    }

    /**
     * Returns directories the client is tracking that are no longer offered by the server.
     *
     * @param availableServerDirs directories currently advertised by the server
     * @param clientListeningDirs directories the client is currently tracking
     * @return list of directory names to purge
     */
    private List<String> getStaleDirs(List<String> availableServerDirs,
                                      List<String> clientListeningDirs) {
        return clientListeningDirs.stream()
                .filter(dir -> !availableServerDirs.contains(dir))
                .toList();
    }

    /**
     * Closes the SSL socket, suppressing any {@link IOException}.
     */
    private void closeSocket() {
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            log.warn("Error closing socket", e);
        }
    }

    /**
     * Builds a mutually-authenticated TLS socket connected to the configured server.
     *
     * @return a connected {@link SSLSocket}
     * @throws Exception if the SSL context cannot be built or the connection fails
     */
    private SSLSocket buildSslSocket() throws Exception {
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

    private String adjustFilePathToClientOS(String relativePath) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            return relativePath.replace("/", "\\");
        }

        return relativePath.replace("\\", "/");
    }
}
