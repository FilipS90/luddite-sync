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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClientPushService {

    public static final byte EVENT_WRITE = 1;
    public static final byte EVENT_DELETE = 2;

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

    private SSLServerSocket serverSocket;
    private volatile boolean running = false;

    /**
     * One entry per connected client.
     * Holds the output stream and the set of rootDirIds the client subscribed to.
     */
    private record ClientSession(DataOutputStream out, Set<Long> subscribedRootDirIds) {
    }

    private final CopyOnWriteArraySet<ClientSession> sessions = new CopyOnWriteArraySet<>();

    @PostConstruct
    public void start() throws Exception {
        serverSocket = buildSslServerSocket();
        running = true;
        log.info("Listening for clients on port {} (mTLS)", port);
        Thread.ofVirtual().name("client-acceptor").start(this::acceptClients);
        Thread.ofVirtual().name("buffer-drain").start(this::drainLoop);
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

            List<SyncHandshakeEntry> handshake = readHandshake(in);
            log.info("Handshake from {}: {} dir(s)", socket.getRemoteSocketAddress(), handshake.size());

            Set<Long> subscribedIds = resolveSubscribedIds(handshake);
            sendCatchUp(handshake, out);

            ClientSession session = new ClientSession(out, subscribedIds);
            sessions.add(session);
            log.info("Client {} is now live, subscribed to {} dir(s)",
                    socket.getRemoteSocketAddress(), subscribedIds.size());

            // Park until the client disconnects
            in.transferTo(java.io.OutputStream.nullOutputStream());

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
                    List<FileChangeEvent> events = fileEventService.drainForRootDir(rootDirId);
                    if (events.isEmpty()) continue;

                    List<ClientSession> interested = sessions.stream()
                            .filter(s -> s.subscribedRootDirIds().contains(rootDirId))
                            .toList();

                    if (interested.isEmpty()) continue;

                    for (FileChangeEvent event : events) {
                        byte eventType = event.getEventKind().name().equals("ENTRY_DELETE")
                                ? EVENT_DELETE : EVENT_WRITE;
                        byte[] pathBytes = event.getRelativePath().getBytes(StandardCharsets.UTF_8);

                        for (ClientSession session : interested) {
                            writeToClient(session, eventType, pathBytes,
                                    event.getAbsoluteFilePath(), event.getRelativePath(),
                                    event.getEventKind().name(), rootDirId);
                        }
                    }
                }
                Thread.sleep(50); // brief pause to avoid busy-spinning
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
        Set<Long> ids = new java.util.HashSet<>();
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
            var files = fileMetadataService.findFilesNewerThan(rootDirId, entry.lastSyncVersion());
            log.info("Sending {} catch-up file(s) for dir '{}'", files.size(), entry.dirName());

            for (var file : files) {
                String absPath = Path.of(rootAbsPath).resolve(file.getRelativePath()).toString();
                byte[] pathBytes = file.getRelativePath().getBytes(StandardCharsets.UTF_8);
                ClientSession tmp = new ClientSession(out, Set.of());
                writeToClient(tmp, EVENT_WRITE, pathBytes, absPath, file.getRelativePath(),
                        "CATCH_UP", rootDirId);
            }
        }
    }

    private void writeToClient(ClientSession session, byte eventType, byte[] pathBytes,
                               String absolutePath, String relativePath, String kindName,
                               long rootDirId) {
        try {
            long syncVersion = syncVersionRepository.next();

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

            // Stamp the version back so future clients know this file has been synced
            if (eventType == EVENT_WRITE) {
                fileMetadataService.stampSyncVersion(rootDirId, relativePath, syncVersion);
            }

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

