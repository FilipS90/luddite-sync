# Luddite Sync — Architecture & Design

## Overview

Luddite Sync is a **unidirectional, real-time file synchronization system** designed to mirror files (primarily photos)
from a single server machine to one or more client machines over a **mutual TLS (mTLS) socket connection**. There are no
REST APIs — all communication happens through a persistent binary TCP socket secured with mTLS.

---

## Project Structure

```
luddite-sync/
├── common-sync/        # Shared models and utilities
├── server-sync/        # Leader node — watches files, pushes to clients
└── client-sync/        # Follower node — receives files and writes to disk
```

All modules are Java 25 / Spring Boot 4 Maven projects under a single parent POM.

---

## Modules

### common-sync

A plain library JAR (not a Spring Boot app). Contains:

- **`FileMetadata`** — model representing a tracked file (id, filename, rootDirId, relativePath, checksum, fileSize,
  syncVersion)
- **`RootDir`** — model representing a watched root directory
- **`SyncHandshakeEntry`** — record sent by the client on connect: `(dirName, lastSyncVersion)`
- **`SyncMessage`** — unused at this point, reserved for future use

No Spring beans, no migrations, no auto-configuration. Just shared models.

---

### server-sync

The **leader node**. Responsibilities:

- Watches configured root directories for file changes
- Maintains a SQLite database of file metadata and sync state
- Accepts mTLS connections from clients
- Sends files and deletes to connected clients in real time
- Handles catch-up for clients that reconnect after being offline
- Periodically updates a DuckDNS hostname so clients can find it dynamically

#### Database (`photos.db`)

Managed by Flyway. Three tables:

