package com.fstojilj.luddite.sync.server.event;

import lombok.Builder;
import lombok.Getter;

import java.nio.file.WatchEvent;

@Getter
@Builder
public class FileChangeEvent {

    private long rootDirId;
    private String absoluteFilePath;
    private WatchEvent.Kind<?> eventKind;

}
