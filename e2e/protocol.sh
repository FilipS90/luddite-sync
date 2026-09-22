#!/bin/bash
# Sync protocol: a directory larger than one response batch, two clients racing for the same
# versions, a root dir added while clients are running, and the file names and shapes that the
# qualified-path handling has to survive. Also the browse/list CLI surface over the REST tree.
#
# Usage: e2e/protocol.sh [--no-build]

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
trap stop_all EXIT

[ "${1:-}" = "--no-build" ] || build_jars
e2e_setup

MANY_DIR="$E2E_ROOT/server/many"
EXTRA_DIR="$E2E_ROOT/server/extra"
# The server answers a SYNC with at most 100 records, so this many files take several polls.
MANY_COUNT=250
UNICODE_DIR="sub dir"
UNICODE_FILE="Ноћ у музеју (2006).txt"
mkdir -p "$MANY_DIR" "$EXTRA_DIR"

echo "root-a"   > "$SHARE_DIR/a.txt"
echo "nested-c" > "$SHARE_DIR/sub/c.txt"
echo "doc-1"    > "$DOCS_DIR/one.md"
echo "extra-x"  > "$EXTRA_DIR/x.txt"
for i in $(seq 1 $MANY_COUNT); do echo "file-$i" > "$MANY_DIR/f$(printf '%03d' "$i").txt"; done

start_server
server_add_dir "$SHARE_DIR"
server_add_dir "$DOCS_DIR"
server_add_dir "$MANY_DIR"

start_client 1
start_client 2
client_cmd 1 sync share
client_cmd 2 sync share
wait_client 1 "Written: share/sub/c.txt" 60
wait_client 2 "Written: share/sub/c.txt" 60
M1="$(client_mirror 1)"
M2="$(client_mirror 2)"

log "A dir larger than one batch is delivered across several polls, once per file"
client_cmd 1 sync many
client_cmd 2 sync many
wait_for_client_files 1 many $MANY_COUNT 180
wait_for_client_files 2 many $MANY_COUNT 180
MANY_ID="$(server_root_dir_id many)"
assert_eq "client 1 wrote every file exactly once" $MANY_COUNT "$(client_log_count 1 'Written: many/')"
assert_eq "client 2 wrote every file exactly once" $MANY_COUNT "$(client_log_count 2 'Written: many/')"
assert_eq "clients hold the same files" "$(client_files 1 | grep '^many/')" "$(client_files 2 | grep '^many/')"
assert_same_content "last file intact" "$MANY_DIR/f$MANY_COUNT.txt" "$M1/many/f$MANY_COUNT.txt"

log "Two clients racing for the same fresh dir get one version per file, and the same ones"
assert_eq "every file was stamped" 0 \
    "$(server_db "SELECT COUNT(*) FROM file_metadata WHERE root_dir_id=$MANY_ID AND sync_version IS NULL")"
assert_eq "no version handed out twice" $MANY_COUNT \
    "$(server_db "SELECT COUNT(DISTINCT sync_version) FROM file_metadata WHERE root_dir_id=$MANY_ID")"
assert_eq "versions stop at one per file" $MANY_COUNT \
    "$(server_db "SELECT MAX(sync_version) FROM file_metadata WHERE root_dir_id=$MANY_ID")"
wait_for_client_db 1 "SELECT last_sync_version FROM root_dirs WHERE dir_name='many'" $MANY_COUNT
wait_for_client_db 2 "SELECT last_sync_version FROM root_dirs WHERE dir_name='many'" $MANY_COUNT
assert_eq "both clients end on the server's version" "$(client_version 1 many)" "$(client_version 2 many)"
assert_eq "server records both holders" $MANY_COUNT \
    "$(server_db "SELECT COUNT(*) FROM file_metadata WHERE root_dir_id=$MANY_ID AND client_ids LIKE '%client-1%' AND client_ids LIKE '%client-2%'")"

log "The same relative path in two dirs stays two separate files"
client_cmd 1 sync docs
echo "from-share" > "$SHARE_DIR/dup.txt"
echo "from-docs"  > "$DOCS_DIR/dup.txt"
wait_client 1 "Written: share/dup.txt" 60
wait_client 1 "Written: docs/dup.txt" 60
assert_eq "share copy" "from-share" "$(cat "$M1/share/dup.txt")"
assert_eq "docs copy"  "from-docs"  "$(cat "$M1/docs/dup.txt")"
assert_eq "tracked under their own dir" 2 \
    "$(client_db 1 "SELECT COUNT(*) FROM file_metadata WHERE relative_path LIKE '%/dup.txt'")"
