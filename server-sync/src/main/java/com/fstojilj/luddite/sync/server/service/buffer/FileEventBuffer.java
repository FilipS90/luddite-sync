package com.fstojilj.luddite.sync.server.service.buffer;

import com.fstojilj.luddite.sync.server.event.FileChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Component
public class FileEventBuffer {

    private final ConcurrentHashMap<Long, LinkedBlockingQueue<FileChangeEvent>> queues =
            new ConcurrentHashMap<>();

    public void publish(FileChangeEvent event) {
        queues.computeIfAbsent(event.getRootDirId(), _ -> new LinkedBlockingQueue<>())
                .add(event);
    }

    public List<FileChangeEvent> drainForRootDir(long rootDirId) {
        var queue = queues.get(rootDirId);
        if (queue == null) return List.of();
        List<FileChangeEvent> events = new ArrayList<>();
        queue.drainTo(events);
        return events;
    }

    /**
     * Returns the set of root dir IDs that currently have pending events.
     */
    public Set<Long> activeRootDirIds() {
        return queues.keySet();
    }
}
