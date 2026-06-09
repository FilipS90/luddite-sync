# Copilot Instructions

## Build and test

- Full build: `./mvnw clean package`
- Full test suite: `./mvnw test`
- Single test class: `./mvnw -pl server-sync -Dtest=PushServiceTest test`
- Single test method: `./mvnw -pl client-sync -Dtest=ClientSyncServiceTest#reconnect test`
- Module-only builds/tests are useful here because this is a multi-module Maven project (`common-sync`, `server-sync`, `client-sync`).

## High-level architecture

- This is a three-module Maven app:
  - `common-sync` holds shared models and utilities used by both sides.
  - `server-sync` is the leader node. It owns the server SQLite database, watches local root directories, and serves file-change data over a persistent mTLS socket.
  - `client-sync` is the follower node. It keeps a local mirror, persists sync state in SQLite, and polls the server for changes.
- Both client and server store SQLite databases under `${user.home}/.luddite/...` and create the parent database directory at startup if needed.
- Startup order matters:
  - Schema initialization runs before the watcher/client runtime starts.
  - The server resumes directory watchers from persisted root-dir rows.
  - The client reconnects automatically and resumes from the last stored sync version.
- Sync protocol flow is fixed:
  1. Server advertises available root directory names.
  2. Client sends its stable `clientId`.
  3. Client sends `(dirName, lastSyncVersion)` handshake entries.
  4. Client polls each subscribed directory and applies file writes/deletes locally.
  5. Deletes are soft-acknowledged with `DELETE_ACK` before the server hard-deletes rows.
- Private root directories are authenticated separately with password hashes; `PasswordUtils.hash()` is used for SHA-256 hashing.

## Key conventions

- Use the existing package split:
  - `repository` = SQL access only
  - `service` = application logic
  - `config` = schema/bootstrap/startup wiring
  - `cli` / `ui` = runtime interaction
- Prefer the repo’s existing persistence style: `JdbcTemplate`, SQLite, and schema creation via startup runners rather than external migrations.
- Keep wire and filesystem paths normalized:
  - server-side watched paths are converted to forward slashes before being sent over the wire
  - client-side writes resolve against the mirror directory
- The server CLI expects an absolute filesystem path for `add`; private roots use `--private` plus `--pswd <password>`.
- The client CLI subscribes to server-advertised directory indexes/names from the current session state and uses `refresh` to force a reconnect/re-poll.
- `sync.client.ui` selects the client front end (`cli` or Swing); `sync.client.mirror-dir`, `sync.server.host`, and `sync.socket.*` are the key runtime settings.
- Long-running background work is intentionally separated:
  - accept loops and connection handlers run on explicit threads
  - directory watching uses a per-task virtual-thread executor
  - CLI loops run on virtual threads
