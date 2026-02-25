package com.fstojilj.luddite.sync.server.service.facade;

import com.fstojilj.luddite.sync.server.event.FileChangeEvent;
import com.fstojilj.luddite.sync.server.service.FileEventService;
import com.fstojilj.luddite.sync.server.service.FileMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@Slf4j
@RequiredArgsConstructor
public class FileEventListener {

    private final FileMetadataService fileMetadataService;
    private final FileEventService fileEventService;

    @EventListener
    public void handleFileEvent(FileChangeEvent event) {
        var absoluteFilePath = Path.of(event.getAbsoluteFilePath());
        var rootDirId = event.getRootDirId();
        var relativePath = event.getRelativePath();

        switch (event.getEventKind().name()) {
            case "ENTRY_CREATE" -> fileMetadataService.addFileMetadata(absoluteFilePath, rootDirId, relativePath);
            case "ENTRY_MODIFY" -> fileMetadataService.updateFileMetadata(absoluteFilePath, rootDirId, relativePath);
            case "ENTRY_DELETE" -> fileMetadataService.deleteFileMetadata(rootDirId, relativePath);
            default -> log.error("Unknown event kind: {}", event.getEventKind().name());
        }

        fileEventService.publish(event);
    }
}