| Table           | Purpose                                                                |
|-----------------|------------------------------------------------------------------------|
| `root_dir`      | Registered root directories (name, absolute path)                      |
| `file_metadata` | Per-file metadata including `sync_version` (NULL until sent and ACK'd) |
| `sync_log`      | Monotonically increasing version counter; status: `PENDING` → `SYNCED` |
| `deleted_files` | Records of deleted files with the sync version at time of deletion     |

`sync_version` in `file_metadata` is deliberately stamped **after** the client ACKs receipt — not when the file is sent.
This enables automatic retry on reconnect.

#### Key Components

| Class                   | Role                                                                                                                                                 |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| `WatcherStartupRunner`  | On startup, registers root dirs from config into DB and starts file watchers                                                                         |
| `DirWatcherService`     | Uses Java `WatchService` to monitor root dirs recursively for `CREATE`, `MODIFY`, `DELETE` events. Dynamically watches newly created subdirectories. |
| `FileEventListener`     | Spring `@EventListener` — receives `FileChangeEvent`, updates `file_metadata` in DB, then publishes to `FileEventBuffer`                             |
| `FileEventBuffer`       | Per-rootDir `LinkedBlockingQueue` of pending `FileChangeEvent`s                                                                                      |
| `FileEventService`      | Facade over `FileEventBuffer`                                                                                                                        |
| `ClientPushService`     | mTLS server socket — accepts clients, advertises dirs, handles handshake, runs catch-up, drains buffer to clients, reads ACKs                        |
| `SyncVersionRepository` | Mints monotonically increasing version numbers by inserting into `sync_log`                                                                          |
| `FileMetadataService`   | CRUD over `file_metadata` and `deleted_files`                                                                                                        |
| `RootDirService`        | CRUD over `root_dir`, triggers initial file scan on new dir registration, stops watcher and cleans metadata on removal                               |
| `DuckDNSUpdateJob`      | Scheduled job — updates DuckDNS every 5 minutes so clients resolve the server hostname                                                               |
| `AdminCli`              | Interactive stdin CLI — allows adding/removing root dirs at runtime without restarting the server                                                    |

---

### client-sync

The **follower node**. Responsibilities:

- Connects to the server over mTLS
- Sends a handshake listing which directories to sync and the last known version for each
- Receives files and deletes, writes them to the local mirror directory
- Persists sync progress to a local SQLite database
- Automatically reconnects on connection loss

#### Database (`sync.db`)

Managed by Flyway. One table:

| Table        | Purpose                                                                      |
|--------------|------------------------------------------------------------------------------|
| `sync_state` | Tracks `last_sync_version` per `dir_name` — survives restarts and reconnects |

#### Key Components

| Class                  | Role                                                               |
|------------------------|--------------------------------------------------------------------|
| `ServerSyncService`    | Connects to server, sends handshake, runs receive loop, sends ACKs |
| `SyncStateRepository`  | Reads/writes `sync_state` table                                    |
| `SyncClientProperties` | Config-bound properties: `mirror-dir`, `dirs` list                 |

---

## Communication Protocol

All communication is over a **persistent mTLS TCP socket** (default port 8888). Both sides use PKCS12 keystores for
identity and a shared truststore for mutual verification. There are no REST endpoints — everything goes through the
socket.

### Wire Format

All multi-byte integers use Java `DataOutputStream` (big-endian).

#### 1. Directory Advertisement (Server → Client)

Sent by the server immediately after the TLS handshake, before the client sends anything.

```
[4 bytes]  number of available dirs (int)
per dir:
  [4 bytes]  name length (int)
  [N bytes]  name (UTF-8 string)
```

#### 2. Handshake (Client → Server)

Sent by the client after receiving the server's directory advertisement.

```
[4 bytes]  number of directory entries (int)
per entry:
  [4 bytes]  dir name length (int)
  [N bytes]  dir name (UTF-8 string)
  [8 bytes]  lastSyncVersion (long) — -1 means full sync requested
```

#### 3. File Event (Server → Client)

Sent for each file change, both during catch-up and in real time.

```
[1 byte]   event type — 1 = WRITE, 2 = DELETE
[4 bytes]  path length (int)
[N bytes]  qualified relative path (UTF-8) — e.g. "photos/2024/img.jpg"
[8 bytes]  syncVersion (long)
[8 bytes]  file size in bytes (long) — 0 for DELETE
[N bytes]  file bytes (only present for WRITE events)
```

#### 4. ACK (Client → Server)

Sent after successfully writing the file to disk and persisting local state.

```
[1 byte]   ACK byte = 3
[8 bytes]  syncVersion (long) — the version being acknowledged
```

---

## Sync Flow

### Startup

```
Server starts
  └─ WatcherStartupRunner runs
       ├─ For each path in sync.server.root-dirs:
       │    ├─ If not in DB → insert into root_dir, scan all files → insert into file_metadata
       │    └─ Start DirWatcherService for that path (recursive)
       └─ ClientPushService starts:
            ├─ Opens mTLS SSLServerSocket on port 8888
            ├─ Starts virtual thread: client-acceptor
            ├─ Starts virtual thread: buffer-drain
            └─ Starts virtual thread: pending-ack-cleanup
  └─ AdminCli starts:
       └─ Starts virtual thread: admin-cli (reads from stdin)

Client starts
  └─ ServerSyncService starts:
       └─ Starts virtual thread: server-sync-receiver → connectAndReceive()
```

### Connection & Handshake

```
Client                                    Server
  │                                          │
  │── TLS handshake (mTLS) ────────────────▶│
  │                                          │ serveClient() starts on virtual thread
  │◀── available dir names ─────────────────│  sendAvailableDirs()
  │    ["photos", "documents", ...]          │
  │                                          │
  │  client resolves dirs to subscribe:      │
  │  - if sync.client.dirs configured        │
  │    → intersect with server's list        │
  │  - if sync.client.dirs is empty          │
  │    → subscribe to all available dirs     │
  │                                          │
  │── Handshake packet ─────────────────────▶│
  │   [dirs + lastSyncVersions]              │  resolveSubscribedIds()
  │                                          │  sendCatchUp()
  │◀── catch-up WRITE/DELETE events ─────────│
  │    (files newer than lastSyncVersion)    │
  │                                          │
  │── ACK per file ────────────────────────▶│  stampSyncVersion() / recordDeletion()
  │                                          │  markSynced()
  │                                          │
  │  [client added to live sessions]         │
  │                                          │
```

### Real-time Sync (Steady State)

```
File change on server disk
  └─ DirWatcherService detects event (ENTRY_CREATE / ENTRY_MODIFY / ENTRY_DELETE)
       └─ Publishes FileChangeEvent via ApplicationEventPublisher
            └─ FileEventListener handles it:
                 ├─ Updates file_metadata in DB (add / update / delete)
                 └─ Publishes to FileEventBuffer (per rootDirId queue)

buffer-drain thread (every 50ms):
  For each rootDirId with pending events:
    If any clients are subscribed to this rootDirId:
      Drain events from queue
      For each event:
        ├─ Mint syncVersion (insert into sync_log → PENDING)
        ├─ Register in pendingAcks map
        └─ writeToClient() → send to all interested sessions

Client receives event:
  ├─ Write file to mirrorDir/qualifiedPath  (or delete)
  ├─ Persist syncStateRepository.upsert(dirName, syncVersion)
  └─ Send ACK(syncVersion) to server

Server receives ACK:
  ├─ Look up pendingAcks[syncVersion]
  ├─ stampSyncVersion() or recordDeletion() on file_metadata / deleted_files
  └─ markSynced() on sync_log
```

### Reconnect & Catch-up

If a client disconnects and reconnects:

1. Client sends handshake with its stored `lastSyncVersion` per dir
2. Server queries `file_metadata WHERE sync_version > lastSyncVersion` — returns all files that were successfully ACK'd
   by at least one client
3. Server queries `deleted_files WHERE sync_version > lastSyncVersion` — returns all deletes
4. Both are sent as catch-up events before the client enters live mode
5. Files that were sent but never ACK'd (e.g. client crashed mid-write) have `sync_version = NULL` in `file_metadata` —
   they are **not** returned in catch-up, but will be re-sent when the next live event for that file occurs, or when a
   new write/modify event arrives

---

## ACK & Retry Design

The sync version stamping is deliberately deferred:

| State                                    | Meaning                                                          |
|------------------------------------------|------------------------------------------------------------------|
| `sync_version = NULL` in `file_metadata` | File has never been successfully delivered to any client         |
| `sync_version = N` in `file_metadata`    | File was delivered and ACK'd by at least one client at version N |
| `status = PENDING` in `sync_log`         | Version minted, event sent, waiting for ACK                      |
| `status = SYNCED` in `sync_log`          | At least one client confirmed receipt                            |

**Pending ACK TTL**: If no ACK arrives within `sync.socket.pending-ack-ttl-ms` (default 20 seconds, configurable), the
entry is evicted from the in-memory `pendingAcks` map and a warning is logged. The file's `sync_version` in
`file_metadata` remains `NULL`, so the file will be retried automatically on the next live event or reconnect.

> **Note**: The TTL should be tuned based on connection speed and typical file sizes. On slow connections or with large
> files, increase `pending-ack-ttl-ms` in `application.yml`.

---

## Admin CLI

The server exposes an interactive CLI on `stdin` for managing root directories at runtime without restarting.

```
  Luddite Sync Server — Admin CLI
  --------------------------------
  list            list all registered root dirs
  add <path>      register and watch a new root dir
  remove <id>     unregister a root dir by ID
  help            show this message
  exit            shut down the server
```

- `add` — registers the path in `root_dir`, scans all existing files into `file_metadata`, starts a `DirWatcherService`
  watcher. Connected clients will receive new events from this dir if they subscribe on next reconnect.
- `remove` — stops the watcher, deletes all `file_metadata` and `deleted_files` entries for that dir, removes from
  `root_dir`.
- Dirs in `sync.server.root-dirs` in `application.yml` are registered automatically on startup. The CLI is additive for
  runtime changes.

---

## Security

- **mTLS**: Both server and client authenticate with PKCS12 certificates signed by a shared CA. The server uses
  `setNeedClientAuth(true)` — unauthenticated clients are rejected at the TLS layer.
- **Encryption in transit**: All socket data (file bytes, paths, sync versions, ACKs) is encrypted by TLS. No plaintext
  is sent over the network.
- **Integrity**: TLS guarantees data hasn't been tampered with in transit.
- **Keystores**: `server-keystore.p12`, `client-keystore.p12`, `truststore.p12` — all loaded from classpath resources.
- **Multiple clients**: All clients can share the same `client-keystore.p12` — mTLS only verifies that the cert is
  signed by the trusted CA, not that it is unique per client.
- **Encryption at rest**: Files are written to disk in plaintext. TLS only covers data in transit.
- **Password management**: The keystore password is in `application.yml`. For production use, move it to an environment
  variable:
  ```yaml
  sync:
    socket:
      password: ${LUDDITE_KEYSTORE_PASSWORD}
  ```

---

## Configuration Reference

### server-sync `application.yml`

| Property                         | Default                         | Description                                                                                                                   |
|----------------------------------|---------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `spring.datasource.url`          | —                               | SQLite DB path                                                                                                                |
| `sync.server.root-dirs`          | —                               | Optional — list of absolute paths to watch and serve on startup. Dirs can also be added/removed at runtime via the Admin CLI. |
| `sync.socket.port`               | `8888`                          | mTLS socket port for client connections                                                                                       |
| `sync.socket.keystore`           | `classpath:server-keystore.p12` | Server TLS keystore                                                                                                           |
| `sync.socket.truststore`         | `classpath:truststore.p12`      | CA truststore                                                                                                                 |
| `sync.socket.password`           | —                               | Keystore/truststore password                                                                                                  |
| `sync.socket.pending-ack-ttl-ms` | `20000`                         | TTL for unACK'd events before eviction                                                                                        |

**Example:**

```yaml
spring:
  datasource:
    url: jdbc:sqlite:C:/Users/fstojiljko/.luddite/server/photos.db

sync:
  server:
    root-dirs:
      - C:/Users/fstojiljko/photos
      - C:/Users/fstojiljko/documents
  socket:
    port: 8888
    password: ${LUDDITE_KEYSTORE_PASSWORD}
    pending-ack-ttl-ms: 20000
```

### client-sync `application.yml`

| Property                 | Default                         | Description                                                                                                                                        |
|--------------------------|---------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `spring.datasource.url`  | —                               | SQLite DB path                                                                                                                                     |
| `sync.server.host`       | `localhost`                     | Server hostname or IP                                                                                                                              |
| `sync.server.port`       | `8888`                          | Server socket port                                                                                                                                 |
| `sync.client.mirror-dir` | —                               | Local directory where synced files are written                                                                                                     |
| `sync.client.dirs`       | —                               | Optional — dir names to sync from server. If empty, subscribes to **all** dirs the server advertises. Names must match server-side root dir names. |
| `sync.socket.keystore`   | `classpath:client-keystore.p12` | Client TLS keystore                                                                                                                                |
| `sync.socket.truststore` | `classpath:truststore.p12`      | CA truststore                                                                                                                                      |
| `sync.socket.password`   | —                               | Keystore/truststore password                                                                                                                       |

**Example (specific dirs):**

```yaml
spring:
  datasource:
    url: jdbc:sqlite:/home/user/.luddite/client/sync.db

sync:
  server:
    host: luddite-sync.duckdns.org
    port: 8888
  client:
    mirror-dir: /home/user/.luddite
    dirs:
      - photos
      - documents
  socket:
    password: ${LUDDITE_KEYSTORE_PASSWORD}
```

**Example (sync everything the server has):**

```yaml
sync:
  client:
    mirror-dir: /home/user/.luddite
    dirs: [ ]   # empty = subscribe to all dirs advertised by the server
```

---

## Threading Model

All long-running tasks use **Java 21+ virtual threads** (`Thread.ofVirtual()`), making blocking I/O (socket reads, file
writes, DB queries) cheap and non-blocking at the OS level.

| Thread name                    | Module | Role                                                         |
|--------------------------------|--------|--------------------------------------------------------------|
| `client-acceptor`              | server | Accepts new mTLS client connections                          |
| `buffer-drain`                 | server | Polls FileEventBuffer every 50ms, pushes to clients          |
| `pending-ack-cleanup`          | server | Evicts stale pendingAcks every `pending-ack-ttl-ms`          |
| `admin-cli`                    | server | Reads stdin commands for managing root dirs at runtime       |
| `client-{addr}` (virtual)      | server | One per connected client — handles handshake + ACK loop      |
| `server-sync-receiver`         | client | Connects to server, runs receive loop, reconnects on failure |
| `duckDnsExecutor` (virtual)    | server | Runs DuckDNS HTTP update every 5 minutes                     |
| `dir-watcher-{path}` (virtual) | server | One per watched directory — blocks on `WatchService.take()`  |

---

## Role Swap

Since both modules share the same mTLS certificates and the data on disk is identical after a full sync, you can
**swap which machine acts as server and which acts as client** at any time. This is useful when you are physically
at the client machine and want to push new files from there.

### When to swap

- You are at the client machine and have new files you want to be the source of truth
- You want to temporarily push from the client side, then swap back when done

### How to swap

Both JARs are self-contained — no code changes needed. You just run the opposite JAR on each machine and update
`application.yml` to point at the new server.

**Step 1 — Stop both machines:**

```
# Machine A (was server): stop server-sync
# Machine B (was client): stop client-sync
```

**Step 2 — On Machine B (new server), update `application.yml`:**

```yaml
sync:
  server:
    root-dirs:
      - /path/to/your/photos   # the directory with your new files
  socket:
    port: 8888
```

Then run `server-sync.jar`.

**Step 3 — On Machine A (new client), update `application.yml`:**

```yaml
sync:
  server:
    host: <Machine B IP or hostname>
    port: 8888
  client:
    mirror-dir: /path/to/mirror
    dirs:
      - photos
```

Then run `client-sync.jar`.

**Step 4 — Swap back when done:**  
Repeat in reverse — stop both, restore original configs, restart original JARs.

### Important notes

- The new client's `sync_state` DB will have the old sync versions from when it was the server — these are irrelevant
  in client mode. The client will register the dirs fresh and catch up from version `-1` if needed.
- If the new client already has the files on disk from when it was the server, the `synced_files` audit on connect
  will see them as present and skip a full re-sync — meaning the swap is fast.
- Certificates do not need to change — both machines already have both `keystore.p12` and `truststore.p12`.
