package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.repository.RootDirRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static com.fstojilj.luddite.sync.server.utils.FileSystemUtils.getDirName;

@Service
@RequiredArgsConstructor
@Slf4j
public class RootDirService {

    private final RootDirRepository rootDirRepository;

    private final FileMetadataService fileMetadataService;

    private final DirWatcherService dirWatcherService;


    @Transactional
    public void addRootDir(String absolutePath) {
        var dirName = getDirName(absolutePath);
        var rootDir = RootDir.builder()
                .name(dirName)
                .absolutePath(absolutePath)
                .build();

        long roodDirId = rootDirRepository.insert(rootDir);
        fileMetadataService.addAllFileMetadataForRoot(absolutePath, roodDirId);
        dirWatcherService.startWatching(absolutePath, roodDirId);
    }

    public Long getRootDirIdByAbsolutePath(String absolutePath) {
        return rootDirRepository.getRootDirIdByAbsolutePath(absolutePath);
    }

    public boolean removeRootDirById(long id) {
        return rootDirRepository.deleteRootDirById(id);
    }

    public String getRootDirPathById(long rootDirId) {
        var rootDir = rootDirRepository.getRootDirById(rootDirId)
                .orElseThrow(() -> new IllegalArgumentException("Root directory not found for ID: " + rootDirId));
        return rootDir.getAbsolutePath();
    }

    public String getRootDirNameById(long rootDirId) {
        return rootDirRepository.getRootDirById(rootDirId)
                .orElseThrow(() -> new IllegalArgumentException("Root directory not found for ID: " + rootDirId))
                .getName();
    }

    public Optional<RootDir> findByName(String name) {
        return rootDirRepository.findByName(name);
    }
}
