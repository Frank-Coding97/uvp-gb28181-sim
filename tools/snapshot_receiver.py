#!/usr/bin/env python3
"""
GB/T 28181-2022 §9.5 抓拍 JPEG 接收 mock 服务。

WVP-Pro 主分支不下发 SnapShotConfig 也不接收 JPEG 上传(2026-06-17 调研结论)。
此脚本起到平台 UploadURL 接收端的作用,模拟器 PUT/POST JPEG 后落盘到 incoming/。

启动:
    python3 tools/snapshot_receiver.py --port 8088 --dir ./dev-env/snapshot-receiver/incoming
默认端口 8088,默认目录 ./incoming。

把 UploadURL 填成 http://<本机IP>:8088/snap/ 下发给设备,设备会按 SnapNum 串行 PUT。

接受 PUT/POST,Content-Type: image/jpeg。其它请求一律 405。
"""
import argparse
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from datetime import datetime


class SnapshotHandler(BaseHTTPRequestHandler):
    server_version = "uvp-snapshot-receiver/0.1"

    def _save(self, method: str) -> None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error(400, "Bad Content-Length")
            return
        if length <= 0:
            self.send_error(411, "Length Required")
            return

        body = self.rfile.read(length)
        if not body[:3] == b"\xff\xd8\xff":
            print(f"[receiver] WARN: body does not start with JPEG SOI (FF D8 FF), got hex={body[:6].hex()}",
                  file=sys.stderr)

        # 文件名:URL path 末尾段如果带 .jpg 就用,否则补 timestamp
        url_path = self.path.lstrip("/")
        if url_path.endswith(".jpg"):
            file_name = os.path.basename(url_path)
        else:
            ts = datetime.now().strftime("%Y%m%dT%H%M%S")
            file_name = f"{ts}_{int(time.time() * 1000) % 1000}.jpg"

        out_dir = self.server.out_dir
        os.makedirs(out_dir, exist_ok=True)
        out_path = os.path.join(out_dir, file_name)
        with open(out_path, "wb") as f:
            f.write(body)

        msg = f"[receiver] {method} {self.path} → {out_path} ({len(body)} bytes)"
        print(msg)

        self.send_response(200, "OK")
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(msg)))
        self.end_headers()
        self.wfile.write(msg.encode("utf-8"))

    def do_PUT(self):
        self._save("PUT")

    def do_POST(self):
        self._save("POST")

    def do_GET(self):
        # 健康探测
        self.send_response(200, "OK")
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"snapshot-receiver up\n")

    def log_message(self, format: str, *args) -> None:
        # 静默默认 INFO,自定义打印替代(避免 BaseHTTPRequestHandler 双倍输出)
        sys.stderr.write("%s - - [%s] %s\n" % (self.address_string(),
                                               self.log_date_time_string(),
                                               format % args))


def serve(port: int, out_dir: str) -> None:
    os.makedirs(out_dir, exist_ok=True)
    abs_dir = os.path.abspath(out_dir)
    server = HTTPServer(("0.0.0.0", port), SnapshotHandler)
    server.out_dir = abs_dir
    print(f"[receiver] listening on 0.0.0.0:{port}, saving to {abs_dir}")
    print(f"[receiver] sample UploadURL: http://<本机IP>:{port}/snap/")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[receiver] stopped.")


def main():
    parser = argparse.ArgumentParser(description="UVP GB28181 snapshot receiver mock")
    parser.add_argument("--port", type=int, default=8088, help="HTTP port (default: 8088)")
    parser.add_argument("--dir", type=str, default="./incoming",
                        help="output directory (default: ./incoming)")
    args = parser.parse_args()
    serve(args.port, args.dir)


if __name__ == "__main__":
    main()
