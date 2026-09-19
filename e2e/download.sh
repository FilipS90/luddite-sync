#!/bin/bash
# One-off downloads: single files, subtrees, whole root dirs, listing indexes, large files
# with progress, private dirs, failures, and a server that stalls or dies mid-transfer.
#
# Usage: e2e/download.sh [--no-build]

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
trap stop_all EXIT

[ "${1:-}" = "--no-build" ] || build_jars
DOWNLOAD_TIMEOUT_MS=3000
e2e_setup

UNICODE="Ноћ у музеју (2006).txt"
echo "root-a"          > "$SHARE_DIR/a.txt"
echo "nested-c"        > "$SHARE_DIR/sub/c.txt"
echo "unicode + space" > "$SHARE_DIR/sub/$UNICODE"
mkdir -p "$SHARE_DIR/sub/deep"
echo "deeper-d"        > "$SHARE_DIR/sub/deep/d.txt"
make_random_file "$SHARE_DIR/big.bin" 40
echo "vault-1"         > "$VAULT_DIR/v1.txt"
echo "vault-2"         > "$VAULT_DIR/v2.txt"
echo "vault-inner"     > "$VAULT_DIR/inner/v3.txt"
# Sparse and far bigger than the socket can push before the scripts below interrupt it.
BULK_DIR="$E2E_ROOT/server/bulk"
mkdir -p "$BULK_DIR"
truncate -s 3G "$BULK_DIR/huge.bin"

start_server
server_add_dir "$SHARE_DIR"
server_add_dir "$VAULT_DIR" --private --pswd "$VAULT_PASSWORD"
server_add_dir "$BULK_DIR"

# Client 1 keeps a sync connection open throughout; client 2 starts out download-only.
start_client 1
client_cmd 1 sync share
wait_client 1 "Written: share/big.bin" 60
start_client 2
DL1="$(client_downloads 1)"
DL2="$(client_downloads 2)"

log "Single file is saved flat"
client_cmd 2 download share sub/c.txt
wait_client 2 "Downloaded 1 file\(s\)"
assert_eq "flat file only" "c.txt" "$(client_downloaded_files 2)"
assert_same_content "c.txt content" "$SHARE_DIR/sub/c.txt" "$DL2/c.txt"

log "Unicode and spaces in the file name"
client_cmd 2 download share "sub/$UNICODE"
wait_client 2 "Downloaded 1 file\(s\)"
assert_same_content "unicode file" "$SHARE_DIR/sub/$UNICODE" "$DL2/$UNICODE"

log "Subdirectory keeps its internal structure under its own name"
client_cmd 2 download share sub
wait_client 2 "Downloaded 3 file\(s\)"
assert_exists "sub/c.txt" "$DL2/sub/c.txt"
assert_exists "sub/deep/d.txt" "$DL2/sub/deep/d.txt"
assert_exists "sub/unicode" "$DL2/sub/$UNICODE"
assert_same_content "deep file content" "$SHARE_DIR/sub/deep/d.txt" "$DL2/sub/deep/d.txt"

log "Whole root dir lands under downloads/<dir>"
client_cmd 2 download share
wait_client 2 "Downloaded 5 file\(s\)" 120
assert_eq "root dir tree" \
    $'share/a.txt\nshare/big.bin\nshare/sub/c.txt\nshare/sub/deep/d.txt\nshare/sub/'"$UNICODE" \
    "$(client_downloaded_files 2 | grep '^share/')"
assert_same_content "big.bin via root download" "$SHARE_DIR/big.bin" "$DL2/share/big.bin"

log "Large file reports progress every 16 MB and leaves no .part behind"
client_cmd 2 download share big.bin
wait_client 2 "Downloaded 1 file\(s\)" 60
assert_client_said 2 "share/big.bin: 16.0 MB / 40.0 MB" "progress at 16 MB"
assert_client_said 2 "share/big.bin: 32.0 MB / 40.0 MB" "progress at 32 MB"
assert_client_said 2 "share/big.bin: 40.0 MB / 40.0 MB" "progress at completion"
assert_same_content "big.bin content" "$SHARE_DIR/big.bin" "$DL2/big.bin"
assert_missing "no big.bin.part" "$DL2/big.bin.part"

log "Download by listing index after browse"
client_cmd 2 browse share sub
wait_client 2 "\[file\] c.txt"
c_index=$(client_log_since 2 | grep -E '^\s+[0-9]+\. .*\[file\] c.txt' | sed -E 's/^\s+([0-9]+)\..*/\1/')
rm "$DL2/c.txt"
client_cmd 2 download "$c_index"
wait_client 2 "Downloaded 1 file\(s\)"
assert_client_said 2 "Downloading: share/sub/c.txt" "index $c_index resolved to c.txt"
assert_same_content "c.txt via index" "$SHARE_DIR/sub/c.txt" "$DL2/c.txt"

