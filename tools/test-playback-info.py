#!/usr/bin/env python3
"""
M5 batch3 §6.4/§6.5 — 回放 INFO seek/pause/resume 自建测试脚本。

目的:WVP/EasyGBS/LiveGBS 三平台拖动暂停均走「BYE+重 INVITE」,无法触发设备端
INFO MANSRTSP 路径。本脚本用 raw UDP socket 构造 INFO 信令,验证 sim 既有
PlaybackEngine.seek / pause / resume 路径走通(矩阵 6.4/6.5 ⚠️→✅)。

前置:sim 已注册 + 平台已发起 PLAYBACK INVITE 建立回放会话 → 拿到 callId / fromTag /
toTag / 设备地址 / dialog 内 CSeq。

用法:
    python3 tools/test-playback-info.py \\
        --sim-ip 192.168.1.50 --sim-port 5060 \\
        --device-id 34020000001320000001 \\
        --call-id <FROM-INVITE-200> --from-tag <FROM> --to-tag <TO> \\
        --action seek --range 60.0 \\
        --cseq 100

action:
  - seek    : 发 PLAY MANSRTSP/1.0 + Range: npt=N-     (N = --range)
  - pause   : 发 PAUSE MANSRTSP/1.0
  - resume  : 发 PLAY MANSRTSP/1.0 (无 Range 头)

验证(在 sim 端):
  - 系统日志屏出现 "回放 seek → 60.0s" / "回放暂停" / "回放恢复"
  - SIP 日志屏出现 INFO 入 + 200 OK 出
  - RTP 包时间戳 / 媒体节奏跳变

不依赖任何第三方库(pure stdlib)。
"""
from __future__ import annotations
import argparse
import socket
import sys
import time
from typing import Optional


def build_info_message(
    sim_ip: str,
    sim_port: int,
    device_id: str,
    call_id: str,
    from_tag: str,
    to_tag: str,
    cseq: int,
    body: str,
    local_ip: str = "0.0.0.0",
    local_port: int = 0,
) -> str:
    """构造 SIP INFO + MANSRTSP body(RFC3261 + GB28181 §9.7.2)。"""
    branch = f"z9hG4bK-info-{int(time.time() * 1000) % 10_000_000}"
    body_bytes = body.encode("utf-8")
    headers = [
        f"INFO sip:{device_id}@{sim_ip}:{sim_port} SIP/2.0",
        f"Via: SIP/2.0/UDP {local_ip}:{local_port};branch={branch};rport",
        # 平台是 caller(发起 INVITE),所以 INFO 的 From=平台 To=设备
        f"From: <sip:34020000002000000001@3402000000>;tag={from_tag}",
        f"To: <sip:{device_id}@3402000000>;tag={to_tag}",
        f"Call-ID: {call_id}",
        f"CSeq: {cseq} INFO",
        f"Content-Type: application/MANSRTSP",
        f"Content-Length: {len(body_bytes)}",
        "Max-Forwards: 70",
        "User-Agent: uvp-sim-info-tester/1.0",
        "",
        body,
    ]
    return "\r\n".join(headers)


def build_mansrtsp_body(action: str, npt_seconds: Optional[float], cseq: int) -> str:
    """构造 MANSRTSP body(GB28181 §9.7.2)。"""
    if action == "seek":
        if npt_seconds is None:
            raise ValueError("seek 需要 --range 参数")
        return (
            f"PLAY MANSRTSP/1.0\r\n"
            f"CSeq: {cseq}\r\n"
            f"Range: npt={npt_seconds:.1f}-\r\n"
            f"\r\n"
        )
    elif action == "pause":
        return f"PAUSE MANSRTSP/1.0\r\nCSeq: {cseq}\r\nPauseTime: now\r\n\r\n"
    elif action == "resume":
        return f"PLAY MANSRTSP/1.0\r\nCSeq: {cseq}\r\n\r\n"
    else:
        raise ValueError(f"未知 action: {action}(可选 seek/pause/resume)")


def main():
    p = argparse.ArgumentParser(description="GB28181 回放 INFO 自建测试")
    p.add_argument("--sim-ip", required=True, help="设备 IP")
    p.add_argument("--sim-port", type=int, default=5060)
    p.add_argument("--device-id", required=True, help="GB28181 设备 ID(20 位)")
    p.add_argument("--call-id", required=True, help="原 INVITE 200 OK 里的 Call-ID")
    p.add_argument("--from-tag", required=True, help="原 dialog 中平台侧 tag")
    p.add_argument("--to-tag", required=True, help="原 dialog 中设备侧 tag")
    p.add_argument("--cseq", type=int, default=100, help="本次 INFO 的 CSeq")
    p.add_argument("--action", required=True, choices=["seek", "pause", "resume"])
    p.add_argument("--range", type=float, dest="npt", default=None,
                   help="seek 目标秒数(npt 起始时间)")
    p.add_argument("--local-port", type=int, default=15060,
                   help="本地 socket 绑定端口(用于回包)")
    args = p.parse_args()

    body = build_mansrtsp_body(args.action, args.npt, args.cseq)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", args.local_port))
    sock.settimeout(3.0)
    local_ip = socket.gethostbyname(socket.gethostname())
    msg = build_info_message(
        sim_ip=args.sim_ip,
        sim_port=args.sim_port,
        device_id=args.device_id,
        call_id=args.call_id,
        from_tag=args.from_tag,
        to_tag=args.to_tag,
        cseq=args.cseq,
        body=body,
        local_ip=local_ip,
        local_port=args.local_port,
    )

    print(f">>> 发送 INFO action={args.action} npt={args.npt} → {args.sim_ip}:{args.sim_port}")
    print(msg)
    print()
    sock.sendto(msg.encode("utf-8"), (args.sim_ip, args.sim_port))

    print("<<< 等待响应(3s 超时)...")
    try:
        data, addr = sock.recvfrom(8192)
        print(f"--- 收到 {len(data)} 字节 from {addr}:")
        print(data.decode("utf-8", errors="replace"))
        first_line = data.decode("utf-8", errors="replace").splitlines()[0]
        if "200" in first_line:
            print(f"\n✅ {first_line.strip()} — sim 已接受 INFO,前往 sim 系统日志验证")
            return 0
        else:
            print(f"\n⚠️ {first_line.strip()} — 非 200 响应")
            return 1
    except socket.timeout:
        print("⚠️ 3s 内无响应。可能原因:")
        print("  - sim 未运行 / 未注册 / 防火墙阻断")
        print("  - call-id/tag 不在活跃 dialog,sim 静默丢弃(SIP 481)")
        print("  - 端口错误")
        return 2
    finally:
        sock.close()


if __name__ == "__main__":
    sys.exit(main())
