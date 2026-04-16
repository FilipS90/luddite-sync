package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import com.fstojilj.luddite.sync.server.repository.RootDirRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Accepts inbound mTLS connections from sync clients and serves file-change data
 * on a poll basis — one client is served at a time to prevent DB/version race conditions.
 *
 * <h2>Connection lifecycle</h2>
 * <ol>
 *   <li>Server advertises all known root directory names.</li>
 *   <li>Client sends its stable {@code hardwareId} (motherboard/BIOS serial or fallback UUID).</li>
 *   <li>Client sends a subscription handshake: list of (dirName, lastSyncVersion) pairs.</li>
 *   <li>Client enters a poll loop: every ~2 s it sends a poll request
 *       ({@code [1b POLL][4b dirNameLen][dirName][8b lastSyncVersion]}) and the server
 *       responds with all records changed since that version.</li>
 *   <li>For soft-deleted records the client sends a delete-ACK
 *       ({@code [1b DELETE_ACK][4b pathLen][path]}) after removing the file from disk;
 *       the server then removes the client from that record's {@code client_ids} list and
 *       hard-deletes the row once all clients have acknowledged.</li>
 *   <li>A {@code SHUTDOWN} byte from the client causes the server to exit with code 2.</li>
 *   <li>A {@code RESUME_SERVER_MODE} byte sent by the server instructs the client to
 *       restart itself as a server (exit code 2 on the client side).</li>
 * </ol>
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
 *   [M bytes] file content (omitted for deletes)
 * </pre>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SyncPollService {

    // ── Wire protocol bytes ───────────────────────────────────────────────────
    public static final byte POLL = 1;
    public static final byte DELETE_ACK = 2;
    public static final byte SHUTDOWN = 3;
    public static final byte RESUME_SERVER_MODE = 4;

    // ── Flag bits in poll response ────────────────────────────────────────────
    private static final byte FLAG_DELETED = 0x01;

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final FileMetadataService fileMetadataService;
    private final RootDirRepository rootDirRepository;

    // ── Config ────────────────────────────────────────────────────────────────
    @Value("${sync.socket.port:8888}")
    private int port;

    @Value("${sync.socket.keystore:classpath:server-keystore.p12}")
    private Resource keystoreResource;

    @Value("${sync.socket.truststore:classpath:truststore.p12}")
    private Resource truststoreResource;

    @Value("${sync.socket.password}")
    private String keystorePassword;

    // ── State ─────────────────────────────────────────────────────────────────
    private SSLServerSocket serverSocket;
    private volatile boolean running = false;

    /**
     * One entry per connected client: key = stable hardware ID, value = remote address string.
     * Used to populate {@code client_ids} on soft-deleted rows and to expose the client list
     * to the admin CLI.
     */
    private final ConcurrentHashMap<String, String> connectedClients = new ConcurrentHashMap<>();

    /**
     * Global lock — only one client's poll request is processed at a time.
     * This prevents concurrent writes to {@code sync_version} and {@code client_ids}.
     */
    private final ReentrantLock pollLock = new ReentrantLock();

    /**
     * A single record to be sent in a poll response: the original metadata row,
     * the sync version assigned to this delivery, and the file bytes (empty for deletes).
     */
    private record PollRecord(FileMetadata meta, long syncVersion, byte[] bytes) {
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Builds the mTLS server socket and starts the client-acceptor virtual thread.
     * Invoked automatically by Spring after dependency injection.
     *
     * @throws Exception if the SSL context or server socket cannot be created
     */
    @PostConstruct
    public void start() throws Exception {
        serverSocket = buildSslServerSocket();
        running = true;
        log.info("Listening for clients on port {} (mTLS)", port);
        Thread.ofPlatform().name("client-acceptor").daemon(false).start(this::acceptClients);
    }

    /**
     * Signals the service to stop and closes the server socket.
     * Invoked automatically by Spring during application shutdown.
     */
    @PreDestroy
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException e) {
            log.warn("Error closing server socket: {}", e.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the remote address strings of all currently connected clients.
     * Used by the admin CLI {@code listc} command.
     *
     * @return list of address strings
     */
    public List<String> listConnectedClients() {
        return new ArrayList<>(connectedClients.values());
    }

    /**
     * Returns a comma-separated string of hardware IDs for all currently connected clients.
     * Called by {@link DirWatcherService} at soft-delete time to populate {@code client_ids}.
     *
     * @return comma-separated hardware IDs, or an empty string if no clients are connected
     */
    public String getConnectedClientIds() {
        return String.join(",", connectedClients.keySet());
    }

    /**
     * Sends a {@code RESUME_SERVER_MODE} signal to the client identified by the given
     * hardware ID, instructing it to restart in server mode (exit code 2).
     * After sending, this process also exits with code 3.
     *
     * @param hardwareId the target client's stable hardware ID
     * @return {@code true} if the signal was sent, {@code false} if no matching session exists
     */
    public boolean sendResumeServerMode(String hardwareId) {
        // We store the DataOutputStream per-session in the serve thread; here we use
        // a simple shared map so the CLI can reach it.
        // The actual write is handled via the clientOutputStreams map below.
        DataOutputStream out = clientOutputStreams.get(hardwareId);
        if (out == null) {
            log.warn("sendResumeServerMode: no session for hardwareId '{}'", hardwareId);
            return false;
        }
        try {
            synchronized (out) {
                out.writeByte(RESUME_SERVER_MODE);
                out.flush();
            }
            log.info("RESUME_SERVER_MODE sent to {} — exiting with code 3", hardwareId);
            System.exit(3);
            return true;
        } catch (IOException e) {
            log.warn("Failed to send RESUME_SERVER_MODE to {}: {}", hardwareId, e.getMessage());
            return false;
        }
    }

    /**
     * Output streams keyed by hardwareId so the CLI can send out-of-band signals.
     */
    private final ConcurrentHashMap<String, DataOutputStream> clientOutputStreams = new ConcurrentHashMap<>();

    // ── Accept loop ───────────────────────────────────────────────────────────

    private void acceptClients() {
        while (running) {
            try {
                SSLSocket clientSocket = (SSLSocket) serverSocket.accept();
                log.info("Client connected: {}", clientSocket.getRemoteSocketAddress());
                Thread.ofVirtual().start(() -> serveClient(clientSocket));
            } catch (SocketException e) {
                if (running) log.error("Accept error", e);
            } catch (IOException e) {
                log.error("Failed to accept client", e);
            }
        }
    }

    // ── Per-client handler ────────────────────────────────────────────────────

    /**
     * Manages the full lifetime of a single client connection.
     *
     * <ol>
     *   <li>Sends the available root directory list.</li>
     *   <li>Reads the client's stable {@code hardwareId}.</li>
     *   <li>Reads the subscription handshake.</li>
     *   <li>Enters the message loop: handles {@code POLL} and {@code DELETE_ACK} messages.</li>
     * </ol>
     *
     * @param socket the accepted SSL socket
     */
    private void serveClient(SSLSocket socket) {
        String hardwareId = null;
        DataOutputStream out = null;
        try {
            var in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());

            // 1 — advertise available root dirs
            sendAvailableDirs(out);

            // 2 — read client's stable hardware ID
            int idLen = in.readInt();
            hardwareId = new String(in.readNBytes(idLen), StandardCharsets.UTF_8);
            log.info("Client {} identified as hardwareId='{}'", socket.getRemoteSocketAddress(), hardwareId);

            // 3 — read subscription handshake
            List<SyncHandshakeEntry> handshake = readHandshake(in);
            log.info("Handshake from {}: {} dir(s)", socket.getRemoteSocketAddress(), handshake.size());

            Set<Long> subscribedIds = resolveSubscribedIds(handshake);

            // Register this client
            connectedClients.put(hardwareId, socket.getRemoteSocketAddress().toString());
            clientOutputStreams.put(hardwareId, out);

            log.info("Client '{}' ({}) subscribed to {} dir(s)",
                    hardwareId, socket.getRemoteSocketAddress(), subscribedIds.size());

            // 4 — message loop
            final String finalHardwareId = hardwareId;
            final DataOutputStream finalOut = out;
            while (true) {
                byte msg = in.readByte();

                switch (msg) {
                    case POLL -> {
                        int nameLen = in.readInt();
                        String dirName = new String(in.readNBytes(nameLen), StandardCharsets.UTF_8);
                        Long lastSyncVersion = in.readLong();
                        handlePoll(finalOut, dirName, lastSyncVersion, finalHardwareId);
                    }
                    case DELETE_ACK -> {
                        int pathLen = in.readInt();
                        String qualifiedPath = new String(in.readNBytes(pathLen), StandardCharsets.UTF_8);
                        handleDeleteAck(qualifiedPath, finalHardwareId);
                    }
                    case SHUTDOWN -> {
                        log.info("Shutdown signal from client '{}' — exiting with code 2", finalHardwareId);
                        System.exit(2);
                    }
                    default -> log.warn("Unexpected byte {} from client '{}'", msg, finalHardwareId);
                }
            }

        } catch (IOException e) {
            log.info("Client '{}' disconnected: {}", hardwareId, socket.getRemoteSocketAddress());
        } finally {
            if (hardwareId != null) {
                connectedClients.remove(hardwareId);
                clientOutputStreams.remove(hardwareId);
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ── Poll handler ──────────────────────────────────────────────────────────

    /**
     * Handles one poll request from a client.
     *
     * <p>Acquires {@link #pollLock} so only one client is served at a time, then queries
     * all records changed since {@code lastSyncVersion} for the requested directory and
     * streams them to the client.
     *
     * <p>For live files, bytes are read from disk. If the file has been deleted from disk
     * between the DB query and the read, the record is skipped with a warning (Bug 2 fix).
     *
     * @param out             the client's output stream
     * @param dirName         the directory name the client is polling
     * @param lastSyncVersion the last sync version the client already has
     * @param hardwareId      the polling client's hardware ID (for logging)
     * @throws IOException if writing to the stream fails
     */
    private void handlePoll(DataOutputStream out, String dirName,
                            Long lastSyncVersion, String hardwareId) throws IOException {
        pollLock.lock();
        try {
            var rootDirOpt = rootDirRepository.findByName(dirName);
            if (rootDirOpt.isEmpty()) {
                log.warn("Poll from '{}' for unknown dir '{}', sending empty response", hardwareId, dirName);
                out.writeInt(0);
                out.flush();
                return;
            }

            long rootDirId = rootDirOpt.get().getId();
            String rootAbsPath = rootDirOpt.get().getAbsolutePath();

            List<FileMetadata> changed = fileMetadataService.findChangedSince(rootDirId, lastSyncVersion);
            log.debug("Poll from '{}' for dir '{}' since v{}: {} record(s)",
                    hardwareId, dirName, lastSyncVersion, changed.size());

            List<PollRecord> payload = new ArrayList<>();

            for (FileMetadata meta : changed) {
                // Mint once here — used both in the wire write and the post-flush DB stamp
                long version = meta.syncVersion() != null
                        ? meta.syncVersion()
                        : fileMetadataService.nextSyncVersion();

                if (meta.deleted()) {
                    payload.add(new PollRecord(meta, version, new byte[0]));
                } else {
                    String relNorm = meta.relativePath().replaceAll("^[/\\\\]+", "");
                    Path absPath = Path.of(rootAbsPath).resolve(relNorm);
                    byte[] fileBytes;
                    try {
                        fileBytes = Files.readAllBytes(absPath);
                    } catch (IOException e) {
                        log.warn("Poll: file no longer on disk, skipping '{}': {}", absPath, e.getMessage());
                        continue;
                    }
                    payload.add(new PollRecord(meta, version, fileBytes));
                }
            }

            // Write the full response to the wire first
            out.writeInt(payload.size());
            for (PollRecord rec : payload) {
                String relNorm = rec.meta().relativePath().replaceAll("^[/\\\\]+", "");
                String qualifiedPath = dirName + "/" + relNorm;
                byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);

                byte flags = rec.meta().deleted() ? FLAG_DELETED : 0;
                out.writeByte(flags);
                out.writeInt(pathBytes.length);
                out.write(pathBytes);
                out.writeLong(rec.syncVersion());
                out.writeLong(rec.bytes().length);
                if (!rec.meta().deleted()) {
                    out.write(rec.bytes());
                }
            }
            out.flush();

            for (PollRecord rec : payload) {
                if (rec.meta().syncVersion() == null) {
                    fileMetadataService.stampSyncVersion(rootDirId, rec.meta().relativePath(), rec.syncVersion());
                }
            }

            if (!payload.isEmpty()) {
                log.debug("Poll response to '{}': {} record(s) for dir '{}'",
                        hardwareId, payload.size(), dirName);
            }
        } finally {
            pollLock.unlock();
        }
    }

    // ── Delete-ACK handler ────────────────────────────────────────────────────

    /**
     * Processes a delete acknowledgement from a client.
     * Removes the client's hardware ID from the row's {@code client_ids} list.
     * When the list is empty the row is hard-deleted.
     *
     * @param qualifiedPath qualified path of the form "dirName/relativePath"
     * @param hardwareId    the acknowledging client's hardware ID
     */
    private void handleDeleteAck(String qualifiedPath, String hardwareId) {
        // qualifiedPath = "dirName/rel/path/file.txt"
        int slash = qualifiedPath.indexOf('/');
        if (slash < 0) {
            log.warn("handleDeleteAck: malformed qualifiedPath '{}'", qualifiedPath);
            return;
        }
        String dirName = qualifiedPath.substring(0, slash);
        String relativePath = qualifiedPath.substring(slash + 1);

        rootDirRepository.findByName(dirName).ifPresentOrElse(
                rootDir -> {
                    fileMetadataService.acknowledgeDelete(rootDir.getId(), relativePath, hardwareId);
                    log.debug("Delete-ACK from '{}' for '{}'", hardwareId, qualifiedPath);
                },
                () -> log.warn("handleDeleteAck: unknown dir '{}' in path '{}'", dirName, qualifiedPath)
        );
    }

    // ── Handshake helpers ─────────────────────────────────────────────────────

    /**
     * Sends the list of all known root directory names to the client.
     *
     * <p>Wire format:
     * <pre>
     * [4 bytes] number of dirs (int)
     * per dir:
     *   [4 bytes] name length (int)
     *   [N bytes] name (UTF-8)
     * </pre>
     *
     * @param out the client's output stream
     * @throws IOException if writing fails
     */
    private void sendAvailableDirs(DataOutputStream out) throws IOException {
        List<String> dirNames = rootDirRepository.findAll().stream()
                .map(RootDir::getName)
                .toList();
        out.writeInt(dirNames.size());
        for (String name : dirNames) {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            out.writeInt(nameBytes.length);
            out.write(nameBytes);
        }
        out.flush();
        log.info("Advertised {} dir(s) to client", dirNames.size());
    }

    /**
     * Reads the subscription handshake from the client.
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
     * @param in the client's input stream
     * @return list of handshake entries
     * @throws IOException if reading fails
     */
    private List<SyncHandshakeEntry> readHandshake(DataInputStream in) throws IOException {
        int count = in.readInt();
        List<SyncHandshakeEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int nameLen = in.readInt();
            String dirName = new String(in.readNBytes(nameLen), StandardCharsets.UTF_8);
            long lastSyncVersion = in.readLong();
            entries.add(new SyncHandshakeEntry(dirName, lastSyncVersion));
        }
        return entries;
    }

    /**
     * Resolves client-supplied directory names to server-side root-dir IDs.
     * Unknown names are skipped with a warning.
     *
     * @param handshake entries from the client
     * @return set of resolved root-dir IDs
     */
    private Set<Long> resolveSubscribedIds(List<SyncHandshakeEntry> handshake) {
        Set<Long> ids = new HashSet<>();
        for (SyncHandshakeEntry entry : handshake) {
            rootDirRepository.findByName(entry.dirName()).ifPresentOrElse(
                    dir -> ids.add(dir.getId()),
                    () -> log.warn("Client requested unknown dir '{}', skipping", entry.dirName())
            );
        }
        return ids;
    }

    // ── SSL setup ─────────────────────────────────────────────────────────────

    /**
     * Builds a mutually-authenticated TLS server socket bound to the configured port.
     *
     * @return a bound, listening {@link SSLServerSocket} with client auth required
     * @throws Exception if the SSL context or socket cannot be created
     */
    private SSLServerSocket buildSslServerSocket() throws Exception {
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

        var socket = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(port);
        socket.setNeedClientAuth(true);
        return socket;
    }
}

