package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.server.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.fstojilj.luddite.sync.server.utils.FileChecksumUtils.calculateFileChecksum;
import static com.fstojilj.luddite.sync.server.utils.FileSystemUtils.listAllFilesForDir;

/**
 * Business logic for file metadata lifecycle on the server.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Order(2)
public class FileMetadataService implements ApplicationRunner {

    private static final Set<String> IGNORED_FILENAMES = Set.of(
            "thumbs.db", "desktop.ini", ".ds_store", ".localized",
            "thumbs.db:encryptable", "ethumbs.db", ".git"
    );

    private final FileMetadataRepository fileMetadataRepository;

    private ConcurrentMap<Integer, Long> syncVersionCounter = new ConcurrentHashMap<>();

    @Override
    public void run(ApplicationArguments args) throws Exception {
        syncVersionCounter = fileMetadataRepository.getMaxSyncVersionByRootDir();
    }

    /**
     * Mints the next monotonically increasing sync version.
     * Seeds from {@code MAX(sync_version)} on the very first invocation.
     */
    public Long nextSyncVersion(int rootDirId) {
        return syncVersionCounter.merge(rootDirId, 1L, Long::sum);
    }

    // ── Write path ────────────────────────────────────────────────────────────

    @Transactional
    public void addFileMetadata(Path filePath, int rootDirId, String relativePath) {
        File file = filePath.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("Path must point to an existing file");
        }
        if (isIgnored(file.getName())) {
            log.debug("Ignoring file: {}", filePath);
            return;
        }
        // Upsert rather than insert: the path may still be occupied by a soft-deleted row
        // awaiting client acks (delete-then-recreate, atomic-save editors, cut/paste).
        fileMetadataRepository.upsert(buildFileMetadata(file, rootDirId, relativePath));
    }

    public void addAllFileMetadataForRoot(String rootAbsolutePath, int rootDirId) {
        final short BATCH_SIZE = 200;
        List<FileMetadata> fileBatch = new ArrayList<>(BATCH_SIZE);

        collectAndAddBatchedFileMetadata(fileBatch, BATCH_SIZE, rootAbsolutePath, rootAbsolutePath, rootDirId);

        if (!fileBatch.isEmpty()) {
            fileMetadataRepository.addAll(fileBatch);
        }

        log.info("Added metadata for files in root dir '{}'", rootAbsolutePath);
    }

    private void collectAndAddBatchedFileMetadata(List<FileMetadata> fileBatch, short batchSize, String dirAbsolutePath,
                                                  String rootAbsolutePath, int rootDirId) {
        List<File> files = listAllFilesForDir(dirAbsolutePath);
        Path rootPath = Path.of(rootAbsolutePath);

        for (File file : files) {
            if (file.isDirectory()) {
                collectAndAddBatchedFileMetadata(fileBatch, batchSize, file.getAbsolutePath(), rootAbsolutePath, rootDirId);
            } else {
                if (isIgnored(file.getName())) {
                    log.debug("Ignoring file during initial scan: {}", file.getAbsolutePath());
                    continue;
                }
                String relativePath = rootPath.relativize(file.toPath()).toString().replace('\\', '/');
                var fileMetadata = buildFileMetadata(file, rootDirId, relativePath);
                fileBatch.add(fileMetadata);

                if (fileBatch.size() == batchSize) {
                    fileMetadataRepository.addAll(fileBatch);
                    fileBatch.clear();
                }
            }
        }
    }

    /**
     * Refreshes the record for a modified file in place and resets its {@code sync_version}
     * to {@code NULL} so the next poll re-delivers it. {@code client_ids} is preserved so
     * clients holding the previous copy remain tracked for a subsequent delete.
     */
    @Transactional
    public void updateFileMetadata(Path absoluteFilePath, int rootDirId, String relativeFilePath) {
        File file = absoluteFilePath.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("Path must point to an existing file");
        }

        fileMetadataRepository.upsert(buildFileMetadata(file, rootDirId, relativeFilePath));
        log.debug("Refreshed FileMetadata for rootDirId: {}, relativePath: {}", rootDirId, relativeFilePath);
    }

    // ── Delete path ───────────────────────────────────────────────────────────

    /**
     * Marks a file as deleted by minting a new sync version and flagging the row. The row's
     * {@code client_ids} — the clients that received the file — is preserved, and the row stays
     * in the database until every one of them has acknowledged the delete.
     *
     * @param rootDirId        root directory ID
     * @param relativeFilePath relative path of the deleted file
     */
    @Transactional
    public void softDeleteFileMetadata(int rootDirId, String relativeFilePath) {
        long version = nextSyncVersion(rootDirId);
        fileMetadataRepository.softDelete(rootDirId, relativeFilePath, version);
        log.info("Soft-deleted (v{}) rootDirId={} '{}'", version, rootDirId, relativeFilePath);
    }

    /**
     * Records that {@code clientId} now holds a copy of each of the given files.
     * Called by the socket layer after the files have been streamed to the client.
     *
     * @param rootDirId     root directory ID
     * @param relativePaths relative paths of the files the client received
     * @param clientId      the receiving client's stable client ID
     */
    @Transactional
    public void addClientToFiles(int rootDirId, List<String> relativePaths, String clientId) {
        fileMetadataRepository.addClientIdToAll(rootDirId, relativePaths, clientId);
    }

    /**
     * Removes {@code clientId} from the {@code client_ids} of a soft-deleted row.
     * When all clients have acknowledged, the row is hard-deleted.
     *
     * @param rootDirId    root directory ID
     * @param relativePath relative file path
     * @param clientId     the acknowledging client's stable client ID
     */
    @Transactional
    public void acknowledgeDelete(int rootDirId, String relativePath, String clientId) {
        fileMetadataRepository.acknowledgeDelete(rootDirId, relativePath, clientId);
    }

    @Transactional
    public void deleteAllForRootDir(int rootDirId) {
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
    public void stampSyncVersion(int rootDirId, String relativePath, long syncVersion) {
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
    public List<FileMetadata> findChangedSince(int rootDirId, Long lastSyncVersion, long limit) {
        return fileMetadataRepository.findChangedSince(rootDirId, lastSyncVersion, limit);
    }

    private FileMetadata buildFileMetadata(File file, int rootDirId, String relativePath) {
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

    private static boolean isIgnored(String filename) {
        return IGNORED_FILENAMES.contains(filename.toLowerCase());
    }

    /**
     * Returns all active (non-deleted) file metadata records for a given root directory ID.
     * Used by the periodic subdir scanner for filesystem vs DB comparison.
     *
     * @param rootDirId root directory ID
     * @return list of active FileMetadata records
     */
    public List<FileMetadata> findAllActiveByRootDirId(int rootDirId) {
        return fileMetadataRepository.findAllActiveByRootDirId(rootDirId);
    }

    /**
     * Returns the current maximum sync version for a root directory.
     * Returns {@code null} if no versioned records exist.
     */
    public Long getMaxSyncVersionForDir(int rootDirId) {
        return fileMetadataRepository.getMaxSyncVersionForDir(rootDirId);
    }

    /**
     * Returns the distinct immediate child directory names under {@code parentRelPath}.
     * Use {@code ""} to query the root level.
     */
    public List<String> findImmediateChildDirNames(int rootDirId, String parentRelPath) {
        return fileMetadataRepository.findImmediateChildDirNames(rootDirId, parentRelPath);
    }

    public List<String> findImmediateChildFileNames(int rootDirId, String parentRelPath) {
        return fileMetadataRepository.findImmediateChildFileNames(rootDirId, parentRelPath);
    }
}
