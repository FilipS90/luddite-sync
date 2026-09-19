package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DirWatcherServiceTest {

    @Mock
    private FileMetadataService fileMetadataService;

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
    void startWatching_indexesRootLevelAndSubdirFilesMissingFromDb() throws Exception {
        Path rootFile = Files.writeString(tempDir.resolve("root.txt"), "root");
        Path subFile = Files.writeString(Files.createDirectory(tempDir.resolve("sub")).resolve("nested.txt"), "nested");
        when(fileMetadataService.findAllActiveByRootDirId(1)).thenReturn(List.of());
        String path = tempDir.toAbsolutePath().toString();

        dirWatcherService.startWatching(path, 1);

        verify(fileMetadataService, timeout(3000)).addFileMetadata(rootFile, 1, "root.txt");
        verify(fileMetadataService, timeout(3000)).addFileMetadata(subFile, 1, "sub/nested.txt");
        dirWatcherService.stopWatching(path);
    }

    @Test
    void startWatching_softDeletesRootLevelFileGoneFromDisk() throws Exception {
        Path kept = Files.writeString(tempDir.resolve("kept.txt"), "kept");
        when(fileMetadataService.findAllActiveByRootDirId(1)).thenReturn(List.of(
                meta("kept.txt", Files.size(kept)), meta("gone.txt", 3)));
        String path = tempDir.toAbsolutePath().toString();

        dirWatcherService.startWatching(path, 1);

        verify(fileMetadataService, timeout(3000)).softDeleteFileMetadata(1, "gone.txt");
        verify(fileMetadataService, never()).softDeleteFileMetadata(1, "kept.txt");
        verify(fileMetadataService, never()).addFileMetadata(any(), anyInt(), anyString());
        dirWatcherService.stopWatching(path);
    }

    private static FileMetadata meta(String relativePath, long size) {
        return FileMetadata.builder().rootDirId(1).filename(relativePath).relativePath(relativePath)
                .fileSize(size).checksum("x").deleted(false).build();
    }

    @Test
    void startWatching_withSubdirectory_shouldOnlyWatchRootDir() throws Exception {
        Path subDir = Files.createDirectory(tempDir.resolve("subdir"));
        String rootPath = tempDir.toAbsolutePath().toString();
        String subPath = subDir.toAbsolutePath().toString();

        dirWatcherService.startWatching(rootPath, 1);

        // Wait for root dir watcher to register
        long deadline = System.currentTimeMillis() + 3000;
        while (!activeWatchers().containsKey(rootPath) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        // Root dir IS watched via WatchService; subdirs are NOT (handled by periodic scan)
        assertThat(activeWatchers()).containsKey(rootPath);
        assertThat(activeWatchers()).doesNotContainKey(subPath);

        dirWatcherService.stopWatching(rootPath);
    }
}

