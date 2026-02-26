package com.fstojilj.luddite.sync.client.cli;

import com.fstojilj.luddite.sync.client.config.SyncClientProperties;
import com.fstojilj.luddite.sync.client.repository.SyncStateRepository;
import com.fstojilj.luddite.sync.client.service.ServerSyncService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Interactive CLI for managing client directory subscriptions at runtime.
 * <p>
 * Commands:
 * list              — show subscribed dirs and their sync state
 * add &lt;name&gt;        — subscribe to a server directory by name
 * remove &lt;name&gt;     — unsubscribe from a directory
 * refresh           — reconnect to server to re-poll available directories
 * mirror            — show the current mirror directory
 * help              — show available commands
 * exit              — shut down the client
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientCli {

    private final SyncClientProperties clientProperties;
    private final SyncStateRepository syncStateRepository;
    private final ServerSyncService serverSyncService;

    @PostConstruct
    public void start() {
        Thread.ofVirtual().name("client-cli").start(this::runLoop);
    }

    private void runLoop() {
        printHelp();
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
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

        switch (command) {
            case "list" -> {
                var entries = syncStateRepository.findAll();
                var subscribedDirs = clientProperties.getDirs();

                if (entries.isEmpty() && subscribedDirs.isEmpty()) {
                    System.out.println("  (no directories configured)");
                    return;
                }

                System.out.println("  Dir Name             | Last Sync Version | Subscribed");
                System.out.println("  ---------------------|-------------------|----------");

                for (var entry : entries) {
                    boolean subscribed = subscribedDirs.isEmpty() || subscribedDirs.contains(entry.dirName());
                    System.out.printf("  %-20s | %-17d | %s%n",
                            entry.dirName(), entry.lastSyncVersion(), subscribed ? "yes" : "no");
                }

                // Show configured dirs not yet in sync_state
                for (String dir : subscribedDirs) {
                    boolean alreadyListed = entries.stream().anyMatch(e -> e.dirName().equals(dir));
                    if (!alreadyListed) {
                        System.out.printf("  %-20s | %-17d | %s%n", dir, -1L, "yes (pending)");
                    }
                }
            }
            case "add" -> {
                if (arg.isEmpty()) {
                    System.out.println("  Usage: add <dir-name>");
                    return;
                }
                if (clientProperties.getDirs().contains(arg)) {
                    System.out.printf("  Already subscribed to: %s%n", arg);
                    return;
                }
                clientProperties.getDirs().add(arg);
                syncStateRepository.registerIfAbsent(arg);
                System.out.printf("  Subscribed to: %s%n", arg);
            }
            case "remove" -> {
                if (arg.isEmpty()) {
                    System.out.println("  Usage: remove <dir-name>");
                    return;
                }
                boolean removed = clientProperties.getDirs().remove(arg);
                if (removed) {
                    System.out.printf("  Unsubscribed from: %s%n", arg);
                } else {
                    System.out.printf("  Not subscribed to: %s%n", arg);
                }
            }
            case "refresh" -> {
                System.out.println("  Reconnecting to server to re-poll available directories...");
                serverSyncService.reconnect();
            }
            case "mirror" -> System.out.printf("  Mirror directory: %s%n", clientProperties.getMirrorDir());
            case "help" -> printHelp();
            case "exit" -> {
                System.out.println("  Shutting down...");
                System.exit(0);
            }
            default -> System.out.printf("  Unknown command: '%s'. Type 'help' for available commands.%n", command);
        }
    }

    private void printHelp() {
        System.out.println();
        System.out.println("  Luddite Sync Client — CLI");
        System.out.println("  -------------------------");
        System.out.println("  list              show subscribed dirs and sync state");
        System.out.println("  add <name>        subscribe to a server directory");
        System.out.println("  remove <name>     unsubscribe from a directory");
        System.out.println("  refresh           reconnect and re-poll server for available dirs");
        System.out.println("  mirror            show current mirror directory");
        System.out.println("  help              show this message");
        System.out.println("  exit              shut down the client");
        System.out.println();
    }
}
