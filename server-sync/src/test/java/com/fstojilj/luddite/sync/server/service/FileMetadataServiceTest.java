package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.server.repository.FileMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileMetadataServiceTest {

    @Mock
    private FileMetadataRepository fileMetadataRepository;

    @InjectMocks
    private FileMetadataService fileMetadataService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // Seed the sync version counter so tests don't depend on DB calls
        ReflectionTestUtils.setField(fileMetadataService, "syncVersionCounter", new ConcurrentHashMap<>(Map.of(1, 0L)));
    }

    // ── nextSyncVersion ───────────────────────────────────────────────────────

    @Test
    void nextSyncVersion_firstCall_returnsOne() {
        ReflectionTestUtils.setField(fileMetadataService, "syncVersionCounter", new ConcurrentHashMap<>());
        long version = fileMetadataService.nextSyncVersion(42);
        assertThat(version).isEqualTo(1L);
    }

    @Test
    void nextSyncVersion_subsequentCalls_increments() {
        fileMetadataService.nextSyncVersion(1);
        long second = fileMetadataService.nextSyncVersion(1);
        assertThat(second).isEqualTo(2L);
    }

    @Test
    void nextSyncVersion_differentRootDirs_areIndependent() {
        ReflectionTestUtils.setField(fileMetadataService, "syncVersionCounter", new ConcurrentHashMap<>());
        fileMetadataService.nextSyncVersion(1);
        fileMetadataService.nextSyncVersion(1);
        long v2 = fileMetadataService.nextSyncVersion(2);
        assertThat(v2).isEqualTo(1L);
    }

    // ── addFileMetadata ───────────────────────────────────────────────────────

    @Test
    void addFileMetadata_validFile_shouldInsert() throws Exception {
        Path file = Files.writeString(tempDir.resolve("photo.jpg"), "data");
        fileMetadataService.addFileMetadata(file, 1, "photo.jpg");
        verify(fileMetadataRepository).add(any(FileMetadata.class));
    }

    @Test
    void addFileMetadata_nonExistentFile_shouldThrow() {
        Path ghost = tempDir.resolve("ghost.jpg");
        assertThatThrownBy(() -> fileMetadataService.addFileMetadata(ghost, 1, "ghost.jpg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing file");
    }

    @Test
    void addFileMetadata_directory_shouldThrow() {
        assertThatThrownBy(() -> fileMetadataService.addFileMetadata(tempDir, 1, "dir"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addFileMetadata_ignoredFile_thumbsDb_shouldNotInsert() throws Exception {
        Path thumbs = Files.writeString(tempDir.resolve("Thumbs.db"), "data");
        fileMetadataService.addFileMetadata(thumbs, 1, "Thumbs.db");
        verify(fileMetadataRepository, never()).add(any());
    }

    @Test
    void addFileMetadata_ignoredFile_desktopIni_shouldNotInsert() throws Exception {
        Path ini = Files.writeString(tempDir.resolve("desktop.ini"), "data");
        fileMetadataService.addFileMetadata(ini, 1, "desktop.ini");
        verify(fileMetadataRepository, never()).add(any());
    }

    @Test
    void addFileMetadata_ignoredFile_caseInsensitive_shouldNotInsert() throws Exception {
        Path upper = Files.writeString(tempDir.resolve("THUMBS.DB"), "data");
        fileMetadataService.addFileMetadata(upper, 1, "THUMBS.DB");
        verify(fileMetadataRepository, never()).add(any());
    }

    // ── updateFileMetadata ────────────────────────────────────────────────────

    @Test
    void updateFileMetadata_existingFile_shouldDeleteAndReinsert() throws Exception {
        Path file = Files.writeString(tempDir.resolve("photo.jpg"), "updated");
        fileMetadataService.updateFileMetadata(file, 1, "photo.jpg");
        verify(fileMetadataRepository).delete(1, "photo.jpg");
        verify(fileMetadataRepository).add(any(FileMetadata.class));
    }

    @Test
    void updateFileMetadata_nonExistentFile_shouldThrow() {
        Path ghost = tempDir.resolve("ghost.jpg");
        assertThatThrownBy(() -> fileMetadataService.updateFileMetadata(ghost, 1, "ghost.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── softDeleteFileMetadata ────────────────────────────────────────────────

    @Test
    void softDeleteFileMetadata_noClientsConnected_shouldHardDelete() {
        fileMetadataService.softDeleteFileMetadata(1, "photo.jpg", "");
        verify(fileMetadataRepository).delete(1, "photo.jpg");
        verify(fileMetadataRepository, never()).softDelete(any(Long.class), any(), any(Long.class), any());
    }

    @Test
    void softDeleteFileMetadata_withClients_shouldSoftDelete() {
        fileMetadataService.softDeleteFileMetadata(1, "photo.jpg", "hw-id-1,hw-id-2");
        verify(fileMetadataRepository).softDelete(1L, "photo.jpg", 1L, "hw-id-1,hw-id-2");
    }

    // ── acknowledgeDelete ─────────────────────────────────────────────────────

    @Test
    void acknowledgeDelete_shouldDelegateToRepository() {
        fileMetadataService.acknowledgeDelete(1, "photo.jpg", "hw-id-1");
        verify(fileMetadataRepository).acknowledgeDelete(1, "photo.jpg", "hw-id-1");
    }

    // ── deleteAllForRootDir ───────────────────────────────────────────────────

    @Test
    void deleteAllForRootDir_shouldDelegate() {
        fileMetadataService.deleteAllForRootDir(5);
        verify(fileMetadataRepository).deleteAllByRootDirId(5);
    }

    // ── stampSyncVersion ──────────────────────────────────────────────────────

    @Test
    void stampSyncVersion_shouldDelegate() {
        fileMetadataService.stampSyncVersion(1, "photo.jpg", 99L);
        verify(fileMetadataRepository).updateSyncVersion(1, "photo.jpg", 99L);
    }

    // ── findChangedSince ──────────────────────────────────────────────────────

    @Test
    void findChangedSince_shouldDelegate() {
        List<FileMetadata> expected = List.of(FileMetadata.builder().id(1L).build());
        when(fileMetadataRepository.findChangedSince(1, 5L, 100)).thenReturn(expected);
        List<FileMetadata> result = fileMetadataService.findChangedSince(1, 5L, 100);
        assertThat(result).isSameAs(expected);
    }

    // ── addAllFileMetadataForRoot ─────────────────────────────────────────────

    @Test
    void addAllFileMetadataForRoot_storedRelativePathsHaveNoLeadingSeparator() throws Exception {
        Path subDir = Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(subDir.resolve("img.jpg"), "data");

        List<FileMetadata> captured = new ArrayList<>();
        doAnswer(inv -> { captured.addAll(inv.getArgument(0)); return null; })
                .when(fileMetadataRepository).addAll(anyList());

        fileMetadataService.addAllFileMetadataForRoot(tempDir.toString(), 1);

        assertThat(captured).isNotEmpty();
        captured.forEach(m ->
                assertThat(m.relativePath())
                        .as("relative path must not start with / or \\")
                        .doesNotStartWith("/")
                        .doesNotStartWith("\\"));
    }

    // ── run (ApplicationRunner) ───────────────────────────────────────────────

    @Test
    void run_shouldSeedSyncVersionCounterFromRepository() throws Exception {
        when(fileMetadataRepository.getMaxSyncVersionByRootDir()).thenReturn(new ConcurrentHashMap<>(Map.of(1, 50L, 2, 200L)));
        fileMetadataService.run(null);
        // After seeding, next version for rootDir 1 should be 51
        long v = fileMetadataService.nextSyncVersion(1);
        assertThat(v).isEqualTo(51L);
    }
}

