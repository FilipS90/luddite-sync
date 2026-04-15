package com.fstojilj.luddite.sync.server.cli;

import com.fstojilj.luddite.sync.server.service.RootDirService;
import com.fstojilj.luddite.sync.server.service.SyncPollService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static java.lang.Thread.sleep;

/**
 * Interactive CLI for managing server root directories at runtime.
 * <p>
 * Commands:
 * list              — list all registered root dirs
 * listc             — list connected clients and their addresses
 * add &lt;path&gt;        — register a new root dir and start watching it
 * remove &lt;id&gt;       — stop watching and unregister a root dir by ID
 * switch-mode &lt;addr&gt; — signal a specific client to restart as a server (use 'listc' for addresses)
 * help              — show available commands
 * exit              — shut down the server
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminCli {

    private final RootDirService rootDirService;
    private final SyncPollService syncPollService;

    @PostConstruct
    public void start() {
        Thread.ofVirtual().name("admin-cli").start(this::runLoop);
    }

    @SneakyThrows
    private void runLoop() {
        sleep(300); // Wait a bit for the server to start up before accepting input
        printHelp();
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handle(line.trim());
            }
        } catch (Exception e) {
            log.error("Admin CLI error", e);
        }
    }

    private void handle(String line) {
        if (line.isEmpty()) return;

        String[] parts = line.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (command) {
            case "list" -> {
                var dirs = rootDirService.findAll();
                if (dirs.isEmpty()) {
                    System.out.println("  (no root dirs registered)");
                } else {
                    System.out.println("  ID  | Path");
                    System.out.println("  ----|-----------------------------");
                    dirs.forEach(d -> System.out.printf("  %-4d| %s%n", d.getId(), d.getAbsolutePath()));
                }
            }
            case "listc" -> {
                var clients = syncPollService.listConnectedClients();
                if (clients.isEmpty()) {
                    System.out.println("  (no clients connected)");
                } else {
                    System.out.println("  Connected clients:");
                    clients.forEach(c -> System.out.println("    - " + c));
                }
            }
            case "add" -> {
                if (arg.isEmpty()) {
                    System.out.println("  Usage: add <absolute-path>");
                    return;
                }
                try {
                    rootDirService.addRootDir(arg);
                    System.out.printf("  Added and watching: %s%n", arg);
                } catch (Exception e) {
                    System.out.printf("  Error: %s%n", e.getMessage());
                }
            }
            case "remove" -> {
                if (arg.isEmpty()) {
                    System.out.println("  Usage: remove <id>");
                    return;
                }
                try {
                    long id = Long.parseLong(arg);
                    boolean removed = rootDirService.removeRootDir(id);
                    if (removed) {
                        System.out.printf("  Removed root dir id=%d%n", id);
                    } else {
                        System.out.printf("  No root dir found with id=%d%n", id);
                    }
                } catch (NumberFormatException e) {
                    System.out.println("  Error: id must be a number");
                }
            }
            case "switch-mode" -> {
                if (arg.isEmpty()) {
                    System.out.println("  Usage: switch-mode <hardware-id>");
                    System.out.println("  Use 'listc' to see connected client hardware IDs.");
                    return;
                }
                boolean sent = syncPollService.sendResumeServerMode(arg);
                if (sent) {
                    System.out.printf("  Switch-mode signal sent to %s%n", arg);
                } else {
                    System.out.printf("  No connected client found with hardware ID: %s%n", arg);
                }
            }
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
        System.out.println("  Luddite Sync Server — Admin CLI");
        System.out.println("  --------------------------------");
        System.out.println("  list                list all registered root dirs");
        System.out.println("  listc               list connected clients and their addresses");
        System.out.println("  add <path>          register and watch a new root dir");
        System.out.println("  remove <id>         unregister a root dir by ID");
        System.out.println("  switch-mode <id>    signal a specific client to restart as a server (use 'listc' for hardware IDs)");
        System.out.println("  help                show this message");
        System.out.println("  exit                shut down the server");
        System.out.println();
    }
}
