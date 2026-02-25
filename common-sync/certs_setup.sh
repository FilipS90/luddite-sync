#!/bin/bash

set -e  # Exit on error

echo "=== Certificate Generation for Sync App ==="
echo ""

# Check dependencies
if ! command -v openssl &> /dev/null; then
    echo "Error: openssl not found"
    exit 1
fi

if ! command -v keytool &> /dev/null; then
    echo "Error: keytool not found (install JDK)"
    exit 1
fi

# Create temp directory
TEMP_DIR="certs-temp"
mkdir -p "$TEMP_DIR"
cd "$TEMP_DIR"

KEYSTORE_PASSWORD="fichony123!"

echo "Step 1/7: Generating CA..."
openssl genrsa -out ca-key.pem 4096
openssl req -new -x509 -days 3650 -key ca-key.pem -out ca-cert.pem \
    -subj "/C=US/ST=State/L=City/O=LudditeSync/CN=Sync CA"

echo "Step 2/7: Generating Server certificate..."
openssl genrsa -out server-key.pem 4096
openssl req -new -key server-key.pem -out server-csr.pem \
    -subj "/C=US/ST=State/L=City/O=LudditeSync/CN=Sync Server"
openssl x509 -req -days 3650 -in server-csr.pem \
    -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial \
    -out server-cert.pem

echo "Step 3/7: Generating Client certificate..."
openssl genrsa -out client-key.pem 4096
openssl req -new -key client-key.pem -out client-csr.pem \
    -subj "/C=US/ST=State/L=City/O=LudditeSync/CN=Sync Client"
openssl x509 -req -days 3650 -in client-csr.pem \
    -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial \
    -out client-cert.pem

echo "Step 4/7: Creating server keystore..."
openssl pkcs12 -export -in server-cert.pem -inkey server-key.pem \
    -out server-keystore.p12 -name server \
    -passout pass:$KEYSTORE_PASSWORD

echo "Step 5/7: Creating client keystore..."
openssl pkcs12 -export -in client-cert.pem -inkey client-key.pem \
    -out client-keystore.p12 -name client \
    -passout pass:$KEYSTORE_PASSWORD

echo "Step 6/7: Creating truststore..."
keytool -import -trustcacerts -alias ca -file ca-cert.pem \
    -keystore truststore.p12 -storetype PKCS12 \
    -storepass $KEYSTORE_PASSWORD -noprompt

echo "Step 7/7: Copying keystores to modules..."

# Copy to server
cp server-keystore.p12 ../../server-sync/src/main/resources/
cp truststore.p12 ../../server-sync/src/main/resources/

# Copy to client
cp client-keystore.p12 ../../client-sync/src/main/resources/
cp truststore.p12 ../../client-sync/src/main/resources/

echo ""
echo "✓ Certificates generated and copied!"
echo ""
echo "Files copied:"
echo "  → server-sync/src/main/resources/server-keystore.p12"
echo "  → server-sync/src/main/resources/truststore.p12"
echo "  → client-sync/src/main/resources/client-keystore.p12"
echo "  → client-sync/src/main/resources/truststore.p12"
echo ""

# Cleanup
cd ..
echo "Cleaning up temporary files..."
rm -rf "$TEMP_DIR"

echo ""
echo "✓ All temporary files deleted"
echo "✓ Setup complete!"