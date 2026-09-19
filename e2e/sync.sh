#!/bin/bash
# Continuous sync: two clients on one dir, adds/edits/deletes at root and subdir level,
# a client that is offline during a delete, custom mirror paths, unsubscribing with and
# without deleting, private dirs, large files, changes made while the server is down, and a
# server-side removal.
#
# Usage: e2e/sync.sh [--no-build]

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
trap stop_all EXIT

[ "${1:-}" = "--no-build" ] || build_jars
e2e_setup

echo "root-a"   > "$SHARE_DIR/a.txt"
echo "root-b"   > "$SHARE_DIR/b.txt"
echo "nested-c" > "$SHARE_DIR/sub/c.txt"
echo "doc-1"    > "$DOCS_DIR/one.md"
echo "vault-1"  > "$VAULT_DIR/v1.txt"
echo "vault-2"  > "$VAULT_DIR/inner/v2.txt"

start_server
server_add_dir "$SHARE_DIR"
server_add_dir "$DOCS_DIR"
server_add_dir "$VAULT_DIR" --private --pswd "$VAULT_PASSWORD"

start_client 1
start_client 2
client_cmd 1 sync share
client_cmd 2 sync share
wait_client 1 "Written: share/sub/c.txt" 60
wait_client 2 "Written: share/sub/c.txt" 60
M1="$(client_mirror 1)"
M2="$(client_mirror 2)"

log "Both clients receive the initial tree at the same version"
assert_eq "client 1 files" $'share/a.txt\nshare/b.txt\nshare/sub/c.txt' "$(client_files 1)"
assert_eq "client 2 files" $'share/a.txt\nshare/b.txt\nshare/sub/c.txt' "$(client_files 2)"
assert_eq "client versions match" "$(client_version 1 share)" "$(client_version 2 share)"
assert_eq "server records both holders" 1 "$(server_db "SELECT COUNT(*) FROM file_metadata WHERE relative_path='a.txt' AND client_ids LIKE '%client-1%' AND client_ids LIKE '%client-2%'")"

log "New files at root (watcher) and in a subdir (scanner) reach both clients"
mark_client 1; mark_client 2
echo "root-d"   > "$SHARE_DIR/d.txt"
echo "nested-e" > "$SHARE_DIR/sub/e.txt"
mkdir -p "$SHARE_DIR/sub/new"
echo "brand-new" > "$SHARE_DIR/sub/new/f.txt"
for n in 1 2; do
    wait_client $n "Written: share/d.txt"
    wait_client $n "Written: share/sub/e.txt"
    wait_client $n "Written: share/sub/new/f.txt"
done
assert_same_content "client 1 nested new dir" "$SHARE_DIR/sub/new/f.txt" "$M1/share/sub/new/f.txt"
assert_same_content "client 2 nested new dir" "$SHARE_DIR/sub/new/f.txt" "$M2/share/sub/new/f.txt"

log "Edited file is re-delivered with new content"
mark_client 1; mark_client 2
echo "root-a-edited with more bytes" > "$SHARE_DIR/a.txt"
wait_client 1 "Written: share/a.txt"
wait_client 2 "Written: share/a.txt"
assert_eq "client 1 sees edit" "root-a-edited with more bytes" "$(cat "$M1/share/a.txt")"
assert_eq "client 2 sees edit" "root-a-edited with more bytes" "$(cat "$M2/share/a.txt")"

log "Delete propagates to both clients and the server row is purged once both ack"
mark_client 1; mark_client 2
rm "$SHARE_DIR/b.txt"
wait_client 1 "Deleted: share/b.txt"
wait_client 2 "Deleted: share/b.txt"
assert_missing "client 1 b.txt gone" "$M1/share/b.txt"
assert_missing "client 2 b.txt gone" "$M2/share/b.txt"
wait_for_server_db "SELECT relative_path FROM file_metadata WHERE relative_path='b.txt'" ""
assert_eq "server row purged" "" "$(server_db "SELECT relative_path FROM file_metadata WHERE relative_path='b.txt'")"
assert_eq "client 1 metadata purged" "" "$(client_db 1 "SELECT relative_path FROM file_metadata WHERE relative_path='share/b.txt'")"

log "Deleting the only file in a subdir removes the empty dir on the clients"
mark_client 1; mark_client 2
rm "$SHARE_DIR/sub/new/f.txt"
wait_client 1 "Deleted: share/sub/new/f.txt"
wait_client 2 "Deleted: share/sub/new/f.txt"
settle 2
assert_missing "client 1 empty dir removed" "$M1/share/sub/new"
assert_missing "client 2 empty dir removed" "$M2/share/sub/new"

log "Client offline during a delete and an add catches up on restart"
stop_client 2
mark_client 1
rm "$SHARE_DIR/d.txt"
echo "while-away" > "$SHARE_DIR/g.txt"
wait_client 1 "Deleted: share/d.txt"
wait_client 1 "Written: share/g.txt"
wait_for_server_db "SELECT client_ids FROM file_metadata WHERE relative_path='d.txt'" "client-2"
assert_eq "server keeps tombstone for offline client" "1|client-2" \
    "$(server_db "SELECT deleted, client_ids FROM file_metadata WHERE relative_path='d.txt'")"
assert_exists "client 2 still has d.txt while offline" "$M2/share/d.txt"
restart_client 2
wait_client 2 "Deleted: share/d.txt" 60
wait_client 2 "Written: share/g.txt" 60
assert_missing "client 2 d.txt gone after restart" "$M2/share/d.txt"
assert_same_content "client 2 g.txt after restart" "$SHARE_DIR/g.txt" "$M2/share/g.txt"
wait_for_server_db "SELECT relative_path FROM file_metadata WHERE relative_path='d.txt'" ""
assert_eq "tombstone purged after last ack" "" "$(server_db "SELECT relative_path FROM file_metadata WHERE relative_path='d.txt'")"
settle 3
assert_eq "clients converge" "$(client_files 1)" "$(client_files 2)"
assert_eq "versions converge" "$(client_version 1 share)" "$(client_version 2 share)"

