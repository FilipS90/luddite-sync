package com.fstojilj.luddite.sync.server.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileChecksumUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void calculateFileChecksum_knownContent_shouldReturnSha256Hex() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello");

        String checksum = FileChecksumUtils.calculateFileChecksum(file);

        // SHA-256 of "hello"
        assertThat(checksum).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void calculateFileChecksum_sameContentTwice_shouldReturnSameChecksum() throws IOException {
        Path file1 = tempDir.resolve("a.txt");
        Path file2 = tempDir.resolve("b.txt");
        Files.writeString(file1, "luddite-sync");
        Files.writeString(file2, "luddite-sync");

        assertThat(FileChecksumUtils.calculateFileChecksum(file1))
                .isEqualTo(FileChecksumUtils.calculateFileChecksum(file2));
    }

    @Test
    void calculateFileChecksum_differentContent_shouldReturnDifferentChecksum() throws IOException {
        Path file1 = tempDir.resolve("a.txt");
        Path file2 = tempDir.resolve("b.txt");
        Files.writeString(file1, "content-a");
        Files.writeString(file2, "content-b");

        assertThat(FileChecksumUtils.calculateFileChecksum(file1))
                .isNotEqualTo(FileChecksumUtils.calculateFileChecksum(file2));
    }

    @Test
    void calculateFileChecksum_emptyFile_shouldReturnSha256OfEmptyString() throws IOException {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");

        String checksum = FileChecksumUtils.calculateFileChecksum(file);

        // SHA-256 of empty string
        assertThat(checksum).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void calculateFileChecksum_nonExistingFile_shouldThrow() {
        Path missing = tempDir.resolve("missing.txt");

        assertThatThrownBy(() -> FileChecksumUtils.calculateFileChecksum(missing))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }
}

