#!/bin/bash
# Interrupted transfers: the server or the client dying or stalling in the middle of a sync or a
# download, a socket port moved under a live connection, and clients catching up after an outage.
#
# Usage: e2e/resilience.sh [--no-build]

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
trap stop_all EXIT

[ "${1:-}" = "--no-build" ] || build_jars
DOWNLOAD_TIMEOUT_MS=5000
e2e_setup

HEAVY_DIR="$E2E_ROOT/server/heavy"
MIXED_DIR="$E2E_ROOT/server/mixed"
STAGING="$E2E_ROOT/staging"
ALT_PORT=$((SOCKET_PORT + 11))
# Big enough that a transfer is still running a few hundred milliseconds in, which is the window
# the kills and stalls below have to land in.
BIG_MB=160
# A transfer that has put this much on disk is under way and can be interrupted mid-file.
PARTIAL_BYTES=$((1024 * 1024))
mkdir -p "$HEAVY_DIR" "$MIXED_DIR" "$STAGING"

echo "root-a"   > "$SHARE_DIR/a.txt"
echo "nested-c" > "$SHARE_DIR/sub/c.txt"
echo "vault-1"  > "$VAULT_DIR/v1.txt"
make_random_file "$HEAVY_DIR/big1.bin" $BIG_MB
# Staged outside the watched dirs: moved in later they are indexed at their final size in one event.
make_random_file "$STAGING/big2.bin" $BIG_MB
make_random_file "$STAGING/big3.bin" $BIG_MB
# The server lists a dir's files alphabetically and the client downloads them in that order, so
# the big one is named to sort last: the small ones are always through before it is interrupted.
for f in a b c; do echo "mixed-$f" > "$MIXED_DIR/$f.txt"; done
make_random_file "$MIXED_DIR/z-big.bin" $BIG_MB

start_server
server_add_dir "$SHARE_DIR"
server_add_dir "$HEAVY_DIR"
server_add_dir "$MIXED_DIR"
server_add_dir "$VAULT_DIR" --private --pswd "$VAULT_PASSWORD"

start_client 1
start_client 2
client_cmd 1 sync share
client_cmd 2 sync share
wait_client 1 "Written: share/sub/c.txt" 60
wait_client 2 "Written: share/sub/c.txt" 60
client_cmd 1 private vault --pswd "$VAULT_PASSWORD"
wait_client 1 "Access granted to private dir: vault" 60
M1="$(client_mirror 1)"
M2="$(client_mirror 2)"
DL2="$(client_downloads 2)"

log "Server killed mid-sync: nothing is credited, and the file is re-sent whole once it is back"
client_cmd 1 sync heavy
wait_for_min_size "$M1/heavy/big1.bin" "$PARTIAL_BYTES" 120
mark_client 1
stop_server KILL
assert_partial "big1.bin interrupted mid-transfer" "$HEAVY_DIR/big1.bin" "$M1/heavy/big1.bin"
assert_eq "no sync version credited for the interrupted file" -1 "$(client_version 1 heavy)"
wait_client 1 "Connection lost: (connection closed after|Connection reset)" 60
start_server
wait_client 1 "Written: heavy/big1.bin" 180
assert_same_content "big1.bin whole after the server came back" "$HEAVY_DIR/big1.bin" "$M1/heavy/big1.bin"
HEAVY_VERSION="$(server_db "SELECT MAX(sync_version) FROM file_metadata WHERE root_dir_id=$(server_root_dir_id heavy)")"
wait_for_client_db 1 "SELECT last_sync_version FROM root_dirs WHERE dir_name='heavy'" "$HEAVY_VERSION"
assert_eq "client caught up to the server's version" "$HEAVY_VERSION" "$(client_version 1 heavy)"

log "Private dir keeps syncing after the crash: the client re-authenticates on reconnect"
mark_client 1
echo "vault-2" > "$VAULT_DIR/v2.txt"
wait_client 1 "Written: vault/v2.txt" 60
assert_same_content "private file after re-auth" "$VAULT_DIR/v2.txt" "$M1/vault/v2.txt"

log "Client killed mid-sync: the file is re-sent from scratch when it comes back"
mv "$STAGING/big2.bin" "$HEAVY_DIR/big2.bin"
wait_for_min_size "$M1/heavy/big2.bin" "$PARTIAL_BYTES" 120
BEFORE_KILL="$(client_version 1 heavy)"
stop_client 1 KILL
assert_partial "big2.bin interrupted mid-transfer" "$HEAVY_DIR/big2.bin" "$M1/heavy/big2.bin"
assert_eq "version still at the last completed file" "$BEFORE_KILL" "$(client_version 1 heavy)"
start_client 1
wait_client 1 "Written: heavy/big2.bin" 180
assert_same_content "big2.bin whole after the client came back" "$HEAVY_DIR/big2.bin" "$M1/heavy/big2.bin"
HEAVY_VERSION="$(server_db "SELECT MAX(sync_version) FROM file_metadata WHERE root_dir_id=$(server_root_dir_id heavy)")"
wait_for_client_db 1 "SELECT last_sync_version FROM root_dirs WHERE dir_name='heavy'" "$HEAVY_VERSION"
assert_eq "version advanced past the retry" "$HEAVY_VERSION" "$(client_version 1 heavy)"

