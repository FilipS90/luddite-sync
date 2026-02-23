package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.FileMetadata;
import com.fstojilj.luddite.sync.server.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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


    public long addFileMetadata(FileMetadata fileMetadata) {
        return fileMetadataRepository.add(fileMetadata);
    }

    public void addAllFileMetadataForRoot(String rootAbsolutePath, long rootDirId) {
        List<File> files = listAllFilesForDir(rootAbsolutePath);
        Path rootPath = Path.of(rootAbsolutePath);

        for (File file : files) {
            String relativePath = File.separator + rootPath.relativize(file.toPath());

            var metadata = buildFileMetadata(file, rootDirId, relativePath);

            fileMetadataRepository.add(metadata);
        }

        recursiveAddFileMetadataForSubdirs(rootAbsolutePath, rootAbsolutePath, rootDirId);
    }

    private void recursiveAddFileMetadataForSubdirs(String dirAbsolutePath, String rootAbsolutePath, long rootDirId) {
        List<File> files = listAllFilesForDir(dirAbsolutePath);
        Path rootPath = Path.of(rootAbsolutePath);

        for (File file : files) {
            if (file.isDirectory()) {
                recursiveAddFileMetadataForSubdirs(file.getAbsolutePath(), rootAbsolutePath, rootDirId);
            } else if (dirAbsolutePath.equals(rootAbsolutePath)) {
                String relativePath = File.separator + rootPath.relativize(file.toPath());

                var metadata = buildFileMetadata(file, rootDirId, relativePath);

                fileMetadataRepository.add(metadata);
            }
        }
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
