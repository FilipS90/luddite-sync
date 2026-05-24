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
import java.util.Arrays;
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
 *   <li>Client sends its stable {@code clientId}.</li>
 *   <li>Client sends a subscription handshake: list of (dirName, lastSyncVersion) pairs.</li>
 *   <li>Client enters a poll loop: every ~2 s it sends a poll request
 *       ({@code [1b POLL][4b dirNameLen][dirName][8b lastSyncVersion]}) and the server
 *       responds with all records changed since that version.</li>
 *   <li>For soft-deleted records the client sends a delete-ACK
 *       ({@code [1b DELETE_ACK][4b pathLen][path]}) after removing the file from disk;
 *       the server then removes the client from that record's {@code client_ids} list and
 *       hard-deletes the row once all clients have acknowledged.</li>
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
public class PushService {

    // ── Wire protocol bytes ───────────────────────────────────────────────────
    public static final byte POLL = 1;
    public static final byte DELETE_ACK = 2;

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
     * One entry per connected client: key = stable client ID, value = remote address string.
     * Used to populate {@code client_ids} on soft-deleted rows and to expose the client list
     * to the admin CLI.
     */
    private final ConcurrentHashMap<String, String> connectedClients = new ConcurrentHashMap<>();

    /**
     * Global lock — only one client's poll request is processed at a time.
     * This prevents concurrent writes to {@code sync_version} and {@code client_ids}.
     */
    private final ReentrantLock pollLock = new ReentrantLock();

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
     * Returns a comma-separated string of client IDs for all currently connected clients.
     * Called by {@link DirWatcherService} at soft-delete time to populate {@code client_ids}.
     *
     * @return comma-separated client IDs, or an empty string if no clients are connected
     */
    public String getConnectedClientIds() {
        return String.join(",", connectedClients.keySet());
    }

