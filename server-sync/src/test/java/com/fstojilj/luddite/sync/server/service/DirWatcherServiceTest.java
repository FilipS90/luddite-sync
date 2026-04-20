package com.fstojilj.luddite.sync.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DirWatcherServiceTest {

    @Mock
    private FileMetadataService fileMetadataService;

    @Mock
    private PushService pushService;

    @InjectMocks
    private DirWatcherService dirWatcherService;

    @TempDir
    Path tempDir;

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, WatchService> activeWatchers() {
        return (ConcurrentHashMap<String, WatchService>) ReflectionTestUtils.getField(dirWatcherService, "activeWatchers");
    }

    @Test
    void stopWatching_pathNotWatched_shouldDoNothing() {
        // Should not throw
        dirWatcherService.stopWatching("/path/not/being/watched");
    }

    @Test
    void stopWatching_watchedPath_shouldCloseWatchService() throws Exception {
        WatchService mockWatcher = mock(WatchService.class);
        activeWatchers().put("/watched/path", mockWatcher);

        dirWatcherService.stopWatching("/watched/path");

        verify(mockWatcher).close();
    }

    @Test
    void startWatching_alreadyWatched_shouldNotStartDuplicate() {
        WatchService mockWatcher = mock(WatchService.class);
        String path = tempDir.toAbsolutePath().toString();
        activeWatchers().put(path, mockWatcher);

        dirWatcherService.startWatching(path, 1);

        assertThat(activeWatchers()).hasSize(1);
    }

    @Test
    void startWatching_newDir_shouldRegisterWatcher() throws Exception {
        String path = tempDir.toAbsolutePath().toString();

        dirWatcherService.startWatching(path, 1);

        // Wait for the virtual thread to register the watcher
        long deadline = System.currentTimeMillis() + 3000;
        while (!activeWatchers().containsKey(path) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertThat(activeWatchers()).containsKey(path);

        // Clean up
        dirWatcherService.stopWatching(path);
    }

    @Test
    void startWatching_andFileCreated_shouldCallAddFileMetadata() throws Exception {
        String path = tempDir.toAbsolutePath().toString();

        dirWatcherService.startWatching(path, 1);

        // Wait for watcher to be registered
        long deadline = System.currentTimeMillis() + 3000;
        while (!activeWatchers().containsKey(path) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        // Create a file to trigger an event
        Files.writeString(tempDir.resolve("test.jpg"), "data");
        Thread.sleep(300); // Give the watch loop time to fire

        verify(fileMetadataService).addFileMetadata(
                tempDir.resolve("test.jpg"), 1, "test.jpg");

        dirWatcherService.stopWatching(path);
    }

    @Test
    void startWatching_withSubdirectory_shouldWatchSubdir() throws Exception {
        Path subDir = Files.createDirectory(tempDir.resolve("subdir"));
        String rootPath = tempDir.toAbsolutePath().toString();
        String subPath = subDir.toAbsolutePath().toString();

        dirWatcherService.startWatching(rootPath, 1);

        // Wait for both root and subdir watchers
        long deadline = System.currentTimeMillis() + 3000;
        while ((!activeWatchers().containsKey(rootPath) || !activeWatchers().containsKey(subPath))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertThat(activeWatchers()).containsKey(subPath);

        dirWatcherService.stopWatching(rootPath);
        dirWatcherService.stopWatching(subPath);
    }
}