log "Stalled server mid-sync: the transfer freezes but is not dropped, and finishes when it resumes"
mv "$STAGING/big3.bin" "$HEAVY_DIR/big3.bin"
wait_for_min_size "$M1/heavy/big3.bin" "$PARTIAL_BYTES" 120
mark_client 1
kill -STOP "$(server_pid)"
settle 6
assert_client_silent 1 "Written: heavy/big3.bin|Connection lost" "stalled server neither completes nor drops the sync"
assert_partial "big3.bin still short while the server is stopped" "$HEAVY_DIR/big3.bin" "$M1/heavy/big3.bin"
kill -CONT "$(server_pid)"
wait_client 1 "Written: heavy/big3.bin" 180
assert_same_content "big3.bin whole after the server resumed" "$HEAVY_DIR/big3.bin" "$M1/heavy/big3.bin"

log "Socket port moved live: open connections keep working, reconnects find the new port"
server_cmd port "$ALT_PORT"
wait_for_log "$E2E_ROOT/server.log" "Now listening on port $ALT_PORT" 30
mark_client 1
echo "port-1" > "$SHARE_DIR/p1.txt"
wait_client 1 "Written: share/p1.txt" 60
assert_exists "open connection keeps delivering after the rebind" "$M1/share/p1.txt"
client_cmd 1 refresh
wait_client 1 "Connected to server localhost:$ALT_PORT" 90
wait_for_client_db 1 "SELECT port FROM host WHERE name='localhost'" "$ALT_PORT"
assert_eq "client stored the new port" "$ALT_PORT" "$(client_db 1 "SELECT port FROM host WHERE name='localhost'")"
mark_client 1
echo "port-2" > "$SHARE_DIR/p2.txt"
wait_client 1 "Written: share/p2.txt" 60
assert_exists "sync continues on the new port" "$M1/share/p2.txt"
server_cmd port "$SOCKET_PORT"
wait_for_log "$E2E_ROOT/server.log" "Now listening on port $SOCKET_PORT" 30
client_cmd 1 refresh
wait_client 1 "Connected to server localhost:$SOCKET_PORT" 90
mark_client 1
echo "port-3" > "$SHARE_DIR/p3.txt"
wait_client 1 "Written: share/p3.txt" 60
assert_exists "sync continues once the port moves back" "$M1/share/p3.txt"

log "Client killed mid-download: no truncated file is left where the download should land"
client_cmd 2 download mixed z-big.bin
wait_for_min_size "$DL2/z-big.bin.part" "$PARTIAL_BYTES" 60
stop_client 2 KILL
assert_missing "no finished file from the interrupted download" "$DL2/z-big.bin"
assert_exists "mirror survives the crash" "$M2/share/a.txt"
start_client 2
client_cmd 2 download mixed z-big.bin
wait_client 2 "Downloaded 1 file\(s\)" 180
assert_same_content "z-big.bin after the retry" "$MIXED_DIR/z-big.bin" "$DL2/z-big.bin"
assert_missing "no .part left after the retry" "$DL2/z-big.bin.part"

log "Server killed mid multi-file download: finished files are kept, the retry completes the set"
client_cmd 2 download mixed
wait_for_min_size "$DL2/mixed/z-big.bin.part" "$PARTIAL_BYTES" 60
stop_server KILL
wait_client 2 "Downloaded 3 file\(s\)" 60
assert_eq "small files that finished first are kept" $'mixed/a.txt\nmixed/b.txt\nmixed/c.txt' \
    "$(client_downloaded_files 2 | grep '^mixed/')"
assert_eq "no partial files left behind" "" "$(find "$DL2" -name '*.part')"
start_server
wait_client 2 "Connected to server" 90
client_cmd 2 download mixed
wait_client 2 "Downloaded 4 file\(s\)" 180
assert_same_content "z-big.bin completed by the retry" "$MIXED_DIR/z-big.bin" "$DL2/mixed/z-big.bin"

log "Both clients offline across a set of changes: each catches up and the tombstone is purged"
stop_client 1
stop_client 2
echo "offline-new"                > "$SHARE_DIR/n1.txt"
echo "offline-edit, more bytes"   > "$SHARE_DIR/a.txt"
rm "$SHARE_DIR/sub/c.txt"
mkdir -p "$SHARE_DIR/sub/deeper"
echo "offline-deep"               > "$SHARE_DIR/sub/deeper/d1.txt"
wait_for_server_db "SELECT deleted FROM file_metadata WHERE relative_path='sub/c.txt'" 1 60
assert_eq "tombstone waits for both clients" 1 \
    "$(server_db "SELECT COUNT(*) FROM file_metadata WHERE relative_path='sub/c.txt' AND client_ids LIKE '%client-1%' AND client_ids LIKE '%client-2%'")"
start_client 1
start_client 2
for n in 1 2; do
    wait_client $n "Written: share/n1.txt" 120
    wait_client $n "Written: share/a.txt" 120
    wait_client $n "Deleted: share/sub/c.txt" 120
    wait_client $n "Written: share/sub/deeper/d1.txt" 120
done
assert_eq "clients converge on the same files" "$(client_files 1 | grep '^share/')" "$(client_files 2 | grep '^share/')"
assert_eq "clients converge on the same version" "$(client_version 1 share)" "$(client_version 2 share)"
wait_for_server_db "SELECT relative_path FROM file_metadata WHERE relative_path='sub/c.txt'" ""
assert_eq "tombstone purged once both acked" "" \
    "$(server_db "SELECT relative_path FROM file_metadata WHERE relative_path='sub/c.txt'")"

OUTAGE_NOISE="No directories to sync|fetchPublicDirs failed|fetchSocketPort failed|authenticate failed"
assert_no_client_errors 1 "$OUTAGE_NOISE"
assert_no_client_errors 2 "$OUTAGE_NOISE|Download failed"

summary
