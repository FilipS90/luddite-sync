package com.fstojilj.luddite.sync.server.service;

import com.fstojilj.luddite.sync.server.event.FileChangeEvent;
import com.fstojilj.luddite.sync.server.service.buffer.FileEventBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileEventService {

    private final FileEventBuffer buffer;

    public void publish(FileChangeEvent event) {
        buffer.publish(event);
    }

    public List<FileChangeEvent> drainForRootDir(long rootDirId) {
        return buffer.drainForRootDir(rootDirId);
    }

    public Set<Long> activeRootDirIds() {
        return buffer.activeRootDirIds();
    }
}