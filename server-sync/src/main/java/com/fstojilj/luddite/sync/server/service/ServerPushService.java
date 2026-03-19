package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import com.fstojilj.luddite.sync.server.event.FileChangeEvent;
import com.fstojilj.luddite.sync.server.repository.SyncVersionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Builder;
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
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Accepts inbound mTLS connections from sync clients and pushes file-change events
 * to all subscribed clients in real time.
 *
 * <p>On startup three virtual threads are launched:
 * <ul>
 *   <li><b>client-acceptor</b> — accepts new SSL connections and spawns a per-client thread.</li>
 *   <li><b>buffer-drain</b> — polls {@link FileEventService} every 50 ms and fans out
 *       pending {@link FileChangeEvent}s to every subscribed {@link ClientSession}.</li>
 *   <li><b>pending-ack-cleanup</b> — periodically removes {@link PendingAck} entries that
 *       have not been acknowledged within the configured TTL, so memory does not grow
 *       unboundedly if a client disappears without sending ACKs.</li>
 * </ul>
 *
 * <p>Each connected client goes through a handshake sequence:
 * <ol>
 *   <li>Server advertises all known root directories.</li>
 *   <li>Client sends back a subscription list with its last known sync version per directory.</li>
 *   <li>Server performs a catch-up send for each subscribed directory, replaying all
 *       writes and deletes that occurred since the client's last sync version.</li>
 *   <li>The client enters a live event stream, ACKing each event with its sync version.</li>
 * </ol>
 *
 * <p>For every event sent to a client a unique {@code syncVersion} is minted via
 * {@link SyncVersionRepository}. The version is stamped on the file metadata record (or the
 * deleted-files record) only after the client's ACK is received, ensuring the version
 * accurately reflects what has actually been delivered.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ServerPushService {

    public static final byte EVENT_WRITE = 1;
    public static final byte EVENT_DELETE = 2;
    public static final byte ACK = 3;
    public static final byte SHUTDOWN = 4;
    public static final byte RESUME_SERVER_MODE = 5;

    private final FileEventService fileEventService;
    private final FileMetadataService fileMetadataService;
    private final RootDirService rootDirService;
    private final SyncVersionRepository syncVersionRepository;

    @Value("${sync.socket.port:8888}")
    private int port;

    @Value("${sync.socket.keystore:classpath:server-keystore.p12}")
    private Resource keystoreResource;

    @Value("${sync.socket.truststore:classpath:truststore.p12}")
    private Resource truststoreResource;

    @Value("${sync.socket.password:fichony123!}")
    private String keystorePassword;

    @Value("${sync.socket.pending-ack-ttl-ms:20000}")
    private long pendingAckTtlMs;

    private SSLServerSocket serverSocket;
    private volatile boolean running = false;

    /**
     * Holds the state for a single connected client: the output stream used to push events,
     * the remote address (for logging and CLI display), and the set of root-directory IDs
     * the client has subscribed to.
     */
    @Builder
    protected record ClientSession(DataOutputStream out, String address, Set<Long> subscribedRootDirIds) {
    }

    /**
     * Tracks a single in-flight event sent to one specific client that is still waiting for
     * an ACK. Keyed by the unique {@code syncVersion} minted for that (client, event) pair.
     *
     * @param rootDirId    the root directory the file belongs to
     * @param relativePath the file path relative to the root directory
     * @param eventType    either {@link #EVENT_WRITE} or {@link #EVENT_DELETE}
     * @param sentAt       wall-clock timestamp (ms) when the event was sent, used for TTL eviction
     */
    private record PendingAck(long rootDirId, String relativePath, byte eventType, long sentAt) {
    }

    private final CopyOnWriteArraySet<ClientSession> sessions = new CopyOnWriteArraySet<>();

    /**
     * One entry per (client, event) pair — keyed by the unique syncVersion minted for that pair.
     * TTL is configurable via sync.socket.pending-ack-ttl-ms (default 20s).
     */
    private final ConcurrentHashMap<Long, PendingAck> pendingAcks = new ConcurrentHashMap<>();

    /**
     * Returns the remote addresses of all currently connected clients.
     * Used by the CLI to display connection status.
     *
     * @return list of address strings (e.g. {@code /192.168.1.10:54321})
     */
    public List<String> listConnectedClients() {
        return sessions.stream().map(ClientSession::address).toList();
    }

    /**
     * Sends a {@code RESUME_SERVER_MODE} signal to the client identified by the given address,
     * instructing it to exit with code 2 so its wrapper script restarts it in server mode.
     * After the signal is sent this process also exits with code 3.
     *
     * @param address the remote address string as returned by {@link #listConnectedClients()}
     * @return {@code true} if the signal was sent successfully, {@code false} if no matching
     * session was found or if writing to the socket failed
     */
    public boolean sendResumeServerMode(String address) {
        return sessions.stream()
                .filter(s -> s.address().equals(address))
                .findFirst()
                .map(session -> {
                    try {
                        session.out().writeByte(RESUME_SERVER_MODE);
                        session.out().flush();
                        log.info("RESUME_SERVER_MODE signal sent to {} — exiting with code 3 to restart as client", address);
                        System.exit(3);
                        return true;
                    } catch (IOException e) {
                        log.warn("Failed to send RESUME_SERVER_MODE to {}: {}", address, e.getMessage());
                        return false;
                    }
                })
                .orElse(false);
    }

    /**
     * Initialises the SSL server socket and starts the three background virtual threads
     * (client-acceptor, buffer-drain, pending-ack-cleanup).
     * Invoked automatically by Spring after dependency injection.
     *
     * @throws Exception if the SSL context or server socket cannot be created
     */
    @PostConstruct
    public void start() throws Exception {
        serverSocket = buildSslServerSocket();
        running = true;
        log.info("Listening for clients on port {} (mTLS)", port);
        Thread.ofVirtual().name("client-acceptor").start(this::acceptClients);
        Thread.ofVirtual().name("buffer-drain").start(this::drainLoop);
        Thread.ofVirtual().name("pending-ack-cleanup").start(this::pendingAckCleanupLoop);
    }

    /**
     * Signals the service to stop and closes the server socket, causing the accept loop
     * to exit cleanly. Invoked automatically by Spring during application shutdown.
     */
    @PreDestroy
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException _) {
            log.warn("Error closing server socket");
        }
    }

    /**
     * Periodically scans {@link #pendingAcks} and removes entries whose age exceeds
     * {@code sync.socket.pending-ack-ttl-ms}. A warning is logged for each evicted entry;
     * the affected client will re-receive the file on its next reconnect via the catch-up path.
     */
    private void pendingAckCleanupLoop() {
        while (running) {
            try {
                Thread.sleep(pendingAckTtlMs);
                long now = System.currentTimeMillis();
                pendingAcks.entrySet().removeIf(entry -> {
                    if (now - entry.getValue().sentAt() > pendingAckTtlMs) {
                        log.warn("Pending ACK timed out after {}ms for sync version {} ({}), removing — client will retry on reconnect",
                                pendingAckTtlMs, entry.getKey(), entry.getValue().relativePath());
                        return true;
                    }
                    return false;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Accepts incoming SSL connections in a loop and spawns a virtual thread for each one.
     * Stops when {@link #running} is {@code false} or the server socket is closed.
     */
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

    /**
     * Manages the full lifetime of a single client connection:
     * <ol>
     *   <li>Advertises available root directories.</li>
     *   <li>Reads the client's subscription handshake.</li>
     *   <li>Performs catch-up delivery of missed events.</li>
     *   <li>Reads incoming ACKs (and an optional SHUTDOWN signal) until the socket closes.</li>
     * </ol>
     * When an ACK is received the corresponding {@link PendingAck} entry is removed and
     * {@link FileMetadataService#stampSyncVersion} or {@link FileMetadataService#recordDeletion}
     * is called to persist the delivery confirmation.
     *
     * @param socket the accepted client SSL socket
     */
    private void serveClient(SSLSocket socket) {
        try {
            var in = new DataInputStream(socket.getInputStream());
            var out = new DataOutputStream(socket.getOutputStream());

            // Step 1: advertise available root dirs to the client
            sendAvailableDirs(out);

            // Step 2: read client's subscription handshake
            List<SyncHandshakeEntry> handshake = readHandshake(in);
            log.info("Handshake from {}: {} dir(s)", socket.getRemoteSocketAddress(), handshake.size());

            Set<Long> subscribedIds = resolveSubscribedIds(handshake);
            String address = socket.getRemoteSocketAddress().toString();
            ClientSession session = new ClientSession(out, address, subscribedIds);
            sessions.add(session);
            sendCatchUp(handshake, session);
            log.info("Client {} is now live, subscribed to {} dir(s)",
                    socket.getRemoteSocketAddress(), subscribedIds.size());

            // Read ACKs from client: [1 byte ACK] [8 bytes syncVersion]
            // or a SHUTDOWN signal: [1 byte SHUTDOWN]
            while (true) {
                byte msg = in.readByte();
                if (msg == ACK) {
                    long syncVersion = in.readLong();
                    PendingAck pending = pendingAcks.remove(syncVersion);
                    if (pending != null) {
                        if (pending.eventType() == EVENT_WRITE) {
                            fileMetadataService.stampSyncVersion(pending.rootDirId(), pending.relativePath(), syncVersion);
                        } else {
                            fileMetadataService.recordDeletion(pending.rootDirId(), pending.relativePath(), syncVersion);
                        }
                        syncVersionRepository.markSynced(syncVersion);
                        log.debug("ACK received and stamped for sync version {}", syncVersion);
                    } else {
                        log.warn("ACK for unknown sync version {}", syncVersion);
                    }
                } else if (msg == SHUTDOWN) {
                    log.info("Shutdown signal received from client {} — exiting with code 2 to trigger client mode", socket.getRemoteSocketAddress());
                    System.exit(2);
                } else {
                    log.warn("Unexpected byte from client: {}", msg);
                }
            }

        } catch (IOException e) {
            log.info("Client disconnected: {}", socket.getRemoteSocketAddress());
        } finally {
            try {
                socket.close();
            } catch (IOException _) {
            }
            // Session is removed in drainLoop when write fails, or here on disconnect
            sessions.removeIf(_ -> socket.isClosed());
        }
    }

    /**
     * Continuously drains the {@link FileEventService} event buffer (every 50 ms) and
     * pushes each event to all {@link ClientSession}s subscribed to the affected root directory.
     * A unique {@code syncVersion} is minted per (client, event) pair and a {@link PendingAck}
     * entry is registered before the event is written to the wire.
     */
    private void drainLoop() {
        while (running) {
            try {
                for (long rootDirId : fileEventService.activeRootDirIds()) {
                    List<ClientSession> interested = sessions.stream()
                            .filter(s -> s.subscribedRootDirIds().contains(rootDirId))
                            .toList();

                    // Don't drain if nobody is listening — events would be lost
                    if (interested.isEmpty()) continue;

                    List<FileChangeEvent> events = fileEventService.drainForRootDir(rootDirId);
                    if (events.isEmpty()) continue;

                    // Look up dir name once per rootDirId, not per event
                    String dirName = rootDirService.getRootDirNameById(rootDirId);

                    for (FileChangeEvent event : events) {
                        byte eventType = event.getEventKind().name().equals("ENTRY_DELETE")
                                ? EVENT_DELETE : EVENT_WRITE;

                        String qualifiedPath = dirName + "/" + event.getRelativePath();
                        byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);

                        // Mint one syncVersion per client — each client ACKs its own version.
                        // stampSyncVersion is idempotent so the first ACK to arrive stamps the file;
                        // subsequent ACKs for the same file just call stamp again with the same value,
                        // which is a safe no-op.
                        for (ClientSession session : interested) {
                            long syncVersion = syncVersionRepository.next();
                            pendingAcks.put(syncVersion, new PendingAck(
                                    rootDirId, event.getRelativePath(), eventType, System.currentTimeMillis()));
                            writeToClient(session, eventType, pathBytes,
                                    event.getAbsoluteFilePath(), qualifiedPath,
                                    event.getEventKind().name(), syncVersion);
                        }
                    }
                }
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Sends the list of all known root directory names to the client so it can decide
     * which directories to subscribe to.
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
     * @throws IOException if writing to the stream fails
     */
    private void sendAvailableDirs(DataOutputStream out) throws IOException {
        List<String> dirNames = rootDirService.findAll().stream()
                .map(dir -> dir.getName())
                .toList();
        out.writeInt(dirNames.size());
        for (String name : dirNames) {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            out.writeInt(nameBytes.length);
            out.write(nameBytes);
        }
        out.flush();
        log.info("Advertised {} available dir(s) to client", dirNames.size());
    }

    /**
     * Reads the subscription handshake sent by the client.
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
     * @return list of {@link SyncHandshakeEntry} records, one per requested directory
     * @throws IOException if reading from the stream fails
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
     * Resolves the client-supplied directory names from the handshake to their server-side
     * {@code rootDirId} values. Unknown directory names are skipped with a warning.
     *
     * @param handshake the entries received from the client
     * @return set of {@code rootDirId} values the client is authorised to receive
     */
    private Set<Long> resolveSubscribedIds(List<SyncHandshakeEntry> handshake) {
        Set<Long> ids = new HashSet<>();
        for (SyncHandshakeEntry entry : handshake) {
            rootDirService.findByName(entry.dirName())
                    .ifPresentOrElse(
                            dir -> ids.add(dir.getId()),
                            () -> log.warn("Client requested unknown dir '{}', skipping", entry.dirName())
                    );
        }
        return ids;
    }

    /**
     * Replays all writes and deletes that occurred since each directory's
     * {@code lastSyncVersion} reported in the handshake, including files that were added
     * but have never been delivered to any client (i.e. those with {@code sync_version IS NULL}).
     *
     * <p>A fresh {@code syncVersion} is minted for every event sent during catch-up and a
     * {@link PendingAck} entry is registered so the normal ACK path stamps the file and marks
     * the version as {@code SYNCED} once the client confirms receipt.
     *
     * @param handshake the entries received from the client, containing dir names and last versions
     * @param session   the client session to write events to
     * @throws IOException if writing to the client's output stream fails
     */
    private void sendCatchUp(List<SyncHandshakeEntry> handshake, ClientSession session) throws IOException {
        for (SyncHandshakeEntry entry : handshake) {
            var rootDir = rootDirService.findByName(entry.dirName());
            if (rootDir.isEmpty()) continue;

            long rootDirId = rootDir.get().getId();
            String rootAbsPath = rootDir.get().getAbsolutePath();

            // Send files created/modified since lastSyncVersion
            var files = fileMetadataService.findFilesNewerThan(rootDirId, entry.lastSyncVersion());
            log.info("Sending {} catch-up file(s) for dir '{}'", files.size(), entry.dirName());
            for (var file : files) {
                // relativePath may be stored with a leading separator (e.g. "/foo.txt") — strip it
                String relativePathNormalized = file.relativePath().replaceAll("^[/\\\\]+", "");
                String absPath = Path.of(rootAbsPath).resolve(relativePathNormalized).toString();
                String qualifiedPath = entry.dirName() + "/" + relativePathNormalized;
                byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);
                byte[] fileBytes = Files.readAllBytes(Path.of(absPath));
                // Mint a new syncVersion so the ACK is tracked and stampSyncVersion is called
                long syncVersion = syncVersionRepository.next();
                pendingAcks.put(syncVersion, new PendingAck(
                        rootDirId, file.relativePath(), EVENT_WRITE, System.currentTimeMillis()));
                synchronized (session.out()) {
                    session.out().writeByte(EVENT_WRITE);
                    session.out().writeInt(pathBytes.length);
                    session.out().write(pathBytes);
                    session.out().writeLong(syncVersion);
                    session.out().writeLong(fileBytes.length);
                    session.out().write(fileBytes);
                    session.out().flush();
                }
                log.debug("Catch-up WRITE (v{}) {}", syncVersion, qualifiedPath);
            }

            // Send deletes that happened since lastSyncVersion
            var deletes = fileMetadataService.findDeletesNewerThan(rootDirId, entry.lastSyncVersion());
            log.info("Sending {} catch-up delete(s) for dir '{}'", deletes.size(), entry.dirName());
            for (var delete : deletes) {
                String relativePath = (String) delete.get("relative_path");
                String qualifiedPath = entry.dirName() + "/" + relativePath;
                byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);
                long syncVersion = syncVersionRepository.next();
                pendingAcks.put(syncVersion, new PendingAck(
                        rootDirId, relativePath, EVENT_DELETE, System.currentTimeMillis()));
                synchronized (session.out()) {
                    session.out().writeByte(EVENT_DELETE);
                    session.out().writeInt(pathBytes.length);
                    session.out().write(pathBytes);
                    session.out().writeLong(syncVersion);
                    session.out().writeLong(0L);
                    session.out().flush();
                }
                log.debug("Catch-up DELETE (v{}) {}", syncVersion, qualifiedPath);
            }
        }
    }

    /**
     * Writes a single file-change event to a client session.
     * For {@link #EVENT_WRITE} events the file bytes are read from disk and included in the
     * payload. For {@link #EVENT_DELETE} events a zero-length body is sent.
     *
     * <p>Wire format:
     * <pre>
     * [1 byte]  event type (EVENT_WRITE or EVENT_DELETE)
     * [4 bytes] path length (int)
     * [N bytes] qualified path (UTF-8, "dirName/relativePath")
     * [8 bytes] syncVersion (long)
     * [8 bytes] file size in bytes (long; 0 for deletes)
     * [M bytes] file content (omitted for deletes)
     * </pre>
     *
     * @param session      the target client session
     * @param eventType    {@link #EVENT_WRITE} or {@link #EVENT_DELETE}
     * @param pathBytes    UTF-8-encoded qualified path
     * @param absolutePath absolute file path on the server (used to read bytes for writes)
     * @param relativePath relative path for logging
     * @param kindName     event kind name for logging
     * @param syncVersion  the unique sync version assigned to this (client, event) pair
     */
    private void writeToClient(ClientSession session, byte eventType, byte[] pathBytes,
                               String absolutePath, String relativePath, String kindName,
                               long syncVersion) {
        try {
            DataOutputStream out = session.out();
            synchronized (out) {
                out.writeByte(eventType);
                out.writeInt(pathBytes.length);
                out.write(pathBytes);
                out.writeLong(syncVersion);

                if (eventType == EVENT_WRITE) {
                    byte[] fileBytes = Files.readAllBytes(Path.of(absolutePath));
                    out.writeLong(fileBytes.length);
                    out.write(fileBytes);
                } else {
                    out.writeLong(0L);
                }

                out.flush();
            }
            log.debug("Pushed {} (v{}) to client: {}", kindName, syncVersion, relativePath);
        } catch (IOException e) {
            log.warn("Failed to push to client, removing session: {}", e.getMessage());
            sessions.remove(session);
        }
    }

    /**
     * Builds a mutually-authenticated TLS server socket bound to the configured port.
     * Loads the server keystore (for server-auth) and the truststore (for client verification)
     * and enables mandatory client authentication ({@code setNeedClientAuth(true)}).
     *
     * @return a bound and listening {@link SSLServerSocket}
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
