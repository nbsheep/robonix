#!/usr/bin/env python3
# SPDX-License-Identifier: MulanPSL-2.0
"""MsdkBackend —— DJI 无人机语义后端（「封装 MSDK」的落点）。

把 SDK 无关的 11 个语义原语映射到 RC Pro 上 DJI 桥接 APK 的 HTTP API
（APK 内部才真正调用 DJI Mobile SDK v5）：

    RoboNIX (driver.py, MCP)
        │ 语义方法
        ▼
    MsdkBackend (本模块)
        │ HTTP REST (WebServer.kt)
        ▼
    RC Pro APK :8080
        │ MSDK
        ▼
    DJI 无人机

API 端点（对照 drone_bridge/main.py 的 DroneClient）：
    GET  /api/status          状态
    GET  /api/video           视频流 (MJPEG)
    POST /api/start           起飞任务 {climbHeight, moveDistance, yawAngle}
    POST /api/stop            紧急停止 → 悬停
    POST /api/gohome          返航降落
    POST /api/manual          手动移动/旋转 {action, value}
    POST /api/gimbal          云台步进 {action, step}
    POST /api/camera          相机 {action}
    POST /api/capture_gps     GPS 坐标

返回值约定：一律返回 dict，顶层 ``success``: bool 表示是否成功，失败带
``message``。driver.py 用 ``json.dumps`` 塞进 ``std_msgs/String status``。

换 SDK 只需换本后端：PSDK/OSDK 写 ``psdk_backend.py``、PX4 写
``mavlink_backend.py``，各自实现**同一组 11 个语义方法**，driver.py 与契约层不变。
"""
from __future__ import annotations

import math
from typing import Any, Dict, Optional

import requests

DEFAULT_HOST = "10.225.57.15"
DEFAULT_PORT = 8080


