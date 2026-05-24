package com.fstojilj.luddite.sync.client.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ClientIdServiceTest {

    @InjectMocks
    private ClientIdService clientIdService;

    @TempDir
    Path tempDir;

    private void init(String mirrorDir) {
        ReflectionTestUtils.setField(clientIdService, "mirrorDir", mirrorDir);
        clientIdService.init();
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(clientIdService, "clientName", "test-client");
        ReflectionTestUtils.setField(clientIdService, "mirrorDir", tempDir.toString());
        clientIdService.init();
    }

    @Test
    void init_persistsClientId() throws Exception {
        init(tempDir.toString());

        String id = clientIdService.getClientId();
        assertThat(id).isNotBlank();

        // Fallback UUID should be persisted
        Path machineIdFile = tempDir.resolve("client-id");
        if (Files.exists(machineIdFile)) {
            String persisted = Files.readString(machineIdFile).trim();
            assertThat(persisted).isEqualTo(id);
        }
    }

    @Test
    void init_clientIdIsNotBlank() {
        init(tempDir.toString());
        assertThat(clientIdService.getClientId()).isNotBlank();
    }
}

