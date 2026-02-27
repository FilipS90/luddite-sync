package com.fstojilj.luddite.sync.client.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncedFileRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private SyncedFileRepository syncedFileRepository;

    @Test
    void upsert_shouldExecuteInsertWithConflictIgnore() {
        syncedFileRepository.upsert("photos", "/img.jpg");

        verify(jdbcTemplate).update(contains("INSERT INTO synced_files"), eq("photos"), eq("/img.jpg"));
    }

    @Test
    void delete_shouldExecuteDelete() {
        syncedFileRepository.delete("photos", "/img.jpg");

        verify(jdbcTemplate).update(contains("DELETE FROM synced_files"), eq("photos"), eq("/img.jpg"));
    }

    @Test
    void findAllByDir_shouldReturnRelativePaths() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("photos")))
                .thenReturn(List.of("/a.jpg", "/b.jpg"));

        List<String> result = syncedFileRepository.findAllByDir("photos");

        assertThat(result).containsExactlyInAnyOrder("/a.jpg", "/b.jpg");
    }

    @Test
    void findAllByDir_noFiles_shouldReturnEmpty() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq("docs")))
                .thenReturn(List.of());

        List<String> result = syncedFileRepository.findAllByDir("docs");

        assertThat(result).isEmpty();
    }
}


