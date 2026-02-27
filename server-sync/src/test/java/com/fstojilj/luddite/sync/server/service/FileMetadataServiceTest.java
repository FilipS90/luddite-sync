package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.server.repository.DeletedFilesRepository;
import com.fstojilj.luddite.sync.server.repository.FileMetadataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileMetadataServiceTest {

    @Mock
    private FileMetadataRepository fileMetadataRepository;

    @Mock
    private DeletedFilesRepository deletedFilesRepository;

    @InjectMocks
    private FileMetadataService fileMetadataService;

    @TempDir
    Path tempDir;

    @Test
    void addFileMetadata_existingFile_shouldAddToRepository(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("img.jpg");
        Files.writeString(file, "photo-content");

        fileMetadataService.addFileMetadata(file, 1L, "/img.jpg");

        verify(fileMetadataRepository).add(any(FileMetadata.class));
    }

    @Test
    void addFileMetadata_nonExistingFile_shouldThrow() {
        Path missing = tempDir.resolve("missing.jpg");

        assertThatThrownBy(() -> fileMetadataService.addFileMetadata(missing, 1L, "/missing.jpg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing file");
    }

    @Test
    void updateFileMetadata_existingRecord_shouldUpdate(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("img.jpg");
        Files.writeString(file, "updated-content");

        FileMetadata existing = FileMetadata.builder()
                .id(1L).rootDirId(1L).relativePath("/img.jpg")
                .filename("img.jpg").fileSize(10L).checksum("old").build();
        when(fileMetadataRepository.findOptionalByRootDirIdAndRelativePath(1L, "/img.jpg"))
                .thenReturn(Optional.of(existing));

        fileMetadataService.updateFileMetadata(file, 1L, "/img.jpg");

        verify(fileMetadataRepository).update(any(FileMetadata.class));
        verify(fileMetadataRepository, never()).add(any());
    }

    @Test
    void updateFileMetadata_noExistingRecord_shouldInsertInstead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("img.jpg");
        Files.writeString(file, "new-content");

        when(fileMetadataRepository.findOptionalByRootDirIdAndRelativePath(1L, "/img.jpg"))
                .thenReturn(Optional.empty());

        fileMetadataService.updateFileMetadata(file, 1L, "/img.jpg");

        verify(fileMetadataRepository).add(any(FileMetadata.class));
        verify(fileMetadataRepository, never()).update(any());
    }

    @Test
    void deleteFileMetadata_shouldDelegateToRepository() {
        fileMetadataService.deleteFileMetadata(1L, "/img.jpg");

        verify(fileMetadataRepository).delete(1L, "/img.jpg");
    }

    @Test
    void deleteAllForRootDir_shouldDeleteMetadataAndDeletedFiles() {
        fileMetadataService.deleteAllForRootDir(1L);

        verify(fileMetadataRepository).deleteAllByRootDirId(1L);
        verify(deletedFilesRepository).deleteAllByRootDirId(1L);
    }

    @Test
    void stampSyncVersion_shouldUpdateRepository() {
        fileMetadataService.stampSyncVersion(1L, "/img.jpg", 42L);

        verify(fileMetadataRepository).updateSyncVersion(1L, "/img.jpg", 42L);
    }

    @Test
    void recordDeletion_shouldInsertIntoDeletedFiles() {
        fileMetadataService.recordDeletion(1L, "/img.jpg", 99L);

        verify(deletedFilesRepository).insert(1L, "/img.jpg", 99L);
    }

    @Test
    void findFilesNewerThan_shouldDelegateToRepository() {
        List<FileMetadata> expected = List.of(FileMetadata.builder().id(1L).build());
        when(fileMetadataRepository.findByRootDirIdWithSyncVersionAfter(1L, 10L)).thenReturn(expected);

        List<FileMetadata> result = fileMetadataService.findFilesNewerThan(1L, 10L);

        assertThat(result).hasSize(1);
        verify(fileMetadataRepository).findByRootDirIdWithSyncVersionAfter(1L, 10L);
    }

    @Test
    void findDeletesNewerThan_shouldDelegateToRepository() {
        List<Map<String, Object>> expected = List.of(Map.of("relative_path", "/img.jpg", "sync_version", 5L));
        when(deletedFilesRepository.findByRootDirIdWithSyncVersionAfter(1L, 3L)).thenReturn(expected);

        List<Map<String, Object>> result = fileMetadataService.findDeletesNewerThan(1L, 3L);

        assertThat(result).hasSize(1);
        verify(deletedFilesRepository).findByRootDirIdWithSyncVersionAfter(1L, 3L);
    }
}

