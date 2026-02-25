package com.fstojilj.luddite.sync.server.service.buffer;

import com.fstojilj.luddite.sync.server.event.FileChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Component
public class FileEventBuffer {

    private final LinkedBlockingQueue<FileChangeEvent> queue = new LinkedBlockingQueue<>();

    public void publish(FileChangeEvent event) {
        queue.add(event);
    }

    public List<FileChangeEvent> drain() {
        List<FileChangeEvent> events = new ArrayList<>();
        queue.drainTo(events);
        return events;
    }
}
