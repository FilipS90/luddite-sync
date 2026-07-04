package com.fstojilj.luddite.sync.server.cli;

import com.fstojilj.luddite.sync.common.util.PasswordUtils;
import com.fstojilj.luddite.sync.server.service.FileSocketService;
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
 * help              — show available commands
 * exit              — shut down the server
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminCli {

    private final RootDirService rootDirService;
    private final FileSocketService fileSocketService;
    private final ApplicationContext applicationContext;

    private final String PSWD_FLAG = "--pswd";
    private final String PRIVATE_FLAG = "--private";

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
                var clients = fileSocketService.listConnectedClients();
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
                    System.out.println("  Available flags: " + PRIVATE_FLAG + ", for private dirs " + PSWD_FLAG + " <password> is mandatory");
                    return;
                }
                boolean isPrivate = arg.contains(PRIVATE_FLAG);
                if (isPrivate && !arg.contains(PSWD_FLAG)) {
                    System.out.println("  Error: Private root dirs require a password. Use --pswd <password> to specify it.");
                    return;
                }

                String password = extractPassword(arg).orElse(null);

                if (isPrivate && password == null) {
                    System.out.println("  Error: Failed to extract password for private root dir. Ensure the --pswd flag is correctly formatted.");
                    return;
                }

                String absolutePath = extractDirPath(arg, isPrivate);
                System.out.println("  Adding root dir: " + absolutePath);

                String passwordHash = password != null ? PasswordUtils.hash(password) : null;

                try {
                    rootDirService.addRootDir(absolutePath, isPrivate, passwordHash);
                    System.out.printf("  Added and watching: %s%n", absolutePath);
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
        System.out.println("  help                show this message");
        System.out.println("  exit                shut down the server");
        System.out.println();
    }

    private Optional<String> extractPassword(String args) {
        int startIndex = args.indexOf(PSWD_FLAG);

        if (startIndex == -1) {
            return Optional.empty();
        }

        startIndex += PSWD_FLAG.length();

        String password = null;

        for (int i = startIndex; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c != ' ' && i == startIndex) {
                log.error("Malformed password flag, use {} <password> format", PSWD_FLAG);
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

    // TODO possible edge case where '--private' or '--pswd' is contained within path
    // would cause failure of this logic, very low probability, prioritizing more important issues
    private String extractDirPath(String args, boolean isPrivate) {
        if (!isPrivate) {
            return args;
        }

        int privateIdx = args.indexOf(PRIVATE_FLAG);
        int pswdIdx = args.indexOf(PSWD_FLAG);

        return args.substring(0, Math.min(privateIdx, pswdIdx)).trim();
    }
}
