package com.fstojilj.luddite.sync.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the list of absolute paths the server should watch and serve to clients.
 * Add or remove entries in application.yml and restart (or POST /actuator/refresh).
 * <p>
 * Example config:
 * sync:
 * server:
 * root-dirs:
 * - /mnt/photos
 * - /mnt/documents
 */
@Component
@ConfigurationProperties(prefix = "sync.server")
@Getter
@Setter
public class SyncServerProperties {

    private List<String> rootDirs = new ArrayList<>();
}

