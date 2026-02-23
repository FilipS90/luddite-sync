package com.fstojilj.luddite.sync.server.service;

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
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class DirWatcherService {

    private final Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, WatchService> activeWatchers = new ConcurrentHashMap<>();

    public void startWatching(String rootPath) {
        if (activeWatchers.containsKey(rootPath)) {
            log.warn("Already watching: {}", rootPath);
            return;
        }
        executor.execute(() -> watch(rootPath));

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

    private void watch(String rootDirPath) {
        WatchService watchService = null;
        Path path = null;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            path = Paths.get(rootDirPath);
            path.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
            recursivelyWatchSubdirs(rootDirPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to initialize WatchService for path: " + rootDirPath, e);
        }

        activeWatchers.put(rootDirPath, watchService);
        log.info("Watch service added for dir: {}", path);

        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path filename = ev.context();
                    log.info("{}: {}", kind.name(), filename);
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
            activeWatchers.remove(rootDirPath);
            log.info("Watch service removed for dir: {}", rootDirPath);
        }
    }

    private void recursivelyWatchSubdirs(String parentDir) throws IOException {
        AtomicReference<Path> subdirPath = new AtomicReference<>();
        try (var fileStream = Files.walk(Path.of(parentDir), Integer.MAX_VALUE)) {
            fileStream.filter(Files::isDirectory).forEach(dir -> {
                subdirPath.set(dir);
                if (!dir.toString().equals(parentDir)) {
                    startWatching(dir.toString());
                }
            });
        } catch (IOException e) {
            throw new IOException("Failed to recursively watch subdirectories of: " + subdirPath, e);
        }
    }
}
