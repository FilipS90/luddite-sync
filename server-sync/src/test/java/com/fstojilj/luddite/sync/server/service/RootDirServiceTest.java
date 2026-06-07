package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.repository.RootDirRepository;
import com.fstojilj.luddite.sync.server.utils.FileSystemUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RootDirServiceTest {

    @Mock
    private RootDirRepository rootDirRepository;
    @Mock
    private FileMetadataService fileMetadataService;
    @Mock
    private DirWatcherService dirWatcherService;

    @InjectMocks
    private RootDirService rootDirService;

    // ── addRootDir ────────────────────────────────────────────────────────────

    @Test
    void addRootDir_validPath_shouldInsertIndexAndWatch() {
        try (MockedStatic<FileSystemUtils> utils = Mockito.mockStatic(FileSystemUtils.class)) {
            utils.when(() -> FileSystemUtils.isValidFileSystemDirectory(any()))
                    .thenAnswer(invocation -> null);

            when(rootDirRepository.insert(any(RootDir.class))).thenReturn(7);
            rootDirService.addRootDir("/photos/family", true, "admin123");
            verify(rootDirRepository).insert(any(RootDir.class));
            verify(fileMetadataService).addAllFileMetadataForRoot("/photos/family", 7);
            verify(dirWatcherService).startWatching("/photos/family", 7);
        }
    }

    // ── removeRootDir ─────────────────────────────────────────────────────────

    @Test
    void removeRootDir_existingId_shouldRemoveAndReturnTrue() {
        RootDir dir = RootDir.builder().id(1).absolutePath("/photos").build();
        when(rootDirRepository.getRootDirById(1)).thenReturn(Optional.of(dir));

        boolean result = rootDirService.removeRootDir(1);

        assertThat(result).isTrue();
        verify(dirWatcherService).stopWatching("/photos");
        verify(fileMetadataService).deleteAllForRootDir(1);
        verify(rootDirRepository).deleteRootDirById(1);
    }

    @Test
    void removeRootDir_nonExistingId_shouldReturnFalseAndDoNothing() {
        when(rootDirRepository.getRootDirById(99)).thenReturn(Optional.empty());

        boolean result = rootDirService.removeRootDir(99);

        assertThat(result).isFalse();
        verify(dirWatcherService, never()).stopWatching(anyString());
        verify(fileMetadataService, never()).deleteAllForRootDir(anyInt());
    }

    // ── findByName ────────────────────────────────────────────────────────────

    @Test
    void findByName_existing_shouldDelegate() {
        RootDir dir = RootDir.builder().name("photos").build();
        when(rootDirRepository.findByName("photos")).thenReturn(Optional.of(dir));

        Optional<RootDir> result = rootDirService.findByName("photos");

        assertThat(result).contains(dir);
    }

    @Test
    void findByName_notExisting_shouldReturnEmpty() {
        when(rootDirRepository.findByName("missing")).thenReturn(Optional.empty());
        assertThat(rootDirService.findByName("missing")).isEmpty();
    }

    // ── findAll ───────────────────────────────────────────────────────────────

    @Test
    void findAll_shouldDelegate() {
        Set<RootDir> dirs = Set.of(RootDir.builder().id(1).name("photos").build());
        when(rootDirRepository.findAll()).thenReturn(dirs);
        assertThat(rootDirService.findAll()).isSameAs(dirs);
    }
}

