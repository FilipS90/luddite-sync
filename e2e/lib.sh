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

SHARE_DIR="$E2E_ROOT/server/share"

log()  { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
pass() { printf '\033[1;32mPASS\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mFAIL\033[0m %s\n' "$*"; FAILURES=$((FAILURES + 1)); }
FAILURES=0

e2e_setup() {
    stop_all
    rm -rf "$E2E_ROOT"
    mkdir -p "$SHARE_DIR/sub" "$E2E_ROOT/server/db"
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
    _open_fifo "$E2E_ROOT/server.in"
    nohup java $JAVA_OPTS -jar "$SERVER_JAR" \
        --server.port="$API_PORT" --sync.socket.port="$SOCKET_PORT" \
        --spring.datasource.url="jdbc:sqlite:$E2E_ROOT/server/db/photos.db" \
        --sync.watcher.scan-interval-seconds="$SCAN_INTERVAL" \
        < "$E2E_ROOT/server.in" > "$E2E_ROOT/server.log" 2>&1 &
    echo $! > "$E2E_ROOT/server.pid"
    wait_for_log "$E2E_ROOT/server.log" "Started SyncServerApp" 30
}

server_cmd() { echo "$*" > "$E2E_ROOT/server.in"; }

# start_client N — mirror dir, DB, log and FIFO live under $E2E_ROOT/cN.
# The client is pointed at localhost and left waiting for `client_cmd N sync <dir>`.
start_client() {
    local n="$1" dir="$E2E_ROOT/c$1"
    mkdir -p "$dir/mirror"
    [ -f "$dir/mirror/client-id" ] || echo "client-$n" > "$dir/mirror/client-id"
    [ -p "$dir/cli.in" ] || _open_fifo "$dir/cli.in"
    log "Starting client $n"
    nohup java $JAVA_OPTS -jar "$CLIENT_JAR" \
        --server.port=0 --sync.server.api-port="$API_PORT" --sync.client.ui=cli \
        --spring.datasource.url="jdbc:sqlite:$dir/sync.db" \
        --sync.client.mirror-dir="$dir/mirror" --sync.client.name="c$n" \
        < "$dir/cli.in" > "$dir/client.log" 2>&1 &
    echo $! > "$dir/client.pid"
    wait_for_log "$dir/client.log" "Using host:port" 30
    if ! grep -q "Using host:port localhost" "$dir/client.log"; then
        client_cmd "$n" host localhost
    fi
    wait_for_log "$dir/client.log" "Server advertises" 60
}

restart_client() {
    local n="$1" dir="$E2E_ROOT/c$1"
    kill "$(cat "$dir/client.pid")" 2>/dev/null
    sleep 2
    : > "$dir/client.log"
    start_client "$n"
}

client_cmd() { local n="$1"; shift; echo "$*" > "$E2E_ROOT/c$n/cli.in"; }

client_log()   { cat "$E2E_ROOT/c$1/client.log"; }
client_files() { (cd "$E2E_ROOT/c$1/mirror" && find . -type f ! -name client-id | sed 's|^\./||' | sort); }
client_db()    { sqlite3 "$E2E_ROOT/c$1/sync.db" "$2"; }
client_version() { client_db "$1" "SELECT last_sync_version FROM root_dirs WHERE dir_name='$2'"; }
server_db()    { sqlite3 "$E2E_ROOT/server/db/photos.db" "$1"; }

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

# Waits until the client has gone at least two poll intervals without new activity.
settle() { sleep "${1:-8}"; }

assert_eq() {
    local what="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then pass "$what"; else fail "$what: expected [$expected] got [$actual]"; fi
}

assert_no_client_errors() {
    local n="$1"
    if grep -E ' (WARN|ERROR) ' "$E2E_ROOT/c$n/client.log" | grep -v 'Connection lost'; then
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
