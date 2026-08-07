#!/usr/bin/env python3
"""Convert a strict semver release version to the Android versionCode scheme."""

from __future__ import annotations

import re
import sys


VERSION_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")


def version_code(version: str) -> int:
    match = VERSION_RE.fullmatch(version)
    if not match:
        raise ValueError(f"version must be MAJOR.MINOR.PATCH, got: {version}")

    major, minor, patch = (int(part) for part in match.groups())
    if major > 99 or minor > 99 or patch > 99:
        raise ValueError("each version component must be <= 99")
    return major * 10000 + minor * 100 + patch


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: version_code.py MAJOR.MINOR.PATCH")
    print(version_code(sys.argv[1]))
