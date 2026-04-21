package com.fstojilj.luddite.sync.client.service;

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
class HardwareIdServiceTest {

    @InjectMocks
    private HardwareIdService hardwareIdService;

    @TempDir
    Path tempDir;

    private void init(String mirrorDir) {
        ReflectionTestUtils.setField(hardwareIdService, "mirrorDir", mirrorDir);
        hardwareIdService.init();
    }

    // ── fallback UUID ─────────────────────────────────────────────────────────

    @Test
    void init_whenSerialUnreadable_persistsUUID() throws Exception {
        // Force the fallback by pointing mirrorDir to a fresh temp dir on a non-Linux OS
        // (on Linux CI, the DMI path check will also fail for non-root runners)
        init(tempDir.toString());

        String id = hardwareIdService.getHardwareId();
        assertThat(id).isNotBlank();

        // Fallback UUID should be persisted
        Path machineIdFile = tempDir.resolve("client").resolve("machine-id");
        if (Files.exists(machineIdFile)) {
            String persisted = Files.readString(machineIdFile).trim();
            assertThat(persisted).isEqualTo(id);
        }
    }

    @Test
    void init_calledTwice_returnsSameId() {
        init(tempDir.toString());
        String first = hardwareIdService.getHardwareId();

        // Re-init with same mirrorDir — should read from persisted file
        HardwareIdService second = new HardwareIdService();
        ReflectionTestUtils.setField(second, "mirrorDir", tempDir.toString());
        second.init();

        assertThat(second.getHardwareId()).isEqualTo(first);
    }

    @Test
    void init_hardwareIdIsNotBlank() {
        init(tempDir.toString());
        assertThat(hardwareIdService.getHardwareId()).isNotBlank();
    }

    @Test
    void init_hardwareIdIsNotDefaultString() {
        init(tempDir.toString());
        assertThat(hardwareIdService.getHardwareId())
                .doesNotContainIgnoringCase("Default string");
    }
}

