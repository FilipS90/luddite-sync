#!/bin/bash
# Shared helpers for end-to-end runs: one real server + N real clients on non-default
# ports, each with its own SQLite DB, mirror dir and stdin FIFO for CLI commands.
#
# Source this file, then call `e2e_setup`, `start_server`, `start_client N`, ...
# Everything lives under $E2E_ROOT (default: ./e2e/.run, git-ignored).

set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
E2E_ROOT="${E2E_ROOT:-$REPO_ROOT/e2e/.run}"
SERVER_JAR="$REPO_ROOT/server-sync/target/server-sync-0.0.1-SNAPSHOT.jar"
CLIENT_JAR="$REPO_ROOT/client-sync/target/client-sync-0.0.1-SNAPSHOT.jar"
API_PORT="${API_PORT:-18080}"
SOCKET_PORT="${SOCKET_PORT:-18888}"
SCAN_INTERVAL="${SCAN_INTERVAL:-5}"
JAVA_OPTS="-Djava.net.preferIPv4Stack=true -Djava.awt.headless=true"
LOG_LEVEL="${LOG_LEVEL:-INFO}"   # e.g. LOG_LEVEL=DEBUG e2e/sync.sh for wire-level detail in the logs

DOWNLOAD_TIMEOUT_MS="${DOWNLOAD_TIMEOUT_MS:-60000}"

# Server-side root dirs: two public ones and a private one, registered by the scenario scripts.
SHARE_DIR="$E2E_ROOT/server/share"
DOCS_DIR="$E2E_ROOT/server/docs"
VAULT_DIR="$E2E_ROOT/server/vault"
VAULT_PASSWORD="s3cret"

