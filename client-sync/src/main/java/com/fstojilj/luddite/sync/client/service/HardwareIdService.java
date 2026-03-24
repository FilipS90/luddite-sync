package com.fstojilj.luddite.sync.client.service;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
public class HardwareIdService {

    private static final String LINUX_DMI_PATH = "/sys/class/dmi/id/board_serial";
    private static final String FALLBACK_PLACEHOLDER = "Default string";

    @Value("${sync.client.mirror-dir:${user.home}/.luddite}")
    private String mirrorDir;

    /**
     * The resolved stable machine ID — available after {@link #init()}.
     */
    @Getter
    private String hardwareId;

    @PostConstruct
    public void init() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String id = null;

        if (os.contains("linux")) {
            id = readLinuxSerial();
        } else if (os.contains("windows")) {
            id = readWindowsSerial();
        }

        if (isBlankOrPlaceholder(id)) {
            log.warn("Could not read BIOS/motherboard serial (os='{}'). Falling back to persisted UUID.", os);
            id = resolveOrCreateFallbackId();
        }

        hardwareId = id;
        log.info("Hardware ID resolved: {}", hardwareId);
    }

    // ── Platform readers ──────────────────────────────────────────────────────

    private String readLinuxSerial() {
        try {
            Path path = Path.of(LINUX_DMI_PATH);
            if (!Files.isReadable(path)) {
                log.debug("DMI board_serial not readable at {}", LINUX_DMI_PATH);
                return null;
            }
            return Files.readString(path).trim();
        } catch (Exception e) {
            log.debug("Failed to read Linux DMI serial: {}", e.getMessage());
            return null;
        }
    }

    private String readWindowsSerial() {
        try {
            Process process = new ProcessBuilder(
                    "wmic", "baseboard", "get", "SerialNumber")
                    .redirectErrorStream(true)
                    .start();

            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                // Output looks like:
                //   SerialNumber
                //   <value>
                return reader.lines()
                        .map(String::trim)
                        .filter(l -> !l.isEmpty() && !l.equalsIgnoreCase("SerialNumber"))
                        .findFirst()
                        .orElse(null);
            }
        } catch (Exception e) {
            log.debug("Failed to run wmic: {}", e.getMessage());
            return null;
        }
    }

    // ── Fallback UUID ─────────────────────────────────────────────────────────

    private String resolveOrCreateFallbackId() {
        Path idFile = Path.of(mirrorDir, "client", "machine-id");
        try {
            if (Files.exists(idFile)) {
                String stored = Files.readString(idFile).trim();
                if (!stored.isEmpty()) {
                    log.debug("Loaded fallback machine-id from {}", idFile);
                    return stored;
                }
            }
            String newId = UUID.randomUUID().toString();
            Files.createDirectories(idFile.getParent());
            Files.writeString(idFile, newId);
            log.info("Generated new fallback machine-id and persisted to {}", idFile);
            return newId;
        } catch (Exception e) {
            // Last resort: use a new UUID for this session only (not ideal but never crashes)
            log.warn("Could not read/write machine-id file at {}: {}", idFile, e.getMessage());
            return UUID.randomUUID().toString();
        }
    }

    private static boolean isBlankOrPlaceholder(String value) {
        return value == null || value.isBlank() || value.equalsIgnoreCase(FALLBACK_PLACEHOLDER);
    }
}

