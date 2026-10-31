#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE_ONLY=false

for arg in "$@"; do
    case "$arg" in
        --package-only) PACKAGE_ONLY=true ;;
        -h|--help)
            cat <<'USAGE'
Build a native Apple Silicon macOS DMG installer for Nuvio HTPC.

Usage:
  ./scripts/build-macos-release-dmg.sh [--package-only]

By default, this builds MPVKit's arm64/x86_64 macOS libmpv artifacts and then
packages the app. --package-only skips MPVKit's source build and expects those
artifacts to already exist under MPVKit/dist.

Optional signing/notarization properties are read from local.properties or the
NUVIO_MACOS_SIGNING_IDENTITY, NUVIO_MACOS_NOTARY_APPLE_ID,
NUVIO_MACOS_NOTARY_TEAM_ID and NUVIO_MACOS_NOTARY_PASSWORD environment variables.
USAGE
            exit 0
            ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "This installer must be built on macOS (the native player links against Apple's frameworks)." >&2
    exit 1
fi

if [[ "$(uname -m)" != "arm64" ]]; then
    echo "Use an Apple Silicon (arm64) Mac to produce the supported macOS installer." >&2
    exit 1
fi

if [[ "$PACKAGE_ONLY" != true ]]; then
    echo "Building MPVKit macOS dependencies..."
    make -C "$ROOT_DIR/MPVKit" build platform=macos
fi

cd "$ROOT_DIR"
./gradlew :composeApp:packageReleaseDmg

DMG="$(find "$ROOT_DIR/composeApp/build/compose/binaries" -type f -name '*.dmg' -print | sort | tail -n 1)"
if [[ -z "$DMG" || ! -s "$DMG" ]]; then
    echo "Gradle completed but did not produce a DMG under composeApp/build/compose/binaries." >&2
    exit 1
fi

echo "macOS Apple Silicon installer: $DMG"
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    echo "dmg_path=$DMG" >> "$GITHUB_OUTPUT"
fi
