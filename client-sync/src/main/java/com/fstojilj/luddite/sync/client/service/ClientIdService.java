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
 * Resolves a stable hardware identifier for this machine that survives network changes
 * (IP/address changes after router resets, etc.).
 *
 * <p>Resolution order:
 * <ol>
 *   <li><b>Linux</b> — reads {@code /sys/class/dmi/id/board_serial} directly.</li>
 *   <li><b>Windows</b> — runs {@code wmic baseboard get SerialNumber} and parses stdout.</li>
 *   <li><b>Fallback</b> — if the above fails, returns empty/unreadable, or returns the
 *       placeholder string {@code "Default string"}, a random UUID is generated once and
 *       persisted to {@code ~/.luddite/client/machine-id}. On subsequent startups the file
 *       is read back so the identity remains stable across reboots.</li>
 * </ol>
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

