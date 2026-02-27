#!/bin/bash
# Luddite Sync — wrapper script
#
# Starts in server mode by default.
# If the server exits with code 2 (triggered by a remote shutdown-server signal from the client),
# it switches to client mode, using the client keystore and pointing at the remote host.
# When the client exits normally (code 0), it switches back to server mode.
#
# Place this script in the same directory as both JARs:
#   server-sync-0.0.1-SNAPSHOT.jar
#   client-sync-0.0.1-SNAPSHOT.jar
#
# Usage:
#   ./luddite.sh                        — start in server mode (default)
#   ./luddite.sh client <remote-host>   — start in client mode pointing at remote-host

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_JAR="$SCRIPT_DIR/server-sync-0.0.1-SNAPSHOT.jar"
CLIENT_JAR="$SCRIPT_DIR/client-sync-0.0.1-SNAPSHOT.jar"

MODE=${1:-server}
REMOTE_HOST=${2:-localhost}

while true; do
    if [ "$MODE" = "server" ]; then
        echo "[luddite] Starting in SERVER mode..."
        java -jar "$SERVER_JAR"
        EXIT_CODE=$?
        if [ $EXIT_CODE -eq 2 ]; then
            echo "[luddite] Shutdown signal received — switching to CLIENT mode (connecting to $REMOTE_HOST)"
            MODE=client
        else
            echo "[luddite] Server exited with code $EXIT_CODE — restarting in server mode in 5s..."
            sleep 5
        fi
    else
        echo "[luddite] Starting in CLIENT mode (connecting to $REMOTE_HOST)..."
        java -jar "$CLIENT_JAR" \
            --sync.server.host="$REMOTE_HOST" \
            --sync.socket.keystore=classpath:client-keystore.p12
        EXIT_CODE=$?
        if [ $EXIT_CODE -eq 2 ]; then
            echo "[luddite] Resume-server-mode signal received — switching back to SERVER mode"
        else
            echo "[luddite] Client exited with code $EXIT_CODE — switching back to SERVER mode"
        fi
        MODE=server
    fi
done
