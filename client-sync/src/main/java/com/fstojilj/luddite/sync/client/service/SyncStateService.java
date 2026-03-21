package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.client.repository.SyncStateRepository;
import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

/**
 * Manages the client-side synchronisation state, including which directories are being
 * tracked, their last known sync versions, and the local mirror on disk.
 *
 * <p>Sync state is persisted via {@link SyncStateRepository} so that the client can
 * resume an interrupted sync without re-downloading files it already has.
 */
@RequiredArgsConstructor
@Slf4j
@Service
public class SyncStateService {

    private final SyncStateRepository syncStateRepository;

    @Value("${sync.client.mirror-dir}")
    private String mirrorDirPath;

    /**
     * Removes directories that are no longer offered by the server.
     * Both the on-disk mirror subtree and the local sync-state record are deleted.
     *
     * @param staleDirs list of directory names that should be purged
     */
    public void removeStaleDirs(List<String> staleDirs) {
        staleDirs.forEach(dir -> {
            var dirPath = Path.of(mirrorDirPath, dir);
            try {
                Files.walkFileTree(dirPath, new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            removeDirectory(dir);
        });

    }

    /**
     * Returns the names of all directories currently registered in the local sync state.
     *
     * @return list of directory names that the client is tracking
     */
    public List<String> retrieveAllInSyncDirs() {
        return syncStateRepository.findAll().stream()
                .map(SyncHandshakeEntry::dirName)
                .toList();
    }

    /**
     * Removes a single directory from the local sync state without touching the mirror on disk.
     * Use {@link #removeStaleDirs} if you also need to delete files from disk.
     *
     * @param directory the directory name to remove
     */
    public void removeDirectory(String directory) {
        syncStateRepository.remove(directory);
    }

    /**
     * Returns all sync-state entries, each containing the directory name and the
     * last sync version acknowledged by this client.
     *
     * @return list of {@link SyncHandshakeEntry} records
     */
    public List<SyncHandshakeEntry> findAll() {
        return syncStateRepository.findAll();
    }

    /**
     * Resets the last sync version for the given directory back to {@code -1}, forcing
     * the server to re-send all files for that directory on the next connection.
     *
     * @param dirName the directory whose sync version should be reset
     */
    public void resetSyncVersionForDir(String dirName) {
        syncStateRepository.reset(dirName);
    }

    /**
     * Registers a directory in the local sync state with a starting sync version of
     * {@code -1} if it is not already present. Safe to call multiple times.
     *
     * @param dirName the directories name to register
     */
    public void registerIfAbsent(String dirName) {
        syncStateRepository.registerIfAbsent(dirName);
    }

    /**
     * Advances the persisted last sync version for the given directory.
     * Called after a file event has been successfully written to disk and ACK'd to the server.
     *
     * @param dirName     the directory whose version should be updated
     * @param syncVersion the new sync version to persist
     */
    public void updateSyncVersion(String dirName, long syncVersion) {
        syncStateRepository.updateSyncVersion(dirName, syncVersion);
    }
}
