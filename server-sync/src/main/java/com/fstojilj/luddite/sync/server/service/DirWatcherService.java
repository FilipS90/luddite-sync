package com.fstojilj.luddite.sync.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Watches one or more filesystem directories for changes and forwards each event
 * to {@link FileMetadataService} (to update the database) and to
 * {@link PushService} (to notify the poll handler that new data is available).
 *
 * <p>A dedicated virtual thread is spawned for each watched directory.
 * Sub-directories created at runtime are registered automatically.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DirWatcherService {

    private final FileMetadataService fileMetadataService;
    private final PushService pushService;

    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, WatchService> activeWatchers = new ConcurrentHashMap<>();

    public void startWatching(String rootPath, long rootDirId) {
        Path rootDir = Paths.get(rootPath).toAbsolutePath();
        startWatching(rootDir, rootDir, rootDirId);
    }

    private void startWatching(Path pathToWatch, Path rootDirPath, long rootDirId) {
        if (activeWatchers.containsKey(pathToWatch.toString())) {
            return;
        }
        executor.execute(() -> watch(pathToWatch, rootDirPath, rootDirId));
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
    }

    private void watch(Path watchedDir, Path rootDirPath, long rootDirId) {
        WatchService watchService = null;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            watchedDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
            recursivelyWatchSubdirs(watchedDir, rootDirPath, rootDirId);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize WatchService for path: " + watchedDir, e);
        }

        activeWatchers.put(watchedDir.toString(), watchService);
        log.info("Watch service added for dir: {}", watchedDir);

        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path absoluteFilePath = watchedDir.resolve(ev.context());
                    // Use forward slashes so paths are cross-platform safe over the wire
                    String relativePath = rootDirPath.relativize(absoluteFilePath)
                            .toString().replace('\\', '/');
                    log.info("{} trigger for file {}", kind.name(), absoluteFilePath);

                    if (kind == StandardWatchEventKinds.ENTRY_CREATE && absoluteFilePath.toFile().isDirectory()) {
                        startWatching(absoluteFilePath, rootDirPath, rootDirId);
                    }

                    handleFileEvent(kind, absoluteFilePath, rootDirId, relativePath);
                }

                if (!key.reset()) {
                    log.warn("Watch key no longer valid, stopping.");
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Watch service interrupted for: {}", watchedDir);
        } catch (ClosedWatchServiceException e) {
            log.info("Watch service closed for: {}", watchedDir);
        } finally {
            try {
                watchService.close();
            } catch (IOException | ClosedWatchServiceException e) {
                // Already closed, ignore
            }
            activeWatchers.remove(watchedDir.toString());
            log.info("Watch service removed for dir: {}", watchedDir);
        }
    }

    /**
     * Dispatches a single filesystem event to {@link FileMetadataService} and then
     * signals {@link PushService} that fresh data is available for this root dir.
     *
     * @param kind             the event kind (CREATE / MODIFY / DELETE)
     * @param absoluteFilePath absolute path of the affected file
     * @param rootDirId        root directory database ID
     * @param relativePath     path relative to the root directory
     */
    private void handleFileEvent(WatchEvent.Kind<?> kind, Path absoluteFilePath,
                                 long rootDirId, String relativePath) {
        try {
            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                fileMetadataService.addFileMetadata(absoluteFilePath, rootDirId, relativePath);
            } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                fileMetadataService.updateFileMetadata(absoluteFilePath, rootDirId, relativePath);
            } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                String connectedClientIds = pushService.getConnectedClientIds();
                fileMetadataService.softDeleteFileMetadata(rootDirId, relativePath, connectedClientIds);
            }
        } catch (Exception e) {
            log.error("Error handling {} event for '{}': {}", kind.name(), absoluteFilePath, e.getMessage(), e);
        }
    }

    private void recursivelyWatchSubdirs(Path currentDir, Path rootDirPath, long rootDirId) throws IOException {
        try (var fileStream = Files.walk(currentDir, Integer.MAX_VALUE)) {
            fileStream.filter(Files::isDirectory)
                    .filter(dir -> !dir.equals(currentDir))
                    .forEach(dir -> startWatching(dir, rootDirPath, rootDirId));
        } catch (IOException e) {
            throw new IOException("Failed to recursively watch subdirectories of: " + currentDir, e);
        }
    }
}
