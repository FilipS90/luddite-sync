package com.fstojilj.luddite.sync.server.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemUtilsTest {

    @TempDir
    Path tempDir;

    // ── getDirName ────────────────────────────────────────────────────────────

    @Test
    void getDirName_absolutePath_returnsLastSegment() {
        assertThat(FileSystemUtils.getDirName("/home/user/photos")).isEqualTo("photos");
    }

    @Test
    void getDirName_pathWithTrailingSlash_returnsLastSegment() {
        assertThat(FileSystemUtils.getDirName("/home/user/photos/")).isEqualTo("photos");
    }

    @Test
    void getDirName_nullPath_throwsIllegalArgument() {
        assertThatThrownBy(() -> FileSystemUtils.getDirName(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getDirName_emptyPath_throwsIllegalArgument() {
        assertThatThrownBy(() -> FileSystemUtils.getDirName(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── listAllFilesForDir ────────────────────────────────────────────────────

    @Test
    void listAllFilesForDir_nonExistentPath_returnsEmpty() {
        List<File> result = FileSystemUtils.listAllFilesForDir("/does/not/exist/at/all");
        assertThat(result).isEmpty();
    }

    @Test
    void listAllFilesForDir_emptyDir_returnsEmpty() {
        List<File> result = FileSystemUtils.listAllFilesForDir(tempDir.toString());
        assertThat(result).isEmpty();
    }

    @Test
    void listAllFilesForDir_dirWithFiles_returnsFiles() throws Exception {
        Files.writeString(tempDir.resolve("a.jpg"), "data");
        Files.writeString(tempDir.resolve("b.jpg"), "data");
        List<File> result = FileSystemUtils.listAllFilesForDir(tempDir.toString());
        assertThat(result).hasSize(2);
    }

    @Test
    void listAllFilesForDir_pathIsFile_returnsEmpty() throws Exception {
        Path file = Files.writeString(tempDir.resolve("file.txt"), "data");
        List<File> result = FileSystemUtils.listAllFilesForDir(file.toString());
        assertThat(result).isEmpty();
    }
}

