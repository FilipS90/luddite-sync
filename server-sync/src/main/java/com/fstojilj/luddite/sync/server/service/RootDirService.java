package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.repository.RootDirRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;

import static com.fstojilj.luddite.sync.server.utils.FileSystemUtils.getDirName;
import static com.fstojilj.luddite.sync.server.utils.FileSystemUtils.isValidFileSystemDirectory;

@Service
@RequiredArgsConstructor
@Slf4j
public class RootDirService {

    private final RootDirRepository rootDirRepository;

    private final FileMetadataService fileMetadataService;

    private final DirWatcherService dirWatcherService;


    @Transactional
    public void addRootDir(String absolutePath, boolean isPrivate, String password) {
        isValidFileSystemDirectory(absolutePath);
        var dirName = getDirName(absolutePath);
        var rootDir = RootDir.builder()
                .name(dirName)
                .isPrivate(isPrivate)
                .password(password)
                .absolutePath(absolutePath)
                .build();

        int roodDirId = rootDirRepository.insert(rootDir);
        fileMetadataService.addAllFileMetadataForRoot(absolutePath, roodDirId);
        dirWatcherService.startWatching(absolutePath, roodDirId);
    }


    @Transactional
    public boolean removeRootDir(int id) {
        var rootDir = rootDirRepository.getRootDirById(id);
        if (rootDir.isEmpty()) {
            return false;
        }
        String absolutePath = rootDir.get().getAbsolutePath();
        dirWatcherService.stopWatching(absolutePath);
        fileMetadataService.deleteAllForRootDir(id);
        rootDirRepository.deleteRootDirById(id);
        log.info("Removed root dir: {} (id={})", absolutePath, id);
        return true;
    }


    public Optional<RootDir> findByName(String name) {
        return rootDirRepository.findByName(name);
    }

    public Set<RootDir> findAll() {
        return rootDirRepository.findAll();
    }
}
