package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.client.repository.RootDirRepository;
import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import com.fstojilj.luddite.sync.common.util.FileSystemUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * Manages the client-side synchronisation state: which server root directories are being
 * tracked, and the last acknowledged sync version for each.
 *
 * <p>State is persisted via {@link RootDirRepository} (the {@code root_dirs} SQLite table)
 * so the client can resume an interrupted sync without re-downloading files it already has.
 */
@RequiredArgsConstructor
@Slf4j
@Service
public class RootDirService {

    private final RootDirRepository rootDirRepository;
    private final FileMetadataService fileMetadataService;

    @Value("${sync.client.mirror-dir}")
    private String mirrorDirPath;

    @Value("${sync.client.retain-local-directory}")
    private boolean retainLocalDirectory;
    
    public void removeStaleDirs(List<String> staleDirs) {
        for (String dir : staleDirs) {
            Path dirPath = Path.of(mirrorDirPath).resolve(dir);
            if (!retainLocalDirectory) {
                FileSystemUtils.deleteDirectoryRecursively(dirPath);
            }
            fileMetadataService.findAllByDir(dir)
                    .forEach(rel -> fileMetadataService.removeRecord(dir, rel));
            removeDirectory(dir);
            log.info("Removed stale dir from sync state: '{}'", dir);
        }
    }

    /**
     * Returns the names of all directories currently registered in the local sync state.
     *
     * @return list of directory names the client is tracking
     */
    public List<String> retrieveAllInSyncDirs() {
        return rootDirRepository.findAll().stream()
                .map(SyncHandshakeEntry::dirName)
                .toList();
    }

    /**
     * Removes a single directory from the local sync state and deletes it from disk.
     *
     * @param directory the directory name to remove
     */
    public void removeDirectory(String directory) {
        Path dirPath = Path.of(mirrorDirPath).resolve(directory);
        FileSystemUtils.deleteDirectoryRecursively(dirPath);
        rootDirRepository.remove(directory);
    }

    /**
     * Returns all sync-state entries, each containing a directory name and the last
     * sync version acknowledged by this client.
     *
     * @return list of {@link SyncHandshakeEntry} records
     */
    public List<SyncHandshakeEntry> findAll() {
        return rootDirRepository.findAll();
    }

    /**
     * Resets the last sync version for the given directory back to {@code -1}, forcing
     * the server to re-send all files for that directory on the next connection.
     *
     * @param dirName the directory whose sync version should be reset
     */
    public void resetSyncVersionForDir(String dirName) {
        rootDirRepository.reset(dirName);
    }

    /**
     * Registers a directory in the local sync state with a starting sync version of
     * {@code -1} if it is not already present. Safe to call multiple times.
     *
     * @param dirName the directory name to register
     */
    public void registerIfAbsent(String dirName) {
        rootDirRepository.registerIfAbsent(dirName);
    }

    /**
     * Advances the persisted last sync version for the given directory.
     * Called after a file event has been successfully written to disk and ACK'd to the server.
     *
     * @param dirName     the directory whose version should be updated
     * @param syncVersion the new sync version to persist
     */
    public void updateSyncVersion(String dirName, long syncVersion) {
        rootDirRepository.updateSyncVersion(dirName, syncVersion);
    }
}
