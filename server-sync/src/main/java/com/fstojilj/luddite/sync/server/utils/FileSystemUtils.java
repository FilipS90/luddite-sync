package com.fstojilj.luddite.sync.server.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

@Slf4j
public final class FileSystemUtils {

    private FileSystemUtils() {
        // Utility class - prevent instantiation
    }

    public static String getDirName(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) {
            throw new IllegalArgumentException("Absolute path cannot be null or empty");
        }

        Path path = Paths.get(absolutePath).normalize();
        return path.getFileName().toString();
    }

    public static List<File> listAllFilesForDir(String absolutePath) {
        File rootDir = new File(absolutePath);
        return List.of(Objects.requireNonNull(rootDir.listFiles()));
    }
}
