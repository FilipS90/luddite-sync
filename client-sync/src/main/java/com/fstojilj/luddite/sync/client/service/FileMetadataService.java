package com.fstojilj.luddite.sync.client.service;

import com.fstojilj.luddite.sync.client.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Value("${sync.client.mirror-dir}")
    private String mirrorDirPath;

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
     * Removes a file from the local mirror: deletes the DB record first, then removes
     * the file from disk.
     *
     * <p>Ordering rationale: the DB record is the source of truth for what the client
     * is supposed to have. Deleting it first means that if the process crashes between
     * the DB delete and the disk delete, the startup audit will find no record for the
     * file and will not attempt to re-request it from the server. If the DB delete
     * itself fails, the disk file is left untouched so no data is lost.
     *
     * <p>If the file is already absent from disk the deletion step is skipped silently.
     *
     * @param dirName      server-side directory name
     * @param relativePath qualified relative path to remove
     * @throws IllegalStateException if the DB record cannot be deleted — disk file is
     *                               left untouched in this case
     */
    public void removeRecord(String dirName, String relativePath) {
        // 1 — delete DB record first; if this fails we abort and leave the disk file intact
        try {
            fileMetadataRepository.delete(dirName, relativePath);
            log.debug("Removed file record from DB: {}/{}", dirName, relativePath);
        } catch (Exception e) {
            log.error("Failed to delete DB record for '{}/{}', aborting disk delete to avoid data loss: {}",
                    dirName, relativePath, e.getMessage(), e);
            throw new IllegalStateException(
                    "DB delete failed for " + dirName + "/" + relativePath + " — disk file left intact", e);
        }

        // 2 — DB record is gone; now safe to remove from disk
        Path target = Path.of(mirrorDirPath).resolve(relativePath).normalize();
        try {
            if (Files.deleteIfExists(target)) {
                log.info("Deleted local file: {}", target);
            } else {
                log.debug("File not on disk (already gone): {}", target);
            }
        } catch (IOException e) {
            // DB record is already gone so we log a warning but do not re-insert —
            // the file will be re-synced on the next poll if it reappears on the server.
            log.warn("DB record removed but could not delete local file '{}': {}", target, e.getMessage());
        }
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