class MsdkBackend:
    """DJI 桥接 APK 的 HTTP 客户端，暴露 11 个语义方法。"""

    def __init__(self, host: str = DEFAULT_HOST, port: int = DEFAULT_PORT):
        self.base = f"http://{host}:{port}"
        self.timeout = 5.0

    # ── 连接探测 ───────────────────────────────────────────
    def ping(self) -> Dict[str, Any]:
        try:
            r = requests.get(f"{self.base}/api/status", timeout=self.timeout)
            return {"success": r.status_code == 200}
        except Exception as e:  # noqa: BLE001
            return {"success": False, "message": f"连接失败：RC Pro 不可达 ({e})"}

    # ── 飞控动作 ───────────────────────────────────────────
    def takeoff(self, altitude: float = 3.0) -> Dict[str, Any]:
        """起飞并悬停：/api/start（climbHeight=altitude）。"""
        return self._post("/api/start", {
            "climbHeight": float(altitude),
            "moveDistance": 0.0,
            "yawAngle": 0.0,
        })

    def land(self) -> Dict[str, Any]:
        """原地降落：API 无该端点，返回失败；改用 rth 或 hover。"""
        return {
            "success": False,
            "message": "API 无 /api/land 原地降落端点；请改用 rth 返航降落，或 hover 紧急悬停。",
        }

    def hover(self) -> Dict[str, Any]:
        """紧急停止 → 悬停：/api/stop。"""
        return self._post("/api/stop")

    def rth(self) -> Dict[str, Any]:
        """返航降落：/api/gohome。"""
        return self._post("/api/gohome")

    def move_velocity(self, vx: float = 0.0, vy: float = 0.0, vz: float = 0.0,
                      wx: float = 0.0, wy: float = 0.0, wz: float = 0.0,
                      duration: float = 1.0) -> Dict[str, Any]:
        """机体系 6DOF 速度向量 → 速度×时长折算位移，走 /api/manual 离散下发。

        vy/vz/wz（左右/上下/偏航）生效；vx（前后）无端点、wx/wy（滚转/俯仰角
        速度）不可独立控制 → 忽略。
        """
        duration = max(float(duration), 0.0)
        dy = float(vy) * duration               # 右为正（米）
        dz = float(vz) * duration               # 上为正（米）
        dyaw = math.degrees(float(wz) * duration)  # rad → deg，右转为正
        ignored = []
        if abs(float(vx)) > 1e-6:
            ignored.append("vx（前后，API 无端点）")
        if abs(float(wx)) > 1e-6 or abs(float(wy)) > 1e-6:
            ignored.append("wx/wy（滚转/俯仰角速度不可独立控制）")
        moves = []
        if abs(dz) > 1e-6:
            moves.append(("climb", self._post("/api/manual", {"action": "climb", "value": dz})))
        if abs(dyaw) > 1e-6:
            moves.append(("rotate", self._post("/api/manual", {"action": "rotate", "value": dyaw})))
        if abs(dy) > 1e-6:
            a = "move_right" if dy > 0 else "move_left"
            moves.append((a, self._post("/api/manual", {"action": a, "value": abs(dy)})))
        if not moves:
            return {"success": False, "message": "没有非零移动分量"}
        ok = all(m[1].get("success") is not False for m in moves)
        suffix = f" [已忽略: {', '.join(ignored)}]" if ignored else ""
        return {
            "success": ok,
            "message": f"速度→位移近似（{duration}s）{suffix} | " + " + ".join(n for n, _ in moves),
            "moves": [{"axis": n, **v} for n, v in moves],
        }

    def rotate_velocity(self, direction: float = 1.0, angular_velocity: float = 0.5,
                        duration: float = 1.0) -> Dict[str, Any]:
        """旋转：direction 1=右转/-1=左转，angular_velocity rad/s，duration 秒 → /api/manual rotate。"""
        dyaw = float(direction) * math.degrees(abs(float(angular_velocity))) * max(float(duration), 0.0)
        if abs(dyaw) < 1e-6:
            return {"success": False, "message": "没有非零旋转分量"}
        result = self._post("/api/manual", {"action": "rotate", "value": dyaw})
        result["message"] = f"旋转 dyaw={dyaw:.1f}°（{'右' if dyaw > 0 else '左'}转）"
        return result

    # ── 云台 / 相机 ────────────────────────────────────────
    def gimbal_velocity(self, vpitch: float = 0.0, vroll: float = 0.0, vyaw: float = 0.0,
                        duration: float = 1.0) -> Dict[str, Any]:
        """云台 3DOF 角速度（°/s）→ 角速度×时长折算角度，走 /api/gimbal 步进下发。vroll 忽略。"""
        duration = max(float(duration), 0.0)
        dpitch = float(vpitch) * duration   # 度
        dyaw = float(vyaw) * duration       # 度
        ignored = " [已忽略 vroll：API 不支持云台横滚]" if abs(float(vroll)) > 1e-6 else ""
        results = []
        if abs(dpitch) > 1e-6:
            a = "pitch_up" if dpitch > 0 else "pitch_down"
            step = min(180.0, max(0.5, abs(dpitch)))
            results.append((a, self._post("/api/gimbal", {"action": a, "step": step})))
        if abs(dyaw) > 1e-6:
            a = "yaw_right" if dyaw > 0 else "yaw_left"
            step = min(180.0, max(0.5, abs(dyaw)))
            results.append((a, self._post("/api/gimbal", {"action": a, "step": step})))
        if not results:
            return {"success": False, "message": "没有非零云台分量"}
        ok = all(r[1].get("success") is not False for r in results)
        return {
            "success": ok,
            "message": f"角速度→步进近似（{duration}s）{ignored} | " + " + ".join(a for a, _ in results),
            "moves": [{"action": a, **v} for a, v in results],
        }

    def gimbal_reset(self) -> Dict[str, Any]:
        """云台回中（平视）：/api/gimbal {action:"level"}。"""
        return self._post("/api/gimbal", {"action": "level", "step": 0.0})

    def camera_capture(self) -> Dict[str, Any]:
        """触发单张拍照：/api/camera {action:"photo"}。"""
        return self._post("/api/camera", {"action": "photo"})

    def camera_video(self) -> Dict[str, Any]:
        """获取视频流 URL（MJPEG）。"""
        return {
            "success": True,
            "video_url": f"{self.base}/api/video",
            "format": "mjpeg",
            "resolution": "640px",
            "fps": 12,
        }

    # ── 状态 ──────────────────────────────────────────────
    def state(self) -> Dict[str, Any]:
        """完整状态：/api/status + /api/capture_gps 合并 GPS 坐标。"""
        status = self._get("/api/status")
        if "error" in status:
            return {"success": False, "message": status.get("error")}
        state = dict(status)
        gps = self._post("/api/capture_gps")
        if gps.get("success") is not False and "latitude" in gps:
            state["latitude"] = gps.get("latitude")
            state["longitude"] = gps.get("longitude")
        return {"success": True, **state}

    # ── 内部 transport ─────────────────────────────────────
    def _get(self, path: str) -> Dict[str, Any]:
        try:
            r = requests.get(f"{self.base}{path}", timeout=self.timeout)
            return r.json() if r.status_code == 200 else {"error": f"HTTP {r.status_code}"}
        except Exception as e:  # noqa: BLE001
            return {"error": str(e)}

    def _post(self, path: str, data: Optional[Dict] = None) -> Dict[str, Any]:
        try:
            r = requests.post(f"{self.base}{path}", json=data or {}, timeout=self.timeout)
            return r.json() if r.status_code == 200 else {"success": False, "message": f"HTTP {r.status_code}"}
        except Exception as e:  # noqa: BLE001
            return {"success": False, "message": f"连接失败：RC Pro 不可达 ({e})"}
