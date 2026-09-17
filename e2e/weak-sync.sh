#!/bin/bash
# Weak sync: the user may delete synced files or drop extra files into the mirror dir
# without triggering a reset or re-download; only server-side changes move files.
#
# Usage: e2e/weak-sync.sh [--no-build]

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
trap stop_all EXIT

[ "${1:-}" = "--no-build" ] || build_jars
e2e_setup

echo "root-a"   > "$SHARE_DIR/a.txt"
echo "root-b"   > "$SHARE_DIR/b.txt"
echo "nested-c" > "$SHARE_DIR/sub/c.txt"

start_server
server_cmd add "$SHARE_DIR"
start_client 1
client_cmd 1 sync share
wait_for_log "$E2E_ROOT/c1/client.log" "Written: share/sub/c.txt" 60

log "Baseline"
assert_eq "initial files" $'share/a.txt\nshare/b.txt\nshare/sub/c.txt' "$(client_files 1)"
assert_eq "initial version" 3 "$(client_version 1 share)"

log "Local delete + untracked file + restart does not re-download"
rm "$E2E_ROOT/c1/mirror/share/a.txt" "$E2E_ROOT/c1/mirror/share/sub/c.txt"
echo "my-own" > "$E2E_ROOT/c1/mirror/share/local-extra.txt"
restart_client 1
wait_for_log "$E2E_ROOT/c1/client.log" "Connected to server" 60
settle
assert_eq "files after restart" $'share/b.txt\nshare/local-extra.txt' "$(client_files 1)"
assert_eq "version unchanged" 3 "$(client_version 1 share)"
assert_eq "no reset logged" 0 "$(client_log 1 | grep -c 'resetting sync version')"

log "New server file arrives; server edit of a locally deleted file brings it back"
echo "new-d" > "$SHARE_DIR/d.txt"
echo "nested-c-v2 longer content" > "$SHARE_DIR/sub/c.txt"
wait_for_log "$E2E_ROOT/c1/client.log" "Written: share/d.txt" 30
wait_for_log "$E2E_ROOT/c1/client.log" "Written: share/sub/c.txt" 30
assert_eq "a.txt still absent" "" "$(client_files 1 | grep '^share/a.txt$')"
assert_eq "c.txt re-delivered" "nested-c-v2 longer content" "$(cat "$E2E_ROOT/c1/mirror/share/sub/c.txt")"
assert_eq "version advanced" 5 "$(client_version 1 share)"

log "Server delete of a locally missing file; server file overwrites untracked local file"
rm "$SHARE_DIR/a.txt"
echo "server-version" > "$SHARE_DIR/local-extra.txt"
wait_for_log "$E2E_ROOT/c1/client.log" "Deleted: share/a.txt" 30
wait_for_log "$E2E_ROOT/c1/client.log" "Written: share/local-extra.txt" 30
settle
assert_eq "local-extra overwritten" "server-version" "$(cat "$E2E_ROOT/c1/mirror/share/local-extra.txt")"
assert_eq "a.txt purged from client db" "" "$(client_db 1 "SELECT relative_path FROM file_metadata WHERE relative_path='share/a.txt'")"
assert_eq "a.txt purged from server db" "" "$(server_db "SELECT relative_path FROM file_metadata WHERE relative_path='a.txt'")"

log "Whole mirror dir wiped + restart: nothing re-fetched, dir recreated lazily"
rm -rf "$E2E_ROOT/c1/mirror/share"
restart_client 1
wait_for_log "$E2E_ROOT/c1/client.log" "Connected to server" 60
settle
echo "after-wipe" > "$SHARE_DIR/e.txt"
wait_for_log "$E2E_ROOT/c1/client.log" "Written: share/e.txt" 30
assert_eq "only new file present" "share/e.txt" "$(client_files 1)"
assert_eq "version advanced once" 8 "$(client_version 1 share)"
assert_no_client_errors 1

summary
