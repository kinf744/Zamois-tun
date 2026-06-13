#!/bin/bash
# ──────────────────────────────────────────────────────────────────────────────
# build_hysteria.sh  — Cross-compile Hysteria2 for Android ABIs
# Requires: Go 1.21+
# ──────────────────────────────────────────────────────────────────────────────

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/../app/src/main/assets/hysteria"

echo "Building Hysteria2 for Android..."

ABIS=(
    "arm64-v8a:arm64:"
    "armeabi-v7a:arm:7"
    "x86_64:amd64:"
)

for ABI_DEF in "${ABIS[@]}"; do
    ABI="${ABI_DEF%%:*}"
    REST="${ABI_DEF#*:}"
    GOARCH="${REST%%:*}"
    GOARM="${REST##*:}"

    OUT="$OUTPUT_DIR/$ABI"
    mkdir -p "$OUT"

    echo "  → Building $ABI (GOARCH=$GOARCH)..."

    BUILD_CMD="CGO_ENABLED=0 GOOS=android GOARCH=$GOARCH"
    if [ -n "$GOARM" ]; then
        BUILD_CMD="$BUILD_CMD GOARM=$GOARM"
    fi

    eval "$BUILD_CMD go build -trimpath -ldflags='-s -w' \
        -o $OUT/hysteria2 \
        github.com/apernet/hysteria/app/cmd"

    if [ -f "$OUT/hysteria2" ]; then
        SIZE=$(du -h "$OUT/hysteria2" | cut -f1)
        echo "    ✓ $OUT/hysteria2 ($SIZE)"
    else
        echo "    ✗ Build failed for $ABI"
    fi
done

echo ""
echo "Done! Hysteria2 binaries in: $OUTPUT_DIR"
