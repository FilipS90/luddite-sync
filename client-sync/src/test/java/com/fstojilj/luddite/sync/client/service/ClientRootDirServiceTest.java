package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.client.repository.RootDirRepository;
import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
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
                new SyncHandshakeEntry("photos", 1L),
                new SyncHandshakeEntry("docs", 2L)
        ));
        List<String> dirs = rootDirService.retrieveAllInSyncDirs();
        assertThat(dirs).containsExactlyInAnyOrder("photos", "docs");
    }

    // ── findAll ───────────────────────────────────────────────────────────────

    @Test
    void findAll_delegatesToRepository() {
        List<SyncHandshakeEntry> entries = List.of(new SyncHandshakeEntry("photos", 5L));
        when(rootDirRepository.findAll()).thenReturn(entries);
        assertThat(rootDirService.findAll()).isSameAs(entries);
    }

    // ── registerIfAbsent ──────────────────────────────────────────────────────

    @Test
    void registerIfAbsent_delegatesToRepository() {
        rootDirService.registerIfAbsent("photos");
        verify(rootDirRepository).registerIfAbsent("photos");
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
        Path photosDir = Files.createDirectory(tempDir.resolve("photos"));
        Files.writeString(photosDir.resolve("file.jpg"), "data");

        rootDirService.removeDirectory("photos");

        verify(rootDirRepository).remove("photos");
        assertThat(photosDir).doesNotExist();
    }

    // ── removeStaleDirs ───────────────────────────────────────────────────────

    @Test
    void removeStaleDirs_retainFalse_deletesFromDisk() throws Exception {
        setMirrorDir(tempDir.toString());
        Path staleDir = Files.createDirectory(tempDir.resolve("stale"));
        Files.writeString(staleDir.resolve("old.jpg"), "data");

        when(fileMetadataService.findAllByDir("stale")).thenReturn(List.of("stale/old.jpg"));

        rootDirService.removeStaleDirs(List.of("stale"));

        verify(fileMetadataService).removeRecord("stale", "stale/old.jpg");
        verify(rootDirRepository).remove("stale");
        assertThat(staleDir).doesNotExist();
    }

    @Test
    void removeStaleDirs_retainTrue_doesNotDeleteFromDisk() throws Exception {
        ReflectionTestUtils.setField(rootDirService, "mirrorDirPath", tempDir.toString());
        ReflectionTestUtils.setField(rootDirService, "retainLocalDirectory", true);
        Path staleDir = Files.createDirectory(tempDir.resolve("stale"));

        when(fileMetadataService.findAllByDir("stale")).thenReturn(List.of());

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

