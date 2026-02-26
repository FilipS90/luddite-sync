package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.server.event.FileChangeEvent;
import com.fstojilj.luddite.sync.server.service.buffer.FileEventBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.StandardWatchEventKinds;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileEventServiceTest {

    @Mock
    private FileEventBuffer buffer;

    @InjectMocks
    private FileEventService fileEventService;

    @Test
    void publish_shouldDelegateToBuffer() {
        FileChangeEvent event = FileChangeEvent.builder()
                .rootDirId(1L)
                .absoluteFilePath("/photos/img.jpg")
                .relativePath("/img.jpg")
                .eventKind(StandardWatchEventKinds.ENTRY_CREATE)
                .build();

        fileEventService.publish(event);

        verify(buffer).publish(event);
    }

    @Test
    void drainForRootDir_shouldReturnEventsFromBuffer() {
        FileChangeEvent event = FileChangeEvent.builder()
                .rootDirId(1L)
                .absoluteFilePath("/photos/img.jpg")
                .relativePath("/img.jpg")
                .eventKind(StandardWatchEventKinds.ENTRY_CREATE)
                .build();
        when(buffer.drainForRootDir(1L)).thenReturn(List.of(event));

        List<FileChangeEvent> result = fileEventService.drainForRootDir(1L);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getAbsoluteFilePath()).isEqualTo("/photos/img.jpg");
    }

    @Test
    void drainForRootDir_emptyQueue_shouldReturnEmptyList() {
        when(buffer.drainForRootDir(99L)).thenReturn(List.of());

        List<FileChangeEvent> result = fileEventService.drainForRootDir(99L);

        assertThat(result).isEmpty();
    }

    @Test
    void activeRootDirIds_shouldReturnIdsFromBuffer() {
        when(buffer.activeRootDirIds()).thenReturn(Set.of(1L, 2L, 3L));

        Set<Long> result = fileEventService.activeRootDirIds();

        assertThat(result).containsExactlyInAnyOrder(1L, 2L, 3L);
    }
}

