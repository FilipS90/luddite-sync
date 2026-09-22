# Luddite Sync

A small, self-hosted one-way file sync. One machine runs the **server** and shares
directories; any number of machines run the **client**, subscribe to those directories,
and keep a local mirror up to date. No cloud, no accounts — just your own machines on
a private network.

- Server watches shared directories and tracks changes in a local SQLite database.
- Clients poll the server every 2 seconds and pull new, changed, and deleted files.
- Directories can be **public** (listed to every client) or **private** (password-protected,
  never listed — a client must know both the name and the password).
- Clients can also **browse** and **download** a directory, subdirectory, or single file
  without subscribing to it.
- Runs on Linux, Windows, and macOS. Both sides are plain Java 25 applications.

---

## Table of contents

1. [How it works](#how-it-works)
2. [Requirements](#requirements)
3. [Networking: VPN setup](#networking-vpn-setup)
4. [Installation](#installation)
5. [Running the server](#running-the-server)
6. [Running the client](#running-the-client)
7. [Private directories](#private-directories)
8. [Configuration reference](#configuration-reference)
9. [Where files live](#where-files-live)
10. [Building from source](#building-from-source)
11. [Architecture](#architecture)
12. [Troubleshooting](#troubleshooting)

---

## How it works

```
 ┌──────────────────────┐            REST  :8080            ┌──────────────────────┐
 │        SERVER        │ <──── dir list / tree / auth ───── │        CLIENT        │
 │                      │                                    │                      │
 │  watches root dirs   │         TCP socket  :8888          │  mirrors subscribed  │
 │  records changes in  │ <──── SYNC / FILE / DELETE_ACK ──> │  dirs under          │
 │  SQLite              │                                    │  ~/.luddite/<dir>    │
 └──────────────────────┘                                    └──────────────────────┘
```

1. On the server you `add` one or more directories. The server indexes them, watches
   the root level with OS file events, and re-scans subdirectories every 30 seconds.
2. A client connects, fetches the list of public directories over REST, and you `sync`
   the ones you want. Private directories are joined with `private <name> --pswd <pw>`.
3. The client then keeps a socket open and asks, every 2 seconds, "anything newer than
   version N?" for each subscribed directory. New and changed files are streamed down;
   deletions are mirrored too.
4. A file deleted on the server is only forgotten by the server once **every** client
   that received it has acknowledged the delete, so a client that was offline still
   gets the deletion when it comes back.

Sync is one-way: server → client. Clients never push files to the server.

### Your local copy is yours ("weak sync")

The client tracks *what the server has sent so far* (a per-directory version cursor),
not *what is currently on your disk*. It never audits the mirror folder, so you are free
to reorganise it:

| You do this locally… | …and the client will |
|---|---|
| Delete a synced file or folder | Leave it deleted. It is **not** re-downloaded on the next poll or restart. |
| Drop your own files into the mirror | Ignore them. They are never uploaded and never touched. |
| Delete the whole mirror folder | Carry on. The folder is recreated when the next new file arrives. |

Only server-side events move files afterwards:

- A file **added** on the server is delivered as usual.
- A file **modified** on the server is delivered again — even if you had deleted your
  copy, it comes back with the new content.
- A file **deleted** on the server is removed locally if still present; if you already
  deleted it nothing happens and the delete is simply acknowledged.
- A server file that lands on the **same path as one of your own files** overwrites it.

If you want a full copy again, unsubscribe and resubscribe (`remove <name>` then
`sync <name>`); that resets the cursor and the server re-sends everything.

---

## Requirements

- **Java 25** (JDK or JRE) on every machine, unless you use the packaged installers
  from the [Releases](../../releases) page, which bundle their own runtime.
- All machines must be able to reach the server on **TCP 8080** (REST) and
  **TCP 8888** (file socket). Both ports are configurable — see
  [Configuration reference](#configuration-reference).
- A private network between the machines. Luddite Sync has no TLS and no user
  accounts; it is designed to run **inside a VPN**, not on the open internet.

---

## Networking: VPN setup

Luddite Sync does not care which VPN you use — it just needs the server to be reachable
by hostname or IP. Two setups have worked well:

### Mixed Linux / Windows (or macOS) nodes → **Tailscale**

[Tailscale](https://tailscale.com/) runs on every platform, punches through NAT on
its own, and gives every node a stable name via MagicDNS.

1. Install Tailscale on the server and on each client machine, log in with the same
   account (or invite the other users to your tailnet).
2. On the server, note its Tailscale hostname (e.g. `nas`) or its `100.x.y.z` address
   — `tailscale status` shows both.
3. On each client, point it at the server:
   ```
   host nas
   ```
   (or `host 100.x.y.z`). The choice is remembered across restarts.

### All-Windows nodes → **Radmin VPN**

[Radmin VPN](https://www.radmin-vpn.com/) is Windows-only but makes joining a network
trivial: create a network on the server machine, give the name and password to the
other users, and they join with one click — no accounts, no invitations.

1. Install Radmin VPN everywhere; on the server create a network, on the clients join it.
2. The Radmin window shows each peer's VPN IP (a `26.x.y.z` address). Use the server's:
   ```
   host 26.x.y.z
   ```

### Firewall

Whichever VPN you use, allow inbound **TCP 8080** and **TCP 8888** on the server's VPN
interface. On Windows the first launch usually triggers a Windows Defender Firewall
prompt for `java` — allow it on private networks. On Linux with `ufw`:

```
sudo ufw allow in on tailscale0 to any port 8080 proto tcp
sudo ufw allow in on tailscale0 to any port 8888 proto tcp
```

---

## Installation

### Option A — packaged installers (no Java needed)

Every tagged release publishes native packages built with `jpackage`:

| Platform | Client | Server |
|----------|--------|--------|
| Linux (Debian/Ubuntu) | `luddite-client_*.deb` | `luddite-server_*.deb` |
| Windows | `LudditeClient-windows.zip` (portable) | `LudditeServer-windows.zip` (portable) |
| macOS | `LudditeClient-*.dmg` | `LudditeServer-*.dmg` |

Download from the [Releases](../../releases) page. The Windows zips are self-contained
app images — unzip anywhere and run `LudditeServer.exe` / `LudditeClient.exe`.
Packaged clients always open the desktop UI; they are launched from a menu or Explorer
with no terminal attached, so the start-up chooser described below is skipped. On a
headless box they fall back to the CLI.

### Option B — run the JARs

Build once (see [Building from source](#building-from-source)), then use the wrapper
scripts from the repository root:

```bash
./luddite.sh            # Linux / macOS — server mode
./luddite.sh client     # Linux / macOS — client mode

luddite.bat             # Windows — server mode
luddite.bat client      # Windows — client mode
```

Or run the JARs directly:

```bash
java -jar server-sync/target/server-sync-0.0.1-SNAPSHOT.jar
java -jar client-sync/target/client-sync-0.0.1-SNAPSHOT.jar
```

The wrapper scripts additionally force UTF-8 and IPv4, which matters for non-ASCII
paths and for VPN adapters that only carry IPv4.

---

## Running the server

Start the server. Once it is up, an admin prompt appears on the same console:

```
  Luddite Sync Server — Admin CLI
  --------------------------------
  list                list all registered root dirs
  listc               list connected clients and their addresses
  add <path>          register and watch a new root dir
                        --private --pswd <password>  make it password-protected
  remove <id>         unregister a root dir by ID
  port [newPort]      show, or change, the socket listening port
  help                show this message
  exit                shut down the server
```

Share a directory:

```
add /mnt/photos
add D:\Family\Videos
add /srv/tax-returns --private --pswd hunter2
```

Paths may contain spaces; do not quote them. The directory's **last path segment**
becomes its share name (`photos`, `Videos`, `tax-returns`) — that is the name clients
see and subscribe to.

Registered directories persist in the server's database and are re-watched on the
next start. `list` shows each one with its ID, name, private flag, and path;
`remove <id>` stops sharing it (files on disk are untouched).

`port <n>` moves the file socket to another port live; connected clients rediscover
it over REST when they reconnect.

---

## Running the client

The client has two front-ends:

- **`cli`** — an interactive prompt on the console.
- **`swing`** — a retro-terminal desktop window with the same features.

Which one starts is decided at launch:

1. On a headless system (no display) the CLI starts silently, whatever else is configured.
2. `--sync.client.ui=cli|swing` on the command line (or the matching env var / system
   property) wins and skips the question below.
3. Otherwise a small window asks **Desktop UI** or **CLI**. Tick *"Don't ask again for the
   next 5 launches"* to reuse the answer; after five launches the choice is forgotten and the
   window returns with the box unchecked. The counter lives in `~/.luddite/ui-choice.properties`
   — delete it to be asked again right away.

```bash
java -jar client-sync-0.0.1-SNAPSHOT.jar                         # ask (or CLI when headless)
java -jar client-sync-0.0.1-SNAPSHOT.jar --sync.client.ui=cli    # CLI, no question
java -jar client-sync-0.0.1-SNAPSHOT.jar --sync.client.ui=swing  # desktop window, no question
```

### First run: point it at your server

The client ships with a placeholder host name (`luddite-server`). Set the real one once;
it is stored in the client's database and reused on every later start:

```
host nas              # Tailscale MagicDNS name
host 26.12.34.56      # Radmin VPN IP
host 192.168.1.10     # plain LAN
```

`host` on its own shows the current host and the history of previously used ones;
`host --forget <name>` drops one from the history.

### Client CLI reference

```
  Luddite Sync Client — CLI
  -------------------------
  list                     show subscribed dirs and sync state
  dirs                     show the root dirs the server offers
  browse [dir] [subpath]   list a server directory's contents (numbered)
  browse <n> / up          enter entry n of the last listing / go up
                             --depth <1-5>  list several levels at once
  sync <name|index>        subscribe to a server directory
                             --path <abs>  mirror it outside the mirror dir
  private <name> --pswd <password>
                           subscribe to a password-protected directory
  download <n>|<dir> [sub] download a listing entry, dir, subdir, or file
  remove <name>            unsubscribe from a directory
                             --delete      also delete local mirror files
  host [name]              show, or switch to, a server host
                             --forget <host>  drop a host from history
  refresh                  reconnect and re-poll server for available dirs
  mirror                   show current mirror directory
  help                     show this message
  exit                     shut down the client
```

### Typical session

```
> dirs
  1. documents
  2. music
  3. photos
  'sync <n>' subscribes (e.g. 'sync 2,3'), 'browse <n>' looks inside, 'download <n>' fetches

> sync 3                     # or: sync photos
  Subscribed to: photos

> sync documents --path /data/docs  # mirror this one somewhere else (single dir only)
  Subscribed to: documents

> list
  Dir Name             | Last Sync Version |
  ---------------------|-------------------|
photos                 | 42                |
documents              | 7                 |
```

Everything in `photos` now lands in `~/.luddite/photos/` and stays current while the
client runs. Every listing is numbered, and the numbers are accepted anywhere a name is
(`sync 2`, `browse 3`, `download 1`).

### Browsing and one-off downloads

You do not have to subscribe to look inside a directory or grab something from it:

```
> browse photos
  photos
    1. [dir ] 2024
    2. [dir ] 2023
    3. [file] cover.jpg
  'browse <n>' enters a dir, 'download <n>' fetches an entry, 'up' goes back

> browse 1                   # enter "2024"
  photos/2024
    1. [dir ] may
    2. [file] readme.txt

> up                         # back to "photos"
> browse photos --depth 3    # whole tree, three levels deep, still numbered
> download 2                 # a single file, or a whole subtree — whichever entry 2 is
  Downloading: photos/2024/readme.txt ...
  Downloaded 1 file(s) to /home/you/.luddite/downloads
```

Downloads go to `<mirror-dir>/downloads/`. A single file is saved flat; a directory
keeps its internal structure under `downloads/<name>/`. Downloads are one-shot — they
are not kept in sync afterwards.

Large files report progress every 16 MB. While a file is in flight it is written to
`<name>.part` and only renamed once every byte has arrived, so an interrupted or reset
connection never leaves a truncated file behind — the download simply reports failure
and can be run again. A connection that sends nothing for
`sync.client.download-read-timeout-ms` is given up on.

### Unsubscribing

```
remove photos            # stop syncing; local files are kept
remove photos --delete   # stop syncing and delete the local mirror
```

---

## Private directories

A private directory is never advertised. `dirs` will not show it, and probing the REST
API for its name returns the same *404* as a directory that does not exist. To use one,
a client needs the exact name **and** the password:

```
> private tax-returns --pswd hunter2
  Requesting access to private dir: tax-returns
  Access granted to private dir: tax-returns
```

After that it behaves like any other subscription: it syncs, and `browse` / `download`
work on it. The password is stored on the client as a SHA-256 hash (the server stores
only the hash too) and is re-sent on every reconnect. A wrong name or password produces
`Access denied` and nothing is stored.

---

## Configuration reference

Both applications are Spring Boot apps, so any property can be overridden on the
command line (`--key=value`), through an environment variable (`SYNC_SOCKET_PORT=9000`),
or with an `application.yml` placed next to the JAR.

### Server

| Property | Default | Meaning |
|----------|---------|---------|
| `server.port` | `8080` | REST API port |
| `sync.socket.port` | `8888` | File-transfer socket port (also changeable live with `port`) |
| `sync.watcher.scan-interval-seconds` | `30` | How often subdirectories are re-scanned for changes |
| `spring.datasource.url` | `jdbc:sqlite:${user.home}/.luddite/server/photos.db` | Metadata database |

```bash
java -jar server-sync-0.0.1-SNAPSHOT.jar --server.port=18080 --sync.socket.port=18888
```

### Client

| Property | Default | Meaning |
|----------|---------|---------|
| `sync.client.ui` | *ask* (`cli` when headless) | `cli` or `swing`; set it to skip the start-up chooser. Headless systems always get `cli` |
| `sync.client.mirror-dir` | `${user.home}/.luddite` | Where subscribed dirs and downloads are stored |
| `sync.client.retain-local-directory` | `true` | Keep local files when the server stops sharing a directory you were subscribed to |
| `sync.client.name` | `luddite-client` | Human-readable suffix written into the client-id file |
| `sync.client.download-read-timeout-ms` | `60000` | Abandon a download when no bytes arrive for this long |
| `sync.server.api-port` | `8080` | Server REST port; must match the server's `server.port` |
| `spring.datasource.url` | `jdbc:sqlite:${user.home}/.luddite/client/sync.db` | Client state database |

The server **host** and **socket port** are *not* properties — they live in the client
database and are managed with the `host` command (the socket port is rediscovered from
the server automatically).

```bash
java -jar client-sync-0.0.1-SNAPSHOT.jar --sync.client.mirror-dir=/data/luddite --sync.client.ui=swing
```

---

## Where files live

```
~/.luddite/                     (Windows: C:\Users\<you>\.luddite\)
├── server/photos.db            server metadata (shared dirs, file index, delete acks)
├── client/sync.db              client state (subscriptions, sync versions, host history)
├── client-id                   stable client identity; delete to make the server treat this
│                               machine as a brand-new client
├── ui-choice.properties        remembered UI/CLI answer and launches left; only present while remembered
├── downloads/                  one-off downloads
├── photos/                     one mirror folder per subscribed dir …
└── documents/                  … unless it was added with --path <elsewhere>
```

The server never touches the directories it shares; it only reads them.

---

## Building from source

```bash
git clone https://github.com/FilipS90/luddite-sync.git
cd luddite-sync
./mvnw clean package            # Linux / macOS
.\mvnw.cmd clean package        # Windows
```

This builds all three modules and runs the test suite (~290 tests). Add `-DskipTests`
to skip it. Artifacts:

- `server-sync/target/server-sync-0.0.1-SNAPSHOT.jar`
- `client-sync/target/client-sync-0.0.1-SNAPSHOT.jar`

Run only one module's tests:

```bash
./mvnw -pl client-sync test
./mvnw -pl server-sync test -Dtest=DirsControllerTest
```

### End-to-end tests

`e2e/` starts a real server and real clients from the built JARs on ports 18080/18888,
with their own SQLite DBs and mirror folders under the git-ignored `e2e/.run/`, and
drives them through the CLI. It needs `bash` and `sqlite3` and does not touch
`~/.luddite`.

```bash
e2e/run-all.sh                # builds the JARs, then runs every suite below
e2e/sync.sh --no-build        # reuse the JARs already in */target
```

| Suite | Covers |
|-------|--------|
| `sync.sh` | two clients on one dir; adds at root (watcher) and in subdirs (scanner); edits; deletes and tombstone purging once every holder acks; a client offline during a delete; large-file chunking; `sync --path`; `remove` with and without `--delete`; re-subscribing; private dirs; a server restart with root and subdir changes made while it was down; the server dropping a root dir |
| `weak-sync.sh` | local deletes, untracked files and a wiped mirror never trigger a re-download; only server-side changes move files |
| `download.sh` | single files (incl. unicode names), subtrees, whole root dirs, `browse` + `download <n>`; 16 MB progress lines; overwriting; missing paths; private dirs with wrong and right passwords, including multi-file downloads that must not de-authorize the sync connection; the server stalling (read timeout, `.part` removed) and crashing mid-transfer |
| `protocol.sh` | a dir bigger than one 100-record response batch, delivered over several polls with one version per file; two clients racing for the same fresh dir; a root dir added while clients run, subscribed by index after `refresh`; the same relative path in two dirs; renames, empty files, non-ASCII names and spaces, a subtree created in one go; `browse --depth`, `browse <n>`, `up`, `list` |
| `resilience.sh` | the server and the client killed mid-sync of a large file (nothing credited, re-sent whole on return) and a stalled server that neither drops nor corrupts the transfer; private dirs re-authenticating after a server crash; the socket port moved under a live connection; a client killed mid-download and a server killed during a multi-file download (finished files kept, no `.part` left, retry completes the set); both clients offline across a set of changes |

`e2e/lib.sh` holds the reusable pieces — `start_server` / `stop_server` / `restart_server`,
`start_client N` / `stop_client N` / `restart_client N` (both take a signal, so `KILL`
simulates a crash), `server_cmd` / `client_cmd` (CLI over a FIFO), `wait_client N PATTERN`
(matches only output printed after the last `client_cmd` or `mark_client`),
`wait_for_server_db` / `wait_for_client_db`, `wait_for_client_files`, `wait_for_min_size`
(waits until a transfer is far enough along to interrupt), `client_files`,
`client_downloaded_files`, `client_db`, `server_db`, `assert_eq`, `assert_same_content`,
`assert_partial`, `assert_no_client_errors` — so a new scenario script is just
`source lib.sh` plus the steps to check. Logs and databases stay in `e2e/.run/` after a run for inspection.

Native packages are produced by the `Package Luddite Sync App` GitHub Actions workflow,
which runs on every `v*` tag (or manually via *Run workflow*) and attaches the `.deb`,
`.dmg`, and Windows `.zip` files to a draft release.

---

## Architecture

Three Maven modules:

| Module | Contents |
|--------|----------|
| `common-sync` | DTOs shared over REST, `RootDir` / `FileMetadata` models, password hashing, filesystem helpers |
| `server-sync` | Spring Boot app: `DirsController` + `ServerInfoController` (REST), `FileSocketService` (binary socket), `DirWatcherService` (file events + periodic scan), `AdminCli` |
| `client-sync` | Spring Boot app: `ClientSyncService` (poll loop), `DownloadService`, `ServerApiClient`, `HostSettingsService`, `ClientCli` and the Swing `ClientUI` |

### REST API (server, port 8080)

| Endpoint | Purpose |
|----------|---------|
| `GET /api/dirs` | Names of public root dirs |
| `GET /api/dirs/{name}/tree?under=<sub>` | Immediate child dirs and files. Header `X-Auth-Hash` required for private dirs; `404` when the dir is unknown *or* private and unauthorized |
| `POST /api/dirs/{name}/auth` | `{clientId, passwordHash}` → `200` granted, `403` denied |
| `GET /api/server/socket-port` | Port the file socket currently listens on |

### Socket protocol (server, port 8888)

Plain TCP. On connect the client sends its ID (`[4b len][utf-8 id]`), then any number of:

| Type | Client sends | Server replies |
|------|--------------|----------------|
| `SYNC` `0x01` | `[4b len][dirName][8b sinceVersion]` | `[4b count]` then per record `[1b flags][4b len][qualifiedPath][8b version][8b size][bytes]`; flag bit 0 = deleted |
| `FILE` `0x02` | `[4b len][qualifiedPath]` | `[8b size][bytes]`, size `-1` = not found / denied |
| `DELETE_ACK` `0x03` | `[4b len][qualifiedPath]` | nothing |

Private-dir access on the socket is gated by the in-memory `AuthCacheService`, which is
populated by the REST `auth` call and cleared when the client disconnects — so a client
must authenticate over REST before every socket session, which `ClientSyncService` does
automatically.

### Deletion flow

1. Server detects a deleted file → row is **soft-deleted** and tagged with the IDs of
   every client that has received the file.
2. Each client sees the record with the *deleted* flag on its next poll, removes the
   local copy, and sends `DELETE_ACK`.
3. When the last tagged client has acked, the row is **hard-deleted**.

---

## Troubleshooting

**`Connection refused` / client keeps reconnecting**
The host is wrong or the server is not reachable. Run `host` on the client to see what
it is using, then `host <correct-name-or-ip>`. Check that the VPN is up on both ends
(`tailscale status` / the Radmin window shows the peer online) and that ports 8080 and
8888 are allowed through the server's firewall.

**`dirs` is empty but the server has shares**
All shares are private, or the client's `sync.server.api-port` does not match the
server's `server.port`.

**Changes in a subfolder show up late**
Only the root level of each share gets instant OS events; subfolders are picked up by
the periodic scan (`sync.watcher.scan-interval-seconds`, default 30 s).

**Cyrillic / non-ASCII paths garbled on Windows**
Use `luddite.bat`, which switches the console to UTF-8, or start `java` with
`-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstdin.encoding=UTF-8`.

**I moved the client to a new machine and deletes are not arriving**
The client identity lives in `~/.luddite/client-id`. Copy that file along with
`client/sync.db` and the mirror folders, or simply resubscribe on the new machine.

**I deleted a synced file locally and want it back**
That is by design — see [weak sync](#your-local-copy-is-yours-weak-sync). Either wait
for the file to change on the server, or run `remove <name>` followed by `sync <name>` to
pull the whole directory again.

**Start over on the server**
Stop it and delete `~/.luddite/server/photos.db`. Shares must then be `add`ed again;
the shared files themselves are never modified.
