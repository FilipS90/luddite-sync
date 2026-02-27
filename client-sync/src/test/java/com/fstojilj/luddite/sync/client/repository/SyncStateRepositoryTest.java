package com.fstojilj.luddite.sync.client.repository;

import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncStateRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private SyncStateRepository syncStateRepository;

    @Test
    void findAll_shouldReturnAllEntries() {
        List<SyncHandshakeEntry> expected = List.of(
                new SyncHandshakeEntry("photos", 10L),
                new SyncHandshakeEntry("docs", 5L)
        );
        when(jdbcTemplate.query(eq("SELECT * FROM sync_state"), any(RowMapper.class)))
                .thenReturn(expected);

        List<SyncHandshakeEntry> result = syncStateRepository.findAll();

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().dirName()).isEqualTo("photos");
    }

    @Test
    void registerIfAbsent_shouldExecuteInsertWithConflictIgnore() {
        syncStateRepository.registerIfAbsent("photos");

        verify(jdbcTemplate).update(contains("INSERT INTO sync_state"), eq("photos"));
    }

    @Test
    void upsert_shouldExecuteInsertWithConflictUpdate() {
        syncStateRepository.upsert("photos", 42L);

        verify(jdbcTemplate).update(contains("INSERT INTO sync_state"), eq("photos"), eq(42L));
    }
}

