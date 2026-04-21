package com.fstojilj.luddite.sync.server.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileChecksumUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    void calculateFileChecksum_knownContent_returnsExpectedSha256() throws Exception {
        // SHA-256 of "hello" = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        Path file = Files.writeString(tempDir.resolve("test.txt"), "hello");
        String checksum = FileChecksumUtils.calculateFileChecksum(file);
        assertThat(checksum).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void calculateFileChecksum_emptyFile_returnsExpectedSha256() throws Exception {
        // SHA-256 of empty string = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        Path file = Files.writeString(tempDir.resolve("empty.txt"), "");
        String checksum = FileChecksumUtils.calculateFileChecksum(file);
        assertThat(checksum).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void calculateFileChecksum_sameContent_returnsSameChecksum() throws Exception {
        Path a = Files.writeString(tempDir.resolve("a.jpg"), "identical");
        Path b = Files.writeString(tempDir.resolve("b.jpg"), "identical");
        assertThat(FileChecksumUtils.calculateFileChecksum(a))
                .isEqualTo(FileChecksumUtils.calculateFileChecksum(b));
    }

    @Test
    void calculateFileChecksum_differentContent_returnsDifferentChecksum() throws Exception {
        Path a = Files.writeString(tempDir.resolve("a.jpg"), "content-a");
        Path b = Files.writeString(tempDir.resolve("b.jpg"), "content-b");
        assertThat(FileChecksumUtils.calculateFileChecksum(a))
                .isNotEqualTo(FileChecksumUtils.calculateFileChecksum(b));
    }

    @Test
    void calculateFileChecksum_nonExistentFile_throwsUncheckedIOException() {
        Path ghost = tempDir.resolve("ghost.jpg");
        assertThatThrownBy(() -> FileChecksumUtils.calculateFileChecksum(ghost))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }
}

