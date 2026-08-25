#!/usr/bin/env python3
# SPDX-License-Identifier: MulanPSL-2.0
"""dji_msdk standalone REPL —— 不依赖 RoboNIX 框架的后端手动联调入口。

驱动层（框架分发）在 ``driver.py``（`@drone.mcp` handler）。本文件是**独立测试
REPL**，直接用 ``MsdkBackend`` 调 APK HTTP 端点，用于在无框架环境下逐个验证
11 个语义方法。

运行:
    RC_PRO_IP=<ip> python3 -m dji_msdk.main
"""
from __future__ import annotations

import json
import logging
import os

from .backend import MsdkBackend

logging.basicConfig(level=logging.INFO, format="[dji_msdk] %(message)s")
log = logging.getLogger("dji_msdk.repl")

_HOST = os.environ.get("RC_PRO_IP", "10.225.57.15")
_PORT = int(os.environ.get("RC_PRO_PORT", "8080"))

# 11 个语义方法（backend.py 的公开方法）
_SEMANTIC = [
    "takeoff", "land", "hover", "rth",
    "move_velocity", "rotate_velocity",
    "gimbal_velocity", "gimbal_reset",
    "camera_capture", "camera_video", "state",
]


def _help() -> None:
    print("\n命令:")
    print("  ping                     —— 探测 APK 可达性")
    print("  status                   —— 原始 /api/status")
    print("  <method> [json_args]     —— 调用任一语义方法，如: takeoff '{\"altitude\":3.0}'")
    print("  可用方法: " + " ".join(_SEMANTIC))
    print("  q / quit / exit          —— 退出\n")


def main() -> None:
    backend = MsdkBackend(_HOST, _PORT)
    print(f"[dji_msdk] standalone REPL 目标: {backend.base}")
    _help()

    while True:
        try:
            raw = input("dji> ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not raw:
            continue
        parts = raw.split(maxsplit=1)
        cmd = parts[0].lower()
        if cmd in ("q", "quit", "exit"):
            break
        if cmd == "help":
            _help()
            continue
        if cmd == "ping":
            print(json.dumps(backend.ping(), ensure_ascii=False))
            continue
        if cmd == "status":
            print(json.dumps(backend._get("/api/status"), ensure_ascii=False))
            continue

        method = getattr(backend, cmd, None)
        if method is None or not callable(method) or cmd.startswith("_"):
            print(f"未知方法: {cmd}（输入 help 查看）")
            continue
        args = {}
        if len(parts) > 1:
            try:
                args = json.loads(parts[1])
            except json.JSONDecodeError as e:
                print(f"参数不是合法 JSON: {e}")
                continue
        try:
            result = method(**args) if isinstance(args, dict) else method()
            print(json.dumps(result, ensure_ascii=False))
        except TypeError as e:
            print(f"参数不匹配: {e}")


if __name__ == "__main__":
    main()
