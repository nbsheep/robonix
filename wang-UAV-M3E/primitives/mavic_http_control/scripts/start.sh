#!/bin/bash
# 获取包根目录
PACKAGE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PACKAGE_DIR" || exit

# 安装依赖（如果还没安装）
pip install -r requirements.txt --quiet 2>/dev/null || true

# 启动原语服务
python3 -m src.driver
