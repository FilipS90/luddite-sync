package com.fstojilj.luddite.sync.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Holds the list of absolute paths the server should watch and serve to clients.
 * Add or remove entries in application.yml and restart (or POST /actuator/refresh).
 * <p>
 * Example config:
 * sync:
 * server:
 * - /mnt/photos
 * - /mnt/documents
 */
@Component
@ConfigurationProperties(prefix = "sync.server")
@Getter
@Setter
public class SyncServerProperties {

    private Set<String> rootDirs = new HashSet<>();
}

