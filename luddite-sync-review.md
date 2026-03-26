# Luddite Sync — Code Review

## Overview

Well-structured multi-module Spring Boot 4 / Java 25 project. The architecture is clean (no REST, raw mTLS TCP socket),
virtual threads are used correctly, and the documentation is excellent. The codebase is clearly written by someone who
thought carefully about protocol design. Below is a frank breakdown of what's good, what's broken, and what could be next.

---

## 🔴 Critical Issues

### 1. DuckDNS token committed to source control
**File:** `server-sync/src/main/resources/application.yml`

```yaml
token: 83b58635-8e13-4337-b5ab-027f58eae593   # ← real secret in repo
```

This token is live and needs to be rotated. Replace with `${LUDDITE_DUCKDNS_TOKEN}` (same as you documented
in the README but didn't apply to the file).

---

### 2. Server `removeRootDir` deletes the source files from disk
**File:** `server-sync/.../service/RootDirService.java`

```java
FileSystemUtils.deleteDirectoryRecursively(java.nio.file.Path.of(absolutePath));
```

Calling `remove 1` from the admin CLI will **permanently delete `/home/filips90/nature_photos`** (or whatever is
registered). The watcher should stop and the DB record should be dropped, but the actual source data must never
be touched by a sync tool. Remove the `deleteDirectoryRecursively` call from `removeRootDir` on the server.

---

### 3. Broken `RootDirs` config record
**File:** `server-sync/.../config/RootDirs.java`

```java
@ConfigurationProperties("sync.server.root-dirs")
public record RootDirs(Set<String> rootDirAbsolutePaths) {}
```

This binding is incorrect. The config property `sync.server.root-dirs` is a plain list (`["/path/a", "/path/b"]`),
but Spring tries to bind it to a record field named `rootDirAbsolutePaths`, which would require the YAML key
`sync.server.root-dirs.root-dir-absolute-paths`. The record always gets an **empty set**, so
`SchemaInitializer`'s pre-seeding loop silently does nothing. The `SyncServerProperties` class (which correctly
reads the same property as `List<String> rootDirs`) should be the single source of truth — `RootDirs` should
be removed or fixed.

---

### 4. Stale files left on clients when deleted with no clients connected
**File:** `server-sync/.../service/FileMetadataService.java`

```java
if (connectedClientIds.isBlank()) {
    fileMetadataRepository.delete(rootDirId, relativeFilePath);  // hard-delete immediately
}
```

If a file is deleted on the server while no clients are connected, the row is hard-deleted and the event is
**permanently lost**. Any client that later connects will never learn about the deletion and will keep the stale
file in its mirror forever. The record should be retained with `client_ids = '*'` (or a sentinel) and cleaned
up after all known clients have acked, or after a TTL.

---

## 🟠 Bugs / Correctness Issues

### 5. Debug `println` calls left in `ClientCli`
**File:** `client-sync/.../cli/ClientCli.java` — lines 68–69

```java
System.out.println(command);
System.out.println(arg);
```

Obvious leftover debug output printed to the terminal on every command. Remove these two lines.

---

### 6. `in.available()` is unreliable on network sockets
**File:** `client-sync/.../service/ClientSyncService.java` — `pollLoop()`

```java
if (in.available() > 0) {
    byte signal = in.readByte();
```

`InputStream.available()` on a TCP socket returns bytes already buffered in the JVM, not bytes available on the
wire. It can (and frequently does) return 0 even when a `RESUME_SERVER_MODE` byte is incoming, causing the signal
to be silently missed. The server sends an out-of-band byte; the protocol needs a dedicated receiver thread or the
signal should be mixed into the normal poll-response framing.

---

### 7. Double-watcher startup in `WatcherStartupRunner`
**File:** `server-sync/.../config/WatcherStartupRunner.java`

The runner first calls `rootDirService.addRootDir(path)` (which internally calls `dirWatcherService.startWatching`),
then immediately loops over **all** dirs and calls `dirWatcherService.startWatching` again. Newly added dirs get
attempted twice. The guard inside `startWatching` saves you from crashing, but the logic is confusing. The second
loop should only iterate dirs that existed before this run (i.e. already in `knownPaths`).

---

### 8. `acknowledgeDelete` — missing lock / atomicity
**File:** `server-sync/.../repository/FileMetadataRepository.java`

