#!/usr/bin/env bash
# drone_bridge 启动脚本
# 由 rbnx boot 调用，启动 RoboNIX 原语驱动
#
# 环境变量：
#   RBNX_ATLAS          — Atlas gRPC 地址（rbnx 自动注入）
#   RBNX_CAP_CONFIG_JSON — 原语配置 JSON（从 manifest 的 config 注入）
#   RC_PRO_IP           — RC Pro IP（优先于 config）
#   RC_PRO_PORT         — RC Pro 端口（优先于 config）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

export PATH="$HOME/.cargo/bin:$HOME/.local/bin:$PATH"

# ============================================================
# 1. 配置 Robonix Python 环境
# ============================================================
ROBONIX_PYLIB="${ROBONIX_PYLIB:-$HOME/robonix/pylib/robonix-api}"
CODEGEN_DIR="$PROJECT_DIR/rbnx-build/codegen/proto_gen"
export PYTHONPATH="$PROJECT_DIR:$ROBONIX_PYLIB:$CODEGEN_DIR:${PYTHONPATH:-}"

# ============================================================
# 2. 解析 RC Pro 地址
# ============================================================
if [ -n "${RBNX_CAP_CONFIG_JSON:-}" ]; then
    RC_IP=$(python3 -c "import json,sys; c=json.loads(sys.argv[1]); print(c.get('rc_pro_ip','10.225.57.15'))" "$RBNX_CAP_CONFIG_JSON" 2>/dev/null || echo "10.225.57.15")
    RC_PORT=$(python3 -c "import json,sys; c=json.loads(sys.argv[1]); print(c.get('rc_pro_port',8080))" "$RBNX_CAP_CONFIG_JSON" 2>/dev/null || echo "8080")
    export RC_PRO_IP="${RC_PRO_IP:-$RC_IP}"
    export RC_PRO_PORT="${RC_PRO_PORT:-$RC_PORT}"
else
    export RC_PRO_IP="${RC_PRO_IP:-10.225.57.15}"
    export RC_PRO_PORT="${RC_PRO_PORT:-8080}"
fi

echo "[drone_bridge] RC Pro: ${RC_PRO_IP}:${RC_PRO_PORT}"
echo "[drone_bridge] Atlas:  ${RBNX_ATLAS:-auto}"

# ============================================================
# 3. 安装运行时依赖
# ============================================================
pip3 install -q requests 2>/dev/null || true

# ============================================================
# 4. 启动 RoboNIX 原语驱动
# ============================================================
exec python3 -u -m drone_bridge.driver
