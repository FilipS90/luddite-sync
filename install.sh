#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

SERVER_YML="$SCRIPT_DIR/server-sync/src/main/resources/application.yml"
CLIENT_YML="$SCRIPT_DIR/client-sync/src/main/resources/application.yml"
CERT_SCRIPT="$SCRIPT_DIR/common-sync/certs_setup.sh"
CERT_CHECK="$SCRIPT_DIR/server-sync/src/main/resources/server-keystore.p12"

echo ""
echo "======================================="
echo "   Luddite Sync — Installation Setup   "
echo "======================================="
echo ""

# ── Prompt for values ────────────────────────────────────────────────────────

read -p "  DuckDNS subdomain (without .duckdns.org) [luddite-sync]: " DUCK_DOMAIN
DUCK_DOMAIN="${DUCK_DOMAIN:-luddite-sync}"

read -p "  DuckDNS token: " DUCK_TOKEN
while [ -z "$DUCK_TOKEN" ]; do
    echo "  Token cannot be empty."
    read -p "  DuckDNS token: " DUCK_TOKEN
done

read -s -p "  Keystore password: " KEYSTORE_PASSWORD
echo ""
while [ -z "$KEYSTORE_PASSWORD" ]; do
    echo "  Password cannot be empty."
    read -s -p "  Keystore password: " KEYSTORE_PASSWORD
    echo ""
done

read -s -p "  Confirm keystore password: " KEYSTORE_PASSWORD_CONFIRM
echo ""
while [ "$KEYSTORE_PASSWORD" != "$KEYSTORE_PASSWORD_CONFIRM" ]; do
    echo "  Passwords do not match. Try again."
    read -s -p "  Keystore password: " KEYSTORE_PASSWORD
    echo ""
    read -s -p "  Confirm keystore password: " KEYSTORE_PASSWORD_CONFIRM
    echo ""
done

# ── Certificates ─────────────────────────────────────────────────────────────

echo ""
if [ -f "$CERT_CHECK" ]; then
    echo "  ✓ Certificates already exist — skipping generation"
else
    echo "  Generating certificates..."
    chmod +x "$CERT_SCRIPT"
    (cd "$SCRIPT_DIR/common-sync" && ./certs_setup.sh "$KEYSTORE_PASSWORD")
    echo "  ✓ Certificates generated"
fi

# ── Update server application.yml ────────────────────────────────────────────

echo ""
echo "  Updating server configuration..."

# DuckDNS domain
sed -i "s|domain:.*|domain: $DUCK_DOMAIN|" "$SERVER_YML"

# DuckDNS token — write literal value, not env var reference
sed -i "s|token:.*|token: $DUCK_TOKEN|" "$SERVER_YML"

# Keystore password
sed -i "s|password:.*|password: $KEYSTORE_PASSWORD|" "$SERVER_YML"

echo "  ✓ Server configuration updated"

# ── Update client application.yml ────────────────────────────────────────────

echo ""
echo "  Updating client configuration..."

# Server host — point at the DuckDNS hostname
sed -i "s|host:.*|host: $DUCK_DOMAIN.duckdns.org|" "$CLIENT_YML"

# Keystore password
sed -i "s|password:.*|password: $KEYSTORE_PASSWORD|" "$CLIENT_YML"

echo "  ✓ Client configuration updated"

# ── Done ─────────────────────────────────────────────────────────────────────

echo ""
echo "======================================="
echo "   Setup complete!"
echo ""
echo "   Next steps:"
echo "   1. Build:  ./mvnw clean package -DskipTests"
echo ""
echo "   Distribute the following files to BOTH machines:"
echo "     server-sync/target/server-sync-0.0.1-SNAPSHOT.jar"
echo "     client-sync/target/client-sync-0.0.1-SNAPSHOT.jar"
echo "     luddite.sh  (Linux/macOS)"
echo "     luddite.bat (Windows)"
echo ""
echo "   On each machine, place all four files in the same directory."
echo ""
echo "   Start server machine:  ./luddite.sh"
echo "   Start client machine:  ./luddite.sh client <server-host>"
echo "======================================="
echo ""

