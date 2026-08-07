#!/usr/bin/env bash
set -euo pipefail

# Uploads one immutable APK first and switches the metadata pointer last.
# The script runs on the download host and deliberately does not restart Nginx.

SITE_ROOT=${1:?usage: deploy-download-site.sh SITE_ROOT STAGING_DIR VERSION}
STAGING_DIR=${2:?usage: deploy-download-site.sh SITE_ROOT STAGING_DIR VERSION}
VERSION=${3:?usage: deploy-download-site.sh SITE_ROOT STAGING_DIR VERSION}
SITE_ROOT=${SITE_ROOT%/}

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "invalid release version: $VERSION" >&2
  exit 2
fi
if [[ "$STAGING_DIR" != "$SITE_ROOT"/.ci-staging/* ]]; then
  echo "staging directory must be inside $SITE_ROOT/.ci-staging" >&2
  exit 2
fi

META="$STAGING_DIR/mobile.json"
test -s "$META"
test "$(jq -er '.version' "$META")" = "$VERSION"
APK_NAME=$(jq -er --arg version "$VERSION" '.filename | select(. == ("uvp-gb28181-sim-" + $version + ".apk"))' "$META")
APK="$STAGING_DIR/$APK_NAME"
test -s "$APK"

EXPECTED_BYTES=$(jq -er '.bytes | numbers' "$META")
EXPECTED_SHA=$(jq -er '.sha256 | strings | select(test("^[0-9a-f]{64}$"))' "$META")
ACTUAL_BYTES=$(stat -c '%s' "$APK")
ACTUAL_SHA=$(sha256sum "$APK" | awk '{print $1}')
test "$EXPECTED_BYTES" = "$ACTUAL_BYTES"
test "$EXPECTED_SHA" = "$ACTUAL_SHA"

test -d "$SITE_ROOT/releases"
test -f "$SITE_ROOT/mobile/index.html"
STAMP=$(date -u +%Y%m%d-%H%M%S)
BACKUP="$SITE_ROOT/.backups/mobile-$STAMP-$VERSION"
mkdir -p "$BACKUP"

if [ -f "$SITE_ROOT/releases/mobile.json" ]; then
  cp -p "$SITE_ROOT/releases/mobile.json" "$BACKUP/mobile.json"
fi

# A versioned filename makes the APK itself immutable. Only the JSON pointer
# is switched atomically after the complete APK has reached the bind mount.
install -m 0644 "$APK" "$SITE_ROOT/releases/.${APK_NAME}.incoming"
mv -f "$SITE_ROOT/releases/.${APK_NAME}.incoming" "$SITE_ROOT/releases/$APK_NAME"
install -m 0644 "$META" "$SITE_ROOT/releases/.mobile.json.incoming"
mv -f "$SITE_ROOT/releases/.mobile.json.incoming" "$SITE_ROOT/releases/mobile.json"

test "$(jq -er '.version' "$SITE_ROOT/releases/mobile.json")" = "$VERSION"
test "$(sha256sum "$SITE_ROOT/releases/$APK_NAME" | awk '{print $1}')" = "$EXPECTED_SHA"

rm -rf -- "$STAGING_DIR"
printf 'published version=%s apk=%s backup=%s\n' "$VERSION" "$APK_NAME" "$BACKUP"
