package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.repository.RootDirRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileSocketServiceTest {

    @Mock
    private FileMetadataService fileMetadataService;
    @Mock
    private RootDirRepository rootDirRepository;
    @Mock
    private AuthCacheService authCacheService;

    @InjectMocks
    private FileSocketService fileSocketService;

    @TempDir
    Path tempDir;

    // ── sendFileBytes — small path (≤ 33 MB → sendAllFileBytes) ──────────────

    @Test
    void sendFileBytes_smallFile_sendsAllBytes() throws Exception {
        byte[] content = "small file content".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(tempDir.resolve("small.bin"), content);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeSendFileBytes(new DataOutputStream(baos), file, content.length);

        assertThat(baos.toByteArray()).isEqualTo(content);
    }

    @Test
    void sendFileBytes_exactlyAtThreshold_usesSmallPath() throws Exception {
        byte[] content = "threshold content".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(tempDir.resolve("threshold.bin"), content);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // fileBytesSize == 33 MB boundary → must take the small (sendAllFileBytes) path
        invokeSendFileBytes(new DataOutputStream(baos), file, 33L * 1024 * 1024);

        assertThat(baos.toByteArray()).isEqualTo(content);
    }

    // ── sendFileBytes — large path (> 33 MB → chunked) ───────────────────────

    @Test
    void sendFileBytes_largeFilePath_sendsAllBytesViaChunkedStream() throws Exception {
        byte[] content = "large file streamed in chunks".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(tempDir.resolve("large.bin"), content);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Pass fileBytesSize just above threshold to force the chunked path
        invokeSendFileBytes(new DataOutputStream(baos), file, 33L * 1024 * 1024 + 1);

        assertThat(baos.toByteArray()).isEqualTo(content);
    }

    @Test
    void sendFileBytes_bothPaths_produceIdenticalOutput() throws Exception {
        byte[] content = "identical content for both paths".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(tempDir.resolve("same.bin"), content);

        ByteArrayOutputStream small = new ByteArrayOutputStream();
        invokeSendFileBytes(new DataOutputStream(small), file, 100);                      // small path

        ByteArrayOutputStream chunked = new ByteArrayOutputStream();
        invokeSendFileBytes(new DataOutputStream(chunked), file, 34L * 1024 * 1024);     // chunked path

        assertThat(small.toByteArray()).isEqualTo(chunked.toByteArray());
    }

    // ── sendFileBytes / sendAllFileBytes — IOException propagation ────────────

    @Test
    void sendFileBytes_smallPath_missingFile_throwsIOException() {
        Path ghost = tempDir.resolve("ghost.bin");
        assertThatThrownBy(() ->
                invokeSendFileBytes(new DataOutputStream(new ByteArrayOutputStream()), ghost, 10))
                .isInstanceOf(IOException.class);
    }

    @Test
    void sendFileBytes_largeChunkedPath_missingFile_throwsIOException() {
        Path ghost = tempDir.resolve("ghost-large.bin");
        assertThatThrownBy(() ->
                invokeSendFileBytes(new DataOutputStream(new ByteArrayOutputStream()), ghost, 34L * 1024 * 1024))
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

    // ── handleFile ────────────────────────────────────────────────────────────

    @Test
    void handleFile_malformedPath_sendsMinusOne() throws Exception {
        ByteArrayOutputStream inputBuffer = new ByteArrayOutputStream();
        DataOutputStream inputData = new DataOutputStream(inputBuffer);
        String qualifiedPath = "noslash";
        byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);
        inputData.writeInt(pathBytes.length);
        inputData.write(pathBytes);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandleFile(
                new DataInputStream(new ByteArrayInputStream(inputBuffer.toByteArray())),
                new DataOutputStream(baos), "client-1");

        assertThat(toDataIn(baos).readLong()).isEqualTo(-1L);
    }

    @Test
    void handleFile_fileNotFound_sendsMinusOne() throws Exception {
        setupRootDir("photos", 1, tempDir.toString());

        ByteArrayOutputStream inputBuffer = new ByteArrayOutputStream();
        DataOutputStream inputData = new DataOutputStream(inputBuffer);
        String qualifiedPath = "photos/nonexistent.jpg";
        byte[] pathBytes = qualifiedPath.getBytes(StandardCharsets.UTF_8);
        inputData.writeInt(pathBytes.length);
        inputData.write(pathBytes);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandleFile(
                new DataInputStream(new ByteArrayInputStream(inputBuffer.toByteArray())),
                new DataOutputStream(baos), "client-1");

        assertThat(toDataIn(baos).readLong()).isEqualTo(-1L);
    }

    @Test
    void handleFile_unauthorizedPrivateDir_sendsMinusOne() throws Exception {
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
        invokeHandleFile(
                new DataInputStream(new ByteArrayInputStream(inputBuffer.toByteArray())),
                new DataOutputStream(baos), "client-1");

        assertThat(toDataIn(baos).readLong()).isEqualTo(-1L);
    }

    @Test
    void handleFile_validPublicFile_sendsFileSizeThenBytes() throws Exception {
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
        invokeHandleFile(
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private void invokeHandleFile(DataInputStream in, DataOutputStream out,
                                  String clientId) throws Exception {
        Method m = FileSocketService.class.getDeclaredMethod(
                "handleFile", DataInputStream.class, DataOutputStream.class, String.class);
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
