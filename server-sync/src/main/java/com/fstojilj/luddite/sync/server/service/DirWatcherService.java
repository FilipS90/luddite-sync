package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.server.event.FileChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DirWatcherService {

    private final ApplicationEventPublisher eventPublisher;
    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, WatchService> activeWatchers = new ConcurrentHashMap<>();

    public void startWatching(String rootPath, long rootDirId) {
        Path rootDir = Paths.get(rootPath).toAbsolutePath();
        startWatching(rootDir, rootDir, rootDirId);
    }

    private void startWatching(Path pathToWatch, Path rootDirPath, long rootDirId) {
        if (activeWatchers.containsKey(pathToWatch.toString())) {
            log.warn("Already watching: {}", pathToWatch);
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
                    String relativePath = rootDirPath.relativize(absoluteFilePath).toString();
                    log.info("{} trigger for file {}", kind.name(), absoluteFilePath);

                    // If a new directory is created, start watching it too
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE && absoluteFilePath.toFile().isDirectory()) {
                        startWatching(absoluteFilePath, rootDirPath, rootDirId);
                        continue;
                    }

                    FileChangeEvent fileChangeEvent = FileChangeEvent.builder()
                            .rootDirId(rootDirId)
                            .absoluteFilePath(absoluteFilePath.toString())
                            .relativePath(relativePath)
                            .eventKind(kind)
                            .build();
                    eventPublisher.publishEvent(fileChangeEvent);
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
