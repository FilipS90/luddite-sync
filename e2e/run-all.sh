#!/bin/bash
# Builds the JARs once, then runs every scenario script in this directory in turn.
#
# Usage: e2e/run-all.sh [--no-build]

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ "${1:-}" = "--no-build" ] || (cd "$E2E_DIR/.." && ./mvnw -q -DskipTests package) || exit 1

failed=()
for script in "$E2E_DIR"/sync.sh "$E2E_DIR"/weak-sync.sh "$E2E_DIR"/download.sh; do
    printf '\n\033[1;35m#### %s\033[0m\n' "$(basename "$script")"
    "$script" --no-build || failed+=("$(basename "$script")")
done

echo
if [ "${#failed[@]}" -eq 0 ]; then
    printf '\033[1;32mALL SUITES PASSED\033[0m\n'
else
    printf '\033[1;31mFAILED:\033[0m %s\n' "${failed[*]}"
    exit 1
fi
