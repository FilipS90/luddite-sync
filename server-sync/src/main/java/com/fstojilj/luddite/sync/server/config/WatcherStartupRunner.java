package com.fstojilj.luddite.sync.server.config;

import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.repository.RootDirRepository;
import com.fstojilj.luddite.sync.server.service.DirWatcherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WatcherStartupRunner implements ApplicationRunner {

    private final RootDirRepository rootDirRepository;
    private final DirWatcherService dirWatcherService;

    /**
     * On startup, registers any new configured dirs and re-registers watchers for all
     * known dirs (persisted from a previous run or newly added).
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting WatcherStartupRunner...");
        var rootDirs = rootDirRepository.findAll();
        log.info("Resuming watchers for {} root dir(s)", rootDirs.size());
        for (RootDir rootDir : rootDirs) {
            dirWatcherService.startWatching(rootDir.getAbsolutePath(), rootDir.getId());
        }
    }
}
