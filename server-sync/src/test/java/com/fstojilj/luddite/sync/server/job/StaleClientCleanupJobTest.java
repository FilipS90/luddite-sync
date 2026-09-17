package com.fstojilj.luddite.sync.server.job;

import com.fstojilj.luddite.sync.server.repository.SyncTimeRepository;
import com.fstojilj.luddite.sync.server.service.FileMetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaleClientCleanupJobTest {

    @Mock
    private SyncTimeRepository syncTimeRepository;
    @Mock
    private FileMetadataService fileMetadataService;

    @InjectMocks
    private StaleClientCleanupJob job;

    @Test
    void purgeStaleClients_purgesEachStaleClient() {
        when(syncTimeRepository.getAllStaleClientIds()).thenReturn(Set.of("stale-1", "stale-2"));

        job.purgeStaleClients();

        verify(fileMetadataService).deleteAllForClient("stale-1");
        verify(fileMetadataService).deleteAllForClient("stale-2");
        verify(syncTimeRepository).delete("stale-1");
        verify(syncTimeRepository).delete("stale-2");
    }

    @Test
    void purgeStaleClients_noStaleClients_doesNothing() {
        when(syncTimeRepository.getAllStaleClientIds()).thenReturn(Set.of());

        job.purgeStaleClients();

        verify(fileMetadataService, never()).deleteAllForClient(anyString());
        verify(syncTimeRepository, never()).delete(anyString());
    }
}
