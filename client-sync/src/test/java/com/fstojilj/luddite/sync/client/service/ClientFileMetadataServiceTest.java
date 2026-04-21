package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.client.repository.FileMetadataRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientFileMetadataServiceTest {

    @Mock
    private FileMetadataRepository fileMetadataRepository;

    @InjectMocks
    private FileMetadataService fileMetadataService;

    @TempDir
    Path tempDir;

    private void setMirrorDir(String path) {
        ReflectionTestUtils.setField(fileMetadataService, "mirrorDirPath", path);
    }

    // ── recordSynced ──────────────────────────────────────────────────────────

    @Test
    void recordSynced_delegatesToRepository() {
        fileMetadataService.recordSynced("photos", "photos/img.jpg");
        verify(fileMetadataRepository).upsert("photos", "photos/img.jpg");
    }

    // ── purgeRecord ───────────────────────────────────────────────────────────

    @Test
    void purgeRecord_deletesDbRecordOnly() {
        fileMetadataService.purgeRecord("photos", "photos/img.jpg");
        verify(fileMetadataRepository).delete("photos", "photos/img.jpg");
    }

    // ── findAllByDir ──────────────────────────────────────────────────────────

    @Test
    void findAllByDir_delegatesToRepository() {
        when(fileMetadataRepository.findAllByDir("photos")).thenReturn(List.of("photos/a.jpg", "photos/b.jpg"));
        List<String> result = fileMetadataService.findAllByDir("photos");
        assertThat(result).containsExactly("photos/a.jpg", "photos/b.jpg");
    }

    // ── removeRecord ─────────────────────────────────────────────────────────

    @Test
    void removeRecord_deletesDbAndDiskFile() throws Exception {
        setMirrorDir(tempDir.toString());
        Files.createDirectories(tempDir.resolve("photos"));
        Path file = Files.writeString(tempDir.resolve("photos/img.jpg"), "data");

        fileMetadataService.removeRecord("photos", "photos/img.jpg");

        verify(fileMetadataRepository).delete("photos", "photos/img.jpg");
        assertThat(file).doesNotExist();
    }

    @Test
    void removeRecord_fileAlreadyAbsent_doesNotThrow() throws Exception {
        setMirrorDir(tempDir.toString());
        // No file created — just the DB delete should happen
        fileMetadataService.removeRecord("photos", "photos/ghost.jpg");
        verify(fileMetadataRepository).delete("photos", "photos/ghost.jpg");
    }

    @Test
    void removeRecord_dbDeleteFails_doesNotDeleteDiskFile() throws Exception {
        setMirrorDir(tempDir.toString());
        Files.createDirectories(tempDir.resolve("photos"));
        Path file = Files.writeString(tempDir.resolve("photos/img.jpg"), "data");

        doThrow(new RuntimeException("DB error"))
                .when(fileMetadataRepository).delete("photos", "photos/img.jpg");

        assertThatThrownBy(() -> fileMetadataService.removeRecord("photos", "photos/img.jpg"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB delete failed");

        // Disk file must NOT have been deleted
        assertThat(file).exists();
    }
}

