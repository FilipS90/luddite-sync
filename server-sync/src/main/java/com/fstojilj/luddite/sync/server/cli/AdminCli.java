package com.fstojilj.luddite.sync.server.cli;

import com.fstojilj.luddite.sync.server.service.PushService;
import com.fstojilj.luddite.sync.server.service.RootDirService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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
    private final PushService pushService;
    private final ApplicationContext applicationContext;

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
                var clients = pushService.listConnectedClients();
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
                    System.out.println("  Available flags: --private, for private dirs --pswd <password> is mandatory");
                    return;
                }
                boolean isPrivate = arg.contains("--private");
                if (isPrivate && !arg.contains("--pswd")) {
                    System.out.println("  Error: Private root dirs require a password. Use --pswd <password> to specify it.");
                    return;
                }

                String password = extractPassword(arg).orElse(null);

                if (isPrivate && password == null) {
                    System.out.println("  Error: Failed to extract password for private root dir. Ensure the --pswd flag is correctly formatted.");
                    return;
                }

                String absolutePath = arg.split("\\s+")[0];
                System.out.println("  Adding root dir: " + absolutePath);
                System.out.println("  Private: " + isPrivate);
                System.out.println("  Password: " + (password != null ? password : "none"));

                try {
                    rootDirService.addRootDir(absolutePath, isPrivate, password);
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
                    int id = Integer.parseInt(arg);
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
                boolean sent = pushService.sendResumeServerMode(arg);
                if (sent) {
                    System.out.printf("  Switch-mode signal sent to %s%n", arg);
                } else {
                    System.out.printf("  No connected client found with hardware ID: %s%n", arg);
                }
            }
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

    private Optional<String> extractPassword(String args) {
        String pswdFlag = "--pswd";
        int startIndex = args.indexOf(pswdFlag);

        if (startIndex == -1) {
            return Optional.empty();
        }

        startIndex += pswdFlag.length();

        String password = null;

        for (int i = startIndex; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c != ' ' && i == startIndex) {
                log.error("Malformed password flag, use --pswd <password> format");
                return Optional.empty();
            }

            if (c == ' ' && i != startIndex) {
                password = args.substring(startIndex + 1, i);
                break;
            } else if (i == args.length() - 1) {
                password = args.substring(startIndex + 1);
                break;
            }
        }

        return Optional.ofNullable(password);
    }
}