log()  { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
pass() { printf '\033[1;32mPASS\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mFAIL\033[0m %s\n' "$*"; FAILURES=$((FAILURES + 1)); }
FAILURES=0

e2e_setup() {
    stop_all
    rm -rf "$E2E_ROOT"
    mkdir -p "$SHARE_DIR/sub" "$DOCS_DIR" "$VAULT_DIR/inner" "$E2E_ROOT/server/db"
}

build_jars() {
    log "Building jars"
    (cd "$REPO_ROOT" && ./mvnw -q -DskipTests package)
}

# Opens a FIFO and keeps a writer attached so the reading process never sees EOF.
_open_fifo() {
    local fifo="$1"
    mkfifo "$fifo"
    (sleep 86400 > "$fifo") &
    echo $! > "$fifo.writer.pid"
}

start_server() {
    log "Starting server on api=$API_PORT socket=$SOCKET_PORT"
    [ -p "$E2E_ROOT/server.in" ] || _open_fifo "$E2E_ROOT/server.in"
    nohup java $JAVA_OPTS -jar "$SERVER_JAR" \
        --server.port="$API_PORT" --sync.socket.port="$SOCKET_PORT" \
        --spring.datasource.url="jdbc:sqlite:$E2E_ROOT/server/db/sync.db" \
        --sync.watcher.scan-interval-seconds="$SCAN_INTERVAL" \
        --logging.level.com.fstojilj="$LOG_LEVEL" \
        < "$E2E_ROOT/server.in" > "$E2E_ROOT/server.log" 2>&1 &
    echo $! > "$E2E_ROOT/server.pid"
    disown
    wait_for_log "$E2E_ROOT/server.log" "Started SyncServerApp" 30
}

server_pid() { cat "$E2E_ROOT/server.pid"; }

# stop_server [SIGNAL] — SIGKILL simulates a crash; the default TERM is a clean shutdown.
stop_server() {
    kill -"${1:-TERM}" "$(server_pid)" 2>/dev/null
    while kill -0 "$(server_pid)" 2>/dev/null; do sleep 0.2; done
}

restart_server() {
    stop_server "${1:-TERM}"
    : > "$E2E_ROOT/server.log"
    start_server
}

server_cmd() { echo "$*" > "$E2E_ROOT/server.in"; }
server_log() { cat "$E2E_ROOT/server.log"; }
server_root_dir_id() { server_db "SELECT id FROM root_dir WHERE name='$1'"; }

# Registers a root dir and waits until the server has indexed every file under it.
server_add_dir() {
    local path="$1"; shift
    local expected
    expected=$(find "$path" -type f | wc -l)
    server_cmd add "$path" "$@"
    wait_for_log "$E2E_ROOT/server.log" "Added and watching: $path" 30
    wait_for_server_files "$(basename "$path")" "$expected"
}

# wait_for_server_files DIR_NAME COUNT [TIMEOUT_S] — until the server DB lists COUNT live files.
wait_for_server_files() {
    local name="$1" expected="$2" timeout="${3:-60}" i=0
    until [ "$(server_db "SELECT COUNT(*) FROM file_metadata WHERE deleted=0 AND root_dir_id=(SELECT id FROM root_dir WHERE name='$name')")" = "$expected" ]; do
        sleep 1
        i=$((i + 1))
        if [ "$i" -ge "$timeout" ]; then
            fail "timed out waiting for $expected indexed file(s) in server dir $name"
            return 1
        fi
    done
}

# wait_for_server_db SQL EXPECTED [TIMEOUT_S] — polls the server DB until the query yields EXPECTED.
wait_for_server_db() {
    local sql="$1" expected="$2" timeout="${3:-30}" i=0
    until [ "$(server_db "$sql")" = "$expected" ]; do
        sleep 0.5
        i=$((i + 1))
        if [ "$i" -ge $((timeout * 2)) ]; then
            fail "timed out waiting for server db [$sql] = [$expected], got [$(server_db "$sql")]"
            return 1
        fi
    done
}

# wait_for_client_db N SQL EXPECTED [TIMEOUT_S] — polls a client DB until the query yields EXPECTED.
wait_for_client_db() {
    local n="$1" sql="$2" expected="$3" timeout="${4:-30}" i=0
    until [ "$(client_db "$n" "$sql")" = "$expected" ]; do
        sleep 0.5
        i=$((i + 1))
        if [ "$i" -ge $((timeout * 2)) ]; then
            fail "timed out waiting for client $n db [$sql] = [$expected], got [$(client_db "$n" "$sql")]"
            return 1
        fi
    done
}

# wait_for_client_files N DIR COUNT [TIMEOUT_S] — until COUNT files are mirrored under DIR.
wait_for_client_files() {
    local n="$1" dir="$2" expected="$3" timeout="${4:-60}" i=0
    until [ "$(client_files "$n" | grep -c "^$dir/")" = "$expected" ]; do
        sleep 0.5
        i=$((i + 1))
        if [ "$i" -ge $((timeout * 2)) ]; then
            fail "timed out waiting for $expected file(s) in client $n dir $dir, have $(client_files "$n" | grep -c "^$dir/")"
            return 1
        fi
    done
}

# Remote addresses the server currently lists for `listc`, one per line.
server_connected_clients() {
    server_cmd listc
    sleep 1
    server_log | awk '/Connected clients:|no clients connected/ {buf=""; next} /^    - / {buf=buf $0 "\n"} END {printf "%s", buf}'
}

# start_client N — mirror dir, DB, log and FIFO live under $E2E_ROOT/cN.
# The client is pointed at localhost and left waiting for `client_cmd N sync <dir>`.
start_client() {
    local n="$1" dir="$E2E_ROOT/c$1"
    mkdir -p "$dir/mirror"
    [ -f "$dir/mirror/client-id" ] || echo "client-$n" > "$dir/mirror/client-id"
    [ -p "$dir/cli.in" ] || _open_fifo "$dir/cli.in"
    echo 0 > "$dir/log.mark"
    log "Starting client $n"
    nohup java $JAVA_OPTS -jar "$CLIENT_JAR" \
        --server.port=0 --sync.server.api-port="$API_PORT" --sync.client.ui=cli \
        --spring.datasource.url="jdbc:sqlite:$dir/sync.db" \
        --sync.client.mirror-dir="$dir/mirror" --sync.client.name="c$n" \
        --sync.client.download-read-timeout-ms="$DOWNLOAD_TIMEOUT_MS" \
        --logging.level.com.fstojilj="$LOG_LEVEL" \
        < "$dir/cli.in" > "$dir/client.log" 2>&1 &
    echo $! > "$dir/client.pid"
    disown
    wait_for_log "$dir/client.log" "Using host:port" 30
    if ! grep -q "Using host:port localhost" "$dir/client.log"; then
        client_cmd "$n" host localhost
    fi
    wait_for_log "$dir/client.log" "Server advertises" 60
}

# stop_client N [SIGNAL] — SIGKILL simulates a crash; the default TERM is a clean shutdown.
stop_client() {
    local pidfile="$E2E_ROOT/c$1/client.pid"
    kill -"${2:-TERM}" "$(cat "$pidfile")" 2>/dev/null
    while kill -0 "$(cat "$pidfile")" 2>/dev/null; do sleep 0.2; done
}

restart_client() {
    stop_client "$1" "${2:-TERM}"
    : > "$E2E_ROOT/c$1/client.log"
    start_client "$1"
}

# Every client_cmd (or an explicit mark_client) remembers where the client log ended, so
# wait_client / assert_client_said only look at what the client printed after that point.
mark_client() { wc -l < "$E2E_ROOT/c$1/client.log" > "$E2E_ROOT/c$1/log.mark"; }
client_cmd()  { local n="$1"; shift; mark_client "$n"; echo "$*" > "$E2E_ROOT/c$n/cli.in"; }

client_log()       { cat "$E2E_ROOT/c$1/client.log"; }
client_log_since() { tail -n +"$(( $(cat "$E2E_ROOT/c$1/log.mark" 2>/dev/null || echo 0) + 1 ))" "$E2E_ROOT/c$1/client.log"; }
client_log_count() { grep -cE "$2" "$E2E_ROOT/c$1/client.log"; }

# wait_client N PATTERN [TIMEOUT_S] — like wait_for_log, over the log since the last mark.
wait_client() {
    local n="$1" pattern="$2" timeout="${3:-30}" i=0
    until client_log_since "$n" | grep -qE "$pattern"; do
        sleep 0.5
        i=$((i + 1))
        if [ "$i" -ge $((timeout * 2)) ]; then
            fail "timed out waiting for client $n to log /$pattern/"
            return 1
        fi
    done
}
client_mirror() { echo "$E2E_ROOT/c$1/mirror"; }
client_downloads() { echo "$E2E_ROOT/c$1/mirror/downloads"; }
# Synced files, relative to the mirror dir; one-off downloads are listed separately.
client_files() { (cd "$E2E_ROOT/c$1/mirror" && find . -type f ! -name client-id ! -path './downloads/*' | sed 's|^\./||' | LC_ALL=C sort); }
client_downloaded_files() { (cd "$E2E_ROOT/c$1/mirror/downloads" 2>/dev/null && find . -type f | sed 's|^\./||' | LC_ALL=C sort); }
# The apps hold the databases open, so both helpers wait out short write locks instead of erroring.
client_db()    { sqlite3 -cmd '.timeout 5000' "$E2E_ROOT/c$1/sync.db" "$2"; }
client_version() { client_db "$1" "SELECT last_sync_version FROM root_dirs WHERE dir_name='$2'"; }
server_db()    { sqlite3 -cmd '.timeout 5000' "$E2E_ROOT/server/db/sync.db" "$1"; }

# wait_for_log FILE PATTERN [TIMEOUT_S] — polls for a grep -E match.
wait_for_log() {
    local file="$1" pattern="$2" timeout="${3:-30}" i=0
    until grep -qE "$pattern" "$file" 2>/dev/null; do
        sleep 1
        i=$((i + 1))
        if [ "$i" -ge "$timeout" ]; then
            fail "timed out waiting for /$pattern/ in $file"
            return 1
        fi
    done
}

# wait_for_path PATH [TIMEOUT_S] — polls until a file or directory exists.
wait_for_path() {
    local path="$1" timeout="${2:-30}" i=0
    until [ -e "$path" ]; do
        sleep 0.1
        i=$((i + 1))
        if [ "$i" -ge $((timeout * 10)) ]; then
            fail "timed out waiting for $path to appear"
            return 1
        fi
    done
}

# wait_for_min_size PATH BYTES [TIMEOUT_S] — polls until a file has grown to at least BYTES,
# i.e. until a transfer is demonstrably under way and can be interrupted. Polls far faster than
# the other waits: over loopback even a 160 MB file is through in a few hundred milliseconds.
wait_for_min_size() {
    local path="$1" min="$2" timeout="${3:-60}" i=0
    until [ -f "$path" ] && [ "$(file_size "$path")" -ge "$min" ]; do
        sleep 0.02
        i=$((i + 1))
        if [ "$i" -ge $((timeout * 50)) ]; then
            fail "timed out waiting for $path to reach $min bytes, at $(file_size "$path")"
            return 1
        fi
    done
}

file_size() { stat -c %s "$1" 2>/dev/null || echo 0; }

# Waits until the client has gone at least two poll intervals without new activity.
settle() { sleep "${1:-8}"; }

# make_random_file PATH SIZE_MB — content that neither compresses nor repeats.
make_random_file() { head -c "$(($2 * 1024 * 1024))" /dev/urandom > "$1"; }

sha() { sha256sum "$1" | cut -d' ' -f1; }

assert_eq() {
    local what="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then pass "$what"; else fail "$what: expected [$expected] got [$actual]"; fi
}

assert_same_content() {
    local what="$1" expected="$2" actual="$3"
    if [ ! -f "$actual" ]; then fail "$what: $actual missing"; return; fi
    if [ "$(sha "$expected")" = "$(sha "$actual")" ]; then pass "$what"; else fail "$what: content differs from $expected"; fi
}

# assert_partial WHAT COMPLETE PARTIAL — PARTIAL is on disk but shorter than COMPLETE, which is
# what an interrupted transfer leaves behind; a full-length file means the interruption came too late.
assert_partial() {
    local what="$1" complete="$2" partial="$3"
    if [ ! -f "$partial" ]; then fail "$what: $partial missing"; return; fi
    if [ "$(file_size "$partial")" -lt "$(file_size "$complete")" ]; then
        pass "$what"
    else
        fail "$what: $partial already holds all $(file_size "$complete") bytes"
    fi
}

assert_exists()  { if [ -e "$2" ]; then pass "$1"; else fail "$1: $2 missing"; fi; }
assert_missing() { if [ ! -e "$2" ]; then pass "$1"; else fail "$1: $2 exists"; fi; }

# assert_client_said N PATTERN [WHAT] / assert_client_silent N PATTERN [WHAT] — over the log since the last mark.
assert_client_said()   { if client_log_since "$1" | grep -qE "$2"; then pass "${3:-client $1 logged /$2/}"; else fail "${3:-client $1 did not log /$2/}"; fi; }
assert_client_silent() { if client_log_since "$1" | grep -qE "$2"; then fail "${3:-client $1 logged /$2/}"; else pass "${3:-client $1 did not log /$2/}"; fi; }

# A fresh client aims at the placeholder host until start_client switches it to localhost.
STARTUP_NOISE='luddite-server:|Server returned no public directories'

# assert_no_client_errors N [IGNORE_REGEX] — WARN/ERROR lines other than reconnects, start-up
# noise and IGNORE_REGEX fail.
assert_no_client_errors() {
    local n="$1" ignore="${2:-^$}"
    if grep -E ' (WARN|ERROR) ' "$E2E_ROOT/c$n/client.log" | grep -v 'Connection lost' \
            | grep -vE "$STARTUP_NOISE" | grep -vE "$ignore"; then
        fail "client $n logged WARN/ERROR"
    else
        pass "client $n log clean"
    fi
}

stop_all() {
    [ -d "$E2E_ROOT" ] || return 0
    for pidfile in "$E2E_ROOT"/*.pid "$E2E_ROOT"/c*/*.pid; do
        [ -f "$pidfile" ] && kill "$(cat "$pidfile")" 2>/dev/null
    done
    sleep 1
}

summary() {
    echo
    if [ "$FAILURES" -eq 0 ]; then pass "all scenarios"; else fail "$FAILURES assertion(s)"; fi
    return "$FAILURES"
}
