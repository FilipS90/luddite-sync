# Refactor Plan: HTTP REST API + Plain Socket for File Bytes

## Problem

Every new metadata operation (dir listing, tree browsing, auth, poll) requires extending a
stateful binary protocol with a new message type and careful byte-order coordination on both
ends. Adding any new feature currently means touching the protocol parser on both sides
simultaneously.

---

## Proposed Architecture

### Before
Single persistent mTLS TCP socket handles everything:
- Available-dirs advertisement
- Subscription handshake
- Poll / file content delivery
- Delete ACKs
- Private-dir authentication

### After

**HTTP REST API** (plain HTTP, port 8080) for all control and metadata:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/dirs` | List public root directory names |
| `GET` | `/api/dirs/{name}/tree` | Immediate child subdirectories of root dir (lazy load, top level) |
| `GET` | `/api/dirs/{name}/tree?under={subPath}` | Immediate child subdirectories of `subPath` within a root dir (one level only) |
| `GET` | `/api/dirs/{name}/changes?since={v}` | Changed file metadata since version `v` (subscribed sync only) |
| `GET` | `/api/dirs/{name}/files?subdir={subPath}` | All current files under `subPath` — one-time download, no version tracking |
| `POST` | `/api/dirs/{name}/auth` | Authenticate a private dir — body: `{"passwordHash":"..."}` — returns 200 or 403 |
| `POST` | `/api/dirs/{name}/acks` | Batch delete acknowledgement — body: `["path1","path2",...]` |

**Plain TCP socket** (port 8889) for file bytes only:
- Client sends: `[4b pathLen][path UTF-8]`
- Server responds: `[8b fileSize][fileSize bytes]` — `fileSize = 0` means file not found
- One connection per file; stateless, no session

---

## mTLS: Made Optional, Not Removed

mTLS is retained as an opt-in layer for users who run luddite-sync as a personal backup
tool where they control both ends. Users who need interoperability with third-party
clients/servers should disable it.

### Mechanism

A Spring property `sync.tls.enabled` (default `false`) gates socket creation:
- `true` -> `SSLServerSocket` / `SSLSocket` (existing keystore config applies)
- `false` -> plain `ServerSocket` / `Socket` (no keystores needed)

A Maven profile `-Ptls`:
- Copies `server-keystore.p12` / `client-keystore.p12` / `truststore.p12` into the jar
- Sets `sync.tls.enabled=true` as the default in the built artifact's `application.yml`

Default build (no profile) produces a plain-socket jar with no keystores bundled.
Runtime override is always possible via `--sync.tls.enabled=true/false`.

### What Changes in Code
- `PushService` (-> `FileSocketService`): socket creation extracted to a factory method
  gated on `sync.tls.enabled`
- `ClientSyncService` file socket: same gate on the client side
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
- Private dir access: `POST /api/dirs/{name}/auth` with `{"passwordHash":"..."}` — no
  plain-text password ever on the wire; `PasswordUtils.hash()` continues to be used client-side
- 2-second poll interval (now a REST call instead of a socket message)

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

`root_dirs` table gains a `local_path TEXT` column:
- Set at subscription time via a JFileChooser dialog (see UI section below)
- `NULL` until explicitly set (subscription dialog enforces a choice)
- Replaces the global `sync.client.mirror-dir` property as the per-dir sync destination
- `last_sync_version` and all other columns unchanged

No `subscribed_subdirs` column — subdir downloads are stateless.

---

## Changes per Module

### `common-sync`
- Add API response DTO records:
  - `DirListResponse(List<String> dirs)`
  - `TreeResponse(List<String> childNames)` — immediate children only
  - `FileEntry(String qualifiedPath, long size)`
  - `FileListResponse(List<FileEntry> files)` — for `/files` one-time download endpoint
  - `ChangeRecord(String path, long size, long version, boolean deleted)`
  - `ChangeListResponse(List<ChangeRecord> records)`
- `SyncHandshakeEntry` stays as internal DB/service model (no longer sent over the wire)

### `server-sync`
- `pom.xml` — add `spring-boot-starter-web`; add `tls` Maven profile for keystore resources
- `application.yml` — add `server.port=8080`, rename `sync.socket.port` ->
  `sync.file-socket.port=8889`, add `sync.tls.enabled=false`
- New `SyncApiController` — implements all REST endpoints above
  - `GET /tree[?under=]`: walks `file_metadata` to find distinct immediate child dirs
  - `GET /files?subdir=`: returns all non-deleted `file_metadata` rows with
    `qualified_path LIKE 'name/subPath/%'`; checks private auth; no version filtering
  - `GET /changes?since=`: existing poll logic, no subdir filtering (root-dir scope only)
- `PushService` -> gutted and renamed `FileSocketService`:
  - Socket creation gated on `sync.tls.enabled`
  - Remove all binary protocol handlers
  - New protocol: read `[4b pathLen][path]`, write `[8b size][bytes]`
  - Remove `ReentrantLock`

### `client-sync`
- `pom.xml` — add `spring-boot-starter-web` (gives `RestClient`); add `tls` Maven profile
- `application.yml` — add `sync.server.api-url=http://{host}:8080`,
  `sync.server.file-socket-port=8889`, `sync.tls.enabled=false`; remove keystore defaults;
  remove `sync.client.mirror-dir`
