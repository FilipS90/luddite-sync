package com.fstojilj.luddite.sync.server.service.facade;

import com.fstojilj.luddite.sync.server.event.FileChangeEvent;
import com.fstojilj.luddite.sync.server.service.FileMetadataService;
import com.fstojilj.luddite.sync.server.service.RootDirService;
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

    private final RootDirService rootDirService;

    @EventListener
    public void handleFileEvent(FileChangeEvent event) {
        var rootDirId = event.getRootDirId();
        var absoluteFilePath = Path.of(event.getAbsoluteFilePath());
        var eventKind = event.getEventKind();
        var rootDirPath = Path.of(rootDirService.getRootDirPathById(rootDirId));
        var relativeFilePath = rootDirPath.relativize(absoluteFilePath).toString();
        switch (eventKind.name()) {
            case "ENTRY_CREATE" ->
                    fileMetadataService.addFileMetadata(absoluteFilePath, rootDirId, relativeFilePath);
            case "ENTRY_MODIFY" ->
                    fileMetadataService.updateFileMetadata(absoluteFilePath, rootDirId, relativeFilePath);
            case "ENTRY_DELETE" ->
                    fileMetadataService.deleteFileMetadata(rootDirId, relativeFilePath);
            default -> log.error("Unknown event kind: {}", eventKind.name());
        }
    }

}
