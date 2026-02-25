package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.server.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static com.fstojilj.luddite.sync.server.utils.FileChecksumUtils.calculateFileChecksum;
import static com.fstojilj.luddite.sync.server.utils.FileSystemUtils.listAllFilesForDir;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileMetadataService {

    private final FileMetadataRepository fileMetadataRepository;

    @Transactional
    public long addFileMetadata(Path filePath, long rootDirId, String relativePath) {
        File file = filePath.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("Path must point to an existing file");
        }

        return fileMetadataRepository.add(buildFileMetadata(file, rootDirId, relativePath));
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

    @Transactional
    public void updateFileMetadata(Path absoluteFilePath, long rootDirId, String relativeFilePath) {
        File file = absoluteFilePath.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("Path must point to an existing file");
        }

        var fileMetadata = fileMetadataRepository.findByRootDirIdAndRelativePath(rootDirId, relativeFilePath);
        fileMetadata.setFileSize(file.length());
        fileMetadata.setChecksum(calculateFileChecksum(absoluteFilePath));

        fileMetadataRepository.update(fileMetadata);
    }

    @Transactional
    public void deleteFileMetadata(long rootDirId, String relativeFilePath) {
        fileMetadataRepository.delete(rootDirId, relativeFilePath);
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
