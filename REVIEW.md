# Luddite Sync — Full Code Review

## Architecture Overview
Three-module Maven project: `common-sync`, `server-sync`, `client-sync`. Uses Spring Boot 4.0.2 on Java 25, mTLS over raw SSL sockets, SQLite for metadata, and a custom binary wire protocol. No HTTP/REST — everything goes through a persistent TCP socket. Solid concept for a p2p sync tool.

---

## 🔴 Critical Issues

### ~~1. `System.exit()` called from within Spring context~~ ✅ Fixed
`System.exit()` calls have been removed. Shutdown is now handled through the Spring context properly.

### 2. Unbounded file read into memory (`Files.readAllBytes`)
In `PushService.handlePoll()`, entire file contents are read with `Files.readAllBytes(absPath)` and stored in the `PollRecord` list before anything is sent. For large files (photos can easily be 50–100 MB) this means all batch records are in heap simultaneously.
```java
fileBytes = Files.readAllBytes(absPath); // 💥 OOM risk
```
**Fix:** Stream file content directly to the socket using `Files.newInputStream` + manual chunked write, or use Java NIO `FileChannel.transferTo`.

### 3. `MAX_FILE_SIZE = Integer.MAX_VALUE` (~2 GB) but read into a single byte array
In `ClientSyncService`:
```java
private static final long MAX_FILE_SIZE = Integer.MAX_VALUE;
byte[] fileBytes = in.readNBytes((int) fileSizeBytes); // allocates entire file in heap
```
`readNBytes(int)` allocates the full byte array at once. A 2 GB file = 2 GB RAM for one file.
**Fix:** Stream the write incrementally using a fixed buffer (e.g. 64 KB chunks) written directly to `Files.newOutputStream`.

### 4. `in.available()` check for out-of-band signals (unreliable)
```java
if (in.available() > 0) {
    byte signal = in.readByte();
```
`InputStream.available()` on a socket returns bytes already in the OS buffer — it can return 0 even if data is in transit. The `RESUME_SERVER_MODE` signal will be silently missed in most real-world conditions.
**Fix:** Use a dedicated out-of-band mechanism — a second socket/port, or reserve a flag byte in every poll response to carry control signals.

---

## 🟠 Security Issues

### 5. Keystore password stored as `String`
The password is correctly injected via `${KEYSTORE_PASSWORD}`, but stored as a `String` field. Strings are immutable and pooled — they can't be zeroed after use, leaving the password in heap until GC.
**Fix:** Store as `char[]`, zero it after `kmf.init()` and `keyStore.load()`.

### 6. Symlinks can bypass path traversal guard
The `Path.startsWith(mirrorRoot)` check after `normalize()` is correct for normal paths. However, a symlink inside `mirrorRoot` pointing outside would bypass this.
**Fix:** Add a `target.toRealPath()` check as a secondary guard (after creating parent dirs).

### 7. `wmic` is deprecated / removed on modern Windows
`HardwareIdService` runs `wmic baseboard get SerialNumber`. `wmic` was deprecated in Windows 10 v21H1 and removed in Windows 11 24H2. On those systems, the hardware ID silently falls through to the UUID fallback, meaning the client loses its stable identity on OS upgrade.
**Fix:** Replace with PowerShell: `Get-WmiObject Win32_BaseBoard | Select-Object -ExpandProperty SerialNumber`.

### 8. No TLS protocol/cipher suite pinning
```java
var ctx = SSLContext.getInstance("TLS");
```
Allows any TLS version the JVM permits.
**Fix:** Call `socket.setEnabledProtocols(new String[]{"TLSv1.3"})` after socket creation.

### 9. `hardwareId` is client-supplied, not certificate-derived
The `hardwareId` is trusted as an identifier but is an arbitrary string claimed by the client. The `client_ids` column in the DB is populated from this value.
**Fix:** Derive the client ID from the mTLS certificate's Subject DN or fingerprint instead.

---

## 🟡 Code Quality Issues

### 10. `public static List<String> serverDirs` — mutable shared state
```java
public static List<String> serverDirs = new ArrayList<>();
```
Non-thread-safe, non-final static field. Race condition between CLI thread reading it and sync loop replacing it.
**Fix:** Use `volatile` + `Collections.unmodifiableList`, or a proper synchronized accessor.

### 11. Debug `println` left in production code
In `ClientCli.handle()`:
```java
System.out.println(command);
System.out.println(arg);  // prints potentially sensitive path arguments
```
**Fix:** Remove these lines.

