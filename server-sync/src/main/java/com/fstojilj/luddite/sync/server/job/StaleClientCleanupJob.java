package com.fstojilj.luddite.sync.server.job;

import com.fstojilj.luddite.sync.server.repository.SyncTimeRepository;
import com.fstojilj.luddite.sync.server.service.FileMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class StaleClientCleanupJob {

    private final SyncTimeRepository syncTimeRepository;
    private final FileMetadataService fileMetadataService;

    @Scheduled(cron = "0 0 0 * * ?") // Runs daily at midnight
    public void purgeStaleClients() {
        Set<String> staleClientIds = syncTimeRepository.getAllStaleClientIds();

        for (String staleClientId : staleClientIds) {
            fileMetadataService.deleteAllForClient(staleClientId);
            syncTimeRepository.delete(staleClientId);
            log.info("Purged stale client data for client ID: {}", staleClientId);
        }
    }
}
