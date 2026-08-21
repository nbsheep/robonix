#!/usr/bin/env python3
# SPDX-License-Identifier: MulanPSL-2.0
"""DJI M3E 无人机 RoboNIX 原语驱动。

通过 HTTP API 桥接 DJI RC Pro (Drone_test APK)，向 RoboNIX 提供无人机
飞控与状态查询能力。

所有能力均通过 **MCP** 暴露（executor 的外部能力分发硬编码走 `Transport::Mcp`，
`rbnx call` 只认 MCP 声明的能力），类型使用 `rbnx codegen --mcp` 生成的
`drone_mcp` / `std_msgs_mcp` dataclass。

Capability surface (11 primitives):
  robonix/primitive/drone/takeoff         rpc  起飞悬停
  robonix/primitive/drone/land            rpc  原地降落
  robonix/primitive/drone/move_velocity   rpc  机体系 6DOF 速度向量控制
  robonix/primitive/drone/rotate_velocity rpc  旋转（方向·角速度·持续时间）
  robonix/primitive/drone/hover           rpc  紧急悬停
  robonix/primitive/drone/rth             rpc  智能返航
  robonix/primitive/drone/gimbal_velocity rpc  云台 3DOF 角速度向量控制
  robonix/primitive/drone/gimbal_reset    rpc  云台回中
  robonix/primitive/drone/camera_capture  rpc  触发单张拍照
  robonix/primitive/drone/camera_video    rpc  获取视频流 URL
  robonix/primitive/drone/state           rpc  完整状态查询（任务状态/操作模式/高度/GPS/巡航/SDK）
"""
from __future__ import annotations

import json
import os

from robonix_api import Primitive, Ok

# ── HTTP 客户端（复用 main.py 中的 DroneClient） ──
from drone_bridge.main import DroneClient

# ── 导入 codegen 生成的 MCP dataclass ──
# 由 `rbnx codegen -p . --out-dir rbnx-build/codegen --mcp` 生成到
# rbnx-build/codegen/robonix_mcp_types/ 下。
import drone_mcp  # noqa: E402
import std_msgs_mcp  # noqa: E402

drone = Primitive(id="drone_bridge", namespace="robonix/primitive/drone")

_client: DroneClient | None = None


def _get_client() -> DroneClient:
    """获取已初始化的 HTTP 客户端"""
    if _client is None:
        rc_ip = os.environ.get("RC_PRO_IP", "10.225.57.15")
        rc_port = int(os.environ.get("RC_PRO_PORT", "8080"))
        return DroneClient(rc_ip, rc_port)
    return _client


def _status(result) -> std_msgs_mcp.String:
    """把 dict 结果序列化为 std_msgs/String 响应字段。"""
    return std_msgs_mcp.String(data=json.dumps(result, ensure_ascii=False))


# ═══════════════════════════════════════════════════════════════════════════════
# 运动控制
# ═══════════════════════════════════════════════════════════════════════════════

@drone.mcp("robonix/primitive/drone/takeoff")
def takeoff(req: drone_mcp.Takeoff_Request) -> drone_mcp.Takeoff_Response:
    """起飞并悬停至指定高度"""
    client = _get_client()
    alt = float(req.altitude) if req.altitude > 0 else 3.0
    result = client.start_mission(climb=alt, move=0.0, yaw=0.0)
    return drone_mcp.Takeoff_Response(status=_status(result))


@drone.mcp("robonix/primitive/drone/land")
def land(_req: drone_mcp.Land_Request) -> drone_mcp.Land_Response:
    """原地降落"""
    client = _get_client()
    result = client.land()
    return drone_mcp.Land_Response(status=_status(result))


@drone.mcp("robonix/primitive/drone/hover")
def hover(_req: drone_mcp.Hover_Request) -> drone_mcp.Hover_Response:
    """紧急悬停"""
    client = _get_client()
    result = client.stop()
    return drone_mcp.Hover_Response(status=_status(result))


@drone.mcp("robonix/primitive/drone/rth")
def rth(_req: drone_mcp.Rth_Request) -> drone_mcp.Rth_Response:
    """智能返航"""
    client = _get_client()
    result = client.go_home()
    return drone_mcp.Rth_Response(status=_status(result))


@drone.mcp("robonix/primitive/drone/move_velocity")
def move_velocity(req: drone_mcp.MoveVelocity_Request) -> drone_mcp.MoveVelocity_Response:
    """机体系 6DOF 速度向量（twist）控制。"""
    client = _get_client()
    result = client.move_velocity(
        vx=float(req.vx), vy=float(req.vy), vz=float(req.vz),
        wx=float(req.wx), wy=float(req.wy), wz=float(req.wz),
        duration=float(req.duration),
    )
    return drone_mcp.MoveVelocity_Response(status=_status(result))


