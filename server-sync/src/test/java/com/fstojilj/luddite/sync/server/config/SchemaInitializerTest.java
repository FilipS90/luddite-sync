package com.fstojilj.luddite.sync.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaInitializerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SyncServerProperties properties;

    @InjectMocks
    private SchemaInitializer schemaInitializer;

    @Test
    void run_shouldExecuteAllCreateTableStatements() throws Exception {
        when(properties.getRootDirs()).thenReturn(Set.of());
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(1);
        lenient().when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        schemaInitializer.run(mock(org.springframework.boot.ApplicationArguments.class));

        verify(jdbcTemplate, atLeast(2)).execute(anyString());
    }

    @Test
    void run_triggerNotExists_shouldCreateTrigger() throws Exception {
        when(properties.getRootDirs()).thenReturn(Set.of());
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);
        lenient().when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        schemaInitializer.run(mock(org.springframework.boot.ApplicationArguments.class));

        // 2 CREATE TABLEs + 2 CREATE TRIGGERs = at least 4 execute() calls
        verify(jdbcTemplate, atLeast(4)).execute(anyString());
    }
}
