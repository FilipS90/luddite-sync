#!/bin/bash

echo "=== Certificate Cleanup Script ==="
echo "Deleting server keystores..."
rm -f ../server-sync/src/main/resources/server-keystore.p12
rm -f ../server-sync/src/main/resources/truststore.p12

echo "Deleting client keystores..."
rm -f ../client-sync/src/main/resources/client-keystore.p12
rm -f ../client-sync/src/main/resources/truststore.p12

echo ""
echo "✓ All .p12 files deleted!"