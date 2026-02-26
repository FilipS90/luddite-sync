package com.fstojilj.luddite.sync.server.service.buffer;

import com.fstojilj.luddite.sync.server.event.FileChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.StandardWatchEventKinds;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FileEventBufferTest {

    private FileEventBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new FileEventBuffer();
    }

    @Test
    void publish_and_drain_shouldReturnPublishedEvents() {
        FileChangeEvent event = FileChangeEvent.builder()
                .rootDirId(1L)
                .absoluteFilePath("/photos/img.jpg")
                .relativePath("/img.jpg")
                .eventKind(StandardWatchEventKinds.ENTRY_CREATE)
                .build();

        buffer.publish(event);
        List<FileChangeEvent> drained = buffer.drainForRootDir(1L);

        assertThat(drained).hasSize(1);
        assertThat(drained.getFirst().getRelativePath()).isEqualTo("/img.jpg");
    }

    @Test
    void drain_emptyBuffer_shouldReturnEmptyList() {
        List<FileChangeEvent> drained = buffer.drainForRootDir(999L);

        assertThat(drained).isEmpty();
    }

    @Test
    void drain_shouldClearQueue() {
        buffer.publish(FileChangeEvent.builder()
                .rootDirId(1L)
                .absoluteFilePath("/a.jpg")
                .relativePath("/a.jpg")
                .eventKind(StandardWatchEventKinds.ENTRY_CREATE)
                .build());

        buffer.drainForRootDir(1L);
        List<FileChangeEvent> second = buffer.drainForRootDir(1L);

        assertThat(second).isEmpty();
    }

    @Test
    void publish_multipleRootDirs_shouldIsolateQueues() {
        buffer.publish(FileChangeEvent.builder()
                .rootDirId(1L)
                .absoluteFilePath("/photos/a.jpg")
                .relativePath("/a.jpg")
                .eventKind(StandardWatchEventKinds.ENTRY_CREATE)
                .build());
        buffer.publish(FileChangeEvent.builder()
                .rootDirId(2L)
                .absoluteFilePath("/docs/readme.md")
                .relativePath("/readme.md")
                .eventKind(StandardWatchEventKinds.ENTRY_MODIFY)
                .build());

        List<FileChangeEvent> dir1 = buffer.drainForRootDir(1L);
        List<FileChangeEvent> dir2 = buffer.drainForRootDir(2L);

        assertThat(dir1).hasSize(1);
        assertThat(dir2).hasSize(1);
        assertThat(dir1.getFirst().getRootDirId()).isEqualTo(1L);
        assertThat(dir2.getFirst().getRootDirId()).isEqualTo(2L);
    }

    @Test
    void activeRootDirIds_shouldReturnAllPublishedDirIds() {
        buffer.publish(FileChangeEvent.builder()
                .rootDirId(1L)
                .absoluteFilePath("/a.jpg")
                .relativePath("/a.jpg")
                .eventKind(StandardWatchEventKinds.ENTRY_CREATE)
                .build());
        buffer.publish(FileChangeEvent.builder()
                .rootDirId(3L)
                .absoluteFilePath("/b.jpg")
                .relativePath("/b.jpg")
                .eventKind(StandardWatchEventKinds.ENTRY_CREATE)
                .build());

        Set<Long> ids = buffer.activeRootDirIds();

        assertThat(ids).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void publish_multipleEventsToSameDir_shouldAccumulate() {
        for (int i = 0; i < 5; i++) {
            buffer.publish(FileChangeEvent.builder()
                    .rootDirId(1L)
                    .absoluteFilePath("/photos/img" + i + ".jpg")
                    .relativePath("/img" + i + ".jpg")
                    .eventKind(StandardWatchEventKinds.ENTRY_CREATE)
                    .build());
        }

        List<FileChangeEvent> drained = buffer.drainForRootDir(1L);
        assertThat(drained).hasSize(5);
    }
}