### 12. `@SneakyThrows` hiding `InterruptedException` in CLI loops
Both `ClientCli.runLoop()` and `AdminCli.runLoop()` use `@SneakyThrows`. The `sleep()` call can throw `InterruptedException` which is silently re-thrown unchecked.
**Fix:** Handle `InterruptedException` explicitly and restore the interrupt flag with `Thread.currentThread().interrupt()`.

### ~~13. `syncVersionCounter` is a non-thread-safe `HashMap`~~ ✅ Fixed
`syncVersionCounter` has been changed to `ConcurrentHashMap`, making the `merge()` call in `nextSyncVersion()` atomic across both the poll handler and watcher threads.

### 14. `add` CLI command: help text vs. implementation mismatch
The help text says `add <name>` but the code parses numeric indices. Additionally:
```java
if (dirNames.contains(arg)) { // arg = "1", dirNames = ["Photos"] — always false
```
The guard check against dir names will never fire since `arg` is a number string.
**Fix:** Update help text to `add <index>` and remove the broken name-based check.

### 15. `DirWatcherService.activeWatchers` check-then-act race
```java
if (activeWatchers.containsKey(pathToWatch.toString())) return;
executor.execute(() -> watch(...));
```
Not atomic. Two threads can both pass the check before either inserts.
**Fix:** Use `putIfAbsent` atomically before spawning the thread.

### ~~16. `TODO` left in production — `Thumbs.db` files~~ ✅ Fixed
A centralized `IGNORED_FILENAMES` set (`thumbs.db`, `desktop.ini`, `.ds_store`, etc.) is now checked in both `addFileMetadata` (watcher path) and `recursiveAddFileMetadataForSubdirs` (initial scan path), with case-insensitive matching.

### 17. Leading path separator stored inconsistently
In `recursiveAddFileMetadataForSubdirs`:
```java
String relativePath = File.separator + rootPath.relativize(file.toPath());
```
This stores paths with a leading `/` or `\`. `DirWatcherService` stores without one. The `replaceAll("^[/\\\\]+", "")` strip in `handlePoll` is a leaky compensating workaround.
**Fix:** Normalize at storage time — never store paths with a leading separator.

---

## 🟢 What's Done Well

- **mTLS everywhere** — both keystore and truststore are loaded, `setNeedClientAuth(true)` is set. Solid mutual auth.
- **Path traversal guard** — the `!target.startsWith(mirrorRoot)` check with normalization is correctly placed before any file writes.
- **Soft-delete with ACK** — the delete handshake (server soft-deletes → client ACKs → server hard-deletes) is well-designed for reliable replication.
- **Virtual threads** — good use of `Thread.ofVirtual()` for CLI loops and `Thread.ofPlatform()` for long-lived acceptor/sync threads.
- **Global poll lock** — the `ReentrantLock` in `PushService` prevents concurrent sync-version races between multiple clients.
- **Hardware ID with fallback** — UUID fallback persisted to `~/.luddite/client/machine-id` is a pragmatic and stable solution.
- **Wire protocol is documented** — Javadoc on both `PushService` and `ClientSyncService` clearly describes the binary format.
- **`adjustFilePathToClientOS`** — correctly converts path separators for cross-platform file writing.

---

## 💡 Feature Suggestions

1. **Compression** — Add gzip/LZ4 compression on the wire. For photos this likely won't help much, but for text/raw files it could save 40–70% bandwidth.
2. **Delta sync** — Currently the entire file is re-sent on any modify. A rolling checksum (rsync algorithm) would only send changed blocks.
3. **Ignore patterns** — Configurable glob patterns (`*.tmp`, `Thumbs.db`, `.DS_Store`) to prevent OS noise from syncing.
4. **Conflict detection** — Currently last-write-wins. A vector clock or modification timestamp comparison could surface conflicts.
5. **Progress reporting** — Large file transfers have no progress indication. A percentage display in the CLI would improve UX significantly.
6. **`luddite.bat` parity** — Verify the Windows `.bat` wrapper has the same mode-switch logic as `luddite.sh`. If it doesn't, Windows users get a degraded experience.
7. **`wmic` deprecation fix** — Already noted; use PowerShell as the primary method on Windows, `wmic` as fallback.
8. **`status` CLI command** — Show connection state, bytes transferred, last sync time per directory.