The `acknowledgeDelete` method does a SELECT then an UPDATE/DELETE in two separate SQL statements without a
transaction or row lock. If two clients ack the same delete concurrently, both could read the same `client_ids`
value, both compute the same "updated" list, and one ack is silently dropped. This method must run inside a
single transaction (it already has `@Transactional` on the service method, so this is OK as long as SQLite's
default isolation is sufficient — but it's worth a comment).

---

### 9. `nextSyncVersion()` seeds from DB inside a hot path
**File:** `server-sync/.../service/FileMetadataService.java`

The `UNSEEDED` guard executes a `queryForObject` on every call until the seed is set. Two threads could both see
`UNSEEDED`, both query, and both try `compareAndSet`. It's thread-safe because of the CAS, but both DB queries
run needlessly. A `synchronized` block around the seed section is cleaner and eliminates the redundant query.

---

### 10. `FileSystemUtils.listAllFilesForDir` is misleadingly named
**File:** `server-sync/.../utils/FileSystemUtils.java`

```java
File[] files = rootDir.listFiles();
return files != null ? List.of(files) : List.of();
```

This only lists **direct children** (not all files), yet it's called from `recursiveAddFileMetadataForSubdirs`.
The recursion happens in the service, not this utility — the name implies it lists all files. Rename it to
`listDirectChildrenOf` or similar to avoid future confusion.

---

### 11. `clientIds` stored as a comma-separated string
**File:** `FileMetadata`, `FileMetadataRepository`

`client_ids` is parsed/rebuilt with `String.split(",")`. Any hardware ID that contains a comma
(unlikely but possible on some systems) would corrupt the list silently. Use a JSON array or a proper
junction table. At minimum, assert no commas when a hardware ID is first registered.

---

### 12. `SyncMessage.java` is an empty file
**File:** `common-sync/.../model/SyncMessage.java`

The file has a `package` declaration and nothing else. It's either an abandoned stub or a forgotten TODO.
Either implement it or delete it to keep the module clean.

---

## 🟡 Performance & Design Concerns

### 13. Files loaded entirely into heap before sending
**File:** `SyncPollService.java` — `handlePoll()`

```java
fileBytes = Files.readAllBytes(absPath);
```

All files are read into `byte[]` before writing to the socket. For RAW photos (30–80 MB each) with multiple
concurrent clients, this rapidly exhausts heap. Use `Files.copy(path, outputStream)` with a buffered wrapper
for zero-copy streaming.

---

### 14. Global `pollLock` serializes all clients
**File:** `SyncPollService.java`

```java
private final ReentrantLock pollLock = new ReentrantLock();
```

Every client waits for the same lock, even when polling completely different root directories. Lock granularity
should be per `rootDirId` (a `ConcurrentHashMap<Long, ReentrantLock>`), so clients on different dirs can proceed
in parallel.

---

### 15. No DB index on the poll hot path
**File:** `SchemaInitializer.java` (server)

The most frequently executed query:
```sql
SELECT * FROM file_metadata
WHERE root_dir_id = ? AND (sync_version IS NULL OR sync_version > ?)
```
has no index beyond the implicit primary key. Add a composite index:
```sql
CREATE INDEX IF NOT EXISTS idx_fm_rootdir_version
ON file_metadata(root_dir_id, sync_version);
```

---

### 16. `RootDir` uses mutable `@Data` class, `FileMetadata` uses immutable record — inconsistent
`FileMetadata` is a clean `record`. `RootDir` is a `@Data` Lombok class with `@NoArgsConstructor`
/ `@AllArgsConstructor`. For a domain model that lives in the common module and crosses the wire, both
should be records (or both mutable classes). The mix is jarring and `@Data` generates `equals/hashCode` on
mutable fields.

---

### 17. Poll interval is hardcoded
**File:** `ClientSyncService.java`

```java
private static final long POLL_INTERVAL_MS = 2_000;
```

This can't be tuned without recompiling. Move it to `application.yml` as `sync.client.poll-interval-ms`.

---

## 🟢 What's Done Well

| Aspect | Notes |
|---|---|
| **Documentation** | Javadoc is detailed and accurate across all layers |
| **Virtual threads** | Correct usage for I/O-heavy workloads throughout |
| **mTLS** | Client auth required, no anonymous connections accepted |
| **Path traversal guard** | `target.startsWith(mirrorRoot)` check in `pollLoop` is solid |
| **Startup audit** | Client detects missing mirror files and resets sync version |
| **Hardware ID fallback** | Persisted UUID handles machines without DMI serials gracefully |
| **Soft-delete design** | `client_ids` list prevents premature hard-delete |
| **Reconnect logic** | Auto-reconnect with 5s back-off, picks up from last persisted version |
| **Role-swap mechanism** | Clever use of exit codes + wrapper scripts |
| **SHA-256 checksums** | Computed for all files on the server side |

---

## 💡 Suggested New Features

| Feature | Why |
|---|---|
| **Streaming file transfer** | Fixes the heap OOM risk (#13) and is necessary for RAW photo sets |
| **SQLite WAL mode** | `PRAGMA journal_mode=WAL` — dramatically better concurrent read performance for poll queries |
| **Per-dir lock** | Replaces global `pollLock`, unblocks parallel polling (#14) |
| **`sync.client.poll-interval-ms` config** | Makes the 2s interval tunable without recompiling |
| **DB index on `(root_dir_id, sync_version)`** | Immediate win for large photo libraries (#15) |
| **`WatchService` OVERFLOW handling** | Re-scan the directory tree when the OS drops events |
| **Selective sync by extension** | `sync.client.include-extensions: [jpg, png, raw, heic]` — avoid syncing temp files |
| **Transfer stats CLI command** | `stats` command showing bytes transferred, files synced, last sync time |
| **Exponential back-off on reconnect** | Current flat 5s could hammer DNS on flaky connections |
| **`application.yml` validation at startup** | Fail fast with a clear message if `sync.client.mirror-dir` or certs are missing |

---

## Quick Wins (low effort, high value)

1. Remove the two debug `println` lines in `ClientCli` (5 min)
2. Rotate the committed DuckDNS token and use `${LUDDITE_DUCKDNS_TOKEN}` in `application.yml` (5 min)
3. Remove `deleteDirectoryRecursively` from server's `removeRootDir` (2 min)
4. Add the DB index in `SchemaInitializer` (5 min)
5. Delete or implement `SyncMessage.java` (2 min)
6. Delete `RootDirs.java` and use only `SyncServerProperties` (10 min)

