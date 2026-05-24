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
import org.springframework.context.ApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushServiceTest {

    @Mock
    private FileMetadataService fileMetadataService;
    @Mock
    private RootDirRepository rootDirRepository;
    @Mock
    private ApplicationContext applicationContext;

    @InjectMocks
    private PushService pushService;

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

    // ── handlePoll — unknown dir ──────────────────────────────────────────────

    @Test
    void handlePoll_unknownDir_sendsZeroRecordCount() throws Exception {
        when(rootDirRepository.findByName("unknown")).thenReturn(Optional.empty());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandlePoll(new DataOutputStream(baos), "unknown", 0L, "hw-1");

        assertThat(toDataIn(baos).readInt()).isZero();
    }

    // ── handlePoll — empty change set ────────────────────────────────────────

    @Test
    void handlePoll_noChangedRecords_sendsZeroRecordCount() throws Exception {
        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100)).thenReturn(List.of());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandlePoll(new DataOutputStream(baos), "photos", 0L, "hw-1");

        assertThat(toDataIn(baos).readInt()).isZero();
    }

    // ── handlePoll — live file wire format ───────────────────────────────────

    @Test
    void handlePoll_liveFile_sendsCorrectWireFormat() throws Exception {
        byte[] content = "photo bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(tempDir.resolve("photo.jpg"), content);

        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100))
                .thenReturn(List.of(liveFile("photo.jpg", 5L)));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandlePoll(new DataOutputStream(baos), "photos", 0L, "hw-1");

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

    // ── handlePoll — deleted record ───────────────────────────────────────────

    @Test
    void handlePoll_deletedRecord_sendsDeletedFlagZeroSizeAndNoContent() throws Exception {
        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100))
                .thenReturn(List.of(deletedFile("photo.jpg", 7L)));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        invokeHandlePoll(new DataOutputStream(baos), "photos", 0L, "hw-1");

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
    void handlePoll_deletedRecord_doesNotCallSendFileBytes() throws Exception {
        // Deleted record must NOT attempt Files.size / sendFileBytes — the file no longer exists.
        setupRootDir("photos", 1, tempDir.toString());
        // "deleted.jpg" is intentionally absent from tempDir
        when(fileMetadataService.findChangedSince(1, 0L, 100))
                .thenReturn(List.of(deletedFile("deleted.jpg", 7L)));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Must not throw even though the file is absent
        invokeHandlePoll(new DataOutputStream(baos), "photos", 0L, "hw-1");

        DataInputStream in = toDataIn(baos);
        assertThat(in.readInt()).isEqualTo(1);
    }

    // ── handlePoll — syncVersion stamping ────────────────────────────────────

    @Test
    void handlePoll_unversionedRecord_stampsAssignedVersion() throws Exception {
        Files.write(tempDir.resolve("new.jpg"), "x".getBytes(StandardCharsets.UTF_8));

        setupRootDir("photos", 1, tempDir.toString());
        FileMetadata unversioned = FileMetadata.builder()
                .relativePath("new.jpg")
                .deleted(false)
                .syncVersion(null)
                .build();
        when(fileMetadataService.findChangedSince(1, 0L, 100)).thenReturn(List.of(unversioned));
        when(fileMetadataService.nextSyncVersion(1)).thenReturn(42L);

        invokeHandlePoll(new DataOutputStream(new ByteArrayOutputStream()), "photos", 0L, "hw-1");

        verify(fileMetadataService).stampSyncVersion(1, "new.jpg", 42L);
    }

    @Test
    void handlePoll_alreadyVersionedRecord_doesNotStampAgain() throws Exception {
        Files.write(tempDir.resolve("existing.jpg"), "x".getBytes(StandardCharsets.UTF_8));

        setupRootDir("photos", 1, tempDir.toString());
        when(fileMetadataService.findChangedSince(1, 0L, 100))
                .thenReturn(List.of(liveFile("existing.jpg", 10L)));

        invokeHandlePoll(new DataOutputStream(new ByteArrayOutputStream()), "photos", 0L, "hw-1");

        verify(fileMetadataService, never()).stampSyncVersion(anyInt(), anyString(), anyLong());
    }

    // ── handlePoll — multiple records ────────────────────────────────────────

    @Test
    void handlePoll_multipleRecords_allWrittenWithCorrectCount() throws Exception {
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
        invokeHandlePoll(new DataOutputStream(baos), "photos", 0L, "hw-1");

        DataInputStream in = toDataIn(baos);
        assertThat(in.readInt()).isEqualTo(2); // both records written
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void invokeSendFileBytes(DataOutputStream out, Path absPath, long fileBytesSize)
            throws Exception {
        Method m = PushService.class.getDeclaredMethod(
                "sendFileBytes", DataOutputStream.class, Path.class, long.class);
        m.setAccessible(true);
        try {
            m.invoke(pushService, out, absPath, fileBytesSize);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ex) throw ex;
            if (cause instanceof RuntimeException ex) throw ex;
            throw e;
        }
    }

    // ── handlePrivateAuth ─────────────────────────────────────────────────────

    @Test
    void handlePrivateAuth_correctHash_writesGrantedAndAddsToSubscribedIds() throws Exception {
        String hash = com.fstojilj.luddite.sync.common.util.PasswordUtils.hash("secret");
        RootDir dir = RootDir.builder().id(42).name("vault").isPrivate(true).password(hash).absolutePath("/vault").build();
        when(rootDirRepository.findByName("vault")).thenReturn(Optional.of(dir));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        java.util.Set<Integer> subscribedIds = new java.util.HashSet<>();

        invokeHandlePrivateAuth(out, "vault", hash, subscribedIds, "client-1");

        assertThat(baos.toByteArray()).isEqualTo(new byte[]{0x01});
        assertThat(subscribedIds).containsExactly(42);
    }

    @Test
    void handlePrivateAuth_wrongHash_writesDeniedAndDoesNotSubscribe() throws Exception {
        String correctHash = com.fstojilj.luddite.sync.common.util.PasswordUtils.hash("secret");
        String wrongHash = com.fstojilj.luddite.sync.common.util.PasswordUtils.hash("wrong");
        RootDir dir = RootDir.builder().id(42).name("vault").isPrivate(true).password(correctHash).absolutePath("/vault").build();
        when(rootDirRepository.findByName("vault")).thenReturn(Optional.of(dir));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        java.util.Set<Integer> subscribedIds = new java.util.HashSet<>();

        invokeHandlePrivateAuth(out, "vault", wrongHash, subscribedIds, "client-1");

        assertThat(baos.toByteArray()).isEqualTo(new byte[]{0x00});
        assertThat(subscribedIds).isEmpty();
    }

    @Test
    void handlePrivateAuth_unknownDir_writesDenied() throws Exception {
        when(rootDirRepository.findByName("ghost")).thenReturn(Optional.empty());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        java.util.Set<Integer> subscribedIds = new java.util.HashSet<>();

        invokeHandlePrivateAuth(out, "ghost", "anyhash", subscribedIds, "client-1");

        assertThat(baos.toByteArray()).isEqualTo(new byte[]{0x00});
        assertThat(subscribedIds).isEmpty();
    }

    @Test
    void handlePrivateAuth_publicDir_writesDenied() throws Exception {
        RootDir dir = RootDir.builder().id(7).name("public").isPrivate(false).password(null).absolutePath("/public").build();
        when(rootDirRepository.findByName("public")).thenReturn(Optional.of(dir));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        java.util.Set<Integer> subscribedIds = new java.util.HashSet<>();

        invokeHandlePrivateAuth(out, "public", "anyhash", subscribedIds, "client-1");

        assertThat(baos.toByteArray()).isEqualTo(new byte[]{0x00});
        assertThat(subscribedIds).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void invokeHandlePrivateAuth(DataOutputStream out, String dirName, String passwordHash,
                                          java.util.Set<Integer> subscribedIds, String clientId) throws Exception {
        Method m = PushService.class.getDeclaredMethod(
                "handlePrivateAuth", DataOutputStream.class, String.class, String.class,
                java.util.Set.class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(pushService, out, dirName, passwordHash, subscribedIds, clientId);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ex) throw ex;
            if (cause instanceof RuntimeException ex) throw ex;
            throw e;
        }
    }

    private void invokeHandlePoll(DataOutputStream out, String dirName,
                                  Long lastSyncVersion, String clientId) throws Exception {
        Method m = PushService.class.getDeclaredMethod(
                "handlePoll", DataOutputStream.class, String.class, Long.class, String.class);
        m.setAccessible(true);
        try {
            m.invoke(pushService, out, dirName, lastSyncVersion, clientId);
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