@drone.mcp("robonix/primitive/drone/rotate_velocity")
def rotate_velocity(req: drone_mcp.RotateVelocity_Request) -> drone_mcp.RotateVelocity_Response:
    """旋转（方向·角速度·持续时间）：direction 1=右转/-1=左转，angular_velocity rad/s，duration 秒。"""
    client = _get_client()
    result = client.rotate_velocity(
        direction=float(req.direction),
        angular_velocity=float(req.angular_velocity),
        duration=float(req.duration),
    )
    return drone_mcp.RotateVelocity_Response(status=_status(result))


# ═══════════════════════════════════════════════════════════════════════════════
# 云台 / 相机
# ═══════════════════════════════════════════════════════════════════════════════

@drone.mcp("robonix/primitive/drone/gimbal_velocity")
def gimbal_velocity(req: drone_mcp.GimbalVelocity_Request) -> drone_mcp.GimbalVelocity_Response:
    """云台 3DOF 角速度向量（°/s）控制：vpitch 俯仰 / vroll 横滚 / vyaw 偏航，duration 持续秒数。"""
    client = _get_client()
    result = client.gimbal_velocity(
        vpitch=float(req.vpitch), vroll=float(req.vroll), vyaw=float(req.vyaw),
        duration=float(req.duration),
    )
    return drone_mcp.GimbalVelocity_Response(status=_status(result))


@drone.mcp("robonix/primitive/drone/gimbal_reset")
def gimbal_reset(_req: drone_mcp.GimbalReset_Request) -> drone_mcp.GimbalReset_Response:
    """云台回中（平视）：对应 /api/gimbal {action:"level"}，仅巡航中可用。"""
    client = _get_client()
    result = client.gimbal_reset()
    return drone_mcp.GimbalReset_Response(status=_status(result))


@drone.mcp("robonix/primitive/drone/camera_capture")
def camera_capture(_req: drone_mcp.CameraCapture_Request) -> drone_mcp.CameraCapture_Response:
    """触发单张拍照。"""
    client = _get_client()
    result = client.camera_capture()
    return drone_mcp.CameraCapture_Response(status=_status(result))


@drone.mcp("robonix/primitive/drone/camera_video")
def camera_video(_req: drone_mcp.CameraVideo_Request) -> drone_mcp.CameraVideo_Response:
    """获取视频流 URL（方案 A：返回 MJPEG 流地址，调用方自行拉流）。"""
    client = _get_client()
    result = {
        "success": True,
        "video_url": client.get_video_url(),
        "format": "mjpeg",
        "resolution": "640px",
        "fps": 12,
        "note": "用浏览器 / curl / ffmpeg 拉流即可",
    }
    return drone_mcp.CameraVideo_Response(status=_status(result))


# ═══════════════════════════════════════════════════════════════════════════════
# 状态查询
# ═══════════════════════════════════════════════════════════════════════════════

@drone.mcp("robonix/primitive/drone/state")
def state(_req: drone_mcp.State_Request) -> drone_mcp.State_Response:
    """获取无人机完整状态（任务状态/操作模式/高度/GPS/巡航状态/SDK状态，/api/status + /api/capture_gps）。"""
    client = _get_client()
    result = client.get_state()
    return drone_mcp.State_Response(status=_status(result))


# ═══════════════════════════════════════════════════════════════════════════════
# Lifecycle
# ═══════════════════════════════════════════════════════════════════════════════

@drone.on_init
def init(config: dict):
    """启动时初始化 HTTP 客户端并验证 RC Pro 连接"""
    global _client
    rc_ip = config.get("rc_pro_ip") or os.environ.get("RC_PRO_IP", "192.168.1.100")
    rc_port = int(config.get("rc_pro_port") or os.environ.get("RC_PRO_PORT", "8080"))
    _client = DroneClient(rc_ip, rc_port)

    if not _client.check_connection():
        print(f"[drone_bridge] ⚠ 无法连接到 RC Pro ({_client.base})，将继续注册但调用可能失败", flush=True)
    else:
        print(f"[drone_bridge] ✅ 已连接到 RC Pro ({_client.base})", flush=True)

    return Ok()


@drone.on_shutdown
def shutdown():
    """关闭时清理"""
    global _client
    _client = None
    return Ok()


# ═══════════════════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    drone.run()