log "Large file goes through the chunked path intact"
make_random_file "$SHARE_DIR/big.bin" 40
mark_client 1; mark_client 2
wait_client 1 "Written: share/big.bin \(40.00 MB" 90
wait_client 2 "Written: share/big.bin \(40.00 MB" 90
assert_same_content "client 1 big.bin" "$SHARE_DIR/big.bin" "$M1/share/big.bin"
assert_same_content "client 2 big.bin" "$SHARE_DIR/big.bin" "$M2/share/big.bin"

log "sync --path mirrors a dir outside the mirror folder"
CUSTOM="$E2E_ROOT/c1/elsewhere/docs"
client_cmd 1 sync docs --path "$CUSTOM"
wait_client 1 "Written: docs/one.md" 60
assert_eq "custom path stored" "$CUSTOM" "$(client_db 1 "SELECT custom_path FROM root_dirs WHERE dir_name='docs'")"
assert_same_content "file at custom path" "$DOCS_DIR/one.md" "$CUSTOM/one.md"
assert_missing "nothing under mirror/docs" "$M1/docs"
mark_client 1
echo "doc-2" > "$DOCS_DIR/two.md"
wait_client 1 "Written: docs/two.md"
assert_exists "later files follow the custom path" "$CUSTOM/two.md"

log "Unsubscribe keeps local files; unsubscribe --delete removes them; re-subscribe re-fetches"
client_cmd 1 remove docs
wait_client 1 "Unsubscribed from: docs"
settle 3
assert_exists "docs kept on disk" "$CUSTOM/one.md"
assert_eq "docs gone from client db" "" "$(client_db 1 "SELECT dir_name FROM root_dirs WHERE dir_name='docs'")"
client_cmd 2 remove share --delete
wait_client 2 "Unsubscribed from 'share' and deleted its local files"
settle 3
assert_missing "client 2 share wiped" "$M2/share"
assert_eq "client 2 share metadata purged" 0 "$(client_db 2 "SELECT COUNT(*) FROM file_metadata WHERE dir_name='share'")"
client_cmd 2 sync share
wait_client 2 "Written: share/big.bin" 90
settle 3
assert_eq "client 2 re-fetched everything" "$(client_files 1 | grep '^share/')" "$(client_files 2)"

log "Private dir: wrong password denied, right password syncs, dir absent from public listing"
client_cmd 2 private vault --pswd nope
wait_client 2 "Access denied to 'vault'" 60
assert_eq "denied dir not tracked" "" "$(client_db 2 "SELECT dir_name FROM root_dirs WHERE dir_name='vault'")"
client_cmd 1 private vault --pswd "$VAULT_PASSWORD"
wait_client 1 "Access granted to private dir: vault" 60
wait_client 1 "Written: vault/inner/v2.txt" 60
assert_same_content "private nested file" "$VAULT_DIR/inner/v2.txt" "$M1/vault/inner/v2.txt"
assert_eq "vault flagged private locally" 1 "$(client_db 1 "SELECT is_private FROM root_dirs WHERE dir_name='vault'")"
client_cmd 2 dirs
wait_client 2 "Server has the following directories available"
assert_client_silent 2 "\. vault " "vault not advertised publicly"
mark_client 1
echo "vault-3" > "$VAULT_DIR/v3.txt"
wait_client 1 "Written: vault/v3.txt"
assert_exists "private dir keeps syncing" "$M1/vault/v3.txt"

log "Server restart: clients reconnect and pick up root and subdir changes made while it was down"
mark_client 1; mark_client 2
stop_server
wait_client 1 "Connection lost" 30
echo "after-restart" > "$SHARE_DIR/h.txt"
echo "nested-i"      > "$SHARE_DIR/sub/i.txt"
rm "$SHARE_DIR/g.txt" "$SHARE_DIR/sub/e.txt"
start_server
wait_client 1 "Connected to server" 60
wait_client 2 "Connected to server" 60
for n in 1 2; do
    wait_client $n "Written: share/h.txt" 60
    wait_client $n "Written: share/sub/i.txt" 60
    wait_client $n "Deleted: share/g.txt" 60
    wait_client $n "Deleted: share/sub/e.txt" 60
done
assert_eq "clients converge after restart" "$(client_files 1 | grep '^share/')" "$(client_files 2)"
assert_exists "private dir survives restart" "$M1/vault/v3.txt"

log "Server removes a root dir: on their next reconnect clients drop it but keep local files"
server_cmd remove "$(server_root_dir_id share)"
wait_for_log "$E2E_ROOT/server.log" "Removed root dir id=" 30
client_cmd 1 refresh
client_cmd 2 refresh
wait_client 1 "Removed stale dir from sync state: 'share'" 60
wait_client 2 "Removed stale dir from sync state: 'share'" 60
assert_exists "client 1 keeps share files" "$M1/share/a.txt"
assert_exists "client 2 keeps share files" "$M2/share/a.txt"
assert_eq "share gone from client 1 db" "" "$(client_db 1 "SELECT dir_name FROM root_dirs WHERE dir_name='share'")"
assert_eq "share metadata gone from client 2 db" 0 "$(client_db 2 "SELECT COUNT(*) FROM file_metadata WHERE dir_name='share'")"
assert_exists "private dir unaffected" "$M1/vault/v1.txt"

assert_no_client_errors 1 "No directories to sync"
assert_no_client_errors 2 "No directories to sync|access denied for dir 'vault'|private dir 'vault': DENIED"

summary
