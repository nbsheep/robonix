#!/usr/bin/env python3
# SPDX-License-Identifier: MulanPSL-2.0
"""DJI M3E 无人机 RoboNIX 原语驱动。

通过 HTTP API 桥接 DJI RC Pro (Drone_test APK)，向 RoboNIX 提供无人机
飞控与状态查询能力。

Capability surface:
  robonix/primitive/drone/takeoff         rpc  起飞悬停
  robonix/primitive/drone/land            rpc  原地降落
  robonix/primitive/drone/move_ee         rpc  飞行至目标 GPS 位姿
  robonix/primitive/drone/hover           rpc  紧急悬停
  robonix/primitive/drone/rth             rpc  智能返航
  robonix/primitive/drone/state_position  rpc  当前位置查询
  robonix/primitive/drone/state_battery   rpc  电量查询
"""
from __future__ import annotations

import json
import os

from robonix_api import Primitive, Ok, Err

# ── HTTP 客户端（复用 main.py 中的 DroneClient） ──
from drone_bridge.main import DroneClient

drone = Primitive(id="drone_bridge", namespace="robonix/primitive/drone")

_client: DroneClient | None = None


def _get_client() -> DroneClient:
    """获取已初始化的 HTTP 客户端"""
    if _client is None:
        rc_ip = os.environ.get("RC_PRO_IP", "10.225.57.15")
        rc_port = int(os.environ.get("RC_PRO_PORT", "8080"))
        return DroneClient(rc_ip, rc_port)
    return _client


# ── 导入 codegen 生成的 proto stub ──
# 由 rbnx codegen 从 capabilities/primitive/drone/*.toml + capabilities/lib/drone/srv/*.srv 生成
import drone_pb2  # noqa: E402
import std_msgs_pb2  # noqa: E402


# ═══════════════════════════════════════════════════════════════════════════════
# 运动控制
# ═══════════════════════════════════════════════════════════════════════════════

@drone.grpc("robonix/primitive/drone/takeoff")
def takeoff(req: "drone_pb2.Takeoff_Request") -> "drone_pb2.Takeoff_Response":
    """起飞并悬停至指定高度"""
    client = _get_client()
    alt = float(req.altitude) if req.altitude > 0 else 3.0
    result = client.start_mission(climb=alt, move=0.0, yaw=0.0)
    return drone_pb2.Takeoff_Response(
        status=std_msgs_pb2.String(data=json.dumps(result, ensure_ascii=False)),
    )


@drone.grpc("robonix/primitive/drone/land")
def land(_req) -> "drone_pb2.Land_Response":
    """原地降落"""
    client = _get_client()
    result = client.land()
    return drone_pb2.Land_Response(
        status=std_msgs_pb2.String(data=json.dumps(result, ensure_ascii=False)),
    )


@drone.grpc("robonix/primitive/drone/move_ee")
def move_ee(req: "drone_pb2.MoveEe_Request") -> "drone_pb2.MoveEe_Response":
    """飞行至目标 GPS 位姿"""
    client = _get_client()
    lat = float(req.latitude)
    lng = float(req.longitude)
    alt = float(req.altitude) if req.altitude > 0 else 5.0

    # 清空旧航点 → 添加目标点 → 启动巡航
    client.clear_waypoints()
    wp_result = client.add_waypoint(lat, lng, alt)
    if wp_result.get("success") is False:
        return drone_pb2.MoveEe_Response(
            status=std_msgs_pb2.String(data=json.dumps(wp_result, ensure_ascii=False)),
        )
    cruise_result = client.start_cruise()
    return drone_pb2.MoveEe_Response(
        status=std_msgs_pb2.String(data=json.dumps(cruise_result, ensure_ascii=False)),
    )


@drone.grpc("robonix/primitive/drone/hover")
def hover(_req) -> "drone_pb2.Hover_Response":
    """紧急悬停"""
    client = _get_client()
    result = client.stop()
    return drone_pb2.Hover_Response(
        status=std_msgs_pb2.String(data=json.dumps(result, ensure_ascii=False)),
    )


