package com.fstojilj.luddite.sync.client.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
}
