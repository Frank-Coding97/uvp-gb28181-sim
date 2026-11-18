#!/usr/bin/env python3
"""把 Android 在线升级清单写进官网仓库。

生成的清单地址：
    https://uvplatform.com/upgrades/android/stable/latest.json

客户端（composeApp 的 UpdateCenter）读同一份 JSON，字段口径：
    version      展示用版本号，不带 v 前缀
    versionCode  安卓 versionCode，比较**只认这个**（版本号字符串不可靠）
    notes        更新说明原文，按行展示
    pub_date     发布时间 ISO-8601
    minSdk       新版本要求的最低 API（本项目固定 26）
    apk.url      安装包直链
    apk.sha256   安装包 sha256（小写十六进制）—— 客户端下载后必须校验
    apk.bytes    安装包字节数

用法：
    # 默认从官网 releases.json 取更新说明，直链走 Gitee Release
    python3 scripts/release/publish-android-update-manifest.py \\
        --apk build/app/outputs/apk/release/uvp-gb28181-sim-1.0.12.apk \\
        --version 1.0.12 --version-code 10012

    # 演练（只打印，不写文件、不检查直链）
    python3 scripts/release/publish-android-update-manifest.py --apk ... \\
        --version 1.0.12 --version-code 10012 --dry-run

为什么默认 Gitee：和桌面端同一个理由 —— GitHub 会把附件名里的空格换成点，
虽然 APK 名不含空格，但保持两个产品的升级链路同源，排查时只看一个地方。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timezone
from pathlib import Path

VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
WEBSITE_PRODUCT = "uvp-28181-sim"
GITEE_REPO = "Frank-Coding/uvp-gb28181-sim"
GITHUB_REPO = "Frank-Coding97/uvp-gb28181-sim"
PUBLIC_PREFIX = "https://uvplatform.com/upgrades/android"


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_notes(args: argparse.Namespace) -> str:
    if args.notes:
        return args.notes.strip()
    if args.notes_file:
        return Path(args.notes_file).read_text(encoding="utf-8").strip()
    try:
        data = json.loads(Path(args.notes_from).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return ""
    entries = data if isinstance(data, list) else data.get("releases", [])
    for entry in entries:
        if entry.get("productId") != WEBSITE_PRODUCT:
            continue
        if str(entry.get("version")) != args.version:
            continue
        notes = entry.get("notes") or [entry.get("note")]
        lines = [str(item).strip() for item in notes if str(item or "").strip()]
        return "\n".join(f"- {line.lstrip('-* ')}" for line in lines)
    return ""


def download_url(args: argparse.Namespace, filename: str) -> str:
    tag = f"v{args.version}"
    if args.host == "github":
        return f"https://github.com/{GITHUB_REPO}/releases/download/{tag}/{filename}"
    return f"https://gitee.com/{GITEE_REPO}/releases/download/{tag}/{filename}"


def probe(url: str) -> None:
    """确认直链可用。Gitee 对 Range 请求偶尔直接断连（桌面端实测过），重试一次。"""
    request = urllib.request.Request(url, headers={"Range": "bytes=0-1023"})
    last_error: Exception | None = None
    for attempt in range(1, 3):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                if response.status not in (200, 206):
                    raise SystemExit(f"更新包直链不可用（HTTP {response.status}）：{url}")
                print(f"直链可用：HTTP {response.status}")
                return
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            last_error = error
            if attempt == 1:
                print(f"直链请求失败（{error}），重试一次")
                time.sleep(2)
    raise SystemExit(f"更新包直链请求失败（{last_error}）：{url}")


def main() -> None:
    parser = argparse.ArgumentParser(description="生成 Android 在线升级清单")
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--min-sdk", type=int, default=26)
    parser.add_argument("--host", choices=("gitee", "github"), default="gitee")
    parser.add_argument(
        "--site-dir",
        type=Path,
        default=Path.home() / "code/uvp/uvplatform-website/docs/public/upgrades/android",
    )
    parser.add_argument(
        "--notes-from",
        default=str(Path.home() / "code/uvp/uvplatform-website/data/releases.json"),
    )
    parser.add_argument("--notes-file", default="")
    parser.add_argument("--notes", default="")
    parser.add_argument("--release-date", default=date.today().isoformat())
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--no-check", action="store_true")
    args = parser.parse_args()

    if not VERSION_RE.fullmatch(args.version):
        parser.error("--version 必须是 MAJOR.MINOR.PATCH")
    if args.version_code <= 0:
        parser.error("--version-code 必须是正整数")
    if not args.apk.is_file():
        parser.error(f"APK 不存在：{args.apk}")

    expected = f"uvp-gb28181-sim-{args.version}.apk"
    if args.apk.name != expected:
        parser.error(f"APK 文件名必须是 {expected}，实际是 {args.apk.name}")

    filename = args.apk.name
    url = download_url(args, filename)
    payload = {
        "version": args.version,
        "versionCode": args.version_code,
        "notes": read_notes(args),
        "pub_date": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "minSdk": args.min_sdk,
        "apk": {
            "url": url,
            "sha256": sha256_of(args.apk),
            "bytes": args.apk.stat().st_size,
        },
        "releasedOn": args.release_date,
    }
    if not payload["notes"]:
        print("提示：没有取到更新说明（notes 为空），客户端「更新说明」区会是空白。")

    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    print(f"\n版本 {args.version} 清单：\n{text}")

    if not args.no_check and not args.dry_run:
        probe(url)

    stable = args.site_dir / "stable/latest.json"
    snapshot = args.site_dir / f"releases/{args.version}/latest.json"
    if args.dry_run:
        print(f"[dry-run] 将写入：\n  {stable}\n  {snapshot}")
        return

    for path in (stable, snapshot):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        print(f"已写入 {path}")

    site_root = args.site_dir
    for _ in range(6):
        if (site_root / "data/releases.json").is_file():
            break
        site_root = site_root.parent
    print("\n下一步（官网推上去约 1 分钟生效）：")
    print(f"  cd {site_root} && git add docs/public/upgrades/android && \\")
    print(f"    git commit -m 'chore(android): 发布 v{args.version} 在线升级清单' && git push")
    print("\n生效后验证（判据是 JSON 里的 version，不是 HTTP 200）：")
    print(
        f"  curl -s {PUBLIC_PREFIX}/stable/latest.json | "
        "python3 -c 'import sys,json;print(json.load(sys.stdin)[\"version\"])'"
    )


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
