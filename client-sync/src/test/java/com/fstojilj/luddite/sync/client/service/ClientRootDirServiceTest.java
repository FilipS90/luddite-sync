package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.client.model.ClientRootDir;
import com.fstojilj.luddite.sync.client.repository.RootDirRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientRootDirServiceTest {

    @Mock
    private RootDirRepository rootDirRepository;
    @Mock
    private FileMetadataService fileMetadataService;

    @InjectMocks
    private RootDirService rootDirService;

    @TempDir
    Path tempDir;

    private void setMirrorDir(String path) {
        ReflectionTestUtils.setField(rootDirService, "mirrorDirPath", path);
        ReflectionTestUtils.setField(rootDirService, "retainLocalDirectory", false);
    }

    // ── retrieveAllInSyncDirs ─────────────────────────────────────────────────

    @Test
    void retrieveAllInSyncDirs_returnsDirNames() {
        when(rootDirRepository.findAll()).thenReturn(List.of(
                new ClientRootDir("photos", 1L, null),
                new ClientRootDir("docs", 2L, null)
        ));
        List<String> dirs = rootDirService.retrieveAllInSyncDirs();
        assertThat(dirs).containsExactlyInAnyOrder("photos", "docs");
    }

    // ── findAll ───────────────────────────────────────────────────────────────

    @Test
    void findAll_delegatesToRepository() {
        List<ClientRootDir> entries = List.of(new ClientRootDir("photos", 5L, null));
        when(rootDirRepository.findAll()).thenReturn(entries);
        assertThat(rootDirService.findAll()).isSameAs(entries);
    }

    // ── registerIfAbsent ──────────────────────────────────────────────────────

    @Test
    void registerWithDefaultPath_delegatesToRepository() {
        rootDirService.registerWithDefaultPath("photos");
        verify(rootDirRepository).registerIfAbsent("photos");
    }

    // ── registerWithCustomPath ────────────────────────────────────────────────

    @Test
    void registerWithCustomPath_delegatesToRepository() {
        rootDirService.registerWithCustomPath("photos", "/custom/photos");
        verify(rootDirRepository).registerWithCustomPath("photos", "/custom/photos");
    }

    // ── resolveLocalPath ──────────────────────────────────────────────────────

    @Test
    void resolveLocalPath_withCustomPath_returnsStoredPath() {
        setMirrorDir(tempDir.toString());
        when(rootDirRepository.findCustomPath("photos")).thenReturn(Optional.of("/custom/photos"));
        assertThat(rootDirService.resolveLocalPath("photos")).isEqualTo(Path.of("/custom/photos"));
    }

    @Test
    void resolveLocalPath_withNullPath_returnsMirrorDirDefault() {
        setMirrorDir(tempDir.toString());
        when(rootDirRepository.findCustomPath("photos")).thenReturn(Optional.empty());
        assertThat(rootDirService.resolveLocalPath("photos")).isEqualTo(tempDir.resolve("photos"));
    }

    // ── updateSyncVersion ─────────────────────────────────────────────────────

    @Test
    void updateSyncVersion_delegatesToRepository() {
        rootDirService.updateSyncVersion("photos", 99L);
        verify(rootDirRepository).updateSyncVersion("photos", 99L);
    }

    // ── resetSyncVersionForDir ────────────────────────────────────────────────

    @Test
    void resetSyncVersionForDir_delegatesToRepository() {
        rootDirService.resetSyncVersionForDir("photos");
        verify(rootDirRepository).reset("photos");
    }

    // ── removeDirectory ───────────────────────────────────────────────────────

    @Test
    void removeDirectory_deletesLocalDirAndCallsRepository() throws Exception {
        setMirrorDir(tempDir.toString());
        when(rootDirRepository.findCustomPath("photos")).thenReturn(Optional.empty());
        Path photosDir = Files.createDirectory(tempDir.resolve("photos"));
        Files.writeString(photosDir.resolve("file.jpg"), "data");

        rootDirService.removeDirectory("photos", true);

        verify(rootDirRepository).remove("photos");
        assertThat(photosDir).doesNotExist();
    }

    @Test
    void removeDirectory_withCustomPath_deletesFromCustomLocation() throws Exception {
        setMirrorDir(tempDir.toString());
        Path customDir = Files.createDirectory(tempDir.resolve("custom-photos"));
        Files.writeString(customDir.resolve("file.jpg"), "data");
        when(rootDirRepository.findCustomPath("photos")).thenReturn(Optional.of(customDir.toString()));

        rootDirService.removeDirectory("photos", true);

        verify(rootDirRepository).remove("photos");
        assertThat(customDir).doesNotExist();
    }

    // ── removeStaleDirs ───────────────────────────────────────────────────────

    @Test
    void removeStaleDirs_retainFalse_deletesFromDisk() throws Exception {
        setMirrorDir(tempDir.toString());
        when(rootDirRepository.findCustomPath("stale")).thenReturn(Optional.empty());
        Path staleDir = Files.createDirectory(tempDir.resolve("stale"));
        Files.writeString(staleDir.resolve("old.jpg"), "data");

        rootDirService.removeStaleDirs(List.of("stale"));

        verify(fileMetadataService).purgeAllFilesForDir("stale");
        verify(rootDirRepository).remove("stale");
        assertThat(staleDir).doesNotExist();
    }

    @Test
    void removeStaleDirs_retainTrue_doesNotDeleteFromDisk() throws Exception {
        ReflectionTestUtils.setField(rootDirService, "mirrorDirPath", tempDir.toString());
        ReflectionTestUtils.setField(rootDirService, "retainLocalDirectory", true);
        when(rootDirRepository.findCustomPath("stale")).thenReturn(Optional.empty());
        Path staleDir = Files.createDirectory(tempDir.resolve("stale"));

        rootDirService.removeStaleDirs(List.of("stale"));

        verify(rootDirRepository).remove("stale");
        assertThat(staleDir).exists();
    }

    @Test
    void removeStaleDirs_empty_doesNothing() {
        setMirrorDir(tempDir.toString());
        rootDirService.removeStaleDirs(List.of());
        verify(rootDirRepository, never()).remove(org.mockito.ArgumentMatchers.any());
    }
}


