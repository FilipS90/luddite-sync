# Refactor Plan: HTTP REST API + Plain Socket for File Bytes

## Proposed Architecture

### Before
Single persistent mTLS TCP socket handles everything:
- Available-dirs advertisement
- Subscription handshake
- Poll / file content delivery
- Private-dir authentication

### After

**HTTP REST API** (plain HTTP by default, port 8080) for all control and metadata:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/dirs` | List public root directory names only — private dirs are never listed |
| `GET` | `/api/dirs/{name}/tree` | Immediate child subdirectory names of a root dir (lazy, top level) |
| `GET` | `/api/dirs/{name}/tree?under={subPath}` | Immediate child subdirectory names of `subPath` (one level only) |
| `POST` | `/api/sync/versions` | Version check — client sends list of dir names it is subscribed to (+ passwordHash for private dirs); server returns its current version per dir; client compares against its own stored versions and initiates socket sync for any outdated dir |
| `GET` | `/api/dirs/{name}/files?subdir={subPath}` | All current files under `subPath` — metadata list for one-time download; client fetches bytes via socket |
| `POST` | `/api/dirs/{name}/auth` | Validate a private dir — body: `{"passwordHash":"..."}` — returns 200 or 403 |

**Delete ACKs remain on the wire socket** (`DELETE_ACK` byte `0x02`) — not migrated to REST.

**Plain TCP socket** (port 8889) for all file transfer — three request types differentiated by first byte:

**SYNC request** (type `0x01`) — triggered when version check reports a dir is outdated:
```
Client → [1b 0x01][4b dirNameLen][dirName][8b sinceVersion][4b hashLen][hash (0 bytes if public dir)]
Server → [4b count]
         per record: [1b flags (bit0=deleted)][4b pathLen][qualifiedPath][8b version][8b fileSize][fileSize bytes]
