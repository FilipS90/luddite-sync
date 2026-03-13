package com.fstojilj.luddite.sync.server.repository;

import com.fstojilj.luddite.sync.common.model.RootDir;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RootDirRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private RootDirRepository rootDirRepository;

    @Test
    void insert_shouldReturnGeneratedKey() {
        doAnswer(inv -> {
            GeneratedKeyHolder keyHolder = inv.getArgument(1);
            keyHolder.getKeyList().add(Map.of("", 5));
            return 1;
        }).when(jdbcTemplate).update(any(), any(GeneratedKeyHolder.class));

        long id = rootDirRepository.insert(RootDir.builder().name("photos").absolutePath("/photos").build());

        assertThat(id).isEqualTo(5L);
    }

    @Test
    void findAll_shouldReturnAllDirs() {
        List<RootDir> dirs = List.of(
                RootDir.builder().id(1L).name("photos").absolutePath("/photos").build(),
                RootDir.builder().id(2L).name("docs").absolutePath("/docs").build()
        );
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(dirs);

        Set<RootDir> result = rootDirRepository.findAll();

        assertThat(result).hasSize(2);
    }

    @Test
    void deleteRootDirById_existingId_shouldReturnTrue() {
        when(jdbcTemplate.update(anyString(), eq(1L))).thenReturn(1);

        boolean result = rootDirRepository.deleteRootDirById(1L);

        assertThat(result).isTrue();
    }

    @Test
    void deleteRootDirById_nonExistingId_shouldReturnFalse() {
        when(jdbcTemplate.update(anyString(), eq(99L))).thenReturn(0);

        boolean result = rootDirRepository.deleteRootDirById(99L);

        assertThat(result).isFalse();
    }

    @Test
    void getRootDirById_existingId_shouldReturnOptional() {
        RootDir dir = RootDir.builder().id(1L).name("photos").absolutePath("/photos").build();
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(1L))).thenReturn(dir);

        Optional<RootDir> result = rootDirRepository.getRootDirById(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("photos");
    }

    @Test
    void getRootDirById_nonExistingId_shouldReturnEmpty() {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(99L))).thenReturn(null);

        Optional<RootDir> result = rootDirRepository.getRootDirById(99L);

        assertThat(result).isEmpty();
    }

    @Test
    void update_shouldExecuteUpdateSql() {
        RootDir dir = RootDir.builder().id(1L).name("photos").absolutePath("/photos").build();

        rootDirRepository.update(dir);

        verify(jdbcTemplate).update(contains("UPDATE root_dir"), eq("photos"), eq("/photos"), eq(1L));
    }
}
