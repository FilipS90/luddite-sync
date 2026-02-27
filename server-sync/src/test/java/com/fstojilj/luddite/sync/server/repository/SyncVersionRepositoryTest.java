package com.fstojilj.luddite.sync.server.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SyncVersionRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private SyncVersionRepository syncVersionRepository;

    @Test
    void next_shouldReturnGeneratedKey() {
        doAnswer(inv -> {
            GeneratedKeyHolder keyHolder = inv.getArgument(1);
            keyHolder.getKeyList().add(java.util.Map.of("", 99));
            return 1;
        }).when(jdbcTemplate).update(any(), any(GeneratedKeyHolder.class));

        long version = syncVersionRepository.next();

        org.assertj.core.api.Assertions.assertThat(version).isEqualTo(99L);
    }

    @Test
    void next_nullKey_shouldThrow() {
        doAnswer(inv -> 1).when(jdbcTemplate).update(any(), any(GeneratedKeyHolder.class));

        assertThatThrownBy(() -> syncVersionRepository.next())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sync version");
    }

    @Test
    void markSynced_shouldUpdateStatus() {
        syncVersionRepository.markSynced(42L);

        verify(jdbcTemplate).update(contains("SYNCED"), eq(42L));
    }
}


