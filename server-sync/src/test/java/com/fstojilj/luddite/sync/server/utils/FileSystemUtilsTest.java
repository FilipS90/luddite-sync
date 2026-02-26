package com.fstojilj.luddite.sync.server.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemUtilsTest {

    @Test
    void getDirName_absolutePath_shouldReturnLastComponent() {
        assertThat(FileSystemUtils.getDirName("/home/user/photos")).isEqualTo("photos");
    }

    @Test
    void getDirName_pathWithTrailingSlash_shouldNormalize() {
        // Paths.get normalizes trailing separators
        assertThat(FileSystemUtils.getDirName("/home/user/photos/")).isEqualTo("photos");
    }

    @Test
    void getDirName_singleComponent_shouldReturnItself() {
        assertThat(FileSystemUtils.getDirName("photos")).isEqualTo("photos");
    }

    @Test
    void getDirName_null_shouldThrow() {
        assertThatThrownBy(() -> FileSystemUtils.getDirName(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getDirName_empty_shouldThrow() {
        assertThatThrownBy(() -> FileSystemUtils.getDirName(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

