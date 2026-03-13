package com.fstojilj.luddite.sync.server.service.facade;

import com.fstojilj.luddite.sync.server.event.FileChangeEvent;
import com.fstojilj.luddite.sync.server.service.FileEventService;
import com.fstojilj.luddite.sync.server.service.FileMetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FileEventListenerTest {

    @Mock
    private FileMetadataService fileMetadataService;

    @Mock
    private FileEventService fileEventService;

    @InjectMocks
    private FileEventListener fileEventListener;

    @Test
    void handleFileEvent_create_shouldAddMetadataAndPublish() {
        FileChangeEvent event = FileChangeEvent.builder()
                .rootDirId(1L)
                .absoluteFilePath("/home/user/photos/img.jpg")
                .relativePath("/img.jpg")
                .eventKind(StandardWatchEventKinds.ENTRY_CREATE)
                .build();

        fileEventListener.handleFileEvent(event);

        verify(fileMetadataService).addFileMetadata(
                Path.of("/home/user/photos/img.jpg"), 1L, "/img.jpg");
        verify(fileEventService).publish(event);
    }

    @Test
    void handleFileEvent_modify_shouldUpdateMetadataAndPublish() {
        FileChangeEvent event = FileChangeEvent.builder()
                .rootDirId(1L)
                .absoluteFilePath("/home/user/photos/img.jpg")
                .relativePath("/img.jpg")
                .eventKind(StandardWatchEventKinds.ENTRY_MODIFY)
                .build();

        fileEventListener.handleFileEvent(event);

        verify(fileMetadataService).updateFileMetadata(
                Path.of("/home/user/photos/img.jpg"), 1L, "/img.jpg");
        verify(fileEventService).publish(event);
    }

    @Test
    void handleFileEvent_delete_shouldDeleteMetadataAndPublish() {
        FileChangeEvent event = FileChangeEvent.builder()
                .rootDirId(1L)
                .absoluteFilePath("/home/user/photos/img.jpg")
                .relativePath("/img.jpg")
                .eventKind(StandardWatchEventKinds.ENTRY_DELETE)
                .build();

        fileEventListener.handleFileEvent(event);

        verify(fileMetadataService).deleteFileMetadata(1L, "/img.jpg");
        verify(fileEventService).publish(event);
    }
}