```

**FILE request** (type `0x02`) — for one-time subdir downloads (after `GET /files?subdir=`):
```
Client → [1b 0x02][4b pathLen][qualifiedPath]
Server → [8b fileSize][fileSize bytes]  (fileSize=0 if not found)
```

**ACK notification** — stays over the wire as is !


---

## mTLS: Made Optional, Not Removed

mTLS is retained as an opt-in layer for users who run luddite-sync as a personal backup
tool where they control both ends. Most users will not use TLS at all. Users who need
interoperability with third-party clients/servers should leave it disabled (default).

### Scope

When `sync.tls.enabled=true`, TLS is enforced on **both**:
- The file TCP socket (`FileSocketService` → `SSLServerSocket` / `SSLSocket`)
- The REST API (Spring Boot embedded server → `server.ssl.*` config activated)

When `sync.tls.enabled=false` (default), both layers use plain connections with no
keystores required.

### Mechanism

A Maven profile `-Ptls`:
- Copies `server-keystore.p12` / `client-keystore.p12` / `truststore.p12` into the jar
- Sets `sync.tls.enabled=true` and the matching `server.ssl.*` properties in the
  built artifact's `application.yml`

If user opts into encryption during installation, the `-Ptls` profile is selected and
mTLS is enforced on both sides automatically. Runtime override is still possible via
`--sync.tls.enabled=true/false`.

### What Changes in Code
- `PushService` (-> `FileSocketService`): socket creation extracted to a factory method
  gated on `sync.tls.enabled`
- `ClientSyncService` file socket: same gate on the client side
- Server `application.yml`: `server.ssl.*` block added but activated only under `-Ptls`
- Keystore wiring in `SyncServerProperties` stays but is only exercised when TLS is enabled

---

## What Is Removed

- Binary protocol constants: `POLL`, `DELETE_ACK`, `PRIVATE_AUTH`
- The stateful connection handshake sequence in `PushService`
- `ReentrantLock` in `PushService` (file reads are now independent per connection)
- `SYNC VERSION` column in the client UI (irrelevant to the user)
- Global `sync.client.mirror-dir` property — replaced by per-root-dir `local_path`

## What Stays the Same

- SQLite schema and all repositories/services
- `DirWatcherService`, `AdminCli`
- `RootDirService`, `FileMetadataService`, `FileMetadataRepository`
- Soft-delete ACK mechanism (`client_ids`, `acknowledgeDelete`) — unchanged; `DELETE_ACK`
  byte `0x02` remains on the socket wire; not migrated to REST
- 2-second poll interval (now a REST call instead of a socket message)
- `[ SYNC PRIVATE ]` button — prompts for dir name + password; private dirs are never
  listed in `GET /api/dirs` and are not shown in the left panel; the only way to access
  a private dir is to know its name and password

---

## Two Distinct Modes: Sync vs One-Time Download

There are two fundamentally different user actions with different semantics:

| | Root dir subscribe | Subdir one-time download |
|---|---|---|
| Trigger | `[ START SYNC >> ]` or double-click root dir | `[ DOWNLOAD ]` or double-click subdir |
| Persistence | Stored in `root_dirs` with `local_path` | Fire-and-forget |
| Version tracking | Yes — `last_sync_version` updated on each poll | No |
| Ongoing updates | Yes — polls every 2 s for changes | No |
| Local destination | User-chosen via file chooser dialog | `~/Downloads/{subdir name}` |
| Private dir check | Via `POST /auth` before subscribing | Server checks on `GET /files` — returns 403 if private and unauthenticated |
| Appears in right panel | Yes | No |

Subdirs are never added to the subscribed panel. They exist only to allow selective
one-time downloads from a large root dir.

---

## Private Directory Authentication

Private dirs are not listed anywhere in the UI or in `GET /api/dirs`. The only way to
reach one is via `[ SYNC PRIVATE ]`, which prompts for the dir name and password.

### Flow
1. User enters dir name + password in the `[ SYNC PRIVATE ]` dialog
2. Client hashes the password (`PasswordUtils.hash()`) and calls `POST /api/dirs/{name}/auth`
   with body `{"passwordHash":"..."}`
3. Server returns 200 → client stores `(name, passwordHash)` in `root_dirs.password_hash`
4. All subsequent requests for that private dir include the stored hash:
   - `POST /api/sync/versions` — included inline in the dir entry (see Poll Protocol below)
   - `GET /api/dirs/{name}/changes?since=v` — sent as `X-Auth-Hash: <hash>` header
   - `GET /api/dirs/{name}/files?subdir=X` — sent as `X-Auth-Hash: <hash>` header
   - Socket SYNC request (type `0x01`) — hash included in the wire message
   - `DELETE_ACK` (type `0x02`) — unchanged; no auth needed (socket is already authenticated via mTLS or trusted connection)
5. Server checks hash against stored password for every request on a private dir;
   returns 403 if missing or incorrect

No session tokens, no cookies — auth is purely per-request via the stored hash.
`PasswordUtils.hash()` is called client-side only; the plain-text password is never stored
or sent over the wire.

---

## Sync Poll Protocol

The poll loop runs every 2 seconds:

1. `POST /api/sync/versions` (REST) — client sends the list of dir names it is subscribed to (+ passwordHash for private dirs); server returns `{dirName: currentVersion}` — **version numbers only**; the client holds its own `last_sync_version` locally and does the comparison itself
2. For each dir where `serverVersion > clientLastKnownVersion`: client opens a socket connection and sends a **SYNC request** (type `0x01`) for that dir since `lastKnownVersion`
3. Server responds on the socket with the count + all changed records (metadata + file bytes inline) since that version
4. Client writes received files to `root_dirs.local_path`, deletes files flagged as deleted, then sends a `DELETE_ACK` (`0x02`) over the socket for each deleted file
5. Client updates `root_dirs.last_sync_version` to the highest version seen

```
POST /api/sync/versions
Body: [{"dirName":"Movies"}, {"dirName":"SecretDir","passwordHash":"abc123"}]
Response: {"Movies": 45, "SecretDir": 15}

