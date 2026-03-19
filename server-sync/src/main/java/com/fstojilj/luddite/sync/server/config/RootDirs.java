package com.fstojilj.luddite.sync.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

@ConfigurationProperties("sync.server.root-dirs")
@Component
public record RootDirs(Set<String> rootDirAbsolutePaths) {
}
