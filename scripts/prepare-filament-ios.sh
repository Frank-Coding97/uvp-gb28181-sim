#!/bin/sh
set -eu

VERSION="1.71.6"
# cross-review R1 #6:官方 release SHA-256(2026-07-12 通过本地已下载 tgz 校验)。
# 更新 VERSION 时必须同步更新此值,否则下载后 checksum 校验失败拒绝解包。
EXPECTED_SHA256="dc4d46a14660649bd99f667e5ff7b41c3aec3ba93b93d0073513920440cd1efc"
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)/.build/filament-ios}"
ARCHIVE="$ROOT/filament-v${VERSION}-ios.tgz"
DIST="$ROOT/filament"

verify_archive() {
  # 优先 shasum(macOS 默认),兜底 sha256sum(Linux CI)。
  actual=""
  if command -v shasum >/dev/null 2>&1; then
    actual=$(shasum -a 256 "$ARCHIVE" | awk '{print $1}')
  elif command -v sha256sum >/dev/null 2>&1; then
    actual=$(sha256sum "$ARCHIVE" | awk '{print $1}')
  else
    echo "prepare-filament-ios: neither shasum nor sha256sum available; cannot verify integrity" >&2
    exit 1
  fi
  if [ "$actual" != "$EXPECTED_SHA256" ]; then
    echo "prepare-filament-ios: SHA-256 mismatch for $ARCHIVE" >&2
    echo "  expected: $EXPECTED_SHA256" >&2
    echo "  actual:   $actual" >&2
    rm -f "$ARCHIVE"
    exit 1
  fi
}

if [ ! -f "$DIST/include/filament/Engine.h" ]; then
  mkdir -p "$ROOT"
  if [ ! -f "$ARCHIVE" ]; then
    curl -L --fail --retry 3 --connect-timeout 10 \
      "https://github.com/google/filament/releases/download/v${VERSION}/filament-v${VERSION}-ios.tgz" \
      -o "$ARCHIVE"
  fi
  verify_archive
  tar -xzf "$ARCHIVE" -C "$ROOT"
fi

build_slice() {
  sdk="$1"
  slice="$2"
  out="$ROOT/lib/$sdk/libfilament-all.a"
  if [ -f "$out" ] && [ "$out" -nt "$ARCHIVE" ]; then
    return
  fi
  mkdir -p "$ROOT/lib/$sdk"
  # Keep only the runtime libraries used by Engine + gltfio. The release
  # bundle also contains optional viewer/debug/tooling libraries and several
  # bundled copies of zstd/meshoptimizer; flattening all of them into one
  # archive creates duplicate symbols (and libviewer pulls in ImGui).
  required="
    libabseil.a
    libbackend.a
    libbasis_transcoder.a
    libdracodec.a
    libfilabridge.a
    libfilaflat.a
    libfilament.a
    libgeometry.a
    libgltfio_core.a
    libimage.a
    libimageio-lite.a
    libktxreader.a
    libshaders.a
    libstb.a
    libuberarchive.a
    libuberzlib.a
    libutils.a
    libzstd.a
  "
  libs=""
  for name in $required; do
    lib="$DIST/lib/${name%.a}.xcframework/$slice/$name"
    if [ ! -f "$lib" ]; then
      echo "Missing Filament library: $lib" >&2
      exit 1
    fi
    libs="$libs \"$lib\""
  done
  eval "set -- $libs"
  libtool -static -o "$out" "$@"
}

build_slice iphoneos ios-arm64
build_slice iphonesimulator ios-arm64_x86_64-simulator
