#!/bin/bash
# Luddite Sync — wrapper script
#
# Starts in server mode by default.
#
# Place this script in the same directory as both JARs:
#   server-sync-0.0.1-SNAPSHOT.jar
#   client-sync-0.0.1-SNAPSHOT.jar
#
# Usage:
#   ./luddite.sh                        — start in server mode (default)
#   ./luddite.sh client <remote-host>   — start in client mode pointing at remote-host

# Source common shell configs to pick up exported variables (e.g. KEYSTORE_PASSWORD)
for f in "$HOME/.zshrc" "$HOME/.zshenv" "$HOME/.bashrc" "$HOME/.bash_profile" "$HOME/.profile"; do
    # shellcheck source=/dev/null
    [ -f "$f" ] && . "$f" 2>/dev/null
done

if [ -z "$KEYSTORE_PASSWORD" ]; then
    export KEYSTORE_PASSWORD=test123
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_JAR="$SCRIPT_DIR/server-sync/target/server-sync-0.0.1-SNAPSHOT.jar"
CLIENT_JAR="$SCRIPT_DIR/client-sync/target/client-sync-0.0.1-SNAPSHOT.jar"

MODE=${1:-server}
REMOTE_HOST=${2:-localhost}

while true; do
    if [ "$MODE" = "server" ]; then
        echo "[luddite] Starting in SERVER mode..."
        java -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstdin.encoding=UTF-8 -DKEYSTORE_PASSWORD="$KEYSTORE_PASSWORD" -jar "$SERVER_JAR"
        EXIT_CODE=$?
        break
    else
        echo "[luddite] Starting in CLIENT mode (connecting to $REMOTE_HOST)..."
        java -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstdin.encoding=UTF-8 -DKEYSTORE_PASSWORD="$KEYSTORE_PASSWORD" -jar "$CLIENT_JAR" \
            --sync.server.host="$REMOTE_HOST" \
            --sync.socket.keystore=classpath:client-keystore.p12
        break
    fi
done