    /**
     * Output streams keyed by client-id so the CLI can send out-of-band signals.
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
     *   <li>Reads the client's stable {@code clientId}.</li>
     *   <li>Reads the subscription handshake.</li>
     *   <li>Enters the message loop: handles {@code POLL} and {@code DELETE_ACK} messages.</li>
     * </ol>
     *
     * @param socket the accepted SSL socket
     */
    private void serveClient(SSLSocket socket) {
        String clientId = null;
        try (var in = new DataInputStream(socket.getInputStream());
             var out = new DataOutputStream(socket.getOutputStream())) {

            // 1 — advertise available root dirs
            sendAvailableDirs(out);

            // 2 — read client's stable client ID
            int idLen = in.readInt();
            clientId = new String(in.readNBytes(idLen), StandardCharsets.UTF_8);
            log.info("Client {} identified as clientId='{}'", socket.getRemoteSocketAddress(), clientId);

            // 3 — read subscription handshake
            List<SyncHandshakeEntry> handshake = readHandshake(in);
            log.info("Handshake from {}: {} dir(s)", socket.getRemoteSocketAddress(), handshake.size());

            Set<Integer> subscribedIds = resolveSubscribedIds(handshake);

            // Register this client
            connectedClients.put(clientId, socket.getRemoteSocketAddress().toString());
            clientOutputStreams.put(clientId, out);

            log.info("Client '{}' ({}) subscribed to {} dir(s)",
                    clientId, socket.getRemoteSocketAddress(), subscribedIds.size());

            // 4 — message loop
            final String finalClientId = clientId;
            while (true) {
                byte msg = in.readByte();

                switch (msg) {
                    case POLL -> {
                        int nameLen = in.readInt();
                        String dirName = new String(in.readNBytes(nameLen), StandardCharsets.UTF_8);
                        Long lastSyncVersion = in.readLong();
                        handlePoll(out, dirName, lastSyncVersion, finalClientId);
                    }
                    case DELETE_ACK -> {
                        int pathLen = in.readInt();
                        String qualifiedPath = new String(in.readNBytes(pathLen), StandardCharsets.UTF_8);
                        handleDeleteAck(qualifiedPath, finalClientId);
                    }
                    default -> log.warn("Unexpected byte {} from client '{}'", msg, finalClientId);
                }
            }

        } catch (IOException e) {
            log.info("Client '{}' disconnected: {}", clientId, socket.getRemoteSocketAddress());
        } finally {
            if (clientId != null) {
                connectedClients.remove(clientId);
                clientOutputStreams.remove(clientId);
            }
            try {
                socket.close();
            } catch (IOException e) {
                log.error("Error closing socket for client '{}': {}", clientId, e.getMessage());
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
     * @param clientId        the polling client's ID (for logging)
     * @throws IOException if writing to the stream fails
     */
    private void handlePoll(DataOutputStream out, String dirName,
                            Long lastSyncVersion, String clientId) throws IOException {
        pollLock.lock();
        try {
            var rootDirOpt = rootDirRepository.findByName(dirName);
            if (rootDirOpt.isEmpty()) {
                log.warn("Poll from '{}' for unknown dir '{}', sending empty response", clientId, dirName);
                out.writeInt(0);
                out.flush();
                return;
            }

            int rootDirId = rootDirOpt.get().getId();
            String rootAbsPath = rootDirOpt.get().getAbsolutePath();

            long limit = 100;
            List<FileMetadata> changed = fileMetadataService.findChangedSince(rootDirId, lastSyncVersion, limit);
            log.debug("Poll from '{}' for dir '{}' since v{}: {} record(s)",
                    clientId, dirName, lastSyncVersion, changed.size());


            // Write the full response to the wire first
            out.writeInt(changed.size());
            for (FileMetadata meta : changed) {
                String relNorm = meta.relativePath().replaceAll("^[/\\\\]+", "");
                String qualifiedPath = dirName + "/" + relNorm;
                Path absPath = Path.of(rootAbsPath).resolve(relNorm);
                long version = meta.syncVersion() != null ? meta.syncVersion() : fileMetadataService.nextSyncVersion(rootDirId);
                byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);

                byte flags = meta.deleted() ? FLAG_DELETED : 0;
                long fileBytesSize = flags == 0 ? Files.size(absPath) : 0;
                out.writeByte(flags);
                out.writeInt(pathBytes.length);
                out.write(pathBytes);
                out.writeLong(version);
                out.writeLong(fileBytesSize);
                if (!meta.deleted()) {
                    sendFileBytes(out, absPath, fileBytesSize);
                }

                if (meta.syncVersion() == null) {
                    fileMetadataService.stampSyncVersion(rootDirId, meta.relativePath(), version);
                }

            }
            out.flush();

        } catch (IOException e) {
            log.warn("Error handling poll from '{}': {}", clientId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error handling poll from '{}'", clientId, e);
            throw e;
        } finally {
            pollLock.unlock();
        }
    }

    /**
     * Streams file bytes to the client in chunks.
     *
     * <p>If the file is deleted from disk between the DB query and the read, this method logs
     * a warning and stops streaming.
     *
     * @param out           the client's output stream
     * @param absPath       the absolute path to the file on disk
     * @param fileBytesSize the expected size of the file in bytes
     * @throws IOException if reading or writing fails
     */
    private void sendFileBytes(DataOutputStream out, Path absPath, long fileBytesSize) throws IOException {
        if (fileBytesSize <= 33 * 1024 * 1024) {
            sendAllFileBytes(absPath, out);
            return;
        }

        long chunkSize = 33L * 1024 * 1024;
        try (var in = Files.newInputStream(absPath)) {
            byte[] buf = new byte[(int) chunkSize];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
        } catch (IOException e) {
            log.error("Failed to read file bytes for '{}': {}", absPath, e.getMessage());
            throw e;
        }
    }

    /**
     * Reads the entire file into memory and writes it to the output stream.
     *
     * <p>Used for small files (<100 MB) to reduce latency. For larger files, {@link #sendFileBytes}
     * is used to stream in chunks.
     *
     * @param absPath the absolute path to the file on disk
     * @param out     the client's output stream
     * @throws IOException if reading or writing fails
     */
    private void sendAllFileBytes(Path absPath, DataOutputStream out) throws IOException {
        try {
            byte[] bytes = Files.readAllBytes(absPath);
            out.write(bytes);
        } catch (IOException e) {
            log.warn("Failed to read file bytes for '{}': {}", absPath, e.getMessage());
            throw e;
        }
    }

    // ── Delete-ACK handler ────────────────────────────────────────────────────

    /**
     * Processes a delete acknowledgement from a client.
     * Removes the client's ID from the row's {@code client_ids} list.
     * When the list is empty the row is hard-deleted.
     *
     * @param qualifiedPath qualified path of the form "dirName/relativePath"
     * @param clientId      the acknowledging client's ID
     */
    private void handleDeleteAck(String qualifiedPath, String clientId) {
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
                    fileMetadataService.acknowledgeDelete(rootDir.getId(), relativePath, clientId);
                    log.debug("Delete-ACK from '{}' for '{}'", clientId, qualifiedPath);
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
                .filter(RootDir::isPrivate)
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
    private Set<Integer> resolveSubscribedIds(List<SyncHandshakeEntry> handshake) {
        Set<Integer> ids = new HashSet<>();
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
        keystorePassword = null; // cleared for security reasons

        try {
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
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}

