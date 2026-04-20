package com.fstojilj.luddite.sync.server.config;

import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.repository.RootDirRepository;
import com.fstojilj.luddite.sync.server.service.DirWatcherService;
import com.fstojilj.luddite.sync.server.service.RootDirService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class WatcherStartupRunner implements ApplicationRunner {

    private final RootDirRepository rootDirRepository;
    private final DirWatcherService dirWatcherService;
    private final RootDirService rootDirService;
    private final SyncServerProperties serverProperties;

    /**
     * On startup, registers any new configured dirs and re-registers watchers for all
     * known dirs (persisted from a previous run or newly added).
     */
    @Override
    public void run(ApplicationArguments args) {
        // Register any configured dirs not yet in the DB
        Set<String> knownPaths = rootDirRepository.findAll().stream()
                .map(r -> r.getAbsolutePath())
                .collect(Collectors.toSet());

        for (String path : serverProperties.getRootDirs()) {
            if (!knownPaths.contains(path)) {
                log.info("Registering new root dir from config: {}", path);
                rootDirService.addRootDir(path);
            }
        }

        // Resume watchers for all dirs already in the DB (including the ones just added)
        var rootDirs = rootDirRepository.findAll();
        log.info("Resuming watchers for {} root dir(s)", rootDirs.size());
        for (RootDir rootDir : rootDirs) {
            dirWatcherService.startWatching(rootDir.getAbsolutePath(), rootDir.getId());
        }
    }
}
