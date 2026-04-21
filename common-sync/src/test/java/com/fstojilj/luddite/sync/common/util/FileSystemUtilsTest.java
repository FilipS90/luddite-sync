package com.fstojilj.luddite.sync.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemUtilsTest {

    @TempDir
    Path tempDir;

    // ── deleteDirectoryRecursively ────────────────────────────────────────────

    @Test
    void deleteDirectoryRecursively_emptyDir_deletesDir() {
        FileSystemUtils.deleteDirectoryRecursively(tempDir);
        assertThat(tempDir).doesNotExist();
    }

    @Test
    void deleteDirectoryRecursively_withFiles_deletesAll() throws Exception {
        Files.writeString(tempDir.resolve("a.jpg"), "data");
        Files.writeString(tempDir.resolve("b.jpg"), "data");

        FileSystemUtils.deleteDirectoryRecursively(tempDir);

        assertThat(tempDir).doesNotExist();
    }

    @Test
    void deleteDirectoryRecursively_withSubdirectories_deletesRecursively() throws Exception {
        Path sub = Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(sub.resolve("nested.jpg"), "data");

        FileSystemUtils.deleteDirectoryRecursively(tempDir);

        assertThat(tempDir).doesNotExist();
    }

    @Test
    void deleteDirectoryRecursively_nonExistentPath_doesNotThrow() {
        Path ghost = tempDir.resolve("does-not-exist");
        // Should log a warning and return cleanly without throwing
        FileSystemUtils.deleteDirectoryRecursively(ghost);
    }
}

