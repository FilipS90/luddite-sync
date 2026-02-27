package com.fstojilj.luddite.sync.server.config;

import com.fstojilj.luddite.sync.common.model.RootDir;
import com.fstojilj.luddite.sync.server.repository.RootDirRepository;
import com.fstojilj.luddite.sync.server.service.DirWatcherService;
import com.fstojilj.luddite.sync.server.service.RootDirService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;

import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatcherStartupRunnerTest {

    @Mock
    private RootDirRepository rootDirRepository;
    @Mock
    private DirWatcherService dirWatcherService;
    @Mock
    private RootDirService rootDirService;
    @Mock
    private SyncServerProperties serverProperties;

    @InjectMocks
    private WatcherStartupRunner watcherStartupRunner;

    @Test
    void run_newConfiguredDir_shouldRegisterAndWatch() throws Exception {
        when(rootDirRepository.findAll()).thenReturn(Set.of());
        when(serverProperties.getRootDirs()).thenReturn(java.util.List.of("/photos"));

        watcherStartupRunner.run(mock(ApplicationArguments.class));

        verify(rootDirService).addRootDir("/photos");
    }

    @Test
    void run_alreadyKnownDir_shouldNotRegisterAgain() throws Exception {
        RootDir existing = RootDir.builder().id(1L).name("photos").absolutePath("/photos").build();
        when(rootDirRepository.findAll()).thenReturn(Set.of(existing));
        when(serverProperties.getRootDirs()).thenReturn(java.util.List.of("/photos"));

        watcherStartupRunner.run(mock(ApplicationArguments.class));

        verify(rootDirService, never()).addRootDir("/photos");
        verify(dirWatcherService).startWatching("/photos", 1L);
    }

    @Test
    void run_noDirs_shouldStartNoWatchers() throws Exception {
        when(rootDirRepository.findAll()).thenReturn(Set.of());
        when(serverProperties.getRootDirs()).thenReturn(java.util.List.of());

        watcherStartupRunner.run(mock(ApplicationArguments.class));

        verify(dirWatcherService, never()).startWatching(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void run_multipleKnownDirs_shouldStartWatcherForEach() throws Exception {
        Set<RootDir> dirs = Set.of(
                RootDir.builder().id(1L).name("photos").absolutePath("/photos").build(),
                RootDir.builder().id(2L).name("docs").absolutePath("/docs").build()
        );
        when(rootDirRepository.findAll()).thenReturn(dirs);
        when(serverProperties.getRootDirs()).thenReturn(java.util.List.of());

        watcherStartupRunner.run(mock(ApplicationArguments.class));

        verify(dirWatcherService).startWatching("/photos", 1L);
        verify(dirWatcherService).startWatching("/docs", 2L);
    }
}