- `ClientSyncService`:
  - Replace mTLS socket + binary protocol with `RestClient` + plain/TLS socket
  - Poll loop: `GET /changes?since=...` (uses `root_dirs.local_path` as destination)
  - New `downloadSubdir(dirName, subPath)`: `GET /files?subdir=X` -> download each file
    via socket to `~/Downloads/{subdir name}`
  - `requestPrivateDir` -> `POST /api/dirs/{name}/auth`
- `root_dirs` schema: add `local_path TEXT` in `SchemaInitializer`; remove mirror-dir wiring
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
- [ ] `common-dtos` — Add API response DTO records to `common-sync`
- [ ] `server-web-dep` — Add `spring-boot-starter-web` + `tls` Maven profile to `server-sync` pom
- [ ] `client-web-dep` — Add `spring-boot-starter-web` + `tls` Maven profile to `client-sync` pom

### Phase 2 — Server REST API
- [ ] `server-controller-dirs` — `GET /api/dirs`, `POST /api/dirs/{name}/auth` *(depends on common-dtos, server-web-dep)*
- [ ] `server-controller-tree` — `GET /api/dirs/{name}/tree[?under=]` returning immediate children only *(depends on server-controller-dirs)*
- [ ] `server-controller-files` — `GET /api/dirs/{name}/files?subdir=` with private-dir auth check *(depends on server-controller-tree)*
- [ ] `server-controller-changes` — `GET /api/dirs/{name}/changes?since=` + `POST /api/dirs/{name}/acks` *(depends on server-controller-dirs)*
- [ ] `server-socket-simplify` — `PushService` -> `FileSocketService` with TLS gate *(depends on server-web-dep)*

### Phase 3 — Client REST migration
- [ ] `client-schema-local-path` — Add `local_path TEXT` to `root_dirs` in `SchemaInitializer`
- [ ] `client-sync-svc` — Refactor `ClientSyncService` to RestClient + plain/TLS socket; add `downloadSubdir()` *(depends on client-web-dep, server-controller-changes, server-controller-files, server-socket-simplify, client-schema-local-path)*

### Phase 4 — UI
- [ ] `ui-two-panel-tree` — Rebuild left panel as expandable indented `JList` with custom cell renderer; change right panel to single-column `JList`; remove sync version; add `[ DOWNLOAD ]` button alongside existing `[ START SYNC >> ]`; wire enabled/disabled state to selection type *(depends on client-sync-svc)*
- [ ] `ui-lazy-expand` — Wire single-click to lazy-load one level via `GET .../tree[?under=]`; toggle expand/collapse *(depends on ui-two-panel-tree, server-controller-tree)*
- [ ] `ui-subscribe-flow` — Wire double-click root dir to JFileChooser dialog + optional password dialog + subscription *(depends on ui-two-panel-tree, client-schema-local-path)*
- [ ] `ui-subdir-download` — Wire double-click subdir / `[ DOWNLOAD ]` to `downloadSubdir()` *(depends on ui-lazy-expand, client-sync-svc)*

### Phase 5 — Cleanup & Tests
- [ ] `update-config` — Update `application.yml` files + `copilot-instructions.md`
- [ ] `api-tests` — Update/add tests

---

## Open Questions

1. **Auth sessions** — REST model is stateless (password hash on every `POST /auth`). Should
   a session token be returned so the client does not re-hash on every reconnect, or is
   stateless fine for the initial cut?

2. **Delete ACK client identity** — Server needs to know which client is ACKing to track
   soft-delete cleanup. Should `clientId` be sent as a header on every request, or only on
   the `POST /acks` body?

3. **Private dir changes auth** — Should `GET /changes` for a private dir require the password
   hash as a header, or is the `/auth` POST result trusted for the session?

4. **File socket port** — Keep separate port 8889 or multiplex on 8080?
   Separate port is simpler and the current plan keeps it separate.

5. **Subdir download default path** — `~/Downloads/{last segment of subdir path}` as the
   default destination. If the dir already exists, append a counter suffix or overwrite?
