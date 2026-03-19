# Bug Report — Catch-Up Always Returns Zero Files & Client Connection Aborted

**Date:** 2026-03-18  
**Reported symptom:** Client logs
`Connection lost: An established connection was aborted by the software in your host machine. Reconnecting in 5s...` and
catch-up always delivers 0 files on reconnect.

---

## Bug 1 — `rs.getLong("sync_version")` silently maps SQL `NULL` to `0`

**File:** `server-sync/src/main/java/com/fstojilj/luddite/sync/server/repository/FileMetadataRepository.java`  
**Location:** `rowMapper` field, `.syncVersion(rs.getLong("sync_version"))` line

### Description

`FileMetadata.syncVersion` is a boxed `Long`, intended to be `null` when a file has been registered on the server but
has never been delivered to any client. In the database these rows are stored with `sync_version = NULL`.

`ResultSet.getLong()` is a primitive-returning method. When the underlying SQL column is `NULL`, JDBC specifies that it
returns the default primitive value — `0` — and does **not** throw. The `0` is then autoboxed into `Long.valueOf(0)` and
stored on the record as if the file was synced at version 0.

### Impact

The catch-up query in `FileMetadataRepository` is:

```sql
SELECT * FROM file_metadata
WHERE root_dir_id = ?
  AND (sync_version IS NULL OR sync_version > ?)
```

This query correctly identifies unsynced files at the SQL level. However, after `rs.getLong()` maps every `NULL` to `0`,
the Java objects no longer carry `null` — they carry `0`.

When the server ACKs a catch-up delivery it calls `stampSyncVersion`, which writes the real minted `syncVersion` back to
the database. This works correctly on the first ever connection. But on subsequent reconnects the client sends its
`lastSyncVersion` (e.g. `5`). At that point, any file that was still unsynced (i.e., `sync_version = NULL` in the DB)
gets fetched by the SQL query. However, because the row mapper converts `NULL → 0`, these files come back to
`sendCatchUp` with `syncVersion = 0` instead of `null` — which is merely cosmetic here.

The real damage is on a **fresh client** (first ever connection) where `lastSyncVersion = -1`. The SQL query returns all
files with `sync_version IS NULL OR sync_version > -1` — that is, every file. After delivery and ACK the correct
`syncVersion` is stamped. So far so good. But if the same client reconnects and sends `lastSyncVersion = 0` (because
that was the lowest version it received), the SQL condition becomes `sync_version IS NULL OR sync_version > 0`. Any file
that was **never ACK'd** (i.e., still `NULL` in the DB) is re-sent, but a file synced at version `0` (which should not
exist, since `SyncVersionRepository.next()` starts at `1`) would be silently excluded. More critically, any file whose
ACK was dropped and whose `syncVersion` remains `NULL` will be treated by Java as `syncVersion = 0`, masking the fact
that the file is undelivered.

**Root cause:** `rs.getLong()` should be `rs.getObject("sync_version", Long.class)` to correctly preserve `null`.

---

## Bug 2 — Unguarded `Files.readAllBytes` in `sendCatchUp` crashes the client session

**File:** `server-sync/src/main/java/com/fstojilj/luddite/sync/server/service/ServerPushService.java`  
**Location:** `sendCatchUp()` method, line `byte[] fileBytes = Files.readAllBytes(Path.of(absPath));`

### Description

During the catch-up phase the server queries the database for all files newer than the client's `lastSyncVersion`, then
reads each file from disk and streams it to the client. There is an inherent **race condition** between the database
query and the disk read: a file can be deleted from the filesystem **after** it appears in the query results but *
*before** `Files.readAllBytes` is called.

When this happens, `Files.readAllBytes` throws `java.nio.file.NoSuchFileException` (a subclass of `IOException`).
Because `sendCatchUp` is declared `throws IOException`, the exception propagates uncaught up through `serveClient`,
which also declares `throws IOException`. The `try/catch(IOException)` in `serveClient` that is meant to handle normal
disconnects catches this instead, causing `socket.close()` to be called in the `finally` block. The server abruptly
terminates the TCP connection with a RST.

The client, mid-handshake and expecting more data, receives a connection reset and logs:

```
Connection lost: An established connection was aborted by the software in your host machine. Reconnecting in 5s...
```

### Impact

- The client never receives any catch-up files for that session.
- The client immediately reconnects, triggering the same catch-up sequence, potentially hitting the same race condition
  in a loop if the filesystem is busy.
- Since `sendCatchUp` is called **before** `sessions.add(session)` adds the session to the live drain loop... wait,
  actually the session **is** added before `sendCatchUp` is called (see `serveClient` lines: `sessions.add(session)`
  then `sendCatchUp`). This means a partial session is live in the drain loop while catch-up is still running, which can
  cause concurrent writes to `session.out()` — partially mitigated by the `synchronized(session.out())` blocks, but the
  session will still be abruptly removed on crash.

**Root cause:** `Files.readAllBytes(Path.of(absPath))` must be wrapped in a `try/catch(IOException)` so that a missing
file causes a warning log and a `continue` to the next file, rather than tearing down the entire client session.

---

## Suggested Fixes

### Fix 1 — `FileMetadataRepository.java`

```java
// Before (wrong — returns 0 for SQL NULL)
.syncVersion(rs.getLong("sync_version"))

// After (correct — returns null for SQL NULL)
        .

syncVersion(rs.getObject("sync_version", Long .class))
```

### Fix 2 — `ServerPushService.java`, `sendCatchUp()`

```java
// Before (wrong — unguarded, crashes session on missing file)
byte[] fileBytes = Files.readAllBytes(Path.of(absPath));

// After (correct — skip missing files gracefully)
byte[] fileBytes;
try{
fileBytes =Files.

readAllBytes(Path.of(absPath));
        }catch(
IOException e){
        log.

warn("Catch-up: file no longer on disk, skipping '{}': {}",qualifiedPath, e.getMessage());
        continue;
        }
```