log "Re-download overwrites a previous copy"
mark_client 1
echo "root-a-v2" > "$SHARE_DIR/a.txt"
wait_client 1 "Written: share/a.txt"
client_cmd 2 download share a.txt
wait_client 2 "Downloaded 1 file\(s\)"
assert_eq "a.txt overwritten" "root-a-v2" "$(cat "$DL2/a.txt")"

log "Missing path and unknown dir fail without writing anything"
client_cmd 2 download share nope/missing.txt
wait_client 2 "Download failed or found no files: share/nope/missing.txt"
client_cmd 2 download nosuchdir
wait_client 2 "No such directory 'nosuchdir'"
assert_missing "nothing written for missing path" "$DL2/missing.txt"
assert_missing "no partial for missing path" "$DL2/missing.txt.part"

log "Private dir: wrong password is denied and the dir stays invisible"
client_cmd 2 private vault --pswd wrong
wait_client 2 "Access denied to 'vault'" 60
client_cmd 2 download vault
wait_client 2 "No such directory 'vault'"
assert_eq "vault not in client db" "" "$(client_db 2 "SELECT dir_name FROM root_dirs WHERE dir_name='vault'")"

log "Private dir: multi-file download works and keeps the sync connection authorized"
client_cmd 2 private vault --pswd "$VAULT_PASSWORD"
wait_client 2 "Access granted to private dir: vault" 60
wait_client 2 "Written: vault/inner/v3.txt" 60
client_cmd 2 download vault
wait_client 2 "Downloaded 3 file\(s\)"
assert_eq "all vault files downloaded" $'vault/inner/v3.txt\nvault/v1.txt\nvault/v2.txt' "$(client_downloaded_files 2 | grep '^vault/')"
mark_client 2
echo "vault-4" > "$VAULT_DIR/v4.txt"
wait_client 2 "Written: vault/v4.txt"
assert_exists "vault still syncing after downloads" "$(client_mirror 2)/vault/v4.txt"
client_cmd 2 download vault inner/v3.txt
wait_client 2 "Downloaded 1 file\(s\)"
assert_same_content "private single file" "$VAULT_DIR/inner/v3.txt" "$DL2/v3.txt"

log "Server still lists both clients after all those short-lived download sockets"
assert_eq "connected clients" 2 "$(server_connected_clients | grep -c .)"

log "Subscribed client downloads into its own downloads folder without touching the mirror"
client_cmd 1 download share sub/deep/d.txt
wait_client 1 "Downloaded 1 file\(s\)"
assert_same_content "client 1 download" "$SHARE_DIR/sub/deep/d.txt" "$DL1/d.txt"
assert_eq "client 1 mirror intact" 5 "$(client_files 1 | grep -c '^share/')"

assert_no_client_errors 1
assert_no_client_errors 2 "Download failed|No directories to sync|access denied for dir 'vault'|private dir 'vault': DENIED"

log "Server stalls mid-transfer: download times out, partial file removed"
client_cmd 2 download bulk huge.bin
wait_for_path "$DL2/huge.bin.part"
kill -STOP "$(server_pid)"
wait_client 2 "Download failed or found no files: bulk/huge.bin" 30
assert_client_said 2 "Download failed for 'bulk/huge.bin': Read timed out" "read timeout reported"
assert_missing "no huge.bin" "$DL2/huge.bin"
assert_missing "no huge.bin.part after timeout" "$DL2/huge.bin.part"
kill -CONT "$(server_pid)"
settle 3

log "Server dies mid-transfer: download fails cleanly and works again after a restart"
client_cmd 2 download bulk huge.bin
wait_for_path "$DL2/huge.bin.part"
stop_server KILL
wait_client 2 "Download failed for 'bulk/huge.bin': (connection closed after|Connection reset)" 30
assert_missing "no huge.bin after crash" "$DL2/huge.bin"
assert_missing "no huge.bin.part after crash" "$DL2/huge.bin.part"
mark_client 2
start_server
wait_client 2 "Connected to server" 60
rm -f "$DL2/c.txt"
client_cmd 2 download share sub/c.txt
wait_client 2 "Downloaded 1 file\(s\)"
assert_same_content "download after server restart" "$SHARE_DIR/sub/c.txt" "$DL2/c.txt"

summary
