# Luddite Sync

A **unidirectional, real-time file synchronization system** that mirrors files (primarily photos) from a server machine
to one or more client machines over a **mutual TLS (mTLS) socket connection**. There are no REST APIs — all
communication happens through a persistent binary TCP socket secured with mTLS.

---

## Table of Contents

- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Certificate Setup](#certificate-setup)
- [Port Forwarding](#port-forwarding)
- [DuckDNS Setup](#duckdns-setup)
- [Configuration](#configuration)
- [Running](#running)
- [CLI Reference](#cli-reference)
- [Role Swap](#role-swap)
- [Communication Protocol](#communication-protocol)
- [Sync Flow](#sync-flow)
- [ACK & Retry Design](#ack--retry-design)
- [Security](#security)
- [Threading Model](#threading-model)

---

## Project Structure

```
luddite-sync/
├── common-sync/        # Shared models (library JAR, not a Spring Boot app)
├── server-sync/        # Leader node — watches files, pushes to clients
├── client-sync/        # Follower node — receives files and writes to disk
├── luddite.sh          # Wrapper script (Linux/macOS) — handles role swap automatically
└── luddite.bat         # Wrapper script (Windows) — handles role swap automatically
```

All modules are Java 25 / Spring Boot 3 Maven projects under a single parent POM.

---

## Prerequisites

- Java 25+
- Maven 3.9+
- `openssl` and `keytool` (JDK) — for certificate generation
- A [DuckDNS](https://www.duckdns.org) account — for dynamic DNS (server side only)

> **SQLite** does not need to be installed. The JDBC driver bundles the native SQLite library and extracts it at
> runtime automatically. The database directories (`~/.luddite/server/` and `~/.luddite/client/`) are also created
> automatically on first startup.

---

## Quick Start

**1. Clone and run the install script**

```bash
git clone https://github.com/FilipS90/luddite-sync.git
cd luddite-sync

# Linux/macOS
chmod +x install.sh
./install.sh

# Windows
install.bat
```

The script will prompt for:

- DuckDNS subdomain
- DuckDNS token
- Keystore password

It will generate certificates (if not already present) and write all values into both `application.yml` files.

**2. Build**

```bash
./mvnw clean package -DskipTests
```

**3. Run**

```bash
# On the server machine
./luddite.sh          # Linux/macOS
luddite.bat           # Windows

# On the client machine
java -jar client-sync/target/client-sync-0.0.1-SNAPSHOT.jar
```

---

## Certificate Setup

> The `install.sh` script handles certificate generation automatically. Only run `certs_setup.sh` manually if you need
> to regenerate certificates independently.

Luddite Sync uses mutual TLS (mTLS) — both server and client authenticate with certificates signed by a shared CA.

```bash
cd common-sync
chmod +x certs_setup.sh
./certs_setup.sh <keystore-password>
```

This generates:

- A self-signed CA
- A server certificate signed by the CA → `server-keystore.p12`
- A client certificate signed by the CA → `client-keystore.p12`
- A truststore containing the CA → `truststore.p12`

And copies them to both modules:

```
server-sync/src/main/resources/server-keystore.p12
server-sync/src/main/resources/client-keystore.p12   ← needed for role swap
server-sync/src/main/resources/truststore.p12

client-sync/src/main/resources/client-keystore.p12
client-sync/src/main/resources/server-keystore.p12   ← needed for role swap
client-sync/src/main/resources/truststore.p12
```

> **Both keystores are copied to both modules** so that either machine can run in either role without needing to
> redistribute certificates manually.

> **Multiple clients** can share the same `client-keystore.p12` — mTLS only verifies that the certificate is signed
> by the trusted CA, not that it is unique per client.

---

## Port Forwarding

The server listens for client connections on port `8888` (configurable via `sync.socket.port`). If the server machine
is behind a home router or NAT, you need to forward this port so clients can reach it from outside the local network.

### Steps (general — exact UI varies by router)

1. Log in to your router admin panel — typically at `192.168.1.1` or `192.168.0.1` in a browser
2. Find the **Port Forwarding** section (sometimes under "NAT", "Virtual Server", or "Advanced")
3. Create a new rule:

   | Field             | Value                                      |
                     |-------------------|--------------------------------------------|
   | Name              | luddite-sync                               |
   | Protocol          | TCP                                        |
   | External port     | 8888                                       |
   | Internal IP       | Local IP of the server machine             |
   | Internal port     | 8888                                       |

4. Save and apply

### Finding the server machine's local IP

```bash
# Linux/macOS
ip route get 1 | awk '{print $7}'

# Windows
ipconfig | findstr "IPv4"
```

### Verifying the port is reachable

From any external machine or phone (not on the same network), you can verify with:

```bash
nc -zv luddite-sync.duckdns.org 8888
```

Or use an online tool like [portchecker.co](https://portchecker.co).

> **Tip:** Assign a static local IP to the server machine in your router's DHCP settings so the forwarding rule
> doesn't break if the machine's local IP changes after a reboot.

---

## DuckDNS Setup

DuckDNS provides a free dynamic DNS hostname so clients can always find the server even if its IP changes.

### 1. Create a DuckDNS account

Go to [https://www.duckdns.org](https://www.duckdns.org) and log in with Google, GitHub, or Reddit.

### 2. Create a subdomain

On the DuckDNS dashboard, enter a subdomain name and click **Add Domain**.
For example: `luddite-sync` → your hostname will be `luddite-sync.duckdns.org`

### 3. Copy your token

Your token is displayed at the top of the DuckDNS dashboard after logging in. It looks like:

```
83b58635-8e13-4337-b5ab-027f58eae593
```

### 4. Configure the server

In `server-sync/src/main/resources/application.yml`:

```yaml
sync:
  dns:
    domain: luddite-sync          # your subdomain (without .duckdns.org)
    token: ${LUDDITE_DUCKDNS_TOKEN}
```

Set the token via environment variable before starting:

```bash
# Linux/macOS
export LUDDITE_DUCKDNS_TOKEN=83b58635-8e13-4337-b5ab-027f58eae593

# Windows
set LUDDITE_DUCKDNS_TOKEN=83b58635-8e13-4337-b5ab-027f58eae593
```

The server will update DuckDNS every 5 minutes automatically, and immediately on startup.

### 5. Configure the client

In `client-sync/src/main/resources/application.yml`, point the client at the DuckDNS hostname:

```yaml
sync:
  server:
    host: luddite-sync.duckdns.org
    port: 8888
```

> The client never needs a DuckDNS entry of its own — it only connects outbound to the server's hostname.

---

## Configuration

### server-sync `application.yml`

| Property                         | Default                         | Description                                                                          |
|----------------------------------|---------------------------------|--------------------------------------------------------------------------------------|
| `spring.datasource.url`          | —                               | SQLite DB path — e.g. `jdbc:sqlite:${user.home}/.luddite/server/photos.db`           |
| `sync.server.root-dirs`          | `[]`                            | Absolute paths to watch and serve on startup. Also manageable at runtime via CLI.    |
| `sync.dns.domain`                | `luddite-sync`                  | DuckDNS subdomain (without `.duckdns.org`)                                           |
| `sync.dns.token`                 | —                               | DuckDNS token from your dashboard                                                    |
| `sync.socket.port`               | `8888`                          | mTLS socket port                                                                     |
| `sync.socket.keystore`           | `classpath:server-keystore.p12` | Server TLS keystore                                                                  |
| `sync.socket.truststore`         | `classpath:truststore.p12`      | CA truststore                                                                        |
| `sync.socket.password`           | `fichony123!`                   | Keystore/truststore password — move to env var in production                         |
| `sync.socket.pending-ack-ttl-ms` | `20000`                         | Milliseconds to wait for a client ACK before evicting — increase on slow connections |

**Full example:**

```yaml
spring:
  datasource:
    url: jdbc:sqlite:${user.home}/.luddite/server/photos.db

sync:
  server:
    root-dirs:
      - /home/user/photos
      - /home/user/documents
  dns:
    domain: luddite-sync
    token: ${LUDDITE_DUCKDNS_TOKEN}
  socket:
    port: 8888
    password: ${LUDDITE_KEYSTORE_PASSWORD}
    pending-ack-ttl-ms: 20000
```

---

### client-sync `application.yml`

| Property                 | Default                         | Description                                                                |
|--------------------------|---------------------------------|----------------------------------------------------------------------------|
| `spring.datasource.url`  | —                               | SQLite DB path — e.g. `jdbc:sqlite:${user.home}/.luddite/client/sync.db`   |
| `sync.server.host`       | `localhost`                     | Server hostname or DuckDNS domain                                          |
| `sync.server.port`       | `8888`                          | Server socket port                                                         |
| `sync.client.mirror-dir` | —                               | Local directory where synced files are written                             |
| `sync.client.dirs`       | `[]`                            | Dir names to sync. If empty, subscribes to all dirs the server advertises. |
| `sync.socket.keystore`   | `classpath:client-keystore.p12` | Client TLS keystore                                                        |
| `sync.socket.truststore` | `classpath:truststore.p12`      | CA truststore                                                              |
| `sync.socket.password`   | `fichony123!`                   | Keystore/truststore password — move to env var in production               |

**Full example:**

```yaml
spring:
  datasource:
    url: jdbc:sqlite:${user.home}/.luddite/client/sync.db

sync:
  server:
    host: luddite-sync.duckdns.org
    port: 8888
  client:
    mirror-dir: ${user.home}/.luddite
    dirs:
      - photos
      - documents
  socket:
    password: ${LUDDITE_KEYSTORE_PASSWORD}
```

> `${user.home}` resolves to `C:\Users\<username>` on Windows and `/home/<username>` on Linux/macOS.
> Any absolute path works on both platforms.

---

## Running

After building, run the wrapper script from the project root. It resolves the JARs from `server-sync/target/` and
`client-sync/target/` automatically.

### Server machine

```bash
./luddite.sh          # Linux/macOS
luddite.bat           # Windows
```

### Client machine

```bash
./luddite.sh client luddite-sync.duckdns.org    # Linux/macOS
luddite.bat client luddite-sync.duckdns.org     # Windows
```

---

## CLI Reference

### Server Admin CLI

Interactive CLI available on `stdin` after the server starts.

```
  list                list all registered root dirs and connected client addresses
  add <path>          register and watch a new root dir
  remove <id>         unregister a root dir by ID
  dns                 show current DuckDNS domain and token
  dns domain <d>      change DuckDNS domain at runtime
  dns token <t>       change DuckDNS token at runtime
  dns update          trigger an immediate DuckDNS update
  switch-mode <addr>  signal a specific client to restart as a server (use 'list' to see addresses)
  help                show this message
  exit                shut down the server
```

### Client CLI

Interactive CLI available on `stdin` after the client starts.

```
  list                show subscribed dirs and sync state
  add <name>          subscribe to a server directory
  remove <name>       unsubscribe from a directory
  refresh             reconnect and re-poll server for available dirs
  shutdown-server     remotely shut down the server
  mirror              show current mirror directory
  help                show this message
  exit                shut down the client
```

---

## Role Swap

Since both JARs are deployed on both machines and both keystores are bundled in both JARs, either machine can run in
either role at any time. This is useful when you are physically at the client machine and want to push new files from
there.

### Initiating a swap (from the client)

Type `shutdown-server` in the **client CLI**:

```
shutdown-server
```

This sends a `SHUTDOWN` signal to the server over the existing mTLS socket. The server exits with code `2`. The wrapper
script on the server machine detects exit code `2` and automatically starts `client-sync.jar` instead, pointing at your
machine.

You then start `server-sync.jar` on your machine manually (or via the wrapper).

### Swapping back (from the server)

When you are done and want to restore the original roles, type `switch-mode <addr>` in the **server Admin CLI**.
Use `list` to see the address of the connected client first:

```
list
switch-mode /192.168.1.10:54321
```

This does two things atomically:

1. Sends `RESUME_SERVER_MODE` to the specified client — it exits with code `2`, wrapper restarts it as a server
2. The current server itself exits with code `3` — its wrapper restarts it as a client pointing at the original server

Both machines swap roles cleanly with no manual intervention.

### Exit codes

| Code | Meaning                                                             |
|------|---------------------------------------------------------------------|
| `0`  | Normal exit / crash — wrapper restarts in the same mode after 5s    |
| `2`  | Received `SHUTDOWN` or `RESUME_SERVER_MODE` — restart in other mode |
| `3`  | Sent `switch-mode` signal — this machine restarts as client         |

### Wrapper script usage

The wrapper scripts handle role transitions automatically based on exit codes:

```bash
# Start normally in server mode
./luddite.sh

# Start directly in client mode pointing at a specific host
./luddite.sh client 192.168.1.100
```

```bat
rem Windows equivalents
luddite.bat
luddite.bat client 192.168.1.100
```

### Important notes

- No certificate changes are needed — both keystores are already in both JARs
- The wrapper passes the correct keystore via Spring Boot args when switching modes
- The original server machine running as client will catch up from its last known sync version on reconnect
- The `synced_files` audit on connect ensures any manually deleted files are automatically re-fetched

---

## Communication Protocol

All communication is over a **persistent mTLS TCP socket** (default port `8888`). All integers use Java
`DataOutputStream` big-endian encoding.

### 1. Directory Advertisement (Server → Client)

Sent immediately after TLS handshake, before the client sends anything.

```
[4 bytes]  number of dirs (int)
per dir:
  [4 bytes]  name length (int)
  [N bytes]  name (UTF-8)
```

### 2. Handshake (Client → Server)

```
[4 bytes]  number of entries (int)
per entry:
  [4 bytes]  dir name length (int)
  [N bytes]  dir name (UTF-8)
  [8 bytes]  lastSyncVersion (long) — -1 = full sync requested
```

### 3. File Event (Server → Client)

```
[1 byte]   event type — 1=WRITE, 2=DELETE, 5=RESUME_SERVER_MODE
[4 bytes]  path length (int)                      ← only for WRITE/DELETE
[N bytes]  qualified path (UTF-8) e.g. "photos/img.jpg"
[8 bytes]  syncVersion (long)
[8 bytes]  file size (long) — 0 for DELETE
[N bytes]  file bytes — only present for WRITE
```

### 4. ACK (Client → Server)

```
[1 byte]   3 (ACK)
[8 bytes]  syncVersion (long)
```

### 5. Control Signals

| Byte | Direction       | Meaning                                              |
|------|-----------------|------------------------------------------------------|
| `3`  | Client → Server | ACK — file received and written to disk              |
| `4`  | Client → Server | SHUTDOWN — server should exit and start as client    |
| `5`  | Server → Client | RESUME_SERVER_MODE — client should restart as server |

---

## Sync Flow

### Startup & Handshake

```
Client                                      Server
  │                                            │
  │── mTLS handshake ────────────────────────▶│
  │◀── available dir names ───────────────────│
  │                                            │
  │── handshake [dirs + lastSyncVersions] ───▶│
  │◀── catch-up WRITE/DELETE events ──────────│  (files newer than lastSyncVersion)
  │── ACK per file ──────────────────────────▶│
  │                                            │
  │  [client enters live mode]                 │
```

### Real-time Sync

```
File change on disk
  └─ DirWatcherService detects event
       └─ FileEventListener updates file_metadata, pushes to FileEventBuffer
            └─ buffer-drain thread (every 50ms) drains queue
                 └─ For each subscribed client session:
                      ├─ Mint syncVersion
                      ├─ Register in pendingAcks
                      └─ Send event over socket

Client receives event
  ├─ Write/delete file on disk
  ├─ Update sync_state and synced_files in local DB
  └─ Send ACK(syncVersion)

Server receives ACK
  ├─ Stamp sync_version on file_metadata
  └─ Mark sync_log entry as SYNCED
```

---

## ACK & Retry Design

`sync_version` in `file_metadata` is stamped **only after** the client ACKs — never when the event is sent.

| State                                  | Meaning                                                 |
|----------------------------------------|---------------------------------------------------------|
| `sync_version = NULL` in file_metadata | Never successfully delivered to any client              |
| `sync_version = N` in file_metadata    | Delivered and ACK'd by at least one client at version N |
| `status = PENDING` in sync_log         | Event sent, waiting for ACK                             |
| `status = SYNCED` in sync_log          | At least one client confirmed receipt                   |

If no ACK arrives within `pending-ack-ttl-ms` (default 20s), the pending entry is evicted and a warning is logged.
The file's `sync_version` remains `NULL` and will be retried on the next live event or reconnect.

---

## Security

- **mTLS** — both sides present certificates signed by the shared CA. Unauthenticated connections are rejected at the
  TLS layer.
- **Encryption in transit** — all data (file bytes, paths, versions, ACKs, control signals) is TLS-encrypted.
- **Encryption at rest** — files are written to disk in plaintext. TLS only covers data in transit.
- **Password management** — keystore passwords default to the value in `application.yml`. For production, use
  environment variables:
  ```yaml
  sync:
    socket:
      password: ${LUDDITE_KEYSTORE_PASSWORD}
  ```

---

## Threading Model

All long-running tasks use Java virtual threads (`Thread.ofVirtual()`).

| Thread name                    | Module | Role                                                         |
|--------------------------------|--------|--------------------------------------------------------------|
| `client-acceptor`              | server | Accepts new mTLS client connections                          |
| `buffer-drain`                 | server | Polls FileEventBuffer every 50ms, pushes events to clients   |
| `pending-ack-cleanup`          | server | Evicts stale pendingAcks every `pending-ack-ttl-ms`          |
| `admin-cli`                    | server | Reads stdin commands for managing root dirs at runtime       |
| `client-{addr}` (virtual)      | server | One per connected client — handles handshake + ACK loop      |
| `duckDnsExecutor` (virtual)    | server | Runs DuckDNS HTTP update every 5 minutes                     |
| `dir-watcher-{path}` (virtual) | server | One per watched directory — blocks on `WatchService.take()`  |
| `server-sync-receiver`         | client | Connects to server, runs receive loop, reconnects on failure |
| `client-cli`                   | client | Reads stdin commands for managing subscriptions at runtime   |
