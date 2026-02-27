package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.repository.RootDirRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    @Test
    void addRootDir_shouldInsertAndStartWatching() {
        String path = "/home/user/photos";
        when(rootDirRepository.insert(any(RootDir.class))).thenReturn(42L);

        rootDirService.addRootDir(path);

        verify(rootDirRepository).insert(any(RootDir.class));
        verify(fileMetadataService).addAllFileMetadataForRoot(path, 42L);
        verify(dirWatcherService).startWatching(path, 42L);
    }

    @Test
    void removeRootDir_existingId_shouldRemoveAndStopWatching() {
        RootDir dir = RootDir.builder()
                .id(1L)
                .name("photos")
                .absolutePath("/home/user/photos")
                .build();
        when(rootDirRepository.getRootDirById(1L)).thenReturn(Optional.of(dir));
        when(rootDirRepository.deleteRootDirById(1L)).thenReturn(true);

        boolean result = rootDirService.removeRootDir(1L);

        assertThat(result).isTrue();
        verify(dirWatcherService).stopWatching("/home/user/photos");
        verify(fileMetadataService).deleteAllForRootDir(1L);
        verify(rootDirRepository).deleteRootDirById(1L);
    }

    @Test
    void removeRootDir_nonExistingId_shouldReturnFalse() {
        when(rootDirRepository.getRootDirById(99L)).thenReturn(Optional.empty());

        boolean result = rootDirService.removeRootDir(99L);

        assertThat(result).isFalse();
        verify(dirWatcherService, never()).stopWatching(anyString());
        verify(fileMetadataService, never()).deleteAllForRootDir(anyLong());
    }

    @Test
    void findAll_shouldDelegateToRepository() {
        Set<RootDir> expected = Set.of(
                RootDir.builder().id(1L).name("photos").absolutePath("/photos").build(),
                RootDir.builder().id(2L).name("docs").absolutePath("/docs").build()
        );
        when(rootDirRepository.findAll()).thenReturn(expected);

        Set<RootDir> result = rootDirService.findAll();

        assertThat(result).hasSize(2);
        verify(rootDirRepository).findAll();
    }

    @Test
    void getRootDirPathById_existingId_shouldReturnPath() {
        RootDir dir = RootDir.builder()
                .id(1L)
                .name("photos")
                .absolutePath("/home/user/photos")
                .build();
        when(rootDirRepository.getRootDirById(1L)).thenReturn(Optional.of(dir));

        String result = rootDirService.getRootDirPathById(1L);

        assertThat(result).isEqualTo("/home/user/photos");
    }

    @Test
    void getRootDirPathById_nonExistingId_shouldThrow() {
        when(rootDirRepository.getRootDirById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rootDirService.getRootDirPathById(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Root directory not found");
    }

    @Test
    void getRootDirNameById_existingId_shouldReturnName() {
        RootDir dir = RootDir.builder()
                .id(1L)
                .name("photos")
                .absolutePath("/home/user/photos")
                .build();
        when(rootDirRepository.getRootDirById(1L)).thenReturn(Optional.of(dir));

        String result = rootDirService.getRootDirNameById(1L);

        assertThat(result).isEqualTo("photos");
    }

    @Test
    void getRootDirNameById_nonExistingId_shouldThrow() {
        when(rootDirRepository.getRootDirById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> rootDirService.getRootDirNameById(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findByName_shouldDelegateToRepository() {
        RootDir dir = RootDir.builder()
                .id(1L)
                .name("photos")
                .absolutePath("/home/user/photos")
                .build();
        when(rootDirRepository.findByName("photos")).thenReturn(Optional.of(dir));

        Optional<RootDir> result = rootDirService.findByName("photos");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("photos");
    }

    @Test
    void findByName_nonExisting_shouldReturnEmpty() {
        when(rootDirRepository.findByName("unknown")).thenReturn(Optional.empty());

        Optional<RootDir> result = rootDirService.findByName("unknown");

        assertThat(result).isEmpty();
    }

    @Test
    void getRootDirIdByAbsolutePath_shouldDelegateToRepository() {
        when(rootDirRepository.getRootDirIdByAbsolutePath("/home/user/photos")).thenReturn(42L);

        Long result = rootDirService.getRootDirIdByAbsolutePath("/home/user/photos");

        assertThat(result).isEqualTo(42L);
    }
}

