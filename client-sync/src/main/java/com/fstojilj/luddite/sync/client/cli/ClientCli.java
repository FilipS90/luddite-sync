package com.fstojilj.luddite.sync.client.cli;

import com.fstojilj.luddite.sync.client.event.ServerDirsAvailableEvent;
import com.fstojilj.luddite.sync.client.model.ClientRootDir;
import com.fstojilj.luddite.sync.client.service.ClientSyncService;
import com.fstojilj.luddite.sync.client.service.DownloadService;
import com.fstojilj.luddite.sync.client.service.HostSettingsService;
import com.fstojilj.luddite.sync.client.service.RootDirService;
import com.fstojilj.luddite.sync.client.service.ServerApiClient;
import com.fstojilj.luddite.sync.common.dto.TreeResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.fstojilj.luddite.sync.client.service.ClientSyncService.serverDirs;
import static java.lang.Thread.sleep;

/**
 * Interactive CLI for managing client directory subscriptions at runtime.
 * <p>
 * Commands:
 * list                        — show subscribed dirs and their sync state
 * dirs                        — show the root dirs the server advertises
 * browse [dir] [subpath]      — list server-side contents; entries are numbered
 * browse &lt;n&gt; / up             — enter entry n of the last listing / go up one level
 *                               (stops at level one; 'dirs' returns to the root dir list)
 * browse ... --depth &lt;n&gt;      — list several levels at once
 * sync &lt;name&gt;                 — subscribe to a server directory by name or index
 * private &lt;name&gt; --pswd &lt;pw&gt;  — subscribe to a password-protected directory
 * download &lt;dir&gt; [subpath]    — download a dir, subdir, or file into the downloads folder
 * remove &lt;name&gt;               — unsubscribe from a directory
 * host [name]                 — show, or switch to, a server host
 * refresh                     — reconnect to server to re-poll available directories
 * mirror                      — show the current mirror directory
 * help                        — show available commands
 * exit                        — shut down the client
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "sync.client.ui", havingValue = "cli", matchIfMissing = true)
public class ClientCli {

    private static final String PSWD_FLAG = "--pswd";
    private static final String PATH_FLAG = "--path";
    private static final String DELETE_FLAG = "--delete";
    private static final String FORGET_FLAG = "--forget";
    private static final String DEPTH_FLAG = "--depth";

    /** Each directory level costs one server round-trip, so deep listings are capped. */
    private static final int MAX_BROWSE_DEPTH = 5;

    /** How long to wait for the server's verdict on a private-dir auth attempt. */
    private static final int AUTH_POLL_ATTEMPTS = 16;
    private static final long AUTH_POLL_INTERVAL_MS = 500;

    private final RootDirService rootDirService;
    private final ClientSyncService clientSyncService;
    private final ServerApiClient serverApiClient;
    private final DownloadService downloadService;
    private final HostSettingsService hostSettingsService;
    private final ApplicationContext applicationContext;

    private List<String> dirNames;

    /**
     * A server-side item shown in the most recent listing, addressable by its number.
     *
     * @param dirName root dir the item belongs to
     * @param relPath path relative to the root dir; {@code ""} for the root dir itself
     * @param isFile  whether the item is a file rather than a directory
     * @param depth   nesting level below the listed location, for indentation
     */
    private record Entry(String dirName, String relPath, boolean isFile, int depth) {
        Entry(String dirName, String relPath, boolean isFile) {
            this(dirName, relPath, isFile, 0);
        }
    }

    /** Location of the most recent listing; {@code null} dirName means the server's root dirs. */
    private Entry location = new Entry(null, "", false);
    private List<Entry> listing = List.of();

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
        String arg = parts.length > 1 ? parts[1].trim() : "";

        var entries = rootDirService.findAll();
        dirNames = entries.stream().map(ClientRootDir::dirName).toList();

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
            case "dirs" -> browseRoots();
            case "browse", "tree" -> browse(arg);
            case "up" -> browse("..");
            case "sync" -> sync(arg);
            case "private" -> addPrivate(arg);
            case "download" -> download(arg);
            case "remove" -> remove(arg);
            case "host" -> host(arg);
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

    // ── browse ────────────────────────────────────────────────────────────────

    /**
     * Lists server-side contents. Every listing is numbered, and a bare number refers to
     * that entry in the most recent listing — at the top level that is the server's root
     * dirs, deeper down it is whatever {@code browse} last printed.
     *
     * @param arg empty to re-list the current location, {@code ..} to go up one level,
     *            {@code <n>} to enter entry n of the last listing, or
     *            {@code <dir-name> [subpath]} to jump to an absolute location; any form
     *            may end with {@code --depth <n>} to list several levels at once
     */
    private void browse(String arg) {
        int depth = parseDepth(arg);
        if (depth < 0) return;
        arg = stripFlag(arg, DEPTH_FLAG);

        if (arg.isEmpty()) {
            if (location.dirName() == null) browseRoots();
            else browseInto(location.dirName(), location.relPath(), depth);
            return;
        }
        if (arg.equals("..") || arg.equalsIgnoreCase("up")) {
            if (location.dirName() == null) {
                System.out.println("  Already at the top level — these are the server's root dirs");
            } else if (location.relPath().isEmpty()) {
                System.out.printf("  Already at level one of '%s' — use 'dirs' to list the server's root dirs%n",
                        location.dirName());
            } else {
                browseInto(location.dirName(), parentOf(location.relPath()), depth);
            }
            return;
        }

        String[] tokens = arg.split("\\s+", 2);
        if (tokens.length == 1 && tokens[0].matches("\\d+")) {
            Entry entry = resolveListingIndex(tokens[0]);
            if (entry == null) return;
            if (entry.isFile()) {
                System.out.printf("  '%s' is a file — use 'download %s' to fetch it%n",
                        lastSegment(entry.relPath()), tokens[0]);
                return;
            }
            browseInto(entry.dirName(), entry.relPath(), depth);
            return;
        }

        String dirName = resolveDirToken(tokens[0]);
        if (dirName == null) return;
        browseInto(dirName, tokens.length > 1 ? normalizeSubPath(tokens[1]) : "", depth);
    }

    /**
     * Reads the {@code --depth} flag, defaulting to a single level.
     *
     * @return the depth, or {@code -1} after printing an error for an invalid value
     */
    private int parseDepth(String arg) {
        String value = extractFlagValue(arg, DEPTH_FLAG);
        if (value == null) return 1;
        if (!value.matches("\\d+") || Integer.parseInt(value) < 1 || Integer.parseInt(value) > MAX_BROWSE_DEPTH) {
            System.out.printf("  %s must be a number from 1 to %d%n", DEPTH_FLAG, MAX_BROWSE_DEPTH);
            return -1;
        }
        return Integer.parseInt(value);
    }

    /** Lists the server's root dirs as the current, numbered listing. */
    private void browseRoots() {
        printAvailableDirs(serverDirs);
    }

    /**
     * Fetches and prints a server directory down to {@code depth} levels, making every
     * entry shown — at any level — part of the current numbered listing.
     */
    private void browseInto(String dirName, String subPath, int depth) {
        String path = subPath.isEmpty() ? dirName : dirName + "/" + subPath;
        String passwordHash = rootDirService.getPasswordHash(dirName);

        location = new Entry(dirName, subPath, false);
        List<Entry> entries = new ArrayList<>();
        collectEntries(dirName, subPath, passwordHash, depth, 0, entries);
        listing = List.copyOf(entries);

        System.out.printf("  %s%n", path);
        if (listing.isEmpty()) {
            System.out.println("  (empty, or access denied)");
            return;
        }
        for (int i = 0; i < listing.size(); i++) {
            Entry entry = listing.get(i);
            System.out.printf("    %d. %s[%s] %s%n", i + 1, "  ".repeat(entry.depth()),
                    entry.isFile() ? "file" : "dir ", lastSegment(entry.relPath()));
        }
        System.out.println("  'browse <n>' enters a dir, 'download <n>' fetches an entry, 'up' goes back");
    }

    /**
     * Walks {@code subPath} depth-first, appending each subdirectory followed by its own
     * contents (down to {@code levelsLeft}), then the files at this level.
     */
    private void collectEntries(String dirName, String subPath, String passwordHash,
                                int levelsLeft, int depth, List<Entry> out) {
        TreeResponse tree = serverApiClient.fetchTree(dirName, subPath, passwordHash);
        for (String child : tree.childNames()) {
            String childPath = joinPath(subPath, child);
            out.add(new Entry(dirName, childPath, false, depth));
            if (levelsLeft > 1) {
                collectEntries(dirName, childPath, passwordHash, levelsLeft - 1, depth + 1, out);
            }
        }
        for (String file : tree.fileNames()) {
            out.add(new Entry(dirName, joinPath(subPath, file), true, depth));
        }
    }

    // ── sync ──────────────────────────────────────────────────────────────────

    /**
     * Subscribes to one or more public server directories, each given by name or by its
     * index in the available-dirs listing.
     *
     * @param arg comma-separated dir names or indices, optionally followed by
     *            {@code --path <absolute-path>} to mirror a single dir outside the mirror root
     */
    private void sync(String arg) {
        if (arg.isEmpty()) {
            System.out.println("  Usage: sync <dir-name|index> (comma-separate to sync multiple)");
            System.out.printf("  Available flags: %s <absolute-path>, to mirror a single dir outside %s%n",
                    PATH_FLAG, mirrorDir);
            return;
        }

        String customPath = extractFlagValue(arg, PATH_FLAG);
        String dirTokens = stripFlag(arg, PATH_FLAG);

        if (dirTokens.isEmpty()) {
            System.out.println("  Usage: sync <dir-name|index> (comma-separate to sync multiple)");
            return;
        }

        List<String> names = new ArrayList<>();
        for (String token : dirTokens.split(",")) {
            String name = resolveDirToken(token.trim());
            if (name == null) return;
            names.add(name);
        }

        if (customPath != null && names.size() > 1) {
            System.out.printf("  Error: %s applies to a single directory only%n", PATH_FLAG);
            return;
        }

        boolean subscribedAny = false;
        for (String name : names) {
            if (dirNames.contains(name)) {
                System.out.printf("  Already subscribed to: %s%n", name);
                continue;
            }
            if (customPath != null) {
                rootDirService.registerWithCustomPath(name, Path.of(customPath).toAbsolutePath().normalize().toString());
            } else {
                rootDirService.registerWithDefaultPath(name);
            }
            System.out.printf("  Subscribed to: %s%n", name);
            subscribedAny = true;
        }

        if (subscribedAny) {
            clientSyncService.reconnect();
        }
    }

    /**
     * Subscribes to a password-protected server directory and reports the server's verdict.
     *
     * @param arg {@code <dir-name> --pswd <password>}; the password runs to the end of the line
     */
    private void addPrivate(String arg) {
        String password = extractFlagValue(arg, PSWD_FLAG);
        String dirName = stripFlag(arg, PSWD_FLAG);

        if (dirName.isEmpty() || password == null || password.isEmpty()) {
            System.out.printf("  Usage: private <dir-name> %s <password>%n", PSWD_FLAG);
            return;
        }
        if (dirNames.contains(dirName)) {
            System.out.printf("  Already subscribed to: %s%n", dirName);
            return;
        }

        System.out.printf("  Requesting access to private dir: %s%n", dirName);
        clientSyncService.requestPrivateDir(dirName, password);

        Boolean granted = awaitAuthResult(dirName);
        if (Boolean.TRUE.equals(granted)) {
            System.out.printf("  Access granted to private dir: %s%n", dirName);
        } else if (Boolean.FALSE.equals(granted)) {
            System.out.printf("  Access denied to '%s' — wrong directory name or password.%n", dirName);
            rootDirService.remove(dirName);
        } else {
            System.out.printf("  Could not confirm access to '%s' — will retry on next reconnect.%n", dirName);
        }
    }

    /**
     * Waits for the reconnect triggered by a private-dir request to produce an auth result.
     *
     * @return {@code true} if granted, {@code false} if denied, {@code null} if no verdict arrived
     */
    private Boolean awaitAuthResult(String dirName) {
        for (int i = 0; i < AUTH_POLL_ATTEMPTS; i++) {
            try {
                Thread.sleep(AUTH_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            Map<String, Boolean> results = clientSyncService.getPrivateAuthResults();
            if (results.containsKey(dirName)) {
                return results.get(dirName);
            }
        }
        return null;
    }

    // ── download ──────────────────────────────────────────────────────────────

    /**
     * Downloads a root dir, subdirectory, or single file into {@code <mirror>/downloads}.
     *
     * @param arg {@code <n>} for entry n of the last listing, or {@code <dir-name> [subpath]}
     */
    private void download(String arg) {
        if (arg.isEmpty()) {
            System.out.println("  Usage: download <n> | download <dir-name> [subpath]");
            return;
        }

        String[] tokens = arg.split("\\s+", 2);
        String dirName;
        String subPath;
        boolean isFile;
        if (tokens.length == 1 && tokens[0].matches("\\d+")) {
            Entry entry = resolveListingIndex(tokens[0]);
            if (entry == null) return;
            dirName = entry.dirName();
            subPath = entry.relPath();
            isFile = entry.isFile();
        } else {
            dirName = resolveDirToken(tokens[0]);
            if (dirName == null) return;
            subPath = tokens.length > 1 ? normalizeSubPath(tokens[1]) : "";
            isFile = isRemoteFile(dirName, subPath);
        }

        String target = subPath.isEmpty() ? dirName : dirName + "/" + subPath;
        System.out.printf("  Downloading: %s ...%n", target);

        int count = downloadService.download(dirName, subPath, isFile);
        if (count > 0) {
            System.out.printf("  Downloaded %d file(s) to %s%n", count, Path.of(mirrorDir, "downloads"));
        } else {
            System.out.printf("  Download failed or found no files: %s%n", target);
        }
    }

    /**
     * Asks the server whether {@code subPath} names a file, by looking for it among its
     * parent's file entries. An empty subpath is the root dir itself, never a file.
     */
    private boolean isRemoteFile(String dirName, String subPath) {
        if (subPath.isEmpty()) return false;

        int lastSlash = subPath.lastIndexOf('/');
        String parent = lastSlash < 0 ? "" : subPath.substring(0, lastSlash);
        String name = lastSlash < 0 ? subPath : subPath.substring(lastSlash + 1);

        return serverApiClient.fetchTree(dirName, parent, rootDirService.getPasswordHash(dirName))
                .fileNames().contains(name);
    }

    // ── remove ────────────────────────────────────────────────────────────────

    /**
     * Unsubscribes from a directory, optionally deleting its local mirror files.
     *
     * @param arg {@code <dir-name> [--delete]}
     */
    private void remove(String arg) {
        boolean deleteLocalFiles = containsFlag(arg, DELETE_FLAG);
        String dirName = stripFlag(arg, DELETE_FLAG);

        if (dirName.isEmpty()) {
            System.out.printf("  Usage (unsubscribe from dir): remove <dir-name> [%s]%n", DELETE_FLAG);
            System.out.printf("  %s also deletes the local mirror files; without it they are kept%n", DELETE_FLAG);
            return;
        }
        if (!dirNames.contains(dirName)) {
            System.out.println("No such directory found");
            return;
        }

        rootDirService.removeDirectory(dirName, deleteLocalFiles);
        System.out.printf(deleteLocalFiles
                ? "  Unsubscribed from '%s' and deleted its local files%n"
                : "  Unsubscribed from: %s%n", dirName);
        clientSyncService.reconnect();
    }

    // ── host ──────────────────────────────────────────────────────────────────

    /**
     * Shows the current host and history, switches to another host, or forgets one.
     *
     * @param arg empty to show current host and history, {@code <host>} to switch,
     *            or {@code --forget <host>} to drop a host from history
     */
    private void host(String arg) {
        if (containsFlag(arg, FORGET_FLAG)) {
            String target = extractFlagValue(arg, FORGET_FLAG);
            if (target == null || target.isEmpty()) {
                System.out.printf("  Usage: host %s <host>%n", FORGET_FLAG);
                return;
            }
            hostSettingsService.forget(target);
            System.out.printf("  Forgot host: %s%n", target);
            return;
        }

        if (arg.isEmpty()) {
            System.out.printf("  Current host: %s%n", hostSettingsService.getCurrentHost());
            List<String> history = hostSettingsService.getHistory();
            if (history.isEmpty()) {
                System.out.println("  (no previously used hosts)");
                return;
            }
            System.out.println("  Previously used hosts:");
            history.forEach(h -> System.out.printf("    - %s%n", h));
            return;
        }

        hostSettingsService.switchTo(arg);
        System.out.printf("  Switching to host: %s%n", arg);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves a user-supplied directory token to a server directory name. A purely numeric
     * token is treated as a 1-based index into the available-dirs listing; anything else
     * must name a public server dir or one this client is subscribed to.
     *
     * @return the directory name, or {@code null} if an index was out of range or the name
     *         is unknown
     */
    private String resolveDirToken(String token) {
        if (!token.matches("\\d+")) {
            if (serverDirs.contains(token) || dirNames.contains(token)) {
                return token;
            }
            System.out.printf("  No such directory '%s' — run 'dirs' to see what the server offers%n", token);
            return null;
        }
        int index = Integer.parseInt(token);
        if (index < 1 || index > serverDirs.size()) {
            System.out.printf("  No directory at index %d — run 'dirs' to see what the server offers%n", index);
            return null;
        }
        return serverDirs.get(index - 1);
    }

    /**
     * Resolves a 1-based number to an entry of the most recent listing. Before any listing
     * has been printed, the server's root dirs are used.
     *
     * @return the entry, or {@code null} if the number is out of range
     */
    private Entry resolveListingIndex(String token) {
        List<Entry> current = listing.isEmpty() && location.dirName() == null
                ? serverDirs.stream().map(name -> new Entry(name, "", false)).toList()
                : listing;
        int index = Integer.parseInt(token);
        if (index < 1 || index > current.size()) {
            System.out.printf("  No entry %d in the current listing — run 'browse' to see it%n", index);
            return null;
        }
        return current.get(index - 1);
    }

    private static String parentOf(String relPath) {
        int lastSlash = relPath.lastIndexOf('/');
        return lastSlash < 0 ? "" : relPath.substring(0, lastSlash);
    }

    private static String lastSegment(String relPath) {
        return relPath.substring(relPath.lastIndexOf('/') + 1);
    }

    private static String joinPath(String parent, String child) {
        return parent.isEmpty() ? child : parent + "/" + child;
    }

    /** Normalizes a user-typed subpath to the {@code '/'}-separated, unanchored form the server expects. */
    private String normalizeSubPath(String subPath) {
        return subPath.trim().replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private boolean containsFlag(String arg, String flag) {
        return arg.equals(flag) || arg.startsWith(flag + " ") || arg.contains(" " + flag);
    }

    /**
     * Returns everything after {@code flag} as that flag's value, so values may contain spaces.
     *
     * @return the trimmed value, or {@code null} if the flag is absent
     */
    private String extractFlagValue(String arg, String flag) {
        if (!containsFlag(arg, flag)) return null;
        return arg.substring(arg.indexOf(flag) + flag.length()).trim();
    }

    /** Returns {@code arg} with {@code flag} and everything after it removed. */
    private String stripFlag(String arg, String flag) {
        if (!containsFlag(arg, flag)) return arg.trim();
        return arg.substring(0, arg.indexOf(flag)).trim();
    }

    private void printHelp() {
        System.out.println();
        System.out.println("  Luddite Sync Client — CLI");
        System.out.println("  -------------------------");
        System.out.println("  list                     show subscribed dirs and sync state");
        System.out.println("  dirs                     show the root dirs the server offers");
        System.out.println("  browse [dir] [subpath]   list a server directory's contents (numbered)");
        System.out.println("  browse <n> / up          enter entry n of the last listing / go up");
        System.out.printf ("                             --depth <1-%d>  list several levels at once%n", MAX_BROWSE_DEPTH);
        System.out.println("  sync <name|index>        subscribe to a server directory");
        System.out.println("                             --path <abs>  mirror it outside the mirror dir");
        System.out.println("  private <name> --pswd <password>");
        System.out.println("                           subscribe to a password-protected directory");
        System.out.println("  download <n>|<dir> [sub] download a listing entry, dir, subdir, or file");
        System.out.println("  remove <name>            unsubscribe from a directory");
        System.out.println("                             --delete      also delete local mirror files");
        System.out.println("  host [name]              show, or switch to, a server host");
        System.out.println("                             --forget <host>  drop a host from history");
        System.out.println("  refresh                  reconnect and re-poll server for available dirs");
        System.out.println("  mirror                   show current mirror directory");
        System.out.println("  help                     show this message");
        System.out.println("  exit                     shut down the client");
        System.out.println();
    }

    @EventListener
    public void onServerDirsAvailable(ServerDirsAvailableEvent event) {
        printAvailableDirs(event.availableDirs());
    }

    /** Prints the server's root dirs and makes them the current numbered listing. */
    private void printAvailableDirs(List<String> availableDirs) {
        location = new Entry(null, "", false);
        listing = availableDirs.stream().map(name -> new Entry(name, "", false)).toList();

        System.out.println();
        System.out.println("  Server has the following directories available:");
        System.out.println("  -----------------------------------------------");
        if (availableDirs.isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (int i = 1; i <= availableDirs.size(); i++) {
                System.out.printf("  %d. %s%n", i, availableDirs.get(i - 1));
            }
        }
        System.out.println();
        System.out.println("  'sync <n>' subscribes (e.g. 'sync 2,3'), 'browse <n>' looks inside, 'download <n>' fetches");
        System.out.println();
    }
}
