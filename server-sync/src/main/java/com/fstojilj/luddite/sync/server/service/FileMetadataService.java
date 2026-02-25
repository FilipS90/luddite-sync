package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.server.repository.DeletedFilesRepository;
import com.fstojilj.luddite.sync.server.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.fstojilj.luddite.sync.server.utils.FileChecksumUtils.calculateFileChecksum;
import static com.fstojilj.luddite.sync.server.utils.FileSystemUtils.listAllFilesForDir;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileMetadataService {

    private final FileMetadataRepository fileMetadataRepository;
    private final DeletedFilesRepository deletedFilesRepository;

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

        var fileMetadata = existing.get();
        fileMetadata.setFileSize(file.length());
        fileMetadata.setChecksum(calculateFileChecksum(absoluteFilePath));
        fileMetadata.setSyncVersion(null);

        fileMetadataRepository.update(fileMetadata);
    }

    @Transactional
    public void deleteFileMetadata(long rootDirId, String relativeFilePath) {
        fileMetadataRepository.delete(rootDirId, relativeFilePath);
    }

    /**
     * Records a deletion event with the given syncVersion for catch-up replay.
     */
    @Transactional
    public void recordDeletion(long rootDirId, String relativePath, long syncVersion) {
        deletedFilesRepository.insert(rootDirId, relativePath, syncVersion);
    }

    /**
     * Called after a file has been successfully sent to at least one client.
     * Sets the sync_version so future clients know they already have this version.
     */
    @Transactional
    public void stampSyncVersion(long rootDirId, String relativePath, long syncVersion) {
        fileMetadataRepository.updateSyncVersion(rootDirId, relativePath, syncVersion);
    }

    public List<FileMetadata> findFilesNewerThan(long rootDirId, long lastSyncVersion) {
        return fileMetadataRepository.findByRootDirIdWithSyncVersionAfter(rootDirId, lastSyncVersion);
    }

    public List<Map<String, Object>> findDeletesNewerThan(long rootDirId, long lastSyncVersion) {
        return deletedFilesRepository.findByRootDirIdWithSyncVersionAfter(rootDirId, lastSyncVersion);
    }

    private FileMetadata buildFileMetadata(File file, long rootDirId, String relativePath) {
        return FileMetadata.builder()
                .filename(file.getName())
                .rootDirId(rootDirId)
                .relativePath(relativePath)
                .fileSize(file.length())
                .checksum(calculateFileChecksum(file.toPath()))
                .syncVersion(null)
                .build();
    }
}
