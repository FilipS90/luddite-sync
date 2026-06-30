package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Watches root directories for changes via inotify (WatchService).
 * Changes directly in the root directory are detected immediately via OS events.
 * Changes inside subdirectories are tracked by a periodic manual scan
 * (configurable via {@code sync.watcher.scan-interval-seconds}, default 30 s).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DirWatcherService {

    private final FileMetadataService fileMetadataService;
    private final FileSocketService fileSocketService;

    @Value("${sync.watcher.scan-interval-seconds:30}")
    private int scanIntervalSeconds = 30;

    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "subdir-scanner");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, WatchService> activeWatchers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledFuture<?>> activeScanners = new ConcurrentHashMap<>();

    public void startWatching(String rootPath, int rootDirId) {
        Path rootDir = Paths.get(rootPath).toAbsolutePath();
        if (activeWatchers.containsKey(rootDir.toString())) {
            return;
        }
        executor.execute(() -> watch(rootDir, rootDirId));
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> scanSubdirs(rootDir, rootDirId),
                scanIntervalSeconds, scanIntervalSeconds, TimeUnit.SECONDS);
        activeScanners.put(rootDir.toString(), future);
    }

    public void stopWatching(String pathToWatch) {
        WatchService watchService = activeWatchers.get(pathToWatch);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.warn("Error closing watch service for: {}", pathToWatch, e);
            }
        }
        ScheduledFuture<?> future = activeScanners.remove(pathToWatch);
        if (future != null) future.cancel(false);
    }

    private void watch(Path rootDirPath, int rootDirId) {
        WatchService watchService = null;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            rootDirPath.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize WatchService for path: " + rootDirPath, e);
        }

        activeWatchers.put(rootDirPath.toString(), watchService);
        log.info("Watch service added for root dir: {}", rootDirPath);

        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path absoluteFilePath = rootDirPath.resolve(ev.context());
                    // Skip directories — subdirectory contents are handled by the periodic scan
                    if (absoluteFilePath.toFile().isDirectory()) {
                        continue;
                    }
                    String relativePath = rootDirPath.relativize(absoluteFilePath)
                            .toString().replace('\\', '/');
                    log.info("{} trigger for file {}", kind.name(), absoluteFilePath);
                    handleFileEvent(kind, absoluteFilePath, rootDirId, relativePath);
                }

                if (!key.reset()) {
                    log.warn("Watch key no longer valid, stopping.");
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Watch service interrupted for: {}", rootDirPath);
        } catch (ClosedWatchServiceException e) {
            log.info("Watch service closed for: {}", rootDirPath);
        } finally {
            try {
                watchService.close();
            } catch (IOException | ClosedWatchServiceException e) {
                // Already closed, ignore
            }
            activeWatchers.remove(rootDirPath.toString());
            log.info("Watch service removed for dir: {}", rootDirPath);
        }
    }

    /**
     * Periodically walks all subdirectory files under {@code rootDirPath} and
     * reconciles the filesystem against the database: inserts new files,
     * re-indexes modified files (by size), and soft-deletes removed files.
     * Root-level files are intentionally excluded — they are covered by inotify.
     */
    private void scanSubdirs(Path rootDirPath, int rootDirId) {
        log.debug("Starting periodic subdir scan for: {}", rootDirPath);

        // Build normalized relPath → FileMetadata map for subdirectory DB records
        Map<String, FileMetadata> dbState = fileMetadataService.findAllActiveByRootDirId(rootDirId)
                .stream()
                .filter(m -> normalizeRelPath(m.relativePath()).contains("/"))
                .collect(Collectors.toMap(
                        m -> normalizeRelPath(m.relativePath()),
                        m -> m,
                        (a, b) -> a));

        Set<String> foundOnDisk = new HashSet<>();

        try (var stream = Files.walk(rootDirPath)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !rootDirPath.equals(p.getParent())) // subdirectory files only
                    .forEach(filePath -> {
                        String relPath = rootDirPath.relativize(filePath).toString().replace('\\', '/');
                        foundOnDisk.add(relPath);

                        FileMetadata dbMeta = dbState.get(relPath);
                        if (dbMeta == null) {
                            try {
                                fileMetadataService.addFileMetadata(filePath, rootDirId, relPath);
                                log.debug("Scan: new subdir file '{}'", relPath);
                            } catch (Exception e) {
                                log.error("Scan: failed to add metadata for '{}'", relPath, e);
                            }
                        } else if (filePath.toFile().length() != dbMeta.fileSize()) {
                            try {
                                fileMetadataService.updateFileMetadata(filePath, rootDirId, dbMeta.relativePath());
                                log.debug("Scan: modified subdir file '{}'", relPath);
                            } catch (Exception e) {
                                log.error("Scan: failed to update metadata for '{}'", relPath, e);
                            }
                        }
                    });
        } catch (IOException e) {
            log.error("Scan failed for root dir '{}': {}", rootDirPath, e.getMessage());
            return;
        }

        // Soft-delete DB records whose files no longer exist on disk
        for (Map.Entry<String, FileMetadata> entry : dbState.entrySet()) {
            if (!foundOnDisk.contains(entry.getKey())) {
                try {
                    String connectedClientIds = fileSocketService.getConnectedClientIds();
                    fileMetadataService.softDeleteFileMetadata(rootDirId, entry.getValue().relativePath(), connectedClientIds);
                    log.debug("Scan: deleted subdir file '{}'", entry.getKey());
                } catch (Exception e) {
                    log.error("Scan: failed to soft-delete metadata for '{}'", entry.getKey(), e);
                }
            }
        }

        log.debug("Finished periodic subdir scan for: {}", rootDirPath);
    }

    private static String normalizeRelPath(String path) {
        String s = path.replace('\\', '/');
        return s.startsWith("/") ? s.substring(1) : s;
    }

    /**
     * Dispatches a single filesystem event to {@link FileMetadataService} and then
     * signals {@link FileSocketService} that fresh data is available for this root dir.
     */
    private void handleFileEvent(WatchEvent.Kind<?> kind, Path absoluteFilePath,
                                 int rootDirId, String relativePath) {
        try {
            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                fileMetadataService.addFileMetadata(absoluteFilePath, rootDirId, relativePath);
            } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                fileMetadataService.updateFileMetadata(absoluteFilePath, rootDirId, relativePath);
            } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                String connectedClientIds = fileSocketService.getConnectedClientIds();
                fileMetadataService.softDeleteFileMetadata(rootDirId, relativePath, connectedClientIds);
            }
        } catch (Exception e) {
            log.error("Error handling {} event for '{}': {}", kind.name(), absoluteFilePath, e.getMessage(), e);
        }
    }
}

