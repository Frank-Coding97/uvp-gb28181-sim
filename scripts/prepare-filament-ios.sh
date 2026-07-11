#!/bin/sh
set -eu

VERSION="1.71.6"
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)/.build/filament-ios}"
ARCHIVE="$ROOT/filament-v${VERSION}-ios.tgz"
DIST="$ROOT/filament"

if [ ! -f "$DIST/include/filament/Engine.h" ]; then
  mkdir -p "$ROOT"
  if [ ! -f "$ARCHIVE" ]; then
    curl -L --fail --retry 3 --connect-timeout 10 \
      "https://github.com/google/filament/releases/download/v${VERSION}/filament-v${VERSION}-ios.tgz" \
      -o "$ARCHIVE"
  fi
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