→ Client has Movies at v42, server says v45 → outdated: open socket SYNC request for Movies since v42
→ Client has SecretDir at v15, server says v15 → current: nothing to do
```

`qualifiedPath` = `{dirName}/{relativePath}` is the consistent identifier across the REST layer and the socket wire.

---

## Tree Browsing: Lazy One-Level Expansion

The left panel displays a flat indented list that expands one level at a time:

- **Root dirs** are always visible (top level, amber colour)
- **Single-click a root dir** -> calls `GET /api/dirs/{name}/tree` (no `?under=`);
  immediate children appear indented below. Click again to collapse and discard children.
- **Single-click an expanded subdir** -> calls `GET /api/dirs/{name}/tree?under={path}`;
  its immediate children appear indented below it. Click again to collapse.
- Only one level is fetched per click — the full tree is never loaded at once.
- **Double-click any subdir** -> initiates one-time download to `~/Downloads/{subdir name}`

`GET /api/dirs/{name}/tree[?under={subPath}]` returns only the immediate children of the
requested path (no depth field needed):
```json
["The_Rock", "old_movies", "shorts"]
```
Each entry is the child directory name only (not a full path). The client constructs the
qualified path by concatenating parent path + "/" + name.

`GET /api/dirs/{name}/files?subdir={relativePath}` returns all current (non-deleted) files
under that path — used for one-time downloads. No version tracking. Server returns 403 if
the root dir is private and no valid auth has been established for this client.

---

## Client `root_dirs` Schema Change

`root_dirs` table gains two new columns:

| Column | Type | Description |
|--------|------|-------------|
| `local_path` | `TEXT` | Absolute local path chosen by user at subscribe time; set via JFileChooser; never null for subscribed dirs |
| `password_hash` | `TEXT NULL` | SHA-256 hash of the private dir password; null for public dirs; set when `[ SYNC PRIVATE ]` succeeds |

`last_sync_version` and all other columns unchanged.
No `subscribed_subdirs` column — subdir downloads are stateless.

---

## Changes per Module

### `common-sync`
- Add API DTO records:
  - `DirListResponse(List<String> dirs)`
  - `TreeResponse(List<String> childNames)` — immediate children only
  - `DirVersionEntry(String dirName, String passwordHash)` — passwordHash nullable; no lastKnownVersion, server doesn't need it
  - `VersionCheckRequest(List<DirVersionEntry> dirs)`
  - `VersionCheckResponse(Map<String, Long> versions)` — dirName → current server version only
  - `FileEntry(String qualifiedPath, long size)`
  - `FileListResponse(List<FileEntry> files)` — for `/files` one-time download endpoint
- `SyncHandshakeEntry` stays as internal DB/service model (no longer sent over the wire)

### `server-sync`
- `pom.xml` — add `spring-boot-starter-web`; add `tls` Maven profile for keystore resources
  and `server.ssl.*` activation
- `application.yml` — add `server.port=8080`, rename `sync.socket.port` ->
  `sync.file-socket.port=8889`, add `sync.tls.enabled=false`
- New `SyncApiController` — implements all REST endpoints above:
  - `GET /dirs`: returns only non-private root dir names
  - `GET /dirs/{name}/tree[?under=]`: walks `file_metadata.relative_path` to find distinct
    immediate child dir segments; private dirs require `X-Auth-Hash` header
  - `POST /sync/versions`: validates auth for private dir entries; returns `Map<String, Long>`
    (dirName → current `MAX(sync_version)`); no change records in response
  - `GET /dirs/{name}/files?subdir=`: returns `FileListResponse` (metadata only); client
    fetches bytes via socket FILE request (type `0x02`); validates `X-Auth-Hash` for private dirs
  - `POST /dirs/{name}/acks`: body `AckRequest(clientId, paths)`; delegates to existing
    `acknowledgeDelete` logic unchanged
- `FileSocketService` handles both socket request types:
  - Type `0x01` SYNC: reads `[dirName, sinceVersion, optional hash]`; queries
    `findChangedSince`; streams records with inline file bytes; validates hash for private dirs
  - Type `0x02` FILE: reads `[qualifiedPath]`; writes `[size][bytes]` (stateless per file)
  - Type `0x02` DELETE_ACK (existing): unchanged — client sends `[1b 0x02][4b pathLen][path]`
    after removing a soft-deleted file; server calls `acknowledgeDelete`
  - Socket creation gated on `sync.tls.enabled`
  - Remove all binary protocol handlers except `DELETE_ACK`
  - Remove `ReentrantLock`

### `client-sync`
- `pom.xml` — add `spring-boot-starter-web` (gives `RestClient`); add `tls` Maven profile
- `application.yml` — add `sync.server.api-url=http://{host}:8080`,
  `sync.server.file-socket-port=8889`, `sync.tls.enabled=false`; remove keystore defaults;
  remove `sync.client.mirror-dir`
