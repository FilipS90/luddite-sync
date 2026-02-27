package com.fstojilj.luddite.sync.server.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileSystemUtilsAdditionalTest {

    @TempDir
    Path tempDir;

    @Test
    void listAllFilesForDir_existingDir_shouldReturnFiles() throws IOException {
        Files.writeString(tempDir.resolve("a.jpg"), "data");
        Files.writeString(tempDir.resolve("b.jpg"), "data");

        List<File> files = FileSystemUtils.listAllFilesForDir(tempDir.toString());

        assertThat(files).hasSize(2);
    }

    @Test
    void listAllFilesForDir_emptyDir_shouldReturnEmptyList() {
        List<File> files = FileSystemUtils.listAllFilesForDir(tempDir.toString());

        assertThat(files).isEmpty();
    }

    @Test
    void listAllFilesForDir_nonExistingDir_shouldReturnEmptyList() {
        List<File> files = FileSystemUtils.listAllFilesForDir("/path/that/does/not/exist");

        assertThat(files).isEmpty();
    }

    @Test
    void listAllFilesForDir_filePathInsteadOfDir_shouldReturnEmptyList() throws IOException {
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "data");

        List<File> files = FileSystemUtils.listAllFilesForDir(file.toString());

        assertThat(files).isEmpty();
    }
}

