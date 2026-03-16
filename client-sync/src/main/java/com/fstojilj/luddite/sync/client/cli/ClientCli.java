package com.fstojilj.luddite.sync.client.cli;

import com.fstojilj.luddite.sync.client.service.ClientSyncService;
import com.fstojilj.luddite.sync.client.service.SyncStateService;
import com.fstojilj.luddite.sync.common.model.SyncHandshakeEntry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Interactive CLI for managing client directory subscriptions at runtime.
 * <p>
 * Commands:
 * list                — show subscribed dirs and their sync state
 * add &lt;name&gt;          — subscribe to a server directory by name
 * remove &lt;name&gt;       — unsubscribe from a directory
 * refresh             — reconnect to server to re-poll available directories
 * shutdown-server     — send a remote shutdown signal to the server over the socket
 * mirror              — show the current mirror directory
 * help                — show available commands
 * exit                — shut down the client
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientCli {

    private final SyncStateService syncStateService;
    private final ClientSyncService clientSyncService;

    @Value("${sync.client.mirror-dir}")
    private String mirrorDir;

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

        var entries = syncStateService.findAll();
        var dirNames = entries.stream().map(SyncHandshakeEntry::dirName).toList();

        switch (command) {
            case "list" -> {
                if (entries.isEmpty()) {
                    System.out.println("  (no directories configured)");
                    return;
                }

                System.out.println("  Dir Name             | Last Sync Version |");
                System.out.println("  ---------------------|-------------------|");

                for (var entry : entries) {
                    System.out.printf("  %-20s | %-17d | %s%n",
                            entry.dirName(), entry.lastSyncVersion());
                }
            }
            case "add" -> {
                if (arg.isEmpty()) {
                    System.out.println("  Usage: add <dir-name>");
                    return;
                }
                if (dirNames.contains(arg)) {
                    System.out.printf("  Already subscribed to: %s%n", arg);
                    return;
                }
                syncStateService.registerIfAbsent(arg);
                System.out.printf("  Subscribed to: %s%n", arg);
            }
            case "remove" -> {
                if (arg.isEmpty()) {
                    System.out.println("  Usage: remove <dir-name>");
                    return;
                }
                if (!dirNames.contains(arg)) {
                    System.out.println("No such directory found");
                    return;
                }

                syncStateService.removeDirectory(arg);
                System.out.printf("  Unsubscribed from: %s%n", arg);
            }
            case "refresh" -> {
                System.out.println("  Reconnecting to server to re-poll available directories...");
                clientSyncService.reconnect();
            }
            case "shutdown-server" -> {
                System.out.println("  Sending shutdown signal to server...");
                clientSyncService.sendShutdown();
            }
            case "mirror" -> System.out.printf("  Mirror directory: %s%n", mirrorDir);
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
        System.out.println("  list                show subscribed dirs and sync state");
        System.out.println("  add <name>          subscribe to a server directory");
        System.out.println("  remove <name>       unsubscribe from a directory");
        System.out.println("  refresh             reconnect and re-poll server for available dirs");
        System.out.println("  shutdown-server     remotely shut down the server");
        System.out.println("  mirror              show current mirror directory");
        System.out.println("  help                show this message");
        System.out.println("  exit                shut down the client");
        System.out.println();
    }
}
