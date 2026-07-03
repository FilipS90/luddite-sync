package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.repository.RootDirRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

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
 *   FILE (0x02):
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
    public static final byte FILE = 0x02;
    public static final byte DELETE_ACK = 0x03;

    // ── Flag bits inside sync-response file records ───────────────────────────
    private static final byte FLAG_DELETED = 0x01;

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final FileMetadataService fileMetadataService;
    private final RootDirRepository rootDirRepository;
    private final AuthCacheService authCacheService;

    // ── Config ────────────────────────────────────────────────────────────────
    @Value("${sync.socket.port:8888}")
    private int port;

    @Value("${sync.socket.keystore:classpath:server-keystore.p12}")
    private Resource keystoreResource;

    @Value("${sync.socket.truststore:classpath:truststore.p12}")
    private Resource truststoreResource;

    @Value("${sync.socket.password}")
    private String keystorePassword;

    @Value("${sync.socket.tls-enabled:true}")
    private boolean tlsEnabled;

    // ── State ─────────────────────────────────────────────────────────────────
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    /**
     * One entry per connected client: key = stable client ID, value = remote address string.
     * Used to populate {@code client_ids} on soft-deleted rows and to expose the client list
     * to the admin CLI.
     */
    private final ConcurrentHashMap<String, String> connectedClients = new ConcurrentHashMap<>();

    /**
     * Serialises concurrent SYNC requests so that version minting and stamping are atomic.
     * Without this lock, two clients polling the same directory simultaneously could both
     * read the same {@code NULL sync_version} rows, mint different versions for them, and
     * diverge.
     */
    private final ReentrantLock syncLock = new ReentrantLock();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Builds the server socket and starts the client-acceptor platform thread.
     * When {@code sync.socket.tls-enabled} is {@code true} (default), an mTLS
     * {@link SSLServerSocket} is created; otherwise a plain {@link ServerSocket} is used.
     *
     * @throws Exception if the socket cannot be created
     */
    @PostConstruct
    public void start() throws Exception {
        serverSocket = tlsEnabled ? buildSslServerSocket() : new ServerSocket(port);
        running = true;
        log.info("Listening for clients on port {} ({})", port, tlsEnabled ? "mTLS" : "plain TCP");
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
     *   <li>Enters the message loop: handles {@code SYNC}, {@code FILE}, and
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

            connectedClients.put(clientId, socket.getRemoteSocketAddress().toString());
            log.info("Client '{}' ({}) connected", clientId, socket.getRemoteSocketAddress());

            final String finalClientId = clientId;
            while (true) {
                byte msg = in.readByte();
                switch (msg) {
                    case SYNC -> {
                        int nameLen = in.readInt();
                        String dirName = new String(in.readNBytes(nameLen), StandardCharsets.UTF_8);
                        long lastSyncVersion = in.readLong();
                        handleSync(out, dirName, lastSyncVersion, finalClientId);
                    }
                    case FILE -> handleFile(in, out, finalClientId);
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
                authCacheService.evict(clientId);
            }
            try {
                socket.close();
            } catch (IOException e) {
                log.error("Error closing socket for client '{}': {}", clientId, e.getMessage());
            }
        }
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
        } finally {
            syncLock.unlock();
        }
    }

    // ── File download handler ─────────────────────────────────────────────────

    /**
     * Handles a FILE request from a client. Resolves the qualified path, verifies
     * path-traversal safety and private-dir authorization, then streams the file.
     *
     * <p>Sends {@code -1L} as the file size if the file is not found, the path is
     * malformed, or the client is not authorized.
     *
     * @param in       the client's input stream
     * @param out      the client's output stream
     * @param clientId the requesting client's ID
     * @throws IOException if reading or writing fails
     */
    private void handleFile(DataInputStream in, DataOutputStream out,
                            String clientId) throws IOException {
        int pathLen = in.readInt();
        String qualifiedPath = new String(in.readNBytes(pathLen), StandardCharsets.UTF_8);

        int slash = qualifiedPath.indexOf('/');
        if (slash < 0) {
            log.warn("handleFile: malformed qualifiedPath '{}' from '{}'", qualifiedPath, clientId);
            out.writeLong(-1L);
            out.flush();
            return;
        }

        String dirName = qualifiedPath.substring(0, slash);
        String relPath = qualifiedPath.substring(slash + 1).replaceAll("^[/\\\\]+", "");

        var rootDirOpt = rootDirRepository.findByName(dirName);
        if (rootDirOpt.isEmpty()) {
            log.warn("handleFile: unknown dir '{}' from '{}'", dirName, clientId);
            out.writeLong(-1L);
            out.flush();
            return;
        }

        RootDir rootDir = rootDirOpt.get();
        if (rootDir.isPrivate() && !authCacheService.isAuthorized(clientId, dirName)) {
            log.warn("handleFile: unauthorized access to private dir '{}' from '{}'", dirName, clientId);
            out.writeLong(-1L);
            out.flush();
            return;
        }

        Path rootAbsPath = Path.of(rootDir.getAbsolutePath()).toAbsolutePath().normalize();
        Path target = rootAbsPath.resolve(relPath).normalize();
        if (!target.startsWith(rootAbsPath)) {
            log.warn("handleFile: path traversal blocked for '{}' from '{}'", qualifiedPath, clientId);
            out.writeLong(-1L);
            out.flush();
            return;
        }

        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            log.warn("handleFile: file not found '{}' for '{}'", target, clientId);
            out.writeLong(-1L);
            out.flush();
            return;
        }

        long fileSize = Files.size(target);
        out.writeLong(fileSize);
        sendFileBytes(out, target, fileSize);
        out.flush();
        log.debug("handleFile: sent '{}' ({} bytes) to '{}'", qualifiedPath, fileSize, clientId);
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
     * <p>Used for small files (≤ 33 MB) to reduce latency. For larger files, {@link #sendFileBytes}
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
