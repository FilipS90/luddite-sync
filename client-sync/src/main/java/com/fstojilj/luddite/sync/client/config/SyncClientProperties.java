package com.fstojilj.luddite.sync.client.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the list of root directory names the client wants to synchronize.
 * Can be updated in application.yml and reloaded at runtime via POST /actuator/refresh.
 * <p>
 * Example config:
 * sync:
 * client:
 * dirs:
 * - photos
 * - documents
 */
@Component
@ConfigurationProperties(prefix = "sync.client")
@Getter
@Setter
public class SyncClientProperties {

    private String mirrorDir;
    private List<String> dirs = new ArrayList<>();

    /**
     * Dirs in desync mode: server WRITE/CREATE events are still applied, but
     * DELETE events from the server are ignored. This lets the user keep their
     * own files in the mirror directory alongside server-tracked files.
     * Not bound to application.yml — managed at runtime via the CLI.
     */
    private final Set<String> desyncedDirs = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public boolean isDesynced(String dirName) {
        return desyncedDirs.contains(dirName);
    }

    public boolean desync(String dirName) {
        return desyncedDirs.add(dirName);
    }

    public boolean resync(String dirName) {
        return desyncedDirs.remove(dirName);
    }
}
