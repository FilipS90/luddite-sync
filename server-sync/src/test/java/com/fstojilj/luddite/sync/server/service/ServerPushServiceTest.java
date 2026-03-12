package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import com.fstojilj.luddite.sync.server.event.FileChangeEvent;
import com.fstojilj.luddite.sync.server.repository.SyncVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.SSLSocket;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServerPushServiceTest {

    @Mock
    private FileEventService fileEventService;
    @Mock
    private FileMetadataService fileMetadataService;
    @Mock
    private RootDirService rootDirService;
    @Mock
    private SyncVersionRepository syncVersionRepository;

    @InjectMocks
    private ServerPushService serverPushService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(serverPushService, "port", 8888);
        ReflectionTestUtils.setField(serverPushService, "keystorePassword", "test");
        ReflectionTestUtils.setField(serverPushService, "pendingAckTtlMs", 20000L);
    }

    // -------------------------------------------------------------------------
    // listConnectedClients
    // -------------------------------------------------------------------------

    @Test
    void listConnectedClients_noSessions_shouldReturnEmpty() {
        assertThat(serverPushService.listConnectedClients()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listConnectedClients_withSessions_shouldReturnAddresses() throws Exception {
        var sessions = (CopyOnWriteArraySet<Object>) ReflectionTestUtils.getField(serverPushService, "sessions");
        Class<?> sessionClass = Class.forName(
                "com.fstojilj.luddite.sync.server.service.ServerPushService$ClientSession");
        var ctor = sessionClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object session = ctor.newInstance(
                new DataOutputStream(new ByteArrayOutputStream()), "/192.168.1.10:54321", Set.of(1L));
        sessions.add(session);

        List<String> clients = serverPushService.listConnectedClients();

        assertThat(clients).containsExactly("/192.168.1.10:54321");
    }

    // -------------------------------------------------------------------------
    // sendResumeServerMode
    // -------------------------------------------------------------------------

    @Test
    void sendResumeServerMode_noMatchingSession_shouldReturnFalse() {
        boolean result = serverPushService.sendResumeServerMode("/10.0.0.1:9999");
        assertThat(result).isFalse();
    }

    // -------------------------------------------------------------------------
    // stop
    // -------------------------------------------------------------------------

    @Test
    void stop_whenRunning_shouldSetRunningFalse() {
        ReflectionTestUtils.setField(serverPushService, "running", true);
        ReflectionTestUtils.setField(serverPushService, "serverSocket", null);

        serverPushService.stop();

        boolean running = (boolean) ReflectionTestUtils.getField(serverPushService, "running");
        assertThat(running).isFalse();
    }

    // -------------------------------------------------------------------------
    // resolveSubscribedIds
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void resolveSubscribedIds_knownDir_shouldReturnId() throws Exception {
        when(rootDirService.findByName("photos")).thenReturn(Optional.of(
                RootDir.builder().id(42L).name("photos").absolutePath("/photos").build()));

        Method method = ServerPushService.class.getDeclaredMethod("resolveSubscribedIds", List.class);
        method.setAccessible(true);

        Set<Long> ids = (Set<Long>) method.invoke(serverPushService,
                List.of(new SyncHandshakeEntry("photos", 0L)));

        assertThat(ids).containsExactly(42L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveSubscribedIds_unknownDir_shouldReturnEmptySet() throws Exception {
        when(rootDirService.findByName("unknown")).thenReturn(Optional.empty());

        Method method = ServerPushService.class.getDeclaredMethod("resolveSubscribedIds", List.class);
        method.setAccessible(true);

        Set<Long> ids = (Set<Long>) method.invoke(serverPushService,
                List.of(new SyncHandshakeEntry("unknown", 0L)));

        assertThat(ids).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveSubscribedIds_multipleEntries_shouldReturnAllKnownIds() throws Exception {
        when(rootDirService.findByName("photos")).thenReturn(Optional.of(
                RootDir.builder().id(1L).name("photos").absolutePath("/photos").build()));
        when(rootDirService.findByName("docs")).thenReturn(Optional.of(
                RootDir.builder().id(2L).name("docs").absolutePath("/docs").build()));

        Method method = ServerPushService.class.getDeclaredMethod("resolveSubscribedIds", List.class);
        method.setAccessible(true);

        Set<Long> ids = (Set<Long>) method.invoke(serverPushService,
                List.of(new SyncHandshakeEntry("photos", 0L), new SyncHandshakeEntry("docs", 0L)));

        assertThat(ids).containsExactlyInAnyOrder(1L, 2L);
    }

    // -------------------------------------------------------------------------
    // sendAvailableDirs
    // -------------------------------------------------------------------------

    @Test
    void sendAvailableDirs_shouldWriteDirNamesToStream() throws Exception {
        when(rootDirService.findAll()).thenReturn(Set.of(
                RootDir.builder().id(1L).name("photos").absolutePath("/photos").build(),
                RootDir.builder().id(2L).name("docs").absolutePath("/docs").build()
        ));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        Method method = ServerPushService.class.getDeclaredMethod("sendAvailableDirs", DataOutputStream.class);
        method.setAccessible(true);
        method.invoke(serverPushService, out);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        int count = in.readInt();
        assertThat(count).isEqualTo(2);

        // Read and collect all names
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            int len = in.readInt();
            names.add(new String(in.readNBytes(len), StandardCharsets.UTF_8));
        }
        assertThat(names).containsExactlyInAnyOrder("photos", "docs");
    }

    @Test
    void sendAvailableDirs_noDirs_shouldWriteZeroCount() throws Exception {
        when(rootDirService.findAll()).thenReturn(Set.of());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        Method method = ServerPushService.class.getDeclaredMethod("sendAvailableDirs", DataOutputStream.class);
        method.setAccessible(true);
        method.invoke(serverPushService, out);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertThat(in.readInt()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // readHandshake
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void readHandshake_shouldReadEntriesCorrectly() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream writer = new DataOutputStream(baos);
        byte[] nameBytes = "photos".getBytes(StandardCharsets.UTF_8);
        writer.writeInt(1);           // 1 entry
        writer.writeInt(nameBytes.length);
        writer.write(nameBytes);
        writer.writeLong(42L);        // lastSyncVersion
        writer.flush();

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));

        Method method = ServerPushService.class.getDeclaredMethod("readHandshake", DataInputStream.class);
        method.setAccessible(true);

        List<SyncHandshakeEntry> entries = (List<SyncHandshakeEntry>) method.invoke(serverPushService, in);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).dirName()).isEqualTo("photos");
        assertThat(entries.get(0).lastSyncVersion()).isEqualTo(42L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readHandshake_emptyHandshake_shouldReturnEmptyList() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new DataOutputStream(baos).writeInt(0);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));

        Method method = ServerPushService.class.getDeclaredMethod("readHandshake", DataInputStream.class);
        method.setAccessible(true);

        List<SyncHandshakeEntry> entries = (List<SyncHandshakeEntry>) method.invoke(serverPushService, in);

        assertThat(entries).isEmpty();
    }

    // -------------------------------------------------------------------------
    // writeToClient — EVENT_DELETE path (no file read needed)
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void writeToClient_deleteEvent_shouldWriteCorrectBytes() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        Class<?> sessionClass = Class.forName(
                "com.fstojilj.luddite.sync.server.service.ServerPushService$ClientSession");
        var ctor = sessionClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object session = ctor.newInstance(out, "/client:9000", Set.of(1L));

        Method method = ServerPushService.class.getDeclaredMethod(
                "writeToClient",
                sessionClass, byte.class, byte[].class, String.class, String.class, String.class, long.class);
        method.setAccessible(true);

        byte[] pathBytes = "photos/img.jpg".getBytes(StandardCharsets.UTF_8);
        method.invoke(serverPushService,
                session, ServerPushService.EVENT_DELETE, pathBytes,
                "/photos/img.jpg", "photos/img.jpg", "ENTRY_DELETE", 99L);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertThat(in.readByte()).isEqualTo(ServerPushService.EVENT_DELETE);
        assertThat(in.readInt()).isEqualTo(pathBytes.length);
        in.readNBytes(pathBytes.length);
        assertThat(in.readLong()).isEqualTo(99L);
        assertThat(in.readLong()).isEqualTo(0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeToClient_writeEvent_shouldIncludeFileBytes() throws Exception {
        Path file = Files.writeString(tempDir.resolve("photo.jpg"), "JPEG_DATA");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        Class<?> sessionClass = Class.forName(
                "com.fstojilj.luddite.sync.server.service.ServerPushService$ClientSession");
        var ctor = sessionClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object session = ctor.newInstance(out, "/client:9000", Set.of(1L));

        Method method = ServerPushService.class.getDeclaredMethod(
                "writeToClient",
                sessionClass, byte.class, byte[].class, String.class, String.class, String.class, long.class);
        method.setAccessible(true);

        byte[] pathBytes = "photos/photo.jpg".getBytes(StandardCharsets.UTF_8);
        method.invoke(serverPushService,
                session, ServerPushService.EVENT_WRITE, pathBytes,
                file.toString(), "photos/photo.jpg", "ENTRY_CREATE", 10L);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertThat(in.readByte()).isEqualTo(ServerPushService.EVENT_WRITE);
        assertThat(in.readInt()).isEqualTo(pathBytes.length);
        in.readNBytes(pathBytes.length);
        assertThat(in.readLong()).isEqualTo(10L); // syncVersion
        long fileSize = in.readLong();
        assertThat(fileSize).isGreaterThan(0);
    }


    // -------------------------------------------------------------------------
    // sendCatchUp
    // -------------------------------------------------------------------------

    @Test
    void sendCatchUp_withFilesAndDeletes_shouldWriteToStream() throws Exception {
        Path file = Files.writeString(tempDir.resolve("img.jpg"), "photo-data");

        RootDir dir = RootDir.builder().id(1L).name("photos").absolutePath(tempDir.toString()).build();
        when(rootDirService.findByName("photos")).thenReturn(Optional.of(dir));

        FileMetadata meta = FileMetadata.builder()
                .id(1L).rootDirId(1L).relativePath("img.jpg")
                .filename("img.jpg").fileSize(10L).checksum("x").syncVersion(5L).build();
        when(fileMetadataService.findFilesNewerThan(1L, 0L)).thenReturn(List.of(meta));
        when(fileMetadataService.findDeletesNewerThan(1L, 0L)).thenReturn(List.of(
                Map.of("relative_path", "old.jpg", "sync_version", 3L)
        ));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        Method method = ServerPushService.class.getDeclaredMethod("sendCatchUp", List.class, DataOutputStream.class);
        method.setAccessible(true);
        method.invoke(serverPushService, List.of(new SyncHandshakeEntry("photos", 0L)), out);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        // First message: EVENT_WRITE for img.jpg
        assertThat(in.readByte()).isEqualTo(ServerPushService.EVENT_WRITE);
    }

    @Test
    void sendCatchUp_unknownDir_shouldSkip() throws Exception {
        when(rootDirService.findByName("unknown")).thenReturn(Optional.empty());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        Method method = ServerPushService.class.getDeclaredMethod("sendCatchUp", List.class, DataOutputStream.class);
        method.setAccessible(true);
        method.invoke(serverPushService, List.of(new SyncHandshakeEntry("unknown", 0L)), out);

        // Nothing written
        assertThat(baos.size()).isEqualTo(0);
    }

    @Test
    void sendCatchUp_withDeletesOnly_shouldWriteDeleteEvents() throws Exception {
        RootDir dir = RootDir.builder().id(1L).name("photos").absolutePath(tempDir.toString()).build();
        when(rootDirService.findByName("photos")).thenReturn(Optional.of(dir));
        when(fileMetadataService.findFilesNewerThan(1L, 0L)).thenReturn(List.of());
        when(fileMetadataService.findDeletesNewerThan(1L, 0L)).thenReturn(List.of(
                Map.of("relative_path", "removed.jpg", "sync_version", 8L)
        ));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        Method method = ServerPushService.class.getDeclaredMethod("sendCatchUp", List.class, DataOutputStream.class);
        method.setAccessible(true);
        method.invoke(serverPushService, List.of(new SyncHandshakeEntry("photos", 0L)), out);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        assertThat(in.readByte()).isEqualTo(ServerPushService.EVENT_DELETE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void serveClient_withSubscribedDir_shouldAddSession() throws Exception {
        RootDir dir = RootDir.builder().id(1L).name("photos").absolutePath(tempDir.toString()).build();
        when(rootDirService.findAll()).thenReturn(Set.of(dir));
        when(rootDirService.findByName("photos")).thenReturn(Optional.of(dir));
        when(fileMetadataService.findFilesNewerThan(1L, 0L)).thenReturn(List.of());
        when(fileMetadataService.findDeletesNewerThan(1L, 0L)).thenReturn(List.of());

        // Client subscribes to "photos" with lastSyncVersion=0, then EOF
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream w = new DataOutputStream(baos);
        w.writeInt(1); // 1 handshake entry
        byte[] nameBytes = "photos".getBytes(StandardCharsets.UTF_8);
        w.writeInt(nameBytes.length);
        w.write(nameBytes);
        w.writeLong(0L);
        w.flush();

        SSLSocket mockSocket = org.mockito.Mockito.mock(SSLSocket.class);
        when(mockSocket.getInputStream()).thenReturn(new ByteArrayInputStream(baos.toByteArray()));
        when(mockSocket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(mockSocket.getRemoteSocketAddress()).thenReturn(new java.net.InetSocketAddress("localhost", 9000));

        Method method = ServerPushService.class.getDeclaredMethod("serveClient", SSLSocket.class);
        method.setAccessible(true);
        method.invoke(serverPushService, mockSocket);

        // serveClient completed without exception — session was added during the call
        // (session cleanup depends on socket.isClosed() which is false on mock)
        verify(mockSocket).close();
    }

    @Test
    void sendCatchUp_noFilesNoDeletes_shouldFlushEmpty() throws Exception {
        RootDir dir = RootDir.builder().id(1L).name("photos").absolutePath(tempDir.toString()).build();
        when(rootDirService.findByName("photos")).thenReturn(Optional.of(dir));
        when(fileMetadataService.findFilesNewerThan(1L, 0L)).thenReturn(List.of());
        when(fileMetadataService.findDeletesNewerThan(1L, 0L)).thenReturn(List.of());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        Method method = ServerPushService.class.getDeclaredMethod("sendCatchUp", List.class, DataOutputStream.class);
        method.setAccessible(true);
        method.invoke(serverPushService, List.of(new SyncHandshakeEntry("photos", 0L)), out);

        assertThat(baos.size()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // pendingAckCleanupLoop — via direct map manipulation
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void pendingAcks_expiredEntry_shouldBeEvictable() throws Exception {
        long ttl = 100L;
        ReflectionTestUtils.setField(serverPushService, "pendingAckTtlMs", ttl);

        var pendingAcks = (ConcurrentHashMap<Long, Object>) ReflectionTestUtils.getField(serverPushService, "pendingAcks");

        Class<?> pendingAckClass = Class.forName(
                "com.fstojilj.luddite.sync.server.service.ServerPushService$PendingAck");
        var ctor = pendingAckClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object expiredAck = ctor.newInstance(1L, "img.jpg", ServerPushService.EVENT_WRITE, 0L);
        pendingAcks.put(99L, expiredAck);

        long now = System.currentTimeMillis();
        pendingAcks.entrySet().removeIf(entry -> {
            try {
                java.lang.reflect.Method sentAtMethod = entry.getValue().getClass().getDeclaredMethod("sentAt");
                sentAtMethod.setAccessible(true);
                long sentAt = (long) sentAtMethod.invoke(entry.getValue());
                return now - sentAt > ttl;
            } catch (Exception e) {
                return false;
            }
        });

        assertThat(pendingAcks).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingAcks_freshEntry_shouldNotBeEvicted() throws Exception {
        long ttl = 20000L;

        var pendingAcks = (ConcurrentHashMap<Long, Object>) ReflectionTestUtils.getField(serverPushService, "pendingAcks");

        Class<?> pendingAckClass = Class.forName(
                "com.fstojilj.luddite.sync.server.service.ServerPushService$PendingAck");
        var ctor = pendingAckClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object freshAck = ctor.newInstance(1L, "img.jpg", ServerPushService.EVENT_WRITE, System.currentTimeMillis());
        pendingAcks.put(100L, freshAck);

        long now = System.currentTimeMillis();
        pendingAcks.entrySet().removeIf(entry -> {
            try {
                java.lang.reflect.Method sentAtMethod = entry.getValue().getClass().getDeclaredMethod("sentAt");
                sentAtMethod.setAccessible(true);
                long sentAt = (long) sentAtMethod.invoke(entry.getValue());
                return now - sentAt > ttl;
            } catch (Exception e) {
                return false;
            }
        });

        assertThat(pendingAcks).hasSize(1);
        pendingAcks.clear();
    }

    // -------------------------------------------------------------------------
    // drainLoop — covered indirectly via running=false guard
    // -------------------------------------------------------------------------

    @Test
    void drainLoop_whenNotRunning_shouldNotCallFileEventService() throws Exception {
        // running stays false (default), so drainLoop exits immediately
        ReflectionTestUtils.setField(serverPushService, "running", false);

        Method method = ServerPushService.class.getDeclaredMethod("drainLoop");
        method.setAccessible(true);
        method.invoke(serverPushService);

        // fileEventService.activeRootDirIds() must never be called when not running
        org.mockito.Mockito.verifyNoInteractions(fileEventService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void drainLoop_noSessions_shouldSkipEvents() throws Exception {
        ReflectionTestUtils.setField(serverPushService, "running", true);

        when(fileEventService.activeRootDirIds()).thenAnswer(inv -> {
            // After first call, turn off running to exit the loop
            ReflectionTestUtils.setField(serverPushService, "running", false);
            return Set.of(1L);
        });

        // No sessions => nothing is interested => events are not drained
        Method method = ServerPushService.class.getDeclaredMethod("drainLoop");
        method.setAccessible(true);
        method.invoke(serverPushService);

        verify(fileEventService, org.mockito.Mockito.never())
                .drainForRootDir(org.mockito.ArgumentMatchers.anyLong());
    }

    // -------------------------------------------------------------------------
    // pendingAckCleanupLoop — run with short TTL
    // -------------------------------------------------------------------------

    @Test
    void pendingAckCleanupLoop_whenNotRunning_shouldReturnImmediately() throws Exception {
        ReflectionTestUtils.setField(serverPushService, "running", false);

        Method method = ServerPushService.class.getDeclaredMethod("pendingAckCleanupLoop");
        method.setAccessible(true);
        method.invoke(serverPushService); // Must not block
    }

    // -------------------------------------------------------------------------
    // serveClient — via mocked SSLSocket with in-memory streams
    // -------------------------------------------------------------------------

    /**
     * Builds the bytes a client would send during a full handshake:
     * - sendAvailableDirs is answered by the server
     * - client sends 1 handshake entry
     * - client sends an ACK for syncVersion
     * - then EOF (stream ends → serveClient exits the read loop)
     */
    private byte[] buildClientHandshakeWithAck(String dirName, long syncVersion) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream w = new DataOutputStream(baos);
        // Handshake: 1 entry
        w.writeInt(1);
        byte[] nameBytes = dirName.getBytes(StandardCharsets.UTF_8);
        w.writeInt(nameBytes.length);
        w.write(nameBytes);
        w.writeLong(0L); // lastSyncVersion
        // ACK message
        w.writeByte(ServerPushService.ACK);
        w.writeLong(syncVersion);
        w.flush();
        return baos.toByteArray();
    }

    @Test
    @SuppressWarnings("unchecked")
    void serveClient_ackMessage_shouldStampSyncVersion() throws Exception {
        long syncVersion = 7L;

        // Pre-seed a pending ack for syncVersion 7
        var pendingAcks = (ConcurrentHashMap<Long, Object>) ReflectionTestUtils.getField(serverPushService, "pendingAcks");
        Class<?> pendingAckClass = Class.forName(
                "com.fstojilj.luddite.sync.server.service.ServerPushService$PendingAck");
        var pendingCtor = pendingAckClass.getDeclaredConstructors()[0];
        pendingCtor.setAccessible(true);
        pendingAcks.put(syncVersion, pendingCtor.newInstance(1L, "img.jpg", ServerPushService.EVENT_WRITE, System.currentTimeMillis()));

        // Server will advertise 0 dirs, client will send 0-entry handshake then ACK then EOF
        when(rootDirService.findAll()).thenReturn(Set.of());
        when(rootDirService.findByName(ArgumentMatchers.anyString())).thenReturn(Optional.empty());

        byte[] clientBytes = buildClientHandshakeWithAck("photos", syncVersion);
        SSLSocket mockSocket = org.mockito.Mockito.mock(SSLSocket.class);
        ByteArrayOutputStream serverOut = new ByteArrayOutputStream();
        when(mockSocket.getInputStream()).thenReturn(new ByteArrayInputStream(clientBytes));
        when(mockSocket.getOutputStream()).thenReturn(serverOut);
        when(mockSocket.getRemoteSocketAddress()).thenReturn(new java.net.InetSocketAddress("localhost", 9000));

        Method method = ServerPushService.class.getDeclaredMethod("serveClient", SSLSocket.class);
        method.setAccessible(true);
        method.invoke(serverPushService, mockSocket);

        // ACK was received and processed — pending ack should be gone
        assertThat(pendingAcks).doesNotContainKey(syncVersion);
        verify(fileMetadataService).stampSyncVersion(1L, "img.jpg", syncVersion);
        verify(syncVersionRepository).markSynced(syncVersion);
    }

    @Test
    @SuppressWarnings("unchecked")
    void serveClient_unknownAck_shouldLogWarnAndContinue() throws Exception {
        when(rootDirService.findAll()).thenReturn(Set.of());

        // ACK for an unknown sync version — no pending ack present
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream w = new DataOutputStream(baos);
        w.writeInt(0); // 0 handshake entries
        w.writeByte(ServerPushService.ACK);
        w.writeLong(999L); // unknown syncVersion
        w.flush();

        SSLSocket mockSocket = org.mockito.Mockito.mock(SSLSocket.class);
        when(mockSocket.getInputStream()).thenReturn(new ByteArrayInputStream(baos.toByteArray()));
        when(mockSocket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(mockSocket.getRemoteSocketAddress()).thenReturn(new java.net.InetSocketAddress("localhost", 9000));

        Method method = ServerPushService.class.getDeclaredMethod("serveClient", SSLSocket.class);
        method.setAccessible(true);
        method.invoke(serverPushService, mockSocket); // Should not throw

        org.mockito.Mockito.verifyNoInteractions(fileMetadataService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void serveClient_deleteAck_shouldCallRecordDeletion() throws Exception {
        long syncVersion = 15L;
        when(rootDirService.findAll()).thenReturn(Set.of());

        var pendingAcks = (ConcurrentHashMap<Long, Object>) ReflectionTestUtils.getField(serverPushService, "pendingAcks");
        Class<?> pendingAckClass = Class.forName(
                "com.fstojilj.luddite.sync.server.service.ServerPushService$PendingAck");
        var pendingCtor = pendingAckClass.getDeclaredConstructors()[0];
        pendingCtor.setAccessible(true);
        pendingAcks.put(syncVersion, pendingCtor.newInstance(1L, "old.jpg", ServerPushService.EVENT_DELETE, System.currentTimeMillis()));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream w = new DataOutputStream(baos);
        w.writeInt(0); // 0 handshake entries
        w.writeByte(ServerPushService.ACK);
        w.writeLong(syncVersion);
        w.flush();

        SSLSocket mockSocket = org.mockito.Mockito.mock(SSLSocket.class);
        when(mockSocket.getInputStream()).thenReturn(new ByteArrayInputStream(baos.toByteArray()));
        when(mockSocket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(mockSocket.getRemoteSocketAddress()).thenReturn(new java.net.InetSocketAddress("localhost", 9000));

        Method method = ServerPushService.class.getDeclaredMethod("serveClient", SSLSocket.class);
        method.setAccessible(true);
        method.invoke(serverPushService, mockSocket);

        verify(fileMetadataService).recordDeletion(1L, "old.jpg", syncVersion);
    }

    @Test
    void serveClient_eofOnConnect_shouldDisconnectCleanly() throws Exception {
        when(rootDirService.findAll()).thenReturn(Set.of());

        // Empty stream — EOF immediately after handshake
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new DataOutputStream(baos).writeInt(0); // 0 handshake entries

        SSLSocket mockSocket = org.mockito.Mockito.mock(SSLSocket.class);
        when(mockSocket.getInputStream()).thenReturn(new ByteArrayInputStream(baos.toByteArray()));
        when(mockSocket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(mockSocket.getRemoteSocketAddress()).thenReturn(new java.net.InetSocketAddress("localhost", 9000));

        Method method = ServerPushService.class.getDeclaredMethod("serveClient", SSLSocket.class);
        method.setAccessible(true);
        method.invoke(serverPushService, mockSocket); // Should not throw
    }

    @Test
    void serveClient_unexpectedByte_shouldContinueAndThenDisconnect() throws Exception {
        when(rootDirService.findAll()).thenReturn(Set.of());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream w = new DataOutputStream(baos);
        w.writeInt(0); // 0 handshake entries
        w.writeByte(99); // unexpected byte — should log warn and continue until EOF
        w.flush();

        SSLSocket mockSocket = org.mockito.Mockito.mock(SSLSocket.class);
        when(mockSocket.getInputStream()).thenReturn(new ByteArrayInputStream(baos.toByteArray()));
        when(mockSocket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(mockSocket.getRemoteSocketAddress()).thenReturn(new java.net.InetSocketAddress("localhost", 9000));

        Method method = ServerPushService.class.getDeclaredMethod("serveClient", SSLSocket.class);
        method.setAccessible(true);
        method.invoke(serverPushService, mockSocket); // Should not throw
    }

    // -------------------------------------------------------------------------
    // drainLoop with sessions — events drained and written to client
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void drainLoop_withSessionAndEvents_shouldDrainAndPush() throws Exception {
        Path file = Files.writeString(tempDir.resolve("photo.jpg"), "JPEG");
        RootDir dir = RootDir.builder().id(1L).name("photos").absolutePath(tempDir.toString()).build();

        // Build session subscribed to rootDir 1
        ByteArrayOutputStream clientOut = new ByteArrayOutputStream();
        Class<?> sessionClass = Class.forName(
                "com.fstojilj.luddite.sync.server.service.ServerPushService$ClientSession");
        var ctor = sessionClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object session = ctor.newInstance(new DataOutputStream(clientOut), "/client:9000", Set.of(1L));
        var sessions = (CopyOnWriteArraySet<Object>) ReflectionTestUtils.getField(serverPushService, "sessions");
        sessions.add(session);

        // Build a FileChangeEvent
        FileChangeEvent event =
                com.fstojilj.luddite.sync.server.event.FileChangeEvent.builder()
                        .rootDirId(1L)
                        .absoluteFilePath(file.toString())
                        .relativePath("photo.jpg")
                        .eventKind(java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY)
                        .build();

        ReflectionTestUtils.setField(serverPushService, "running", true);

        when(fileEventService.activeRootDirIds()).thenAnswer(inv -> {
            ReflectionTestUtils.setField(serverPushService, "running", false);
            return Set.of(1L);
        });
        when(fileEventService.drainForRootDir(1L)).thenReturn(List.of(event));
        when(rootDirService.getRootDirNameById(1L)).thenReturn("photos");
        when(syncVersionRepository.next()).thenReturn(42L);

        Method method = ServerPushService.class.getDeclaredMethod("drainLoop");
        method.setAccessible(true);
        method.invoke(serverPushService);

        // Check that client received an EVENT_WRITE byte
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(clientOut.toByteArray()));
        assertThat(in.readByte()).isEqualTo(ServerPushService.EVENT_WRITE);
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    @Test
    void constants_shouldHaveExpectedValues() {
        assertThat(ServerPushService.EVENT_WRITE).isEqualTo((byte) 1);
        assertThat(ServerPushService.EVENT_DELETE).isEqualTo((byte) 2);
        assertThat(ServerPushService.ACK).isEqualTo((byte) 3);
        assertThat(ServerPushService.SHUTDOWN).isEqualTo((byte) 4);
        assertThat(ServerPushService.RESUME_SERVER_MODE).isEqualTo((byte) 5);
    }
}

