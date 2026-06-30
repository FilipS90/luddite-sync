package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.common.model.SocketOperation;
import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import com.fstojilj.luddite.sync.common.util.PasswordUtils;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Establishes and maintains a persistent connection to the sync server,
 * polling for file-change events every 2 seconds and applying them to the local
 * mirror directory.
 *
 * <h2>Connection lifecycle</h2>
 * <ol>
 *   <li>HTTP phase: fetches public dirs, authenticates private dirs (populating
 *       the server's {@code AuthCacheService}), and builds a {@code List<SocketOperation>}.</li>
 *   <li>Opens the socket, sends the stable {@code clientId}, then executes operations.</li>
 *   <li>Enters a poll loop that every 2 s sends a {@code SYNC} request per subscribed
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
@Order(2)
public class ClientSyncService implements ApplicationRunner {

    private static final byte SYNC       = 0x01;
    private static final byte FILE       = 0x02;
    private static final byte DELETE_ACK = 0x03;

    private static final byte FLAG_DELETED = 0x01;

    private static final long POLL_INTERVAL_MS = 2_000;

    private final RootDirService rootDirService;
    private final FileMetadataService fileMetadataService;
    private final ClientIdService clientIdService;
    private final ServerApiClient serverApiClient;

    /**
     * Dirs currently advertised by the server — exposed for the CLI {@code add} command
     * and the UI refresh loop. Updated via HTTP on each connect cycle.
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

    @Value("${sync.socket.tls-enabled:true}")
    private boolean tlsEnabled;

    private volatile boolean running = false;
    private Socket socket;

    /**
     * Stores the most recent PRIVATE_AUTH result per directory name.
     * {@code true} = granted, {@code false} = denied.
     * Populated during each connect cycle; read by the UI to show access-denied popups.
     */
    private final ConcurrentHashMap<String, Boolean> privateAuthResults = new ConcurrentHashMap<>();

    /**
     * Starts the sync loop after the schema has been initialized.
     * Runs as {@link ApplicationRunner} with {@code @Order(2)}, after
     * {@code SchemaInitializer} ({@code @Order(1)}) has created all tables.
     */
    @Override
    public void run(ApplicationArguments args) {
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
     * Returns {@code true} if the SSL socket is currently open and connected.
     */
    public boolean isConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected();
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
     * Main sync loop: performs HTTP negotiation, opens the socket, sends the clientId,
     * and executes the list of operations. Reconnects automatically on {@link IOException}
     * with a 5-second back-off.
     */
    private void connectAndSync() {
        while (running) {
            try {
                // HTTP phase — all negotiation before opening the socket
                List<String> serverPublicDirs = serverApiClient.fetchPublicDirs();
                serverDirs = serverPublicDirs;
                log.info("Server advertises {} public dir(s): {}", serverPublicDirs.size(), serverPublicDirs);

                // Wait for user to subscribe if nothing configured yet
                List<String> clientListeningDirs = rootDirService.retrieveAllInSyncDirs();
                printAvailableDirs(serverPublicDirs);
                if (clientListeningDirs.isEmpty()) {
                    while (running) {
                        sleep(7_000);
                        clientListeningDirs = rootDirService.retrieveAllInSyncDirs();
                        if (!clientListeningDirs.isEmpty()) break;
                    }
                }

                // Purge stale public dirs
                List<String> privateDirNames = rootDirService.findAllPrivate().stream()
                        .map(e -> e[0])
                        .toList();
                removeStaleDirectories(serverPublicDirs, clientListeningDirs, privateDirNames);

                // Authenticate private dirs via HTTP — server populates AuthCacheService
                String clientId = clientIdService.getClientId();
                List<String> privateDirsToSync = authenticatePrivateDirsViaHttp(clientId);

                // Build SocketOperation list
                List<String> publicDirsToSync = serverPublicDirs.stream()
                        .filter(clientListeningDirs::contains)
                        .toList();

                List<SocketOperation> operations = buildOperations(publicDirsToSync, privateDirsToSync);

                if (operations.isEmpty()) {
                    log.warn("No operations to execute after HTTP negotiation — waiting before retry");
                    sleep(5_000);
                    continue;
                }

                registerDirs(Stream.concat(publicDirsToSync.stream(), privateDirsToSync.stream()).toList());
                auditMissingFiles(Stream.concat(publicDirsToSync.stream(), privateDirsToSync.stream()).toList());

                // Open socket once, send clientId, execute operations
                socket = buildSocket();
                log.info("Connected to server {}:{}", serverHost, serverPort);

                var out = new DataOutputStream(socket.getOutputStream());
                var in = new DataInputStream(socket.getInputStream());

                sendClientId(out, clientId);

                executeOperations(in, out, operations);

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

    private void removeStaleDirectories(List<String> serverServedDirs, List<String> clientListeningDirs,
                                        List<String> privateDirNames) {
        List<String> staleDirs = getStaleDirs(serverServedDirs, clientListeningDirs, privateDirNames);
        rootDirService.removeStaleDirs(staleDirs);
    }

    /**
     * Authenticates all locally stored private directories via HTTP and returns
     * those granted by the server. The server populates its {@code AuthCacheService}
     * on each successful auth so the socket service can verify access.
     *
     * @param clientId the client's stable identifier sent with each auth request
     * @return list of private directory names the server granted access to
     */
    private List<String> authenticatePrivateDirsViaHttp(String clientId) {
        List<String[]> stored = rootDirService.findAllPrivate();
        List<String> granted = new ArrayList<>();
        for (String[] entry : stored) {
            String dirName = entry[0];
            String hash = entry[1];
            if (hash == null) continue;
            boolean ok = serverApiClient.authenticate(clientId, dirName, hash);
            privateAuthResults.put(dirName, ok);
            if (ok) {
                granted.add(dirName);
                log.info("HTTP auth for private dir '{}': GRANTED", dirName);
            } else {
                rootDirService.remove(dirName);
                log.warn("HTTP auth for private dir '{}': DENIED — removing", dirName);
            }
        }
        return granted;
    }

    /**
     * Builds the list of {@link SocketOperation}s to execute for this connect cycle.
     * Downloads (feature #18) are placed first; continuous syncs follow.
     *
     * @param publicDirs  public directory names to sync
     * @param privateDirs private directory names that have been authorized
     * @return ordered list of operations to execute over the socket
     */
    private List<SocketOperation> buildOperations(List<String> publicDirs, List<String> privateDirs) {
        return Stream.concat(publicDirs.stream(), privateDirs.stream())
                .map(SocketOperation.Sync::new)
                .collect(Collectors.toList());
    }

    /**
     * Sends the client's stable client ID to the server immediately after the socket
     * connection is established.
     *
     * <p>Wire format: {@code [4 bytes] id length, [N bytes] id (UTF-8)}
     *
     * @param out      the server output stream
     * @param clientId the stable client identifier to send
     * @throws IOException if writing fails
     */
    private void sendClientId(DataOutputStream out, String clientId) throws IOException {
        byte[] idBytes = clientId.getBytes(StandardCharsets.UTF_8);
        out.writeInt(idBytes.length);
        out.write(idBytes);
        out.flush();
        log.info("Sent clientId to server: {}", clientId);
    }

    /**
     * Executes all operations in order: downloads first, then enters the continuous
     * sync poll loop for all SYNC operations.
     *
     * @param in         the server input stream
     * @param out        the server output stream
     * @param operations the list of operations to execute
     * @throws IOException if any IO operation fails
     */
    private void executeOperations(DataInputStream in, DataOutputStream out,
                                   List<SocketOperation> operations) throws IOException {
        for (SocketOperation op : operations) {
            if (op instanceof SocketOperation.Download download) {
                executeDownload(in, out, download);
            }
        }
        List<String> syncDirs = operations.stream()
                .filter(op -> op instanceof SocketOperation.Sync)
                .map(op -> ((SocketOperation.Sync) op).dirName())
                .toList();
        if (!syncDirs.isEmpty()) {
            pollLoop(in, out, syncDirs);
        }
    }

    /**
     * Stub for the one-time file download operation (feature #18, not yet implemented).
     *
     * @param in       the server input stream
     * @param out      the server output stream
     * @param download the download operation to execute
     * @throws IOException if any IO operation fails
     */
    private void executeDownload(DataInputStream in, DataOutputStream out,
                                 SocketOperation.Download download) throws IOException {
        log.info("Download requested for '{}' — not yet implemented", download.qualifiedPath());
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
     * Polls the server every {@value #POLL_INTERVAL_MS} ms for each subscribed directory.
     * For each directory:
     * <ol>
     *   <li>Sends a {@code SYNC} request with the last known sync version.</li>
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

                // Send sync request
                byte[] nameBytes = dirName.getBytes(StandardCharsets.UTF_8);
                out.writeByte(SYNC);
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
                    long fileSizeBytes = in.readLong();

                    boolean deleted = (flags & FLAG_DELETED) != 0;

                    Path target = mirrorRoot.resolve(relPath).normalize();

                    // Reject any path whose canonical form is not a descendant of mirrorRoot.
                    if (!target.startsWith(mirrorRoot)) {
                        log.error("Path traversal blocked — server sent path outside mirror dir: '{}'", relPath);
                        // Must drain bytes from stream to keep it in sync before continuing
                        if (!deleted && fileSizeBytes > 0) {
                            in.skipNBytes(fileSizeBytes);
                        }
                        continue;
                    }

                    if (deleted) {
                        // Delete from disk first, then purge DB record (disk-only delete already done here,
                        // so use purgeRecord not removeRecord to avoid a second disk delete attempt)
                        Files.deleteIfExists(target);
                        fileMetadataService.purgeRecord(dirName, relPath);
                        log.info("Deleted: {}", relPath);

                        // ACK the delete so server can remove from client_ids
                        byte[] ackPathBytes = relPath.getBytes(StandardCharsets.UTF_8);
                        out.writeByte(DELETE_ACK);
                        out.writeInt(ackPathBytes.length);
                        out.write(ackPathBytes);
                        out.flush();
                    } else {
                        Files.createDirectories(target.getParent());
                        if (fileSizeBytes <= 33L * 1024 * 1024) {
                            // Small file — read whole into memory, write at once
                            byte[] fileBytes = in.readNBytes((int) fileSizeBytes);
                            Files.write(target, fileBytes);
                        } else {
                            // Large file — read in 33 MB chunks, stream directly to disk
                            try (var fileOut = Files.newOutputStream(target)) {
                                byte[] buf = new byte[33 * 1024 * 1024];
                                long remaining = fileSizeBytes;
                                while (remaining > 0) {
                                    int toRead = (int) Math.min(buf.length, remaining);
                                    int read = in.readNBytes(buf, 0, toRead); // reads exactly toRead bytes
                                    fileOut.write(buf, 0, read);
                                    remaining -= read;
                                }
                            }
                        }
                        fileMetadataService.recordSynced(dirName, relPath);
                        String fileSizeMb = String.format("%.2f", (double) fileSizeBytes / (1024 * 1024));
                        log.info("Written: {} ({} MB, v{})", relPath, fileSizeMb, syncVersion);
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
                log.warn("Unexpected byte from server outside poll: {}", signal);
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
        }
        System.out.println();
        System.out.println("  Use the 'add' command with dir indices, e.g. 'add 1' or 'add 2,3'");
        System.out.println();
    }

    /**
     * Returns directories the client is tracking that are no longer offered by the server,
     * excluding private dirs (they are never advertised so must never be treated as stale).
     *
     * @param availableServerDirs directories currently advertised by the server
     * @param clientListeningDirs directories the client is currently tracking
     * @param privateDirNames     private dir names to exclude from stale check
     * @return list of directory names to purge
     */
    private List<String> getStaleDirs(List<String> availableServerDirs,
                                      List<String> clientListeningDirs,
                                      List<String> privateDirNames) {
        return clientListeningDirs.stream()
                .filter(dir -> !availableServerDirs.contains(dir))
                .filter(dir -> !privateDirNames.contains(dir))
                .toList();
    }

    /**
     * Requests access to a private directory from the currently connected server.
     * Stores credentials locally on success so reconnects re-authenticate automatically.
     *
     * <p>This method is called from the UI thread via a SwingWorker and therefore
     * triggers a {@link #reconnect()} rather than writing to the live socket directly
     * (which the poll loop owns). The result of the auth attempt during reconnect is
     * available via {@link #getPrivateAuthResults()}.
     *
     * @param dirName       the private directory name
     * @param plainPassword the plain-text password entered by the user
     */
    public void requestPrivateDir(String dirName, String plainPassword) {
        String hash = PasswordUtils.hash(plainPassword);
        rootDirService.registerPrivateDir(dirName, hash);
        // Clear any previous result so the UI can detect a fresh one after reconnect
        privateAuthResults.remove(dirName);
        reconnect();
    }

    /**
     * Returns an immutable snapshot of the most recent PRIVATE_AUTH results,
     * keyed by directory name. {@code true} = granted, {@code false} = denied.
     *
     * @return copy of current auth results
     */
    public Map<String, Boolean> getPrivateAuthResults() {
        return Map.copyOf(privateAuthResults);
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
     * Builds a socket connected to the configured server.
     * When {@code sync.socket.tls-enabled} is {@code true} (default), builds a
     * mutually-authenticated TLS socket; otherwise builds a plain TCP socket.
     *
     * @return a connected {@link Socket}
     * @throws Exception if the connection fails
     */
    private Socket buildSocket() throws Exception {
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
