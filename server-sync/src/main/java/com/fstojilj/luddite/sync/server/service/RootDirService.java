package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.repository.RootDirRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.fstojilj.luddite.sync.server.utils.FileSystemUtils.getDirName;

@Service
@RequiredArgsConstructor
@Slf4j
public class RootDirService {

    private final RootDirRepository rootDirRepository;

    private final FileMetadataService fileMetadataService;

    private final DirWatcherService dirWatcherService;


    public void addRootDir(String absolutePath) {
        var dirName = getDirName(absolutePath);
        var rootDir = RootDir.builder()
                .name(dirName)
                .absolutePath(absolutePath)
                .build();

        long roodDirId = rootDirRepository.insert(rootDir);
        fileMetadataService.addAllFileMetadataForRoot(absolutePath, roodDirId);
        dirWatcherService.startWatching(absolutePath);
    }

    public boolean removeRootDirById(long id) {
        return rootDirRepository.deleteRootDirById(id);
    }
}
