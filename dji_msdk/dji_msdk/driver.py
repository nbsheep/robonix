#!/usr/bin/env python3
# SPDX-License-Identifier: MulanPSL-2.0
"""dji_msdk 驱动 —— DJI 无人机 RoboNIX 原语驱动（MCP handler 层）。

所有能力通过 **MCP** 暴露（executor 的外部能力分发硬编码走 ``Transport::Mcp``，
``rbnx call`` 只认 MCP 声明的能力），类型使用 ``rbnx codegen --mcp`` 生成的
``drone_mcp`` / ``std_msgs_mcp`` dataclass。

Capability surface（11 primitives，均为 ``robonix/primitive/drone/*``）：
  takeoff / land / move_velocity / rotate_velocity / hover / rth /
  gimbal_velocity / gimbal_reset / camera_capture / camera_video / state

本文件 **SDK 无关**：只调 ``dji_msdk.backend.MsdkBackend`` 的语义方法，不出现任何
MSDK 术语。换 SDK（PSDK/OSDK/PX4）只换后端，本文件与契约层不变。
"""
from __future__ import annotations

import json
import os
from typing import Any

from robonix_api import Primitive, Ok  # type: ignore

from .backend import MsdkBackend

# ── 导入 codegen 生成的 MCP dataclass ──
# 由 `rbnx codegen -p . --out-dir rbnx-build/codegen --mcp` 生成到
# rbnx-build/codegen/robonix_mcp_types/ 下。
import drone_mcp  # noqa: E402
import std_msgs_mcp  # noqa: E402

drone = Primitive(id="dji_msdk", namespace="robonix/primitive/drone")

_backend: MsdkBackend | None = None


def _get_backend() -> MsdkBackend:
    """获取已初始化的后端（未初始化时从环境变量懒加载）。"""
    global _backend
    if _backend is None:
        host = os.environ.get("RC_PRO_IP", "10.225.57.15")
        port = int(os.environ.get("RC_PRO_PORT", "8080"))
        _backend = MsdkBackend(host, port)
    return _backend


def _status(result) -> std_msgs_mcp.String:
    """把 dict 结果序列化为 std_msgs/String 响应字段。"""
    return std_msgs_mcp.String(data=json.dumps(result, ensure_ascii=False))


# ═══════════════════════════════════════════════════════════════════════════════
# 运动控制
# ═══════════════════════════════════════════════════════════════════════════════

@drone.mcp("robonix/primitive/drone/takeoff")
def takeoff(req: drone_mcp.Takeoff_Request) -> drone_mcp.Takeoff_Response:
    """起飞并悬停至指定高度。"""
    alt = float(req.altitude) if req.altitude > 0 else 3.0
    return drone_mcp.Takeoff_Response(status=_status(_get_backend().takeoff(alt)))


@drone.mcp("robonix/primitive/drone/land")
def land(_req: drone_mcp.Land_Request) -> drone_mcp.Land_Response:
    """原地降落。"""
    return drone_mcp.Land_Response(status=_status(_get_backend().land()))


@drone.mcp("robonix/primitive/drone/move_velocity")
def move_velocity(req: drone_mcp.MoveVelocity_Request) -> drone_mcp.MoveVelocity_Response:
    """机体系 6DOF 速度向量（twist）控制。"""
    result = _get_backend().move_velocity(
        vx=float(req.vx), vy=float(req.vy), vz=float(req.vz),
        wx=float(req.wx), wy=float(req.wy), wz=float(req.wz),
        duration=float(req.duration),
    )
    return drone_mcp.MoveVelocity_Response(status=_status(result))


@drone.mcp("robonix/primitive/drone/rotate_velocity")
def rotate_velocity(req: drone_mcp.RotateVelocity_Request) -> drone_mcp.RotateVelocity_Response:
    """旋转：direction 1=右转/-1=左转，angular_velocity rad/s，duration 秒。"""
    result = _get_backend().rotate_velocity(
        direction=float(req.direction),
        angular_velocity=float(req.angular_velocity),
        duration=float(req.duration),
    )
    return drone_mcp.RotateVelocity_Response(status=_status(result))


