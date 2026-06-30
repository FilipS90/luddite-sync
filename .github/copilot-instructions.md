# Copilot Instructions — luddite-sync

## Build & Test Commands

```bash
# Build all modules
./mvnw clean install

# Run all tests
./mvnw test

# Run tests for a single module
./mvnw test -pl server-sync
./mvnw test -pl client-sync
./mvnw test -pl common-sync

# Run a single test class
./mvnw test -pl server-sync -Dtest=PushServiceTest

# Run a single test method
./mvnw test -pl server-sync -Dtest=PushServiceTest#methodName
```

The `KEYSTORE_PASSWORD` environment variable must be set to start either application.

## Development Notes
- Never use a full java class path in code, always use the class name and import it. This is a common code style in this project.
- Each non private method should have its own Javadoc comment and unit test.
- Follow the project's naming conventions for variables, methods, and classes.
- Before the final confirmation about changes, always build with tests, ensure both apps can start successfully.
- If you are unsure even about a small thing, do not start developing prior to confirming it with the orchestrator.
- If you are asked to push code to luddite-sync, you first need to run $env:GIT_SSH_COMMAND="ssh -i C:/Users/fstojiljko/.ssh/id_fs_github".
- Humility invites progress, and overconfidence invites decline, be skeptical about your work.
- Simplicity is the ultimate sophistication.

## Architecture

Three-module Maven project (`groupId: com.fstojilj.luddite`):

- **`common-sync`** — shared Java records (models), DTOs, and utility classes (`PasswordUtils`, `FileSystemUtils`). No Spring dependencies.
- **`server-sync`** — Spring Boot app that watches local directories and serves connected clients. Runs two listeners: an HTTP REST API (port 8080) and a raw mTLS TCP sync socket (port 8888).
- **`client-sync`** — Spring Boot app that connects to the server, mirrors subscribed directories locally, and provides a CLI (or Swing UI).

### Two Communication Channels

| Channel | Port | Purpose |
|---------|------|---------|
| HTTP REST (`DirsController`) | 8080 | Directory listing, version check, private-dir auth |
| Raw mTLS TCP (`PushService` ↔ `ClientSyncService`) | 8888 | Binary file transfer, delete ACKs — **no HTTP here** |

The socket channel uses a custom binary wire protocol documented in `PushService` and `ClientSyncService` Javadoc. Both sides use `DataInputStream`/`DataOutputStream` directly.

### Sync Flow

1. Client connects via mTLS; server advertises root dir names.
2. Client sends its stable `clientId` + subscription handshake (dir name → last known `syncVersion`).
3. Client polls every 2 seconds per subscribed dir; server responds with all records where `sync_version > lastSyncVersion`.
4. **Soft-delete handshake**: server marks deleted files with `deleted=TRUE` and a `client_ids` list → client receives the delete, removes the file, sends a `DELETE_ACK` → server removes the client from `client_ids` → hard-deletes once the list is empty.

### Server-side Change Detection

`DirWatcherService` uses two mechanisms:
- **`java.nio.file.WatchService`** (inotify) — for files directly in the root directory.
- **Periodic subdirectory scan** (default every 30 s, configurable via `sync.watcher.scan-interval-seconds`) — for nested subdirectory files.

### Database

Both modules use SQLite (`~/.luddite/server/photos.db` and `~/.luddite/client/sync.db`). Access is via `JdbcTemplate` directly — **no JPA or Spring Data**. Schemas are created on startup by `SchemaInitializer` using DDL constants in `SchemaConstants`.

### Certificates

mTLS uses `.p12` keystores/truststores bundled as classpath resources. `certs_setup.sh` / `certs_cleanup.sh` in `server-sync/` manage local certificate generation.

## Key Conventions

### Data Model
- Models are **Java records** with `@Builder` (e.g., `FileMetadata`, `RootDir`, `SyncHandshakeEntry`). Use `.toBuilder()` for copies.
- DTOs live in `common-sync` and are shared by both applications.
- No directory rows in the DB — directory structure is derived from file `relative_path` strings.

### Relative Paths
- **Canonical form is no leading separator** (e.g., `photos/2024/img.jpg`). Some older DB entries may have a leading `/` or `\` — normalize with `.replaceAll("^[/\\\\]+", "")` before comparison.
- Path separators are always normalized to `/` internally using `.replace('\\', '/')`.
- The `adjustFilePathToClientOS` method handles cross-platform conversion when writing files on the client.

### `sync_version`
- Monotonically increasing `long` per root directory, seeded from `MAX(sync_version)` at startup.
- Managed by `FileMetadataService.nextSyncVersion(int rootDirId)` using `ConcurrentHashMap.merge()`.
- A `NULL` `sync_version` means the record has never been delivered to any client.

### Private Directories
- Passwords are stored and compared as SHA-256 hex strings via `PasswordUtils.hash()`.
- The raw password is never persisted or sent over the wire; always hash first.
- The `X-Auth-Hash` HTTP header carries the hash for REST endpoints.

### Threading Model
- **Virtual threads** (`Thread.ofVirtual()`) for CLI loops (`AdminCli`, `ClientCli`) and short-lived tasks.
- **Platform threads** (`Thread.ofPlatform()`) for long-lived acceptor and sync loops in `PushService`/`ClientSyncService`.
- Background services are started in `@PostConstruct` methods.

### Dependency Injection
- Spring constructor injection via Lombok `@RequiredArgsConstructor` throughout.
- `@Value` for scalar config properties; `@ConfigurationProperties` for structured config (`SyncServerProperties`).

### UI Toggle
- `ClientCli` is annotated `@ConditionalOnProperty(name = "sync.client.ui", havingValue = "cli")`.
- Set `sync.client.ui: swing` for the Swing UI (`ClientUI`) or `cli` for the terminal CLI.

### Wire Protocol Constants
- Byte constants (`POLL = 1`, `DELETE_ACK = 2`, `PRIVATE_AUTH = 3`) are defined in `PushService` as `public static final` and mirrored as `private static final` in `ClientSyncService`. Keep them in sync manually.

### Ignored Files
- `FileMetadataService.IGNORED_FILENAMES` (case-insensitive set) gates both the watcher path and the initial scan. Add OS noise files here, not inline.