@drone.grpc("robonix/primitive/drone/rth")
def rth(_req) -> "drone_pb2.Rth_Response":
    """智能返航"""
    client = _get_client()
    result = client.go_home()
    return drone_pb2.Rth_Response(
        status=std_msgs_pb2.String(data=json.dumps(result, ensure_ascii=False)),
    )


# ═══════════════════════════════════════════════════════════════════════════════
# 状态查询
# ═══════════════════════════════════════════════════════════════════════════════

@drone.grpc("robonix/primitive/drone/state_position")
def state_position(_req) -> "drone_pb2.GetPosition_Response":
    """获取当前位置"""
    client = _get_client()
    status = client.get_status()
    if "error" in status:
        return drone_pb2.GetPosition_Response(
            position=std_msgs_pb2.String(data=json.dumps({"error": status["error"]}, ensure_ascii=False)),
        )
    pos = {
        "latitude": status.get("latitude"),
        "longitude": status.get("longitude"),
        "altitude": status.get("altitude", 0.0),
        "heading": status.get("heading", 0.0),
    }
    return drone_pb2.GetPosition_Response(
        position=std_msgs_pb2.String(data=json.dumps(pos, ensure_ascii=False)),
    )


@drone.grpc("robonix/primitive/drone/state_battery")
def state_battery(_req) -> "drone_pb2.GetBattery_Response":
    """获取电池电量"""
    client = _get_client()
    status = client.get_status()
    if "error" in status:
        return drone_pb2.GetBattery_Response(
            battery=std_msgs_pb2.String(data=json.dumps({"error": status["error"]}, ensure_ascii=False)),
        )
    bat = {
        "percent": status.get("batteryPercent", 0.0),
        "voltage": status.get("batteryVoltage", 0.0),
    }
    return drone_pb2.GetBattery_Response(
        battery=std_msgs_pb2.String(data=json.dumps(bat, ensure_ascii=False)),
    )


# ═══════════════════════════════════════════════════════════════════════════════
# 近距离相对移动 / 云台 / 相机（v1 精简原语集）
# ═══════════════════════════════════════════════════════════════════════════════

@drone.grpc("robonix/primitive/drone/move_relative")
def move_relative(req: "drone_pb2.MoveRelative_Request") -> "drone_pb2.MoveRelative_Response":
    """机体系相对移动：dx 前后 / dy 左右 / dz 上下 / dyaw 偏航。"""
    client = _get_client()
    result = client.move_relative(
        dx=float(req.dx), dy=float(req.dy), dz=float(req.dz), dyaw=float(req.dyaw),
    )
    return drone_pb2.MoveRelative_Response(
        status=std_msgs_pb2.String(data=json.dumps(result, ensure_ascii=False)),
    )


@drone.grpc("robonix/primitive/drone/gimbal_rotate")
def gimbal_rotate(req: "drone_pb2.GimbalRotate_Request") -> "drone_pb2.GimbalRotate_Response":
    """设置云台姿态（绝对角度，度）。"""
    client = _get_client()
    result = client.gimbal_rotate(
        pitch=float(req.pitch), roll=float(req.roll), yaw=float(req.yaw),
    )
    return drone_pb2.GimbalRotate_Response(
        status=std_msgs_pb2.String(data=json.dumps(result, ensure_ascii=False)),
    )


@drone.grpc("robonix/primitive/drone/camera_capture")
def camera_capture(_req) -> "drone_pb2.CameraCapture_Response":
    """触发单张拍照。"""
    client = _get_client()
    result = client.camera_capture()
    return drone_pb2.CameraCapture_Response(
        status=std_msgs_pb2.String(data=json.dumps(result, ensure_ascii=False)),
    )


@drone.grpc("robonix/primitive/drone/camera_zoom")
def camera_zoom(req: "drone_pb2.CameraZoom_Request") -> "drone_pb2.CameraZoom_Response":
    """设置相机变焦倍率。"""
    client = _get_client()
    result = client.camera_zoom(factor=float(req.factor))
    return drone_pb2.CameraZoom_Response(
        status=std_msgs_pb2.String(data=json.dumps(result, ensure_ascii=False)),
    )


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
