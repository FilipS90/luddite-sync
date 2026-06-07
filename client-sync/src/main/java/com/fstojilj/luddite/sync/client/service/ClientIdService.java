package com.fstojilj.luddite.sync.client.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Resolves a stable client identifier for this machine
 * (IP/address changes after router resets, etc.).
 *
 */
@Service
@Slf4j
public class ClientIdService {

    @Value("${sync.client.mirror-dir:${user.home}/.luddite}")
    private String mirrorDir;

    @Value("${sync.client.name}")
    private String clientName;

    @Getter
    private String clientId;

    @PostConstruct
    public void init() {
        clientId = generateId();
        log.info("Client ID initialized: {}", clientId);
    }

    // ── Fallback UUID ─────────────────────────────────────────────────────────

    private String generateId() {
        Path idFile = Path.of(mirrorDir, "client-id");
        try {
            if (Files.exists(idFile)) {
                String stored = Files.readString(idFile).trim();
                if (!stored.isEmpty()) {
                    log.debug("Loaded client-id from {}", idFile);
                    return stored;
                }
            }
            String newId = UUID.randomUUID().toString();
            Files.createDirectories(idFile.getParent());
            Files.writeString(idFile, newId + "_" + clientName);
            log.info("Generated new client-id and persisted to {}", idFile);
            return newId;
        } catch (Exception e) {
            // Last resort: use a new UUID for this session only (not ideal but never crashes)
            log.warn("Could not read/write client-id file at {}: {}", idFile, e.getMessage());
            return UUID.randomUUID().toString();
        }
    }
}

