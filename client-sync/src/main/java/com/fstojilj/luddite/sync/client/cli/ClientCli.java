package com.fstojilj.luddite.sync.client.cli;

import com.fstojilj.luddite.sync.client.service.ClientSyncService;
import com.fstojilj.luddite.sync.client.service.RootDirService;
import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static com.fstojilj.luddite.sync.client.service.ClientSyncService.serverDirs;
import static java.lang.Thread.sleep;

/**
 * Interactive CLI for managing client directory subscriptions at runtime.
 * <p>
 * Commands:
 * list                — show subscribed dirs and their sync state
 * add &lt;name&gt;          — subscribe to a server directory by name
 * remove &lt;name&gt;       — unsubscribe from a directory
 * refresh             — reconnect to server to re-poll available directories
 * mirror              — show the current mirror directory
 * help                — show available commands
 * exit                — shut down the client
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "sync.client.ui", havingValue = "cli")
public class ClientCli {

    private final RootDirService rootDirService;
    private final ClientSyncService clientSyncService;
    private final ApplicationContext applicationContext;

    private List<String> dirNames;

    @Value("${sync.client.mirror-dir}")
    private String mirrorDir;

    @PostConstruct
    public void start() {
        Thread.ofVirtual().name("client-cli").start(this::runLoop);
    }

    @SneakyThrows
    private void runLoop() {
        sleep(400);
        printHelp();
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handle(line.trim());
            }
        } catch (Exception e) {
            log.error("Client CLI error", e);
        }
    }

    private void handle(String line) {
        if (line.isEmpty()) return;

        String[] parts = line.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        System.out.println(command);
        System.out.println(arg);

        var entries = rootDirService.findAll();
        dirNames = entries.stream().map(SyncHandshakeEntry::dirName).toList();

        switch (command) {
            case "list" -> {
                if (entries.isEmpty()) {
                    System.out.println("  (no directories configured)");
                    return;
                }

                System.out.println("  Dir Name             | Last Sync Version |");
                System.out.println("  ---------------------|-------------------|");

                for (var entry : entries) {
                    System.out.printf("%-20s   | %-17d |%n",
                            entry.dirName(), entry.lastSyncVersion());
                }
            }
            case "add" -> {
                if (arg.isEmpty()) {
                    System.out.println("  Usage: add directory index (use comma to separate if adding multiple)");
                    return;
                }
                if (dirNames.contains(arg)) {
                    System.out.printf("  Already subscribed to: %s%n", arg);
                    return;
                }
                List<String> dirs = registerDirs(arg);
                System.out.printf("  Subscribed to: %s%n", dirs);
            }
            case "remove" -> {
                if (arg.isEmpty()) {
                    System.out.println("  Usage (unsubscribe from dir): remove <dir-name>");
                    return;
                }
                if (!dirNames.contains(arg)) {
                    System.out.println("No such directory found");
                    return;
                }

                rootDirService.removeDirectory(arg, false);
                System.out.printf("  Unsubscribed from: %s%n", arg);
            }
            case "refresh" -> {
                System.out.println("  Reconnecting to server to re-poll available directories...");
                clientSyncService.reconnect();
            }
            case "mirror" -> System.out.printf("  Mirror directory: %s%n", mirrorDir);
            case "help" -> printHelp();
            case "exit" -> {
                System.out.println("  Shutting down...");
                SpringApplication.exit(applicationContext, () -> 0);
            }
            default -> System.out.printf("  Unknown command: '%s'. Type 'help' for available commands.%n", command);
        }
    }

    private void printHelp() {
        System.out.println();
        System.out.println("  Luddite Sync Client — CLI");
        System.out.println("  -------------------------");
        System.out.println("  list                show subscribed dirs and sync state");
        System.out.println("  add <name>          subscribe to a server directory");
        System.out.println("  remove <name>       unsubscribe from a directory");
        System.out.println("  refresh             reconnect and re-poll server for available dirs");
        System.out.println("  mirror              show current mirror directory");
        System.out.println("  help                show this message");
        System.out.println("  exit                shut down the client");
        System.out.println();
    }

    private List<String> registerDirs(String directoryIndices) {
        List<Integer> indices = Arrays.stream(directoryIndices.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .toList();

        List<String> names = indices.stream().map(i -> serverDirs.get(i - 1)).toList();

        names.forEach(name -> {
            if (dirNames.contains(name)) {
                System.out.printf("  Already subscribed to: %s%n", name);
            } else {
                rootDirService.registerIfAbsent(name);
                System.out.printf("  Subscribed to: %s%n", name);
            }
        });

        return names;
    }
}
