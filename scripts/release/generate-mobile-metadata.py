#!/usr/bin/env python3
"""Generate download-site/releases/mobile.json from the final APK."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import date
from pathlib import Path


VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--release-date", default=date.today().isoformat())
    parser.add_argument("--min-android", default="8.0 (API 26)")
    args = parser.parse_args()

    if not VERSION_RE.fullmatch(args.version):
        parser.error("--version must be MAJOR.MINOR.PATCH")
    if args.version_code <= 0:
        parser.error("--version-code must be positive")
    if not args.apk.is_file():
        parser.error(f"APK does not exist: {args.apk}")

    expected_name = f"uvp-gb28181-sim-{args.version}.apk"
    if args.apk.name != expected_name:
        parser.error(f"APK name must be {expected_name}, got {args.apk.name}")

    payload = {
        "product": "UVP GB28181 Sim",
        "platform": "Android",
        "version": args.version,
        "versionCode": args.version_code,
        "filename": args.apk.name,
        "bytes": args.apk.stat().st_size,
        "sha256": sha256(args.apk),
        "minAndroid": args.min_android,
        "releaseDate": args.release_date,
        "downloadPath": f"../releases/{args.apk.name}",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