- `ClientSyncService`:
  - Replace mTLS socket + binary protocol with `RestClient` + plain/TLS socket
  - Poll loop (every 2 s):
    1. `POST /api/sync/versions` — send list of subscribed dir names (+ passwordHash for private); receive current version per dir; compare against local `last_sync_version`
    2. For each dir where serverVersion > `root_dirs.last_sync_version`: open socket, send
       SYNC request (type `0x01`) with dirName + sinceVersion + passwordHash if private
    3. Receive changed records + file bytes from socket; write files to `local_path`;
       delete files flagged deleted; send `DELETE_ACK` (`0x02`) over socket for each deleted file
    4. Update `root_dirs.last_sync_version`
  - New `downloadSubdir(dirName, subPath)`: `GET /api/dirs/{name}/files?subdir=X`
    → download each file via socket to `~/Downloads/{last segment of subPath}`
  - `requestPrivateDir` -> `POST /api/dirs/{name}/auth`; on success, stores `passwordHash`
    in `root_dirs.password_hash`
- `root_dirs` schema: add `local_path TEXT` and `password_hash TEXT NULL` columns in
  `SchemaInitializer`
- `application.yml` — `spring.main.web-application-type=none`

### `ClientUI` — Revised Two-Panel Layout

Layout (unchanged panel count, revised left panel behaviour):
```
[ SERVER DIRS + TREE (left, expandable) ] | [ SUBSCRIBED (right) ]
                  [ action buttons ]
```

**LEFT panel** — `JList<String>` displaying root dirs and their lazily expanded subtrees

Rendering rules:
- Root dir item: no indent, amber (`FG_AMBER`), e.g. `"Movies"`
- Depth-1 subdir: 2 spaces indent, dim green (`FG_DIM`), e.g. `"  The_Rock"`
- Depth-2 subdir: 4 spaces indent, dim green, e.g. `"    Casablanca"`
- Expanded root dirs show a collapse indicator, e.g. `"Movies [−]"` vs `"Movies [+]"` (or
  just rely on the child items being present/absent as the visual cue)
- The model stores both display string and full relative path; a custom `ListCellRenderer`
  applies the correct colour and indent per row type

Interactions:
- **Single-click root dir** -> toggle expand/collapse (lazy-load immediate children on first expand)
- **Single-click subdir** -> toggle expand/collapse one level deeper (lazy-load)
- **Double-click subdir** -> trigger one-time download to `~/Downloads/{last segment of subdir path}`
- **Double-click root dir** -> open subscribe flow (same as `[ START SYNC >> ]`)

**RIGHT panel** — `SUBSCRIBED` — `JList<String>` (replaces the old table)
- Shows subscribed root dir names only (e.g. `"Movies"`, `"Backups"`)
- Sync version removed entirely
- Double-click -> stop sync confirmation dialog (unchanged)

**Subscribe flow (double-click root dir)**:
1. If the root dir is private: show the existing password dialog (`syncPrivate` logic)
2. A JFileChooser directory-picker dialog opens, pre-populated with
   `~/.luddite/client/mirror/{dirName}` as the default path
3. Dialog has two options: **[ SELECT DIRECTORY ]** and **[ USE DEFAULT ]**
4. Chosen path is saved to `root_dirs.local_path`; dir is added to the subscribed list

**Action buttons** (below both panels):
`[ START SYNC >> ]` `[ DOWNLOAD ]` `[ SYNC PRIVATE ]` `[ STOP SYNC ]` `[ STOP & DELETE ]`
`[ REFRESH ]` `[ EXIT ]`

