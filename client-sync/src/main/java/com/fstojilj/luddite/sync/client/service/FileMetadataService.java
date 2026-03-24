package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.client.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Manages the client-side record of files that have been successfully synced to disk.
 *
 * <p>Wraps {@link FileMetadataRepository} (the {@code file_metadata} SQLite table) and
 * provides the business operations needed by {@link ClientSyncService}:
 * recording a newly written file, removing a deleted file, and auditing which files
 * are expected to be present on disk.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileMetadataService {

    private final FileMetadataRepository fileMetadataRepository;

    /**
     * Records a file as successfully written to the local mirror.
     * Safe to call repeatedly — duplicates are silently ignored.
     *
     * @param dirName      server-side directory name (first path component of the qualified path)
     * @param relativePath qualified relative path including the dir name prefix
     */
    public void recordSynced(String dirName, String relativePath) {
        fileMetadataRepository.upsert(dirName, relativePath);
        log.debug("Recorded synced file: {}/{}", dirName, relativePath);
    }

    /**
     * Removes the record for a file that has been deleted from the local mirror.
     * Called when the server delivers a delete event or when the startup audit
     * finds a file missing from disk.
     *
     * @param dirName      server-side directory name
     * @param relativePath qualified relative path to remove
     */
    public void removeRecord(String dirName, String relativePath) {
        fileMetadataRepository.delete(dirName, relativePath);
        log.debug("Removed file record: {}/{}", dirName, relativePath);
    }

    /**
     * Returns all relative paths that were previously confirmed as synced for a given
     * directory. Used during the startup audit to detect files missing from disk.
     *
     * @param dirName server-side directory name
     * @return list of relative paths previously written to disk
     */
    public List<String> findAllByDir(String dirName) {
        return fileMetadataRepository.findAllByDir(dirName);
    }
}

