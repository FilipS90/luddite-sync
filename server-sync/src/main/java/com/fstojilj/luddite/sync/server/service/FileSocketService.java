package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.repository.RootDirRepository;
import com.fstojilj.luddite.sync.server.repository.SyncTimeRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Accepts inbound connections from sync clients and serves file-change data.
 *
 * <p>All directory negotiation and private-directory authentication is performed
 * via HTTP (see {@code DirsController} and {@code AuthCacheService}) before the
 * client opens the socket. The socket is used exclusively for binary file transfer
 * and delete acknowledgements.
 *
 * <h2>Wire protocol</h2>
 * <pre>
 * On connect:
 *   Client → [4b idLen][clientId (UTF-8)]
 *
 * Per request:
 *   [1b type]
 *   SYNC (0x01):
 *     Client → [4b dirNameLen][dirName][8b sinceVersion]
 *     Server → [4b count] per record: [1b flags][4b pathLen][qualifiedPath][8b version][8b fileSize][fileSize bytes]
 *   DOWNLOAD_FILE (0x02):
 *     Client → [4b pathLen][qualifiedPath]
 *     Server → [8b fileSize (-1L = not found/denied)][fileSize bytes if ≥ 0]
 *   DELETE_ACK (0x03):
 *     Client → [4b pathLen][qualifiedPath]
 *     Server → (no response)
 * </pre>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileSocketService {

    // ── Wire protocol bytes — client→server message types ────────────────────
    public static final byte SYNC = 0x01;
    public static final byte DOWNLOAD_FILE = 0x02;
    public static final byte DELETE_ACK = 0x03;

    // ── Flag bits inside sync-response file records ───────────────────────────
    private static final byte FLAG_DELETED = 0x01;

    /** Minimum gap between two {@code sync_time} writes for the same client. */
    private static final long SYNC_TIME_WRITE_INTERVAL_MS = 60 * 60 * 1000L;

    /** Upper bound on the streaming buffer; smaller files allocate only what they need. */
    private static final int MAX_CHUNK_BYTES = 33 * 1024 * 1024;

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final FileMetadataService fileMetadataService;
    private final RootDirRepository rootDirRepository;
    private final SyncTimeRepository syncTimeRepository;
    private final AuthCacheService authCacheService;

    // ── Config ────────────────────────────────────────────────────────────────
    @Value("${sync.socket.port:8888}")
    private int port;

    // ── State ─────────────────────────────────────────────────────────────────
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    /**
     * One entry per connected client: key = stable client ID, value = remote address string.
     * Exposed to the admin CLI via {@link #listConnectedClients()}.
     */
    private final ConcurrentHashMap<String, String> connectedClients = new ConcurrentHashMap<>();

    /**
     * Last time each connected client's {@code sync_time} row was written. Clients poll every
     * couple of seconds, so without this every poll would be a SQLite write.
     */
    private final ConcurrentHashMap<String, Long> lastSyncTimeWrite = new ConcurrentHashMap<>();

    /**
     * Number of open sockets per client ID. A client keeps one long-lived sync connection and
     * opens a short-lived one per downloaded file, all under the same ID; per-client state is
     * only dropped when the last of them closes.
     */
    private final ConcurrentHashMap<String, Integer> openConnections = new ConcurrentHashMap<>();

    /**
     * Serialises concurrent SYNC requests so that version minting and stamping are atomic.
     * Without this lock, two clients polling the same directory simultaneously could both
     * read the same {@code NULL sync_version} rows, mint different versions for them, and
     * diverge.
     */
    private final ReentrantLock syncLock = new ReentrantLock();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Builds the plain TCP server socket and starts the client-acceptor platform thread.
     *
     * @throws Exception if the socket cannot be created
     */
    @PostConstruct
    public void start() throws Exception {
        serverSocket = new ServerSocket(port);
        running = true;
        log.info("Listening for clients on port {}", port);
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
     * Returns the port currently being listened on. Exposed to the admin CLI and to
     * {@code ServerInfoController} so clients can discover it over REST.
     */
    public int getPort() {
        return port;
    }

    /**
     * Rebinds the listening socket to a new port without restarting the JVM.
     * Already-connected clients are unaffected; only new connections are routed to the
     * new port. Called from the admin CLI's {@code port} command.
     *
     * @param newPort the port to rebind to
     * @throws Exception if the new port cannot be bound (e.g. already in use)
     */
    public synchronized void changePort(int newPort) throws Exception {
        stop();
        this.port = newPort;
        start();
    }

    /**
     * Returns the remote address strings of all currently connected clients.
     * Used by the admin CLI {@code listc} command.
     *
     * @return list of address strings
     */
    public List<String> listConnectedClients() {
        return new ArrayList<>(connectedClients.values());
    }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private void acceptClients() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
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
     *   <li>Reads the client's stable {@code clientId}.</li>
     *   <li>Enters the message loop: handles {@code SYNC}, {@code DOWNLOAD_FILE}, and
     *       {@code DELETE_ACK} messages.</li>
     * </ol>
     *
     * @param socket the accepted socket
     */
    private void serveClient(Socket socket) {
        String clientId = null;
        try (var in = new DataInputStream(socket.getInputStream());
             var out = new DataOutputStream(socket.getOutputStream())) {

            int idLen = in.readInt();
            clientId = new String(in.readNBytes(idLen), StandardCharsets.UTF_8);
            log.info("Client {} identified as clientId='{}'", socket.getRemoteSocketAddress(), clientId);

            openConnections.merge(clientId, 1, Integer::sum);
            connectedClients.putIfAbsent(clientId, socket.getRemoteSocketAddress().toString());
            log.info("Client '{}' ({}) connected", clientId, socket.getRemoteSocketAddress());

            final String finalClientId = clientId;
            while (true) {
                byte msg = in.readByte();
                switch (msg) {
                    case SYNC -> {
                        int nameLen = in.readInt();
                        String dirName = new String(in.readNBytes(nameLen), StandardCharsets.UTF_8);
                        long lastSyncVersion = in.readLong();
                        recordSyncTime(finalClientId);
                        handleSync(out, dirName, lastSyncVersion, finalClientId);
                    }
                    case DOWNLOAD_FILE -> handleDownloadFile(in, out, finalClientId);
                    case DELETE_ACK -> {
                        int pathLen = in.readInt();
                        String qualifiedPath = new String(in.readNBytes(pathLen), StandardCharsets.UTF_8);
                        handleDeleteAck(qualifiedPath, finalClientId);
                    }
                    default -> {
                        log.warn("Unexpected byte {} from client '{}' — dropping the connection", msg, finalClientId);
                        throw new IOException("stream desync from client '" + finalClientId + "'");
                    }
                }
            }

        } catch (IOException e) {
            log.info("Client '{}' disconnected: {}", clientId, socket.getRemoteSocketAddress());
        } finally {
            if (clientId != null && closeConnection(clientId)) {
                connectedClients.remove(clientId);
                lastSyncTimeWrite.remove(clientId);
                authCacheService.evict(clientId);
            }
            try {
                socket.close();
            } catch (IOException e) {
                log.error("Error closing socket for client '{}': {}", clientId, e.getMessage());
            }
        }
    }

    /**
     * Records that one of {@code clientId}'s sockets has closed.
     *
     * @return {@code true} if it was the client's last open socket
     */
    private boolean closeConnection(String clientId) {
        return openConnections.computeIfPresent(clientId, (id, n) -> n > 1 ? n - 1 : null) == null;
    }

    /**
     * Persists the client's last-sync timestamp, at most once per
     * {@value #SYNC_TIME_WRITE_INTERVAL_MS} ms per connection. The first SYNC after a
     * connect always writes, since the in-memory entry is dropped on disconnect.
     *
     * @param clientId the polling client's stable ID
     */
    private void recordSyncTime(String clientId) {
        long now = System.currentTimeMillis();
        Long last = lastSyncTimeWrite.get(clientId);
        if (last != null && now - last < SYNC_TIME_WRITE_INTERVAL_MS) {
            return;
        }
        syncTimeRepository.upsert(clientId, now);
        lastSyncTimeWrite.put(clientId, now);
    }

    // ── Sync handler ──────────────────────────────────────────────────────────

    /**
     * Handles one SYNC request from a client.
     *
     * <p>Acquires {@link #syncLock} so only one client's SYNC is processed at a time.
     * This prevents concurrent clients from reading the same {@code NULL sync_version}
     * rows, minting different versions for them, and diverging.
     *
     * <p>For private directories, validates that the client has been granted access
     * via {@link AuthCacheService} (populated by HTTP auth). If not authorized, sends
     * an empty response ({@code count=0}) and logs a warning.
     *
     * <p>For live files, bytes are read from disk. If the file has been deleted from
     * disk between the DB query and the read, the record is skipped with a warning.
     *
     * @param out             the client's output stream
     * @param dirName         the directory name the client is syncing
     * @param lastSyncVersion the last sync version the client already has
     * @param clientId        the requesting client's ID (for logging)
     * @throws IOException if writing to the stream fails
     */
    private void handleSync(DataOutputStream out, String dirName,
                            Long lastSyncVersion, String clientId) throws IOException {
        syncLock.lock();
        try {
            var rootDirOpt = rootDirRepository.findByName(dirName);
            if (rootDirOpt.isEmpty()) {
                log.warn("Sync from '{}' for unknown dir '{}', sending empty response", clientId, dirName);
                out.writeInt(0);
                out.flush();
                return;
            }

            RootDir rootDir = rootDirOpt.get();
            if (rootDir.isPrivate() && !authCacheService.isAuthorized(clientId, dirName)) {
                log.warn("Sync from '{}' for unauthorized private dir '{}', sending empty response", clientId, dirName);
                out.writeInt(0);
                out.flush();
                return;
            }

            int rootDirId = rootDir.getId();
            String rootAbsPath = rootDir.getAbsolutePath();

            long limit = 100;
            List<FileMetadata> changed = fileMetadataService.findChangedSince(rootDirId, lastSyncVersion, limit);
            log.debug("Sync from '{}' for dir '{}' since v{}: {} record(s)",
                    clientId, dirName, lastSyncVersion, changed.size());

            out.writeInt(changed.size());
            for (FileMetadata meta : changed) {
                String relNorm = meta.relativePath();
                String qualifiedPath = dirName + "/" + relNorm;
                Path absPath = Path.of(rootAbsPath).resolve(relNorm);
                long version = meta.syncVersion() != null ? meta.syncVersion() : fileMetadataService.
                                                                                 nextSyncVersion(rootDirId);
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

            // Record the client as a holder of every live file it just received.
            // Deleted rows are excluded: client_ids on those means "still has to ack the delete".
            List<String> receivedPaths = changed.stream()
                    .filter(meta -> !meta.deleted())
                    .map(FileMetadata::relativePath)
                    .toList();
            fileMetadataService.addClientToFiles(rootDirId, receivedPaths, clientId);
        } finally {
            syncLock.unlock();
        }
    }

    // ── File download handler ─────────────────────────────────────────────────

    /**
     * Handles a DOWNLOAD_FILE request from a client: a one-off fetch of a single file, outside
     * the sync protocol. Directory downloads are driven entirely by the client, which walks
     * the tree over HTTP and issues one DOWNLOAD_FILE request per descendant file; this handler
     * only ever serves a single regular file.
     *
     * <p>Resolves the qualified path, verifies path-traversal safety and private-dir
     * authorization, then streams the file. Sends {@code -1L} as the file size if the
     * target is not a regular file, the path is malformed, or the client is not authorized.
     *
     * @param in       the client's input stream
     * @param out      the client's output stream
     * @param clientId the requesting client's ID
     * @throws IOException if reading or writing fails
     */
    private void handleDownloadFile(DataInputStream in, DataOutputStream out,
                                    String clientId) throws IOException {
        int pathLen = in.readInt();
        String qualifiedPath = new String(in.readNBytes(pathLen), StandardCharsets.UTF_8);

        int slash = qualifiedPath.indexOf('/');
        if (slash < 0) {
            log.warn("handleDownloadFile: malformed qualifiedPath '{}' from '{}'", qualifiedPath, clientId);
            out.writeLong(-1L);
            out.flush();
            return;
        }

        String dirName = qualifiedPath.substring(0, slash);
        String relPath = qualifiedPath.substring(slash + 1).replaceAll("^[/\\\\]+", "");

        var rootDirOpt = rootDirRepository.findByName(dirName);
        if (rootDirOpt.isEmpty()) {
            log.warn("handleDownloadFile: unknown dir '{}' from '{}'", dirName, clientId);
            out.writeLong(-1L);
            out.flush();
            return;
        }

        RootDir rootDir = rootDirOpt.get();
        if (rootDir.isPrivate() && !authCacheService.isAuthorized(clientId, dirName)) {
            log.warn("handleDownloadFile: unauthorized access to private dir '{}' from '{}'", dirName, clientId);
            out.writeLong(-1L);
            out.flush();
            return;
        }

        Path rootAbsPath = Path.of(rootDir.getAbsolutePath()).toAbsolutePath().normalize();
        Path target = rootAbsPath.resolve(relPath).normalize();
        if (!target.startsWith(rootAbsPath)) {
            log.warn("handleDownloadFile: path traversal blocked for '{}' from '{}'", qualifiedPath, clientId);
            out.writeLong(-1L);
            out.flush();
            return;
        }

        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            log.warn("handleDownloadFile: file not found '{}' for '{}'", target, clientId);
            out.writeLong(-1L);
            out.flush();
            return;
        }

        long fileSize = Files.size(target);
        out.writeLong(fileSize);
        sendFileBytes(out, target, fileSize);
        out.flush();
        log.debug("handleDownloadFile: sent '{}' ({} bytes) to '{}'", qualifiedPath, fileSize, clientId);
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

    // ── File streaming helpers ─────────────────────────────────────────────────

    /**
     * Streams exactly {@code fileBytesSize} bytes of {@code absPath} to the client, matching the
     * length already announced in the record header.
     *
     * <p>A file that grew after its size was sampled is cut at the announced length — the watcher
     * indexes the later content and a following sync carries it. A file that shrank, or that was
     * deleted between the DB query and the read, cannot fill the announced length, so the record
     * fails and the connection is dropped rather than leaving the client's stream misaligned.
     *
     * @param out           the client's output stream
     * @param absPath       the absolute path to the file on disk
     * @param fileBytesSize the number of bytes announced for this file
     * @throws IOException if reading or writing fails, or the file is shorter than announced
     */
    private void sendFileBytes(DataOutputStream out, Path absPath, long fileBytesSize) throws IOException {
        long remaining = fileBytesSize;
        try (var in = Files.newInputStream(absPath)) {
            byte[] buf = new byte[(int) Math.min(remaining, MAX_CHUNK_BYTES)];
            while (remaining > 0) {
                int read = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (read < 0) {
                    throw new EOFException("'" + absPath + "' ended after " + (fileBytesSize - remaining)
                            + " of " + fileBytesSize + " announced bytes");
                }
                out.write(buf, 0, read);
                remaining -= read;
            }
        } catch (IOException e) {
            log.error("Failed to send file bytes for '{}': {}", absPath, e.getMessage());
            throw e;
        }
    }
}
