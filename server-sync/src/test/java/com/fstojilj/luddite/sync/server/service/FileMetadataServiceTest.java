package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.server.repository.FileMetadataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    private JdbcTemplate jdbcTemplate;

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
    void softDeleteFileMetadata_shouldDelegateToRepository() {
        // nextSyncVersion uses the AtomicLong (seeded at 0), so first call returns 1
        fileMetadataService.softDeleteFileMetadata(1L, "/img.jpg", "hw-abc,hw-def");

        verify(fileMetadataRepository).softDelete(1L, "/img.jpg", 1L, "hw-abc,hw-def");
    }

    @Test
    void deleteAllForRootDir_shouldDeleteMetadata() {
        fileMetadataService.deleteAllForRootDir(1L);

        verify(fileMetadataRepository).deleteAllByRootDirId(1L);
    }

    @Test
    void stampSyncVersion_shouldUpdateRepository() {
        fileMetadataService.stampSyncVersion(1L, "/img.jpg", 42L);

        verify(fileMetadataRepository).updateSyncVersion(1L, "/img.jpg", 42L);
    }

    @Test
    void acknowledgeDelete_shouldDelegateToRepository() {
        fileMetadataService.acknowledgeDelete(1L, "/img.jpg", "hw-abc");

        verify(fileMetadataRepository).acknowledgeDelete(1L, "/img.jpg", "hw-abc");
    }

    @Test
    void findChangedSince_shouldDelegateToRepository() {
        List<FileMetadata> expected = List.of(FileMetadata.builder().id(1L).build());
        when(fileMetadataRepository.findChangedSince(1L, 10L)).thenReturn(expected);

        List<FileMetadata> result = fileMetadataService.findChangedSince(1L, 10L);

        assertThat(result).hasSize(1);
        verify(fileMetadataRepository).findChangedSince(1L, 10L);
    }
}
