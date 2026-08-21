#!/bin/bash
# 优雅停止原语服务
pkill -TERM -f "python3 -m src.driver" 2>/dev/null || true
sleep 1
pkill -KILL -f "python3 -m src.driver" 2>/dev/null || true
