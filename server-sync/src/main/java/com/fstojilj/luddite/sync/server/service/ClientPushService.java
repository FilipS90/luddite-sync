package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import com.fstojilj.luddite.sync.server.event.FileChangeEvent;
import com.fstojilj.luddite.sync.server.repository.SyncVersionRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
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

@Service
@Slf4j
@RequiredArgsConstructor
public class ClientPushService {

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
     * One entry per connected client.
     * Holds the output stream and the set of rootDirIds the client subscribed to.
     */
    private record ClientSession(DataOutputStream out, Set<Long> subscribedRootDirIds) {
    }

    /**
     * Tracks an event sent to one specific client, waiting for its ACK.
     * Each client gets its own syncVersion per event, so this entry belongs
     * to exactly one client — no cross-client ACK collisions.
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
     * Sends a RESUME_SERVER_MODE signal to all connected clients.
     * Each client will exit with code 2, causing its wrapper script to restart it as a server.
     */
    public void sendResumeServerMode() {
        sessions.forEach(session -> {
            try {
                session.out().writeByte(RESUME_SERVER_MODE);
                session.out().flush();
                log.info("RESUME_SERVER_MODE signal sent to a client");
            } catch (IOException e) {
                log.warn("Failed to send RESUME_SERVER_MODE to a client: {}", e.getMessage());
            }
        });
    }

    @PostConstruct
    public void start() throws Exception {
        serverSocket = buildSslServerSocket();
        running = true;
        log.info("Listening for clients on port {} (mTLS)", port);
        Thread.ofVirtual().name("client-acceptor").start(this::acceptClients);
        Thread.ofVirtual().name("buffer-drain").start(this::drainLoop);
        Thread.ofVirtual().name("pending-ack-cleanup").start(this::pendingAckCleanupLoop);
    }

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

    // -------------------------------------------------------------------------
    // Pending ACK cleanup
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Accept loop
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Per-client handshake + lifetime
    // -------------------------------------------------------------------------

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
            sendCatchUp(handshake, out);

            ClientSession session = new ClientSession(out, subscribedIds);
            sessions.add(session);
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

    // -------------------------------------------------------------------------
    // Buffer drain loop — polls buffer and pushes to subscribed clients
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Sends all available root dir names to the client so it can decide what to subscribe to.
     * Wire format:
     * [4 bytes] number of dirs (int)
     * per dir:
     * [4 bytes] name length (int)
     * [N bytes] name (UTF-8)
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
     * Reads the handshake sent by the client:
     * [4 bytes] number of entries
     * per entry:
     * [4 bytes] dir name length
     * [N bytes] dir name (UTF-8)
     * [8 bytes] lastSyncVersion
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
     * Maps dir names from the handshake to their server-side rootDirIds, skipping unknown ones.
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
     * For each requested dir, sends all files whose sync_version is newer
     * than what the client reported.
     */
    private void sendCatchUp(List<SyncHandshakeEntry> handshake, DataOutputStream out) throws IOException {
        for (SyncHandshakeEntry entry : handshake) {
            var rootDir = rootDirService.findByName(entry.dirName());
            if (rootDir.isEmpty()) continue;

            long rootDirId = rootDir.get().getId();
            String rootAbsPath = rootDir.get().getAbsolutePath();

            // Send files created/modified since lastSyncVersion
            var files = fileMetadataService.findFilesNewerThan(rootDirId, entry.lastSyncVersion());
            log.info("Sending {} catch-up file(s) for dir '{}'", files.size(), entry.dirName());
            for (var file : files) {
                String absPath = Path.of(rootAbsPath).resolve(file.getRelativePath()).toString();
                String qualifiedPath = entry.dirName() + "/" + file.getRelativePath();
                byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);
                byte[] fileBytes = Files.readAllBytes(Path.of(absPath));
                out.writeByte(EVENT_WRITE);
                out.writeInt(pathBytes.length);
                out.write(pathBytes);
                out.writeLong(file.getSyncVersion());
                out.writeLong(fileBytes.length);
                out.write(fileBytes);
                log.debug("Catch-up WRITE (v{}) {}", file.getSyncVersion(), qualifiedPath);
            }

            // Send deletes that happened since lastSyncVersion
            var deletes = fileMetadataService.findDeletesNewerThan(rootDirId, entry.lastSyncVersion());
            log.info("Sending {} catch-up delete(s) for dir '{}'", deletes.size(), entry.dirName());
            for (var delete : deletes) {
                String relativePath = (String) delete.get("relative_path");
                long syncVersion = ((Number) delete.get("sync_version")).longValue();
                String qualifiedPath = entry.dirName() + "/" + relativePath;
                byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);
                out.writeByte(EVENT_DELETE);
                out.writeInt(pathBytes.length);
                out.write(pathBytes);
                out.writeLong(syncVersion);
                out.writeLong(0L);
                log.debug("Catch-up DELETE (v{}) {}", syncVersion, qualifiedPath);
            }

            out.flush();
        }
    }

    private void writeToClient(ClientSession session, byte eventType, byte[] pathBytes,
                               String absolutePath, String relativePath, String kindName,
                               long syncVersion) {
        try {
            DataOutputStream out = session.out();
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


            log.debug("Pushed {} (v{}) to client: {}", kindName, syncVersion, relativePath);
        } catch (IOException e) {
            log.warn("Failed to push to client, removing session: {}", e.getMessage());
            sessions.remove(session);
        }
    }

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