@drone.mcp("robonix/primitive/drone/hover")
def hover(_req: drone_mcp.Hover_Request) -> drone_mcp.Hover_Response:
    """紧急悬停。"""
    return drone_mcp.Hover_Response(status=_status(_get_backend().hover()))


@drone.mcp("robonix/primitive/drone/rth")
def rth(_req: drone_mcp.Rth_Request) -> drone_mcp.Rth_Response:
    """智能返航（返航并降落）。"""
    return drone_mcp.Rth_Response(status=_status(_get_backend().rth()))


# ═══════════════════════════════════════════════════════════════════════════════
# 云台 / 相机
# ═══════════════════════════════════════════════════════════════════════════════

@drone.mcp("robonix/primitive/drone/gimbal_velocity")
def gimbal_velocity(req: drone_mcp.GimbalVelocity_Request) -> drone_mcp.GimbalVelocity_Response:
    """云台 3DOF 角速度（°/s）控制。"""
    result = _get_backend().gimbal_velocity(
        vpitch=float(req.vpitch), vroll=float(req.vroll), vyaw=float(req.vyaw),
        duration=float(req.duration),
    )
    return drone_mcp.GimbalVelocity_Response(status=_status(result))


@drone.mcp("robonix/primitive/drone/gimbal_reset")
def gimbal_reset(_req: drone_mcp.GimbalReset_Request) -> drone_mcp.GimbalReset_Response:
    """云台回中（平视）。"""
    return drone_mcp.GimbalReset_Response(status=_status(_get_backend().gimbal_reset()))


@drone.mcp("robonix/primitive/drone/camera_capture")
def camera_capture(_req: drone_mcp.CameraCapture_Request) -> drone_mcp.CameraCapture_Response:
    """触发单张拍照。"""
    return drone_mcp.CameraCapture_Response(status=_status(_get_backend().camera_capture()))


@drone.mcp("robonix/primitive/drone/camera_video")
def camera_video(_req: drone_mcp.CameraVideo_Request) -> drone_mcp.CameraVideo_Response:
    """获取视频流 URL（MJPEG）。"""
    return drone_mcp.CameraVideo_Response(status=_status(_get_backend().camera_video()))


# ═══════════════════════════════════════════════════════════════════════════════
# 状态查询
# ═══════════════════════════════════════════════════════════════════════════════

@drone.mcp("robonix/primitive/drone/state")
def state(_req: drone_mcp.State_Request) -> drone_mcp.State_Response:
    """获取无人机完整状态（/api/status + /api/capture_gps）。"""
    return drone_mcp.State_Response(status=_status(_get_backend().state()))


# ═══════════════════════════════════════════════════════════════════════════════
# Lifecycle
# ═══════════════════════════════════════════════════════════════════════════════

@drone.on_init
def init(config: dict | None) -> Any:
    """启动时初始化后端并探测 RC Pro 连接。"""
    global _backend
    cfg = config or {}
    host = cfg.get("rc_pro_ip") or os.environ.get("RC_PRO_IP", "10.225.57.15")
    port = int(cfg.get("rc_pro_port") or os.environ.get("RC_PRO_PORT", "8080"))
    _backend = MsdkBackend(host, port)
    if not _backend.ping().get("success"):
        print(f"[dji_msdk] ⚠ 无法连接到 RC Pro ({_backend.base})，将继续注册但调用可能失败", flush=True)
    else:
        print(f"[dji_msdk] ✅ 已连接到 RC Pro ({_backend.base})", flush=True)
    return Ok()


@drone.on_shutdown
def shutdown() -> Any:
    """关闭时清理。"""
    global _backend
    _backend = None
    return Ok()


# ═══════════════════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    drone.run()
