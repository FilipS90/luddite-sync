package com.fstojilj.luddite.sync.client.service;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Covers {@link ClientSyncService#applyRecord} with in-memory streams instead of a socket.
 * An {@code IOException} escaping the delete path is treated as a connection loss by the caller
 * and skips the DELETE_ACK, so the server would re-send the delete on every reconnect.
 */
@ExtendWith(MockitoExtension.class)
class ClientSyncServiceApplyRecordTest {

    private static final byte DELETE_ACK = 0x03;
    private static final byte FLAG_DELETED = 0x01;
    private static final byte FLAG_LIVE = 0x00;
    private static final String DIR = "share";

    @Mock
    private RootDirService rootDirService;
    @Mock
    private FileMetadataService fileMetadataService;
    @Mock
    private ClientIdService clientIdService;
    @Mock
    private ServerApiClient serverApiClient;
    @Mock
    private ClientSocketFactory socketFactory;

    @InjectMocks
    private ClientSyncService clientSyncService;

    @TempDir
    Path mirror;

    // ── delete: ACK is always sent ────────────────────────────────────────────

    @Test
    void delete_existingFile_removesItPurgesRecordAndAcks() throws Exception {
        Path file = Files.writeString(mirror.resolve("a.txt"), "x");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        applyDelete(out, DIR + "/a.txt");

        assertThat(file).doesNotExist();
        verify(fileMetadataService).purgeRecord(DIR, DIR + "/a.txt");
        assertAck(out, DIR + "/a.txt");
    }

    @Test
    void delete_fileAlreadyMissing_doesNotThrowAndStillAcks() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThatCode(() -> applyDelete(out, DIR + "/ghost.txt")).doesNotThrowAnyException();

        verify(fileMetadataService).purgeRecord(DIR, DIR + "/ghost.txt");
        assertAck(out, DIR + "/ghost.txt");
    }

    @Test
    void delete_ackEchoesServerPathVerbatim_evenWhenLocalPathIsOsAdjusted() throws Exception {
        String originalOs = System.getProperty("os.name");
        System.setProperty("os.name", "Windows 11");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            applyDelete(out, DIR + "/sub/deep/x.txt");

            assertAck(out, DIR + "/sub/deep/x.txt");
        } finally {
            System.setProperty("os.name", originalOs);
        }
    }

    // ── delete: empty-parent pruning ──────────────────────────────────────────

    @Test
    void delete_lastFileInNestedDirs_prunesEveryEmptyParentButNotTheRoot() throws Exception {
        Path file = mirror.resolve("sub/deep/c.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");

        applyDelete(new ByteArrayOutputStream(), DIR + "/sub/deep/c.txt");

        assertThat(mirror.resolve("sub/deep")).doesNotExist();
        assertThat(mirror.resolve("sub")).doesNotExist();
        assertThat(mirror).exists();
    }

    @Test
    void delete_siblingRemains_stopsPruningAtFirstNonEmptyDir() throws Exception {
        Files.createDirectories(mirror.resolve("sub/deep"));
        Files.writeString(mirror.resolve("sub/deep/c.txt"), "x");
        Path sibling = Files.writeString(mirror.resolve("sub/keep.txt"), "y");

        applyDelete(new ByteArrayOutputStream(), DIR + "/sub/deep/c.txt");

        assertThat(mirror.resolve("sub/deep")).doesNotExist();
        assertThat(mirror.resolve("sub")).isDirectory();
        assertThat(sibling).exists();
    }

    @Test
    void delete_fileDirectlyUnderRoot_neverRemovesRoot() throws Exception {
        Files.writeString(mirror.resolve("only.txt"), "x");

        applyDelete(new ByteArrayOutputStream(), DIR + "/only.txt");

        assertThat(mirror).isDirectory();
    }

    @Test
    void delete_parentCannotBeRemoved_stillPurgesAndAcks() throws Exception {
        // Read-only grandparent makes removing the empty parent fail with AccessDeniedException.
        Path parent = Files.createDirectories(mirror.resolve("locked/inner"));
        Files.writeString(parent.resolve("c.txt"), "x");
        Path locked = mirror.resolve("locked");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(locked);
        Files.setPosixFilePermissions(locked, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        assumeTrue(!Files.isWritable(locked), "test needs a non-root user for read-only dirs to take effect");

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            assertThatCode(() -> applyDelete(out, DIR + "/locked/inner/c.txt")).doesNotThrowAnyException();

            assertThat(parent).isDirectory();
            verify(fileMetadataService).purgeRecord(DIR, DIR + "/locked/inner/c.txt");
            assertAck(out, DIR + "/locked/inner/c.txt");
        } finally {
            Files.setPosixFilePermissions(locked, original);
        }
    }

    // ── path traversal ────────────────────────────────────────────────────────

    @Test
    void delete_pathOutsideMirror_isRejectedWithoutAckOrPurge() throws Exception {
        Path outside = Files.writeString(mirror.resolveSibling("victim.txt"), "x");
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        applyDelete(out, DIR + "/../victim.txt");

        assertThat(outside).exists();
        assertThat(out.size()).isZero();
        verify(fileMetadataService, never()).purgeRecord(anyString(), anyString());
    }

    @Test
    void liveFile_pathOutsideMirror_drainsPayloadSoStreamStaysAligned() throws Exception {
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));

        clientSyncService.applyRecord(in, new DataOutputStream(new ByteArrayOutputStream()),
                DIR, mirror, FLAG_LIVE, DIR + "/../evil.txt", 1L, payload.length);

        assertThat(in.available()).isZero();
        assertThat(mirror.resolveSibling("evil.txt")).doesNotExist();
        verify(fileMetadataService, never()).recordSynced(anyString(), anyString());
    }

    // ── live file ─────────────────────────────────────────────────────────────

    @Test
    void liveFile_writesBytesCreatesParentsAndRecordsSynced() throws Exception {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        clientSyncService.applyRecord(in, new DataOutputStream(out),
                DIR, mirror, FLAG_LIVE, DIR + "/new/dir/f.txt", 9L, payload.length);

        assertThat(mirror.resolve("new/dir/f.txt")).hasBinaryContent(payload);
        verify(fileMetadataService).recordSynced(DIR, DIR + "/new/dir/f.txt");
        assertThat(out.size()).as("no ACK for live files").isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void applyDelete(ByteArrayOutputStream out, String serverPath) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(new byte[0]));
        clientSyncService.applyRecord(in, new DataOutputStream(out), DIR, mirror, FLAG_DELETED, serverPath, 1L, 0L);
    }

    private static void assertAck(ByteArrayOutputStream out, String expectedPath) throws Exception {
        DataInputStream ack = new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        assertThat(ack.readByte()).isEqualTo(DELETE_ACK);
        int len = ack.readInt();
        assertThat(new String(ack.readNBytes(len), StandardCharsets.UTF_8)).isEqualTo(expectedPath);
        assertThat(ack.available()).as("exactly one ACK").isZero();
    }
}
