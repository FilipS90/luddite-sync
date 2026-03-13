package com.fstojilj.luddite.sync.server.repository;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileMetadataRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private FileMetadataRepository fileMetadataRepository;

    private FileMetadata sampleMetadata() {
        return FileMetadata.builder()
                .id(1L).filename("img.jpg").rootDirId(1L)
                .syncVersion(5L)
                .relativePath("/img.jpg").checksum("abc").fileSize(100L)
                .build();
    }

    @Test
    void add_shouldReturnGeneratedKey() {
        doAnswer(inv -> {
            GeneratedKeyHolder kh = inv.getArgument(1);
            kh.getKeyList().add(Map.of("", 42));
            return 1;
        }).when(jdbcTemplate).update(any(), any(GeneratedKeyHolder.class));

        long id = fileMetadataRepository.add(sampleMetadata());

        assertThat(id).isEqualTo(42L);
    }

    @Test
    void add_nullKey_shouldThrow() {
        doAnswer(inv -> 1).when(jdbcTemplate).update(any(), any(GeneratedKeyHolder.class));

        assertThatThrownBy(() -> fileMetadataRepository.add(sampleMetadata()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generated key");
    }

    @Test
    void findByRootDirIdAndRelativePath_found_shouldReturn() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1L), eq("/img.jpg")))
                .thenReturn(List.of(sampleMetadata()));

        FileMetadata result = fileMetadataRepository.findByRootDirIdAndRelativePath(1L, "/img.jpg");

        assertThat(result.filename()).isEqualTo("img.jpg");
    }

    @Test
    void findByRootDirIdAndRelativePath_notFound_shouldThrow() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyLong(), anyString()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> fileMetadataRepository.findByRootDirIdAndRelativePath(1L, "/missing.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findOptionalByRootDirIdAndRelativePath_found_shouldReturnPresent() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1L), eq("/img.jpg")))
                .thenReturn(List.of(sampleMetadata()));

        Optional<FileMetadata> result = fileMetadataRepository.findOptionalByRootDirIdAndRelativePath(1L, "/img.jpg");

        assertThat(result).isPresent();
    }

    @Test
    void findOptionalByRootDirIdAndRelativePath_notFound_shouldReturnEmpty() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyLong(), anyString()))
                .thenReturn(List.of());

        Optional<FileMetadata> result = fileMetadataRepository.findOptionalByRootDirIdAndRelativePath(1L, "/missing.jpg");

        assertThat(result).isEmpty();
    }

    @Test
    void update_shouldExecuteUpdateSql() {
        FileMetadata meta = sampleMetadata();

        fileMetadataRepository.update(meta);

        verify(jdbcTemplate).update(contains("UPDATE file_metadata"),
                eq("img.jpg"), eq(1L), eq("/img.jpg"), eq("abc"), eq(100L), any(), eq(5L), eq(1L));
    }

    @Test
    void findByRootDirIdWithSyncVersionAfter_shouldReturnResults() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1L), eq(5L)))
                .thenReturn(List.of(sampleMetadata()));

        List<FileMetadata> result = fileMetadataRepository.findByRootDirIdWithSyncVersionAfter(1L, 5L);

        assertThat(result).hasSize(1);
    }

    @Test
    void updateSyncVersion_shouldExecuteUpdate() {
        fileMetadataRepository.updateSyncVersion(1L, "/img.jpg", 10L);

        verify(jdbcTemplate).update(anyString(), eq(10L), eq(1L), eq("/img.jpg"));
    }

    @Test
    void delete_shouldExecuteDelete() {
        when(jdbcTemplate.update(anyString(), eq(1L), eq("/img.jpg"))).thenReturn(1);

        fileMetadataRepository.delete(1L, "/img.jpg");

        verify(jdbcTemplate).update(contains("DELETE FROM file_metadata"), eq(1L), eq("/img.jpg"));
    }

    @Test
    void deleteAllByRootDirId_shouldExecuteDelete() {
        fileMetadataRepository.deleteAllByRootDirId(1L);

        verify(jdbcTemplate).update(contains("DELETE FROM file_metadata"), eq(1L));
    }
}

