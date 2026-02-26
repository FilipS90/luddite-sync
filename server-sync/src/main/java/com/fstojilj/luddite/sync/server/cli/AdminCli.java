package com.fstojilj.luddite.sync.server.cli;

import com.fstojilj.luddite.sync.server.dns.DuckDNSUpdateJob;
import com.fstojilj.luddite.sync.server.service.RootDirService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Interactive CLI for managing server root directories and DNS at runtime.
 * <p>
 * Commands:
 * list              — list all registered root dirs with their IDs
 * add &lt;path&gt;        — register a new root dir and start watching it
 * remove &lt;id&gt;       — stop watching and unregister a root dir by ID
 * dns               — show current DuckDNS domain and token
 * dns domain &lt;d&gt;    — change DuckDNS domain
 * dns token &lt;t&gt;     — change DuckDNS token
 * dns update        — trigger an immediate DuckDNS update
 * help              — show available commands
 * exit              — shut down the server
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminCli {

    private final RootDirService rootDirService;
    private final DuckDNSUpdateJob duckDNSUpdateJob;

    @PostConstruct
    public void start() {
        Thread.ofVirtual().name("admin-cli").start(this::runLoop);
    }

    private void runLoop() {
        printHelp();
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
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
            case "dns" -> handleDns(arg);
            case "help" -> printHelp();
            case "exit" -> {
                System.out.println("  Shutting down...");
                System.exit(0);
            }
            default -> System.out.printf("  Unknown command: '%s'. Type 'help' for available commands.%n", command);
        }
    }

    private void handleDns(String arg) {
        if (arg.isEmpty()) {
            System.out.printf("  Domain: %s%n", duckDNSUpdateJob.getDomain());
            System.out.printf("  Token:  %s%n", duckDNSUpdateJob.getToken());
            return;
        }

        String[] parts = arg.split("\\s+", 2);
        String subCommand = parts[0].toLowerCase();
        String value = parts.length > 1 ? parts[1] : "";

        switch (subCommand) {
            case "domain" -> {
                if (value.isEmpty()) {
                    System.out.println("  Usage: dns domain <new-domain>");
                    return;
                }
                duckDNSUpdateJob.setDomain(value);
                System.out.printf("  DuckDNS domain changed to: %s%n", value);
            }
            case "token" -> {
                if (value.isEmpty()) {
                    System.out.println("  Usage: dns token <new-token>");
                    return;
                }
                duckDNSUpdateJob.setToken(value);
                System.out.printf("  DuckDNS token changed to: %s%n", value);
            }
            case "update" -> {
                System.out.println("  Triggering DuckDNS update...");
                duckDNSUpdateJob.updateDuckDNS();
            }
            default ->
                    System.out.printf("  Unknown dns sub-command: '%s'. Try: dns domain <d> | dns token <t> | dns update%n", subCommand);
        }
    }

    private void printHelp() {
        System.out.println();
        System.out.println("  Luddite Sync Server — Admin CLI");
        System.out.println("  --------------------------------");
        System.out.println("  list              list all registered root dirs");
        System.out.println("  add <path>        register and watch a new root dir");
        System.out.println("  remove <id>       unregister a root dir by ID");
        System.out.println("  dns               show current DuckDNS domain & token");
        System.out.println("  dns domain <d>    change DuckDNS domain");
        System.out.println("  dns token <t>     change DuckDNS token");
        System.out.println("  dns update        trigger an immediate DuckDNS update");
        System.out.println("  help              show this message");
        System.out.println("  exit              shut down the server");
        System.out.println();
    }
}
