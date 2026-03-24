package com.fstojilj.luddite.sync.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Shared filesystem utilities used by both the client and server modules.
 */
public final class FileSystemUtils {

    private static final Logger log = LoggerFactory.getLogger(FileSystemUtils.class);

    private FileSystemUtils() {
    }

    /**
     * Recursively deletes a directory and all of its contents from the filesystem.
     *
     * <p>If {@code dirPath} does not exist the method returns immediately.
     * Individual failures are logged as warnings and do not abort the walk.
     *
     * @param dirPath the directory to delete
     */
    public static void deleteDirectoryRecursively(Path dirPath) {
        if (!Files.exists(dirPath)) {
            log.warn("Directory '{}' not found on disk — skipping deletion", dirPath);
            return;
        }

        try {
            Files.walkFileTree(dirPath, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.delete(file);
                        log.info("Deleted file '{}'", file);
                    } catch (IOException e) {
                        log.warn("Could not delete file '{}': {}", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Failed to visit file '{}': {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try {
                        Files.delete(dir);
                        log.debug("Deleted directory '{}'", dir);
                    } catch (IOException e) {
                        log.warn("Could not delete directory '{}': {}", dir, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Error walking directory tree '{}': {}", dirPath, e.getMessage(), e);
        }
    }
}

