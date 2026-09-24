package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.repository.RootDirRepository;
import com.fstojilj.luddite.sync.server.repository.SyncTimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileSocketServiceTest {

    @Mock
    private FileMetadataService fileMetadataService;
    @Mock
    private RootDirRepository rootDirRepository;
    @Mock
    private SyncTimeRepository syncTimeRepository;
    @Mock
    private AuthCacheService authCacheService;

    @InjectMocks
    private FileSocketService fileSocketService;

    @TempDir
    Path tempDir;

    // ── sendFileBytes — the payload matches the announced length ─────────────

    @Test
    void sendFileBytes_sendsExactlyTheAnnouncedCount() throws Exception {
        byte[] content = "small file content".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(tempDir.resolve("small.bin"), content);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeSendFileBytes(new DataOutputStream(baos), file, content.length);

        assertThat(baos.toByteArray()).isEqualTo(content);
    }

    @Test
    void sendFileBytes_fileGrewSinceHeader_sendsOnlyTheAnnouncedCount() throws Exception {
        byte[] content = "announced-part-and-then-some-more".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(tempDir.resolve("grown.bin"), content);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeSendFileBytes(new DataOutputStream(baos), file, 14);

        assertThat(baos.toByteArray()).isEqualTo("announced-part".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sendFileBytes_fileShorterThanAnnounced_throwsEOFException() throws Exception {
        Path file = Files.write(tempDir.resolve("short.bin"), "five5".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() ->
                invokeSendFileBytes(new DataOutputStream(new ByteArrayOutputStream()), file, 50))
                .isInstanceOf(EOFException.class);
    }

    @Test
    void sendFileBytes_zeroBytes_writesNothing() throws Exception {
        Path file = Files.write(tempDir.resolve("empty.bin"), new byte[0]);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeSendFileBytes(new DataOutputStream(baos), file, 0);

        assertThat(baos.toByteArray()).isEmpty();
    }

    @Test
    void sendFileBytes_countAboveChunkSize_streamsEveryByte() throws Exception {
        byte[] content = new byte[(33 * 1024 * 1024) + 1024];
        ThreadLocalRandom.current().nextBytes(content);
        Path file = Files.write(tempDir.resolve("over-chunk.bin"), content);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeSendFileBytes(new DataOutputStream(baos), file, content.length);

        assertThat(baos.toByteArray()).isEqualTo(content);
    }

    // ── sendFileBytes — IOException propagation ───────────────────────────────

    @Test
    void sendFileBytes_missingFile_throwsIOException() {
        Path ghost = tempDir.resolve("ghost.bin");
        assertThatThrownBy(() ->
                invokeSendFileBytes(new DataOutputStream(new ByteArrayOutputStream()), ghost, 10))
                .isInstanceOf(IOException.class);
    }

    // ── handleSync — unknown dir ──────────────────────────────────────────────

    @Test
    void handleSync_unknownDir_sendsZeroRecordCount() throws Exception {
        when(rootDirRepository.findByName("unknown")).thenReturn(Optional.empty());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandleSync(new DataOutputStream(baos), "unknown", 0L, "hw-1");

        assertThat(toDataIn(baos).readInt()).isZero();
    }

    // ── handleSync — empty change set ────────────────────────────────────────

    @Test
    void handleSync_noChangedRecords_sendsZeroRecordCount() throws Exception {
        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100)).thenReturn(List.of());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandleSync(new DataOutputStream(baos), "photos", 0L, "hw-1");

        assertThat(toDataIn(baos).readInt()).isZero();
    }

    // ── handleSync — private dir auth ─────────────────────────────────────────

    @Test
    void handleSync_privateDir_unauthorizedClient_sendsZeroRecordCount() throws Exception {
        RootDir privateDir = RootDir.builder().id(1).name("vault").isPrivate(true)
                .password("hash").absolutePath(tempDir.toString()).build();
        when(rootDirRepository.findByName("vault")).thenReturn(Optional.of(privateDir));
        when(authCacheService.isAuthorized("client-1", "vault")).thenReturn(false);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandleSync(new DataOutputStream(baos), "vault", 0L, "client-1");

        assertThat(toDataIn(baos).readInt()).isZero();
    }

    @Test
    void handleSync_publicDir_noAuthCheck_sendsRecords() throws Exception {
        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100)).thenReturn(List.of());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandleSync(new DataOutputStream(baos), "photos", 0L, "client-1");

        assertThat(toDataIn(baos).readInt()).isZero();
        verify(authCacheService, never()).isAuthorized(anyString(), anyString());
    }

    // ── handleSync — live file wire format ────────────────────────────────────

    @Test
    void handleSync_liveFile_sendsCorrectWireFormat() throws Exception {
        byte[] content = "photo bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(tempDir.resolve("photo.jpg"), content);

        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100))
                .thenReturn(List.of(liveFile("photo.jpg", 5L)));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandleSync(new DataOutputStream(baos), "photos", 0L, "hw-1");

        DataInputStream in = toDataIn(baos);

        assertThat(in.readInt()).isEqualTo(1);                                 // record count

        byte flags = in.readByte();
        assertThat(flags & 0x01).isZero();                                     // not deleted

        int pathLen = in.readInt();
        String path = new String(in.readNBytes(pathLen), StandardCharsets.UTF_8);
        assertThat(path).isEqualTo("photos/photo.jpg");                        // qualified path

        assertThat(in.readLong()).isEqualTo(5L);                               // syncVersion

        long wireFileSize = in.readLong();
        assertThat(wireFileSize).isEqualTo(content.length);                    // file size on wire

        assertThat(in.readNBytes((int) wireFileSize)).isEqualTo(content);      // file bytes
    }

    // ── handleSync — deleted record ───────────────────────────────────────────

    @Test
    void handleSync_deletedRecord_sendsDeletedFlagZeroSizeAndNoContent() throws Exception {
        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100))
                .thenReturn(List.of(deletedFile("photo.jpg", 7L)));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandleSync(new DataOutputStream(baos), "photos", 0L, "hw-1");

        DataInputStream in = toDataIn(baos);

        assertThat(in.readInt()).isEqualTo(1);          // record count

        byte flags = in.readByte();
        assertThat(flags & 0x01).isEqualTo(1);          // FLAG_DELETED must be set

        int pathLen = in.readInt();
        in.skipNBytes(pathLen);                         // skip path bytes
        in.readLong();                                  // skip version

        assertThat(in.readLong()).isZero();              // file size = 0 for deletes
        assertThat(in.available()).isZero();             // no file content written after
    }

    @Test
    void handleSync_deletedRecord_doesNotCallSendFileBytes() throws Exception {
        setupRootDir("photos", 1, tempDir.toString());
        // "deleted.jpg" is intentionally absent from tempDir
        when(fileMetadataService.findChangedSince(1, 0L, 100))
                .thenReturn(List.of(deletedFile("deleted.jpg", 7L)));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Must not throw even though the file is absent
        invokeHandleSync(new DataOutputStream(baos), "photos", 0L, "hw-1");

        DataInputStream in = toDataIn(baos);
        assertThat(in.readInt()).isEqualTo(1);
    }

    // ── handleSync — syncVersion stamping ────────────────────────────────────

    @Test
    void handleSync_unversionedRecord_stampsAssignedVersion() throws Exception {
        Files.write(tempDir.resolve("new.jpg"), "x".getBytes(StandardCharsets.UTF_8));

        setupRootDir("photos", 1, tempDir.toString());
        FileMetadata unversioned = FileMetadata.builder()
                .relativePath("new.jpg")
                .deleted(false)
                .syncVersion(null)
                .build();
        when(fileMetadataService.findChangedSince(1, 0L, 100)).thenReturn(List.of(unversioned));
        when(fileMetadataService.nextSyncVersion(1)).thenReturn(42L);

        invokeHandleSync(new DataOutputStream(new ByteArrayOutputStream()), "photos", 0L, "hw-1");

        verify(fileMetadataService).stampSyncVersion(1, "new.jpg", 42L);
    }

    @Test
    void handleSync_alreadyVersionedRecord_doesNotStampAgain() throws Exception {
        Files.write(tempDir.resolve("existing.jpg"), "x".getBytes(StandardCharsets.UTF_8));

        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100))
                .thenReturn(List.of(liveFile("existing.jpg", 10L)));

        invokeHandleSync(new DataOutputStream(new ByteArrayOutputStream()), "photos", 0L, "hw-1");

        verify(fileMetadataService, never()).stampSyncVersion(anyInt(), anyString(), anyLong());
    }

    // ── handleSync — multiple records ────────────────────────────────────────

    @Test
    void handleSync_multipleRecords_allWrittenWithCorrectCount() throws Exception {
        byte[] contentA = "file-a".getBytes(StandardCharsets.UTF_8);
        byte[] contentB = "file-b".getBytes(StandardCharsets.UTF_8);
        Files.write(tempDir.resolve("a.jpg"), contentA);
        Files.write(tempDir.resolve("b.jpg"), contentB);

        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100)).thenReturn(List.of(
                liveFile("a.jpg", 1L),
                liveFile("b.jpg", 2L)
        ));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandleSync(new DataOutputStream(baos), "photos", 0L, "hw-1");

        DataInputStream in = toDataIn(baos);
        assertThat(in.readInt()).isEqualTo(2); // both records written
    }

    // ── handleDownloadFile ────────────────────────────────────────────────────

    @Test
    void handleDownloadFile_malformedPath_sendsMinusOne() throws Exception {
        ByteArrayOutputStream inputBuffer = new ByteArrayOutputStream();
        DataOutputStream inputData = new DataOutputStream(inputBuffer);
        String qualifiedPath = "noslash";
        byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);
        inputData.writeInt(pathBytes.length);
        inputData.write(pathBytes);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandleDownloadFile(
                new DataInputStream(new ByteArrayInputStream(inputBuffer.toByteArray())),
                new DataOutputStream(baos), "client-1");

        assertThat(toDataIn(baos).readLong()).isEqualTo(-1L);
    }

    @Test
    void handleDownloadFile_fileNotFound_sendsMinusOne() throws Exception {
        setupRootDir("photos", 1, tempDir.toString());

        ByteArrayOutputStream inputBuffer = new ByteArrayOutputStream();
        DataOutputStream inputData = new DataOutputStream(inputBuffer);
        String qualifiedPath = "photos/nonexistent.jpg";
        byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);
        inputData.writeInt(pathBytes.length);
        inputData.write(pathBytes);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandleDownloadFile(
                new DataInputStream(new ByteArrayInputStream(inputBuffer.toByteArray())),
                new DataOutputStream(baos), "client-1");

        assertThat(toDataIn(baos).readLong()).isEqualTo(-1L);
    }

    @Test
    void handleDownloadFile_unauthorizedPrivateDir_sendsMinusOne() throws Exception {
        RootDir privateDir = RootDir.builder().id(1).name("vault").isPrivate(true)
                .password("hash").absolutePath(tempDir.toString()).build();
        when(rootDirRepository.findByName("vault")).thenReturn(Optional.of(privateDir));
        when(authCacheService.isAuthorized("client-1", "vault")).thenReturn(false);

        ByteArrayOutputStream inputBuffer = new ByteArrayOutputStream();
        DataOutputStream inputData = new DataOutputStream(inputBuffer);
        String qualifiedPath = "vault/secret.jpg";
        byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);
        inputData.writeInt(pathBytes.length);
        inputData.write(pathBytes);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandleDownloadFile(
                new DataInputStream(new ByteArrayInputStream(inputBuffer.toByteArray())),
                new DataOutputStream(baos), "client-1");

        assertThat(toDataIn(baos).readLong()).isEqualTo(-1L);
    }

    @Test
    void handleDownloadFile_validPublicFile_sendsFileSizeThenBytes() throws Exception {
        byte[] content = "photo bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(tempDir.resolve("photo.jpg"), content);
        setupRootDir("photos", 1, tempDir.toString());

        ByteArrayOutputStream inputBuffer = new ByteArrayOutputStream();
        DataOutputStream inputData = new DataOutputStream(inputBuffer);
        String qualifiedPath = "photos/photo.jpg";
        byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);
        inputData.writeInt(pathBytes.length);
        inputData.write(pathBytes);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandleDownloadFile(
                new DataInputStream(new ByteArrayInputStream(inputBuffer.toByteArray())),
                new DataOutputStream(baos), "client-1");

        DataInputStream result = toDataIn(baos);
        long fileSize = result.readLong();
        assertThat(fileSize).isEqualTo(content.length);
        assertThat(result.readNBytes((int) fileSize)).isEqualTo(content);
    }

    // ── handleSync — client_ids recording ────────────────────────────────────

    @Test
    void handleSync_liveFiles_recordsClientForEveryFileSent() throws Exception {
        Files.write(tempDir.resolve("a.jpg"), "a".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("b.jpg"), "b".getBytes(StandardCharsets.UTF_8));
        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100))
                .thenReturn(List.of(liveFile("a.jpg", 1L), liveFile("b.jpg", 2L)));

        invokeHandleSync(new DataOutputStream(new ByteArrayOutputStream()), "photos", 0L, "hw-1");

        verify(fileMetadataService).addClientToFiles(1, List.of("a.jpg", "b.jpg"), "hw-1");
    }

    @Test
    void handleSync_deletedRecords_areNotRecordedAsHolders() throws Exception {
        Files.write(tempDir.resolve("live.jpg"), "x".getBytes(StandardCharsets.UTF_8));
        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100))
                .thenReturn(List.of(deletedFile("gone.jpg", 3L), liveFile("live.jpg", 4L)));

        invokeHandleSync(new DataOutputStream(new ByteArrayOutputStream()), "photos", 0L, "hw-1");

        verify(fileMetadataService).addClientToFiles(1, List.of("live.jpg"), "hw-1");
    }

    @Test
    void handleSync_onlyDeletedRecords_recordsNoHolders() throws Exception {
        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100))
                .thenReturn(List.of(deletedFile("gone.jpg", 3L)));

        invokeHandleSync(new DataOutputStream(new ByteArrayOutputStream()), "photos", 0L, "hw-1");

        verify(fileMetadataService).addClientToFiles(1, List.of(), "hw-1");
    }

    @Test
    void handleSync_unauthorizedPrivateDir_recordsNothing() throws Exception {
        RootDir priv = RootDir.builder().id(2).name("secret").absolutePath(tempDir.toString()).isPrivate(true).build();
        when(rootDirRepository.findByName("secret")).thenReturn(Optional.of(priv));
        when(authCacheService.isAuthorized("hw-1", "secret")).thenReturn(false);

        invokeHandleSync(new DataOutputStream(new ByteArrayOutputStream()), "secret", 0L, "hw-1");

        verify(fileMetadataService, never()).addClientToFiles(anyInt(), anyList(), anyString());
    }

    @Test
    void handleSync_unknownDir_recordsNothing() throws Exception {
        when(rootDirRepository.findByName("nope")).thenReturn(Optional.empty());

        invokeHandleSync(new DataOutputStream(new ByteArrayOutputStream()), "nope", 0L, "hw-1");

        verify(fileMetadataService, never()).addClientToFiles(anyInt(), anyList(), anyString());
    }

    @Test
    void handleSync_streamFailsMidTransfer_doesNotRecordClient() throws Exception {
        Files.write(tempDir.resolve("a.jpg"), "abc".getBytes(StandardCharsets.UTF_8));
        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100)).thenReturn(List.of(liveFile("a.jpg", 1L)));

        DataOutputStream broken = new DataOutputStream(new OutputStream() {
            private int written;
            @Override public void write(int b) throws IOException {
                if (++written > 8) throw new IOException("peer reset");
            }
        });

        assertThatThrownBy(() -> invokeHandleSync(broken, "photos", 0L, "hw-1"))
                .isInstanceOf(IOException.class);

        verify(fileMetadataService, never()).addClientToFiles(anyInt(), anyList(), anyString());
    }

    // ── handleDeleteAck ──────────────────────────────────────────────────────

    @Test
    void handleDeleteAck_qualifiedPath_acknowledgesWithDirIdAndRelativePath() throws Exception {
        setupRootDir("photos", 7, tempDir.toString());

        invokeHandleDeleteAck("photos/sub/deep/x.jpg", "hw-1");

        verify(fileMetadataService).acknowledgeDelete(7, "sub/deep/x.jpg", "hw-1");
    }

    @Test
    void handleDeleteAck_pathWithoutSlash_isIgnored() throws Exception {
        invokeHandleDeleteAck("photos", "hw-1");

        verify(fileMetadataService, never()).acknowledgeDelete(anyInt(), anyString(), anyString());
    }

    @Test
    void handleDeleteAck_backslashSeparatedPath_isIgnored() throws Exception {
        invokeHandleDeleteAck("photos\\sub\\x.jpg", "hw-1");

        verify(fileMetadataService, never()).acknowledgeDelete(anyInt(), anyString(), anyString());
    }

    @Test
    void handleDeleteAck_unknownDir_isIgnored() throws Exception {
        when(rootDirRepository.findByName("ghost")).thenReturn(Optional.empty());

        invokeHandleDeleteAck("ghost/x.jpg", "hw-1");

        verify(fileMetadataService, never()).acknowledgeDelete(anyInt(), anyString(), anyString());
    }

    // ── recordSyncTime ────────────────────────────────────────────────────────

    @Test
    void recordSyncTime_firstCall_writesToRepository() throws Exception {
        invokeRecordSyncTime("hw-1");

        verify(syncTimeRepository).upsert(eq("hw-1"), anyLong());
    }

    @Test
    void recordSyncTime_repeatedCallsWithinInterval_writeOnce() throws Exception {
        invokeRecordSyncTime("hw-1");
        invokeRecordSyncTime("hw-1");
        invokeRecordSyncTime("hw-1");

        verify(syncTimeRepository, times(1)).upsert(eq("hw-1"), anyLong());
    }

    @Test
    void recordSyncTime_afterIntervalElapsed_writesAgain() throws Exception {
        invokeRecordSyncTime("hw-1");
        @SuppressWarnings("unchecked")
        var lastWrite = (ConcurrentHashMap<String, Long>)
                ReflectionTestUtils.getField(fileSocketService, "lastSyncTimeWrite");
        lastWrite.put("hw-1", System.currentTimeMillis() - 2 * 60 * 60 * 1000L);

        invokeRecordSyncTime("hw-1");

        verify(syncTimeRepository, times(2)).upsert(eq("hw-1"), anyLong());
    }

    @Test
    void recordSyncTime_differentClients_areThrottledIndependently() throws Exception {
        invokeRecordSyncTime("hw-1");
        invokeRecordSyncTime("hw-2");

        verify(syncTimeRepository).upsert(eq("hw-1"), anyLong());
        verify(syncTimeRepository).upsert(eq("hw-2"), anyLong());
    }

    // ── serveClient — per-client state across several sockets ───────────────

    @Test
    void serveClient_downloadSocketClosing_keepsAuthWhileSyncSocketIsOpen() throws Exception {
        int port = startOnFreePort();
        try (Socket sync = identify(port, "hw-1");
             Socket download = identify(port, "hw-1")) {
            requestDownload(download, "unknown/file.bin");

            download.close();
            awaitConnectionCount("hw-1", 1);

            verify(authCacheService, never()).evict("hw-1");
            assertThat(fileSocketService.listConnectedClients()).hasSize(1);
        } finally {
            fileSocketService.stop();
        }
        verify(authCacheService, timeout(2000)).evict("hw-1");
    }

    @Test
    void serveClient_lastSocketClosing_evictsAuthAndConnectedClient() throws Exception {
        int port = startOnFreePort();
        try {
            Socket first = identify(port, "hw-1");
            Socket second = identify(port, "hw-1");
            awaitConnectionCount("hw-1", 2);

            first.close();
            awaitConnectionCount("hw-1", 1);
            verify(authCacheService, never()).evict("hw-1");

            second.close();
            verify(authCacheService, timeout(2000)).evict("hw-1");
            awaitConnectionCount("hw-1", 0);
            assertThat(fileSocketService.listConnectedClients()).isEmpty();
        } finally {
            fileSocketService.stop();
        }
    }

    @Test
    void serveClient_distinctClients_evictedIndependently() throws Exception {
        int port = startOnFreePort();
        try (Socket other = identify(port, "hw-2")) {
            Socket one = identify(port, "hw-1");
            awaitConnectionCount("hw-1", 1);

            one.close();
            verify(authCacheService, timeout(2000)).evict("hw-1");
            verify(authCacheService, never()).evict("hw-2");
            assertThat(fileSocketService.listConnectedClients()).hasSize(1);
        } finally {
            fileSocketService.stop();
        }
    }

    private int startOnFreePort() throws Exception {
        ReflectionTestUtils.setField(fileSocketService, "port", 0);
        fileSocketService.start();
        return ((ServerSocket) ReflectionTestUtils.getField(fileSocketService, "serverSocket")).getLocalPort();
    }

    private static Socket identify(int port, String clientId) throws IOException {
        Socket socket = new Socket("localhost", port);
        byte[] id = clientId.getBytes(StandardCharsets.UTF_8);
        var out = new DataOutputStream(socket.getOutputStream());
        out.writeInt(id.length);
        out.write(id);
        out.flush();
        return socket;
    }

    /** Sends one DOWNLOAD_FILE request and waits for the server's size reply. */
    private static void requestDownload(Socket socket, String qualifiedPath) throws IOException {
        byte[] path = qualifiedPath.getBytes(StandardCharsets.UTF_8);
        var out = new DataOutputStream(socket.getOutputStream());
        out.writeByte(FileSocketService.DOWNLOAD_FILE);
        out.writeInt(path.length);
        out.write(path);
        out.flush();
        assertThat(new DataInputStream(socket.getInputStream()).readLong()).isEqualTo(-1L);
    }

    @SuppressWarnings("unchecked")
    private void awaitConnectionCount(String clientId, int expected) throws InterruptedException {
        var open = (ConcurrentHashMap<String, Integer>) ReflectionTestUtils.getField(fileSocketService, "openConnections");
        long deadline = System.currentTimeMillis() + 2000;
        while (open.getOrDefault(clientId, 0) != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(open.getOrDefault(clientId, 0)).isEqualTo(expected);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void invokeRecordSyncTime(String clientId) throws Exception {
        Method m = FileSocketService.class.getDeclaredMethod("recordSyncTime", String.class);
        m.setAccessible(true);
        m.invoke(fileSocketService, clientId);
    }

    private void invokeHandleDeleteAck(String qualifiedPath, String clientId) throws Exception {
        Method m = FileSocketService.class.getDeclaredMethod("handleDeleteAck", String.class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(fileSocketService, qualifiedPath, clientId);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException ex) throw ex;
            throw e;
        }
    }

    private void invokeSendFileBytes(DataOutputStream out, Path absPath, long fileBytesSize)
            throws Exception {
        Method m = FileSocketService.class.getDeclaredMethod(
                "sendFileBytes", DataOutputStream.class, Path.class, long.class);
        m.setAccessible(true);
        try {
            m.invoke(fileSocketService, out, absPath, fileBytesSize);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ex) throw ex;
            if (cause instanceof RuntimeException ex) throw ex;
            throw e;
        }
    }

    private void invokeHandleSync(DataOutputStream out, String dirName,
                                  Long lastSyncVersion, String clientId) throws Exception {
        Method m = FileSocketService.class.getDeclaredMethod(
                "handleSync", DataOutputStream.class, String.class, Long.class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(fileSocketService, out, dirName, lastSyncVersion, clientId);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ex) throw ex;
            if (cause instanceof RuntimeException ex) throw ex;
            throw e;
        }
    }

    private void invokeHandleDownloadFile(DataInputStream in, DataOutputStream out,
                                  String clientId) throws Exception {
        Method m = FileSocketService.class.getDeclaredMethod(
                "handleDownloadFile", DataInputStream.class, DataOutputStream.class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(fileSocketService, in, out, clientId);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ex) throw ex;
            if (cause instanceof RuntimeException ex) throw ex;
            throw e;
        }
    }

    private void setupRootDir(String name, int id, String absPath) {
        RootDir dir = RootDir.builder().id(id).name(name).absolutePath(absPath).build();
        when(rootDirRepository.findByName(name)).thenReturn(Optional.of(dir));
    }

    private FileMetadata liveFile(String relativePath, long syncVersion) {
        return FileMetadata.builder()
                .relativePath(relativePath)
                .deleted(false)
                .syncVersion(syncVersion)
                .build();
    }

    private FileMetadata deletedFile(String relativePath, long syncVersion) {
        return FileMetadata.builder()
                .relativePath(relativePath)
                .deleted(true)
                .syncVersion(syncVersion)
                .build();
    }

    private DataInputStream toDataIn(ByteArrayOutputStream baos) {
        return new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
    }
}
