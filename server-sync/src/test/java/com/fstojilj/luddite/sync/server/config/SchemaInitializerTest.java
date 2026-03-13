package com.fstojilj.luddite.sync.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaInitializerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private SchemaInitializer schemaInitializer;

    @Test
    void run_shouldExecuteAllCreateTableStatements() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(1);

        schemaInitializer.run(mock(org.springframework.boot.ApplicationArguments.class));

        // Verify that at least the core tables were created
        verify(jdbcTemplate, org.mockito.Mockito.atLeast(5)).execute(anyString());
    }

    @Test
    void run_triggerNotExists_shouldCreateTrigger() throws Exception {
        // Return 0 so triggers get created
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);

        schemaInitializer.run(mock(org.springframework.boot.ApplicationArguments.class));

        // Both triggers should be executed
        verify(jdbcTemplate, org.mockito.Mockito.atLeast(7)).execute(anyString());
    }
}

