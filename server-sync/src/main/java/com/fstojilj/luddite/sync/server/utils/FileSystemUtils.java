package com.fstojilj.luddite.sync.server.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

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
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            log.warn("Path does not exist or is not a directory: {}", absolutePath);
            return List.of();
        }
        File[] files = rootDir.listFiles();
        return files != null ? List.of(files) : List.of();
    }
}
