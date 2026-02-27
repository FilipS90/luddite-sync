package com.fstojilj.luddite.sync.server.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeletedFilesRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private DeletedFilesRepository deletedFilesRepository;

    @Test
    void insert_shouldExecuteUpsertSql() {
        deletedFilesRepository.insert(1L, "/img.jpg", 42L);

        verify(jdbcTemplate).update(contains("INSERT INTO deleted_files"), eq(1L), eq("/img.jpg"), eq(42L));
    }

    @Test
    void findByRootDirIdWithSyncVersionAfter_shouldReturnResults() {
        List<Map<String, Object>> expected = List.of(
                Map.of("relative_path", "/img.jpg", "sync_version", 5L)
        );
        when(jdbcTemplate.queryForList(anyString(), eq(1L), eq(3L))).thenReturn(expected);

        List<Map<String, Object>> result = deletedFilesRepository.findByRootDirIdWithSyncVersionAfter(1L, 3L);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().get("relative_path")).isEqualTo("/img.jpg");
    }

    @Test
    void findByRootDirIdWithSyncVersionAfter_noResults_shouldReturnEmpty() {
        when(jdbcTemplate.queryForList(anyString(), anyLong(), anyLong())).thenReturn(List.of());

        List<Map<String, Object>> result = deletedFilesRepository.findByRootDirIdWithSyncVersionAfter(1L, 100L);

        assertThat(result).isEmpty();
    }

    @Test
    void deleteAllByRootDirId_shouldExecuteDelete() {
        deletedFilesRepository.deleteAllByRootDirId(1L);

        verify(jdbcTemplate).update(contains("DELETE FROM deleted_files"), eq(1L));
    }
}


