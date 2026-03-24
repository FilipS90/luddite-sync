package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.server.repository.FileMetadataRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.fstojilj.luddite.sync.server.utils.FileChecksumUtils.calculateFileChecksum;
import static com.fstojilj.luddite.sync.server.utils.FileSystemUtils.listAllFilesForDir;

/**
 * Business logic for file metadata lifecycle on the server.
 *
 * <p>Owns the monotonically increasing {@code syncVersion} counter — seeded from
 * {@code MAX(sync_version)} in the database on startup so versions survive restarts.
 * Every write or soft-delete that should be replicated to clients must go through
 * {@link #nextSyncVersion()} to obtain a new version number.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FileMetadataService {

    private final FileMetadataRepository fileMetadataRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Monotonic counter seeded from the DB max on startup.
     */
    private final AtomicLong syncVersionCounter = new AtomicLong(0);

    /**
     * Seeds {@link #syncVersionCounter} from the highest {@code sync_version} currently
     * stored in the database so that version numbers never go backwards after a restart.
     */
    @PostConstruct
    public void initSyncVersionCounter() {
        Long max = jdbcTemplate.queryForObject(
                "SELECT MAX(sync_version) FROM file_metadata", Long.class);
        long seed = (max != null) ? max : 0L;
        syncVersionCounter.set(seed);
        log.info("Sync-version counter seeded at {}", seed);
    }

    /**
     * Mints the next monotonically increasing sync version.
     *
     * @return a version number guaranteed to be greater than all previously issued versions
     */
    public long nextSyncVersion() {
        return syncVersionCounter.incrementAndGet();
    }

    // ── Write path ────────────────────────────────────────────────────────────

    /**
     * Returns the new syncVersion assigned to this file.
     */
    @Transactional
    public void addFileMetadata(Path filePath, long rootDirId, String relativePath) {
        File file = filePath.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("Path must point to an existing file");
        }
        fileMetadataRepository.add(buildFileMetadata(file, rootDirId, relativePath));
    }

    @Transactional
    public void addAllFileMetadataForRoot(String rootAbsolutePath, long rootDirId) {
        recursiveAddFileMetadataForSubdirs(rootAbsolutePath, rootAbsolutePath, rootDirId);
    }

    private void recursiveAddFileMetadataForSubdirs(String dirAbsolutePath, String rootAbsolutePath, long rootDirId) {
        List<File> files = listAllFilesForDir(dirAbsolutePath);
        Path rootPath = Path.of(rootAbsolutePath);

        for (File file : files) {
            if (file.isDirectory()) {
                recursiveAddFileMetadataForSubdirs(file.getAbsolutePath(), rootAbsolutePath, rootDirId);
            } else {
                String relativePath = File.separator + rootPath.relativize(file.toPath());
                fileMetadataRepository.add(buildFileMetadata(file, rootDirId, relativePath));
            }
        }
    }

    /**
     * Returns the new syncVersion assigned to this file.
     */
    @Transactional
    public void updateFileMetadata(Path absoluteFilePath, long rootDirId, String relativeFilePath) {
        File file = absoluteFilePath.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("Path must point to an existing file");
        }

        var existing = fileMetadataRepository.findOptionalByRootDirIdAndRelativePath(rootDirId, relativeFilePath);
        if (existing.isEmpty()) {
            // ENTRY_MODIFY can race ahead of ENTRY_CREATE - treat as add
            log.warn("ENTRY_MODIFY for unknown file, inserting instead: {}", relativeFilePath);
            fileMetadataRepository.add(buildFileMetadata(file, rootDirId, relativeFilePath));
            return;
        }

        var fileMetadata = existing.get().toBuilder()
                .fileSize(file.length())
                .checksum(calculateFileChecksum(absoluteFilePath))
                .syncVersion(null)
                .build();

        fileMetadataRepository.update(fileMetadata);
    }

    // ── Delete path ───────────────────────────────────────────────────────────

    /**
     * Soft-deletes a file: marks it as deleted, bumps its {@code sync_version}, and sets
     * {@code client_ids} to the comma-separated hardware IDs of every currently connected
     * client. The row stays in the database until every client has acknowledged the delete.
     *
     * @param rootDirId          root directory ID
     * @param relativeFilePath   relative path of the deleted file
     * @param connectedClientIds comma-separated hardware IDs of all connected clients;
     *                           pass an empty string if no clients are connected (row is
     *                           hard-deleted immediately in {@link #acknowledgeDelete})
     */
    @Transactional
    public void softDeleteFileMetadata(long rootDirId, String relativeFilePath, String connectedClientIds) {
        long version = nextSyncVersion();
        fileMetadataRepository.softDelete(rootDirId, relativeFilePath, version, connectedClientIds);
        log.info("Soft-deleted (v{}) rootDirId={} '{}', pending clients: [{}]",
                version, rootDirId, relativeFilePath,
                connectedClientIds.isEmpty() ? "none" : connectedClientIds);
    }

    /**
     * Removes {@code hardwareId} from the {@code client_ids} of a soft-deleted row.
     * When all clients have acknowledged, the row is hard-deleted.
     *
     * @param rootDirId    root directory ID
     * @param relativePath relative file path
     * @param hardwareId   the acknowledging client's stable hardware ID
     */
    @Transactional
    public void acknowledgeDelete(long rootDirId, String relativePath, String hardwareId) {
        fileMetadataRepository.acknowledgeDelete(rootDirId, relativePath, hardwareId);
    }

    @Transactional
    public void deleteAllForRootDir(long rootDirId) {
        fileMetadataRepository.deleteAllByRootDirId(rootDirId);
    }

    // ── Sync-version stamping ─────────────────────────────────────────────────

    /**
     * Stamps the {@code sync_version} on a file record after a client confirms receipt.
     * Uses {@code MAX} so the version never regresses.
     *
     * @param rootDirId    root directory ID
     * @param relativePath relative file path
     * @param syncVersion  the version to stamp
     */
    @Transactional
    public void stampSyncVersion(long rootDirId, String relativePath, long syncVersion) {
        fileMetadataRepository.updateSyncVersion(rootDirId, relativePath, syncVersion);
    }

    // ── Query path ────────────────────────────────────────────────────────────

    /**
     * Returns all file metadata rows (live and soft-deleted) whose {@code sync_version}
     * is greater than {@code lastSyncVersion}, or that have never been delivered
     * ({@code sync_version IS NULL}). This is the primary feed for the poll response.
     *
     * @param rootDirId       root directory ID
     * @param lastSyncVersion last version the client has acknowledged
     * @return list of changed records
     */
    public List<FileMetadata> findChangedSince(long rootDirId, long lastSyncVersion) {
        return fileMetadataRepository.findChangedSince(rootDirId, lastSyncVersion);
    }

    /**
     * @deprecated Use {@link #findChangedSince} for the poll-based architecture.
     */
    @Deprecated
    public List<FileMetadata> findFilesNewerThan(long rootDirId, long lastSyncVersion) {
        return fileMetadataRepository.findByRootDirIdWithSyncVersionAfter(rootDirId, lastSyncVersion);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private FileMetadata buildFileMetadata(File file, long rootDirId, String relativePath) {
        return FileMetadata.builder()
                .filename(file.getName())
                .rootDirId(rootDirId)
                .relativePath(relativePath)
                .fileSize(file.length())
                .checksum(calculateFileChecksum(file.toPath()))
                .syncVersion(null)
                .deleted(false)
                .clientIds(null)
                .build();
    }
}