- `[ START SYNC >> ]` — enabled only when a root dir is selected; opens the subscribe flow
  (JFileChooser + optional password dialog). Grayed out when a subdir is selected.
- `[ DOWNLOAD ]` — enabled only when a subdir is selected; triggers one-time download to
  `~/Downloads/{subdir name}`. Grayed out when a root dir is selected.
- `[ SYNC PRIVATE ]` — unchanged (initiates auth + subscribe for a private root dir)

---

## Todo List

### Phase 1 — Foundation
- [x] `common-dtos` — Add all DTO records to `common-sync` (incl. `VersionCheckRequest/Response`, `AckRequest`)
- [x] `server-web-dep` — Add `spring-boot-starter-web` to `server-sync` pom; server on port 8080
- [x] `client-web-dep` — Add `spring-boot-starter-web` + `tls` Maven profile to `client-sync` pom

### Phase 2 — Server REST API
- [x] `server-controller-dirs` — `GET /api/dirs` (public only), `POST /api/dirs/{name}/auth`
- [x] `server-controller-versions` — `POST /api/dirs/versions`: returns `Map<String, Long>` (version numbers only), with per-entry private-dir auth
- [x] `server-controller-tree` — `GET /api/dirs/{name}/tree[?under=]` returning immediate children; `X-Auth-Hash` for private dirs
- [x] `server-controller-files` — `GET /api/dirs/{name}/files?subdir=` with `X-Auth-Hash` check *(depends on server-controller-tree)*
- [ ] `server-socket-simplify` — `PushService` -> `FileSocketService`; TLS gate; two request types: `0x01` SYNC (streams changed records + bytes since version) and `0x02` FILE (single file by path); `0x02` DELETE_ACK remains unchanged *(depends on server-web-dep)*

### Phase 3 — Client REST migration
- [x] `client-schema-cols` — Add `local_path TEXT` and `password_hash TEXT NULL` to `root_dirs` in `SchemaInitializer`
- [x] `client-sync-svc` — Refactor `ClientSyncService`: two-step poll loop (versions then changes), `downloadSubdir()`, private dir auth storage *(depends on client-web-dep, server-controller-changes, server-controller-files, server-socket-simplify, client-schema-cols)*

### Phase 4 — UI
- [x] `ui-two-panel-tree` — Rebuild left panel as expandable indented `JList` with custom cell renderer; right panel to single-column `JList`; remove sync version; add `[ DOWNLOAD ]` button; wire enabled/disabled state to selection type *(depends on client-sync-svc)*
- [x] `ui-lazy-expand` — Wire single-click to lazy-load one level via `GET .../tree[?under=]`; toggle expand/collapse *(depends on ui-two-panel-tree, server-controller-tree)*
- [x] `ui-subscribe-flow` — Wire double-click root dir / `[ START SYNC >> ]` to JFileChooser dialog + optional password dialog + subscription *(depends on ui-two-panel-tree, client-schema-cols)*
- [x] `ui-subdir-download` — Wire double-click subdir / `[ DOWNLOAD ]` to `downloadSubdir()` *(depends on ui-lazy-expand, client-sync-svc)*

### Phase 5 — Cleanup & Tests
- [ ] `update-config` — Update `application.yml` files + `copilot-instructions.md`
- [ ] `api-tests` — Update/add tests

---

## Open Questions

1. **File socket port** — Keep separate port 8889 or multiplex on 8080?
   Separate port.

2. **Subdir download destination collision** — `~/Downloads/{last segment of subdir path}`
   as the default destination. If the dir already exists, overwrite.

3. **Soft-delete connected-client tracking (deferred)** — `DirWatcherService` currently calls
   `PushService.getConnectedClientIds()` at soft-delete time to populate `client_ids`. With
   no persistent connections in the REST model, this always returns empty, breaking the
   ACK-before-hard-delete guarantee. Deferred for now; a future fix would introduce a
   `known_clients` table populated by a `/register` endpoint that clients call on startup.
   
   Answer: I believe client_ids are extracted from the port connection ?
