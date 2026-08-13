#!/usr/bin/env bash
# drone_bridge 构建脚本
# 运行 rbnx codegen 从 TOML 合约 + IDL 生成 proto stub（drone_pb2, std_msgs_pb2）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

export PATH="$HOME/.cargo/bin:$HOME/.local/bin:$PATH"

echo "[drone_bridge] rbnx codegen..."

if command -v rbnx &>/dev/null; then
    cd "$PROJECT_DIR"
    rbnx codegen -p . --out-dir rbnx-build/codegen
    echo "[drone_bridge] codegen 完成"
else
    echo "[drone_bridge] ⚠ rbnx 未安装，跳过 codegen"
    echo "                 安装: cargo install --git https://github.com/syswonder/robonix rbnx"
    mkdir -p "$PROJECT_DIR/rbnx-build"
    touch "$PROJECT_DIR/rbnx-build/.rbnx-built"
fi