assert_eq "client 2 is unaffected by a dir it does not sync" "" "$(client_files 2 | grep '^docs/')"

log "A root dir added while clients are running shows up on refresh and syncs by index"
server_add_dir "$EXTRA_DIR"
client_cmd 1 refresh
wait_client 1 "Server has the following directories available" 90
EXTRA_INDEX="$(client_log_since 1 | sed -nE 's/^ +([0-9]+)\. extra  .*/\1/p' | head -1)"
assert_eq "extra is advertised" 1 "$(printf '%s' "$EXTRA_INDEX" | grep -c '^[0-9]\+$')"
client_cmd 1 sync "$EXTRA_INDEX"
wait_client 1 "Subscribed to: extra" 30
wait_client 1 "Written: extra/x.txt" 90
assert_same_content "file from the new dir" "$EXTRA_DIR/x.txt" "$M1/extra/x.txt"

log "A rename on the server arrives as a delete plus a write"
mark_client 1
mv "$SHARE_DIR/a.txt" "$SHARE_DIR/a-renamed.txt"
wait_client 1 "Deleted: share/a.txt" 60
wait_client 1 "Written: share/a-renamed.txt" 60
assert_missing "old name gone" "$M1/share/a.txt"
assert_same_content "new name in place" "$SHARE_DIR/a-renamed.txt" "$M1/share/a-renamed.txt"

log "An empty file syncs as an empty file"
mark_client 1
: > "$SHARE_DIR/empty.bin"
wait_client 1 "Written: share/empty.bin \(0.00 MB" 60
assert_exists "empty file created" "$M1/share/empty.bin"
assert_eq "still zero bytes" 0 "$(file_size "$M1/share/empty.bin")"

log "Non-ASCII names, spaces, and a subtree created in one go all reach the mirror"
mark_client 1
mkdir -p "$SHARE_DIR/$UNICODE_DIR" "$SHARE_DIR/sub/x/y/z"
echo "unicode + space" > "$SHARE_DIR/$UNICODE_DIR/$UNICODE_FILE"
echo "deep-nested"     > "$SHARE_DIR/sub/x/y/z/deep.txt"
wait_client 1 "Written: share/sub dir/" 60
wait_client 1 "Written: share/sub/x/y/z/deep.txt" 60
assert_same_content "unicode name" "$SHARE_DIR/$UNICODE_DIR/$UNICODE_FILE" "$M1/share/$UNICODE_DIR/$UNICODE_FILE"
assert_same_content "deeply nested file" "$SHARE_DIR/sub/x/y/z/deep.txt" "$M1/share/sub/x/y/z/deep.txt"

log "browse walks the server tree by depth, by index, and back up"
client_cmd 1 browse share --depth 3
wait_client 1 "'browse <n>' enters a dir" 30
assert_client_said 1 "\[dir \] y" "depth 3 reaches three levels down"
assert_client_said 1 "\[file\] c.txt" "files of a nested dir are listed"
SUB_INDEX="$(client_log_since 1 | sed -nE 's/^ +([0-9]+)\. \[dir \] sub  .*/\1/p' | head -1)"
client_cmd 1 browse "$SUB_INDEX"
wait_client 1 "^  share/sub$" 30
assert_client_said 1 "\[file\] c.txt" "entering sub lists its files"
client_cmd 1 up
wait_client 1 "^  share$" 30
assert_client_said 1 "\[dir \] sub" "up returns to the dir root"
# Every listing renumbers, so the file index has to come from the listing 'up' just printed.
FILE_INDEX="$(client_log_since 1 | sed -nE 's/^ +([0-9]+)\. \[file\] dup\.txt  .*/\1/p' | head -1)"
client_cmd 1 browse "$FILE_INDEX"
wait_client 1 "is a file — use 'download" 30
assert_client_said 1 "'dup.txt' is a file — use 'download $FILE_INDEX' to fetch it" \
    "browsing a file points at download"

log "list reports each subscribed dir and the version the client has reached"
settle 3
client_cmd 1 list
wait_client 1 "Dir Name" 30
assert_client_said 1 "^docs +\| $(client_version 1 docs) +\|" "docs at its current version"
assert_client_said 1 "^many +\| $MANY_COUNT +\|" "many at the last version of the batch run"

assert_no_client_errors 1 "No directories to sync"
assert_no_client_errors 2 "No directories to sync"

summary
