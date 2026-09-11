package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.client.event.ServerDirsAvailableEvent;
import com.fstojilj.luddite.sync.client.model.ClientRootDir;
import com.fstojilj.luddite.sync.client.repository.HostRepository;
import com.fstojilj.luddite.sync.common.util.PasswordUtils;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Establishes and maintains a persistent connection to the sync server,
 * polling for file-change events every 2 seconds and applying them to the local
 * mirror directory.
 *
 * <h2>Connection lifecycle</h2>
 * <ol>
 *   <li>HTTP phase: fetches public dirs, authenticates private dirs (populating
 *       the server's {@code AuthCacheService}), and resolves the list of directory names
 *       to sync.</li>
 *   <li>Opens the socket, sends the stable {@code clientId}, then enters the poll loop.</li>
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
@Order(3)
public class ClientSyncService implements ApplicationRunner {

    private static final byte SYNC = 0x01;
    private static final byte DELETE_ACK = 0x03;

    private static final byte FLAG_DELETED = 0x01;

    private static final long POLL_INTERVAL_MS = 2_000;

    private final RootDirService rootDirService;
    private final FileMetadataService fileMetadataService;
    private final ClientIdService clientIdService;
    private final ServerApiClient serverApiClient;
    private final ClientSocketFactory socketFactory;
    private final HostRepository hostRepository;

    /**
     * Dirs currently advertised by the server — exposed for the CLI {@code add} command
     * and the UI refresh loop. Updated via HTTP on each connect cycle.
     */
    public static List<String> serverDirs = new ArrayList<>();

    private volatile boolean running = false;

    /**
     * -- GETTER --
     * Returns the current state of the sync connection, explicitly tracked at each
     * transition point (connect, disconnect, and per-tick file transfer) rather than
     * derived from raw socket introspection — see
     * .
     */
    @Getter
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;

    private Socket socket;

    /**
     * Stores the most recent PRIVATE_AUTH result per directory name.
     * {@code true} = granted, {@code false} = denied.
     * Populated during each connect cycle; read by the UI to show access-denied popups.
     */
    private final ConcurrentHashMap<String, Boolean> privateAuthResults = new ConcurrentHashMap<>();

    private final ApplicationEventPublisher serverDirsEventPublisher;

    /**
     * Starts the sync loop after the schema has been initialized and the last-used host
     * has been restored. Runs as {@link ApplicationRunner} with {@code @Order(3)}, after
     * {@code SchemaInitializer} ({@code @Order(1)}) has created all tables and
     * {@code HostSettingsInitializer} ({@code @Order(2)}) has restored the host.
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
        connectionState = ConnectionState.DISCONNECTED;
        closeSocket();
    }

    /**
     * Returns {@code true} if the connection state is anything other than
     * {@link ConnectionState#DISCONNECTED}.
     */
    public boolean isConnected() {
        return connectionState != ConnectionState.DISCONNECTED;
    }

    /**
     * Drops the current connection so the sync loop reconnects immediately,
     * re-reading the list of available directories from the server.
     * Intended to be called by the CLI {@code refresh} command.
     */
    public void reconnect() {
        log.info("Reconnect requested — dropping current connection to re-poll server dirs");
        // Set state eagerly so the UI reflects the drop immediately, rather than waiting
        // for the background loop to observe the resulting IOException.
        connectionState = ConnectionState.DISCONNECTED;
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
                List<String> serverPublicDirs = serverApiClient.fetchPublicDirs();

                if (serverPublicDirs.isEmpty()) {
                    log.warn("Server returned no public directories — waiting 5s before retry");
                    sleep(15_000);
                    continue;
                }

                serverDirs = serverPublicDirs;
                log.info("Server advertises {} public dir(s): {}", serverPublicDirs.size(), serverPublicDirs);
                serverDirsEventPublisher.publishEvent(new ServerDirsAvailableEvent(serverPublicDirs));

                // Wait for user to subscribe if nothing configured yet
                List<String> clientListeningDirs = rootDirService.retrieveAllInSyncDirs();
                if (clientListeningDirs.isEmpty()) {
                    while (running) {
                        sleep(7_000);
                        clientListeningDirs = rootDirService.retrieveAllInSyncDirs();
                        if (!clientListeningDirs.isEmpty()) break;
                    }
                }

                List<String> privateDirNames = rootDirService.findAllPrivate().stream()
                        .map(e -> e[0])
                        .toList();
                removeStaleDirectories(serverPublicDirs, clientListeningDirs, privateDirNames);

                // Authenticate private dirs via HTTP — server populates AuthCacheService
                String clientId = clientIdService.getClientId();
                List<String> privateDirsToSync = authenticatePrivateDirsViaHttp(clientId);

                List<String> publicDirsToSync = serverPublicDirs.stream()
                        .filter(clientListeningDirs::contains)
                        .toList();

                List<String> dirsToSync = Stream.concat(publicDirsToSync.stream(), privateDirsToSync.stream())
                        .toList();

                if (dirsToSync.isEmpty()) {
                    log.warn("No directories to sync after HTTP negotiation — waiting before retry");
                    sleep(5_000);
                    continue;
                }

                registerDirs(dirsToSync);
                auditMissingFiles(dirsToSync);

                // Discover the server's current socket port (it may have been changed live via
                // the server's admin CLI) before opening the socket, and persist it — the DB is
                // the only record of "last known good port for this host", so this keeps it
                // fresh rather than ever going stale.
                int socketPort = serverApiClient.fetchSocketPort(socketFactory.getServerPort());
                socketFactory.setServerPort(socketPort);
                hostRepository.recordUsed(socketFactory.getServerHost(), socketPort);

                // Open socket once, send clientId, enter the poll loop
                socket = socketFactory.connect();
                log.info("Connected to server {}:{}", socketFactory.getServerHost(), socketFactory.getServerPort());

                var out = new DataOutputStream(socket.getOutputStream());
                var in = new DataInputStream(socket.getInputStream());

                sendClientId(out, clientId);
                connectionState = ConnectionState.IDLE;

                pollLoop(in, out, serverPublicDirs, privateDirsToSync);

            } catch (IOException e) {
                connectionState = ConnectionState.DISCONNECTED;
                if (running) {
                    log.warn("Connection lost: {}. Reconnecting in 5s...", e.getMessage());
                    sleep(5_000);
                }
            } catch (Exception e) {
                connectionState = ConnectionState.DISCONNECTED;
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
     * Registers each directory in the local sync state with a {@code -1} starting version
     * if it is not already present. Safe to call on every connect.
     *
     * @param dirs list of directory names to register
     */
    private void registerDirs(List<String> dirs) {
        dirs.forEach(rootDirService::registerWithDefaultPath);
    }

    /**
     * Audits the local mirror against the database of synced-file records.
     * Any file previously acknowledged but no longer present on disk causes the
     * directory's sync version to be reset to {@code -1} so the server re-sends it.
     *
     * @param dirs list of directory names to audit
     */
    private void auditMissingFiles(List<String> dirs) {
        for (String dirName : dirs) {
            Path dirBase = rootDirService.resolveLocalPath(dirName);

            // If the entire directory is absent, reset everything for this dir
            if (!Files.exists(dirBase)) {
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
                        // rel is a qualified path like "dirName/subdir/file.txt"; strip the
                        // dirName prefix and resolve the remainder under this dir's own base
                        Path filePath = dirBase.resolve(stripDirPrefix(dirName, rel)).normalize();
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
     *
     * <p>The set of directories to poll is recomputed on <em>every</em> iteration from
     * {@link RootDirService#retrieveAllInSyncDirs()} intersected with {@code serverPublicDirs},
     * unioned with {@code authorizedPrivateDirs}. This means a directory subscribed after this
     * connection was already established (e.g. a second {@code [ START SYNC >> ]} or CLI
     * {@code add}) is picked up on the very next tick — no socket reconnect required. A
     * directory is unsubscribed just as immediately by dropping out of
     * {@code retrieveAllInSyncDirs()}.
     *
     * <p>Directories seen for the first time in this connection (including the initial batch)
     * are registered and audited for missing local files before their first poll.
     *
     * <p>For each directory polled:
     * <ol>
     *   <li>Sends a {@code SYNC} request with the last known sync version.</li>
     *   <li>Reads the response records.</li>
     *   <li>Writes live files to disk or deletes soft-deleted files.</li>
     *   <li>Sends a {@code DELETE_ACK} for each deleted file.</li>
     *   <li>Persists the highest received {@code syncVersion}.</li>
     * </ol>
     *
     * <p>{@link #getConnectionState()} is flipped to {@link ConnectionState#TRANSFERRING} as
     * soon as any polled directory reports a non-empty record count for the current tick, and
     * back to {@link ConnectionState#IDLE} once every directory has been processed.
     *
     * @param in                    the server input stream
     * @param out                   the server output stream
     * @param serverPublicDirs      all public directories currently advertised by the server
     * @param authorizedPrivateDirs private directories authorized via HTTP for this connection
     * @throws IOException if reading or writing fails (triggers reconnect)
     */
    private void pollLoop(DataInputStream in, DataOutputStream out,
                          List<String> serverPublicDirs, List<String> authorizedPrivateDirs) throws IOException {
        Set<String> seenDirs = new HashSet<>();
        while (running) {
            List<String> clientDirs = rootDirService.retrieveAllInSyncDirs();
            List<String> dirsToSync = Stream.concat(
                    serverPublicDirs.stream().filter(clientDirs::contains),
                    authorizedPrivateDirs.stream()
            ).distinct().toList();

            List<String> newDirs = dirsToSync.stream().filter(d -> !seenDirs.contains(d)).toList();
            if (!newDirs.isEmpty()) {
                registerDirs(newDirs);
                auditMissingFiles(newDirs);
                seenDirs.addAll(newDirs);
            }

            for (String dirName : dirsToSync) {
                long lastVersion = rootDirService.findAll().stream()
                        .filter(e -> e.dirName().equals(dirName))
                        .mapToLong(ClientRootDir::lastSyncVersion)
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
                if (count > 0) connectionState = ConnectionState.TRANSFERRING;
                long highestVersion = lastVersion;

                Path dirBase = rootDirService.resolveLocalPath(dirName).toAbsolutePath().normalize();

                for (int i = 0; i < count; i++) {
                    byte flags = in.readByte();
                    int pathLen = in.readInt();
                    String serverPath = new String(in.readNBytes(pathLen), StandardCharsets.UTF_8);
                    long syncVersion = in.readLong();
                    long fileSizeBytes = in.readLong();

                    applyRecord(in, out, dirName, dirBase, flags, serverPath, syncVersion, fileSizeBytes);

                    if (syncVersion > highestVersion) highestVersion = syncVersion;
                }

                if (highestVersion > lastVersion) {
                    rootDirService.updateSyncVersion(dirName, highestVersion);
                }
            }

            if (connectionState == ConnectionState.TRANSFERRING) connectionState = ConnectionState.IDLE;

            // Check for server-initiated signals (non-blocking: peek at available bytes)
            if (in.available() > 0) {
                byte signal = in.readByte();
                log.warn("Unexpected byte from server outside poll: {}", signal);
            }

            sleep(POLL_INTERVAL_MS);
        }
    }

    /**
     * Applies a single record from a SYNC response to the local mirror: deletes the file (and any
     * parent directories left empty) and ACKs the delete, or writes the file's bytes from
     * {@code in}. Always consumes exactly the record's payload from {@code in}, so the stream
     * stays aligned for the next record even when the record is rejected.
     *
     * <p>Package-private for tests; {@link #pollLoop} is the only production caller.
     *
     * @param in            stream positioned at the start of this record's file bytes (none for deletes)
     * @param out           stream to the server, used to send the DELETE_ACK
     * @param dirName       the synced directory the record belongs to
     * @param dirBase       absolute, normalised local root of that directory
     * @param flags         record flags ({@link #FLAG_DELETED})
     * @param serverPath    the qualified path exactly as the server sent it ("dirName/rel/path");
     *                      echoed back verbatim in the DELETE_ACK because the server parses it with '/'
     * @param syncVersion   the record's sync version (logging only)
     * @param fileSizeBytes number of file bytes that follow in {@code in} for a live record
     * @throws IOException if reading, writing, or the ACK fails (triggers reconnect)
     */
    void applyRecord(DataInputStream in, DataOutputStream out, String dirName, Path dirBase,
                     byte flags, String serverPath, long syncVersion, long fileSizeBytes) throws IOException {
        String relPath = adjustFilePathToClientOS(serverPath);
        boolean deleted = (flags & FLAG_DELETED) != 0;

        Path target = dirBase.resolve(stripDirPrefix(dirName, relPath)).normalize();

        // Reject any path whose canonical form is not a descendant of dirBase.
        if (!target.startsWith(dirBase)) {
            log.error("Path traversal blocked — server sent path outside mirror dir: '{}'", relPath);
            // Must drain bytes from stream to keep it in sync before continuing
            if (!deleted && fileSizeBytes > 0) {
                in.skipNBytes(fileSizeBytes);
            }
            return;
        }

        if (deleted) {
            // Delete from disk first, then purge DB record (disk-only delete already done here,
            // so use purgeRecord not removeRecord to avoid a second disk delete attempt)
            Files.deleteIfExists(target);
            deleteEmptyParents(target.getParent(), dirBase);
            fileMetadataService.purgeRecord(dirName, relPath);
            log.info("Deleted: {}", relPath);

            // ACK the delete so server can remove from client_ids
            byte[] ackPathBytes = serverPath.getBytes(StandardCharsets.UTF_8);
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
     * Walks up from {@code dir} toward {@code stopAt} (exclusive), removing each directory that
     * is empty, stopping at the first non-empty one. The server only sends per-file deletes, so
     * this is what removes directories emptied by a delete. {@code stopAt} itself is never removed.
     *
     * <p>Best-effort: an {@link IOException} (race with a new file, permissions) is logged and
     * swallowed so it cannot block the DELETE_ACK.
     *
     * @param dir    directory to start from (the deleted file's parent)
     * @param stopAt the local root of the synced dir; never deleted
     */
    private void deleteEmptyParents(Path dir, Path stopAt) {
        while (dir != null && dir.startsWith(stopAt) && !dir.equals(stopAt)) {
            if (!Files.isDirectory(dir)) {
                return;
            }
            try {
                try (Stream<Path> entries = Files.list(dir)) {
                    if (entries.findAny().isPresent()) {
                        return;
                    }
                }
                Files.delete(dir);
            } catch (IOException e) {
                log.warn("Could not remove empty directory {}: {}", dir, e.getMessage());
                return;
            }
            log.info("Removed empty directory: {}", dir);
            dir = dir.getParent();
        }
    }

    private String adjustFilePathToClientOS(String relativePath) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            return relativePath.replace("/", "\\");
        }

        return relativePath.replace("\\", "/");
    }

    /**
     * Strips the leading {@code dirName} segment from a qualified path (e.g.
     * {@code "photos/2024/img.jpg"} with {@code dirName="photos"} becomes {@code "2024/img.jpg"}),
     * so the remainder can be resolved relative to that directory's own local base — which may
     * be a custom path rather than {@code mirrorDir/dirName}.
     *
     * <p>If the first segment does not match {@code dirName} the path is returned unchanged,
     * as a defensive fallback.
     *
     * @param dirName       the root directory name (expected first path segment)
     * @param qualifiedPath the qualified path "dirName/relativePath", already OS-adjusted
     * @return the path relative to the directory root
     */
    private Path stripDirPrefix(String dirName, String qualifiedPath) {
        Path path = Path.of(qualifiedPath);
        if (path.getNameCount() > 1 && path.getName(0).toString().equals(dirName)) {
            return path.subpath(1, path.getNameCount());
        }
        return path;
    }
}
