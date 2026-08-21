#!/usr/bin/env python3
"""
drone_bridge 主入口 —— DJI M3E 无人机 Robonix 原语。

架构:
  Robonix (rbnx chat / executor)
      │ MCP (driver.py)
      ▼
  drone_bridge (本模块)
      │ HTTP (REST API v4.0)
      ▼
  RC Pro (Drone_test APK :8080)
      │ MSDK
      ▼
  M3E 无人机

本模块职责：
  1. DroneClient —— 封装 RC Pro 的 Web HTTP API（见 Desktop/WEB_API.md v4.0），
     供 driver.py 的 MCP handler 调用。
  2. 独立 REPL —— 不依赖 RoboNIX 时的手动联调入口（python3 main.py）。

运行方式:
  1. 通过 rbnx boot 自动启动（driver.py 被 executor 加载）
  2. 手动测试: RC_PRO_IP=<ip> python3 main.py
"""

import json
import logging
import math
import os
import signal
import sys
import threading
import time
from typing import Any, Dict, Optional

import requests

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------

RC_PRO_IP = os.environ.get("RC_PRO_IP", "10.225.57.15")
RC_PRO_PORT = int(os.environ.get("RC_PRO_PORT", "8080"))
ATLAS_ENDPOINT = os.environ.get("RBNX_ATLAS_ENDPOINT", "127.0.0.1:50051")
TELEMETRY_INTERVAL = float(os.environ.get("TELEMETRY_INTERVAL", "1.0"))  # 秒
LOG_LEVEL = os.environ.get("LOG", "INFO")

logging.basicConfig(
    level=getattr(logging, LOG_LEVEL.upper(), logging.INFO),
    format="[drone_bridge] %(asctime)s %(levelname)s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("drone_bridge")

# ---------------------------------------------------------------------------
# HTTP 客户端
# ---------------------------------------------------------------------------

class DroneClient:
    """RC Pro Web HTTP API (v4.0) 客户端。

    对照 Desktop/WEB_API.md —— 无人机控制台 WebServer.kt v4.0：
      - 状态:      GET  /api/status
      - 视频流:    GET  /api/video  (MJPEG)
      - 任务:      POST /api/start /api/stop /api/reset /api/gohome
      - 模式:      POST /api/mode {mode: standby|cruise|manual}
      - 巡航:      POST /api/add_waypoint /api/capture_gps /api/clear_waypoints
                   /api/start_cruise /api/pause_cruise /api/resume_cruise
      - 手动:      POST /api/takeoff_hover /api/manual {action, value}
      - 云台/相机: POST /api/gimbal {action, step} /api/camera {action}
    """

    def __init__(self, host: str = RC_PRO_IP, port: int = RC_PRO_PORT):
        self.base = f"http://{host}:{port}"
        self.timeout = 5.0  # 秒
        self._connected = False

    def check_connection(self) -> bool:
        """测试连接是否可达"""
        try:
            r = requests.get(f"{self.base}/api/status", timeout=self.timeout)
            self._connected = r.status_code == 200
            return self._connected
        except Exception:
            self._connected = False
            return False

    @property
    def connected(self) -> bool:
        return self._connected

    # ---- 查询 ----

    def get_status(self) -> Dict[str, Any]:
        """获取完整飞行状态（/api/status 原始 JSON）。

        字段: missionState / altitude / sdkRegistered / productConnected /
        statusMessage / vsEnabled / operationMode / waypointCount / waypoints /
        cruiseWaypointIndex / cruiseActive / cruisePaused / cruiseFeedback /
        cameraFeedback / climbHeight / moveDistance / yawAngle / sdkStatusText /
        sdkInitProcess / sdkInitComplete / sdkInitStarted。
        （API v4.0 不返回电量/电压/经纬度/航向。）
        """
        try:
            r = requests.get(f"{self.base}/api/status", timeout=self.timeout)
            return r.json() if r.status_code == 200 else {"error": f"HTTP {r.status_code}"}
        except Exception as e:
            return {"error": str(e)}

    def get_state(self) -> Dict[str, Any]:
        """获取无人机完整状态（/api/status + /api/capture_gps 合并 GPS 坐标）。"""
        status = self.get_status()
        if "error" in status:
            return status
        state = dict(status)
        gps = self.capture_gps()
        if isinstance(gps, dict) and gps.get("success") is True and "latitude" in gps:
            state["latitude"] = gps.get("latitude")
            state["longitude"] = gps.get("longitude")
        elif isinstance(gps, dict):
            state["gpsError"] = gps.get("message", "GPS 未获取")
        return state

    def get_video_url(self) -> str:
        """返回 MJPEG 视频流 URL"""
        return f"{self.base}/api/video"

    # ---- 任务控制 ----

    def start_mission(self, climb: float = 1.0, move: float = 0.5, yaw: float = 0.0) -> Dict:
        """启动自动任务（/api/start）。前置: missionState==IDLE 且 SDK 已激活。"""
        return self._post("/api/start", {
            "climbHeight": climb,
            "moveDistance": move,
            "yawAngle": yaw,
        })

    def stop(self) -> Dict:
        """紧急停止 → 悬停（/api/stop）"""
        return self._post("/api/stop")

    def reset(self) -> Dict:
        """重置 UI 状态（/api/reset）"""
        return self._post("/api/reset")

    def go_home(self) -> Dict:
        """返航降落（/api/gohome）"""
        return self._post("/api/gohome")

    def land(self) -> Dict:
        """原地降落。

        ⚠️ API v4.0 无 /api/land 原地降落端点，暂返回失败；
        可用 rth（返航降落）或 stop（紧急悬停）替代。
        """
        return {
            "success": False,
            "message": "API v4.0 无 /api/land 原地降落端点；请改用 rth 返航降落，或 stop 紧急悬停。",
        }

    # ---- 模式切换 ----

    def switch_mode(self, mode: str = "standby") -> Dict:
        """切换操作模式（/api/mode）。mode ∈ {standby, cruise, manual}，小写。"""
        return self._post("/api/mode", {"mode": str(mode).lower()})

    # ---- 巡航（航点） ----

    def add_waypoint(self, lat: float, lng: float, alt: float = 5.0) -> Dict:
        """添加 GPS 航点（/api/add_waypoint）"""
        return self._post("/api/add_waypoint", {
            "latitude": lat, "longitude": lng, "altitude": alt,
        })

    def capture_gps(self) -> Dict:
        """获取当前 GPS 坐标（/api/capture_gps）。成功时返回 latitude/longitude。"""
        return self._post("/api/capture_gps")

    def clear_waypoints(self) -> Dict:
        """清空航点（/api/clear_waypoints）"""
        return self._post("/api/clear_waypoints")

    def start_cruise(self) -> Dict:
        """开始巡航（/api/start_cruise）。前置: IDLE 且至少 1 航点。"""
        return self._post("/api/start_cruise")

    def pause_cruise(self) -> Dict:
        """紧急悬停 / 中止巡航（/api/pause_cruise）。保留航点与进度，不返航。"""
        return self._post("/api/pause_cruise")

    def resume_cruise(self) -> Dict:
        """重启巡航（/api/resume_cruise）。前置: cruisePaused==true。"""
        return self._post("/api/resume_cruise")

    # ---- 手动操控 ----

    def takeoff_hover(self) -> Dict:
        """起飞并悬停（/api/takeoff_hover）。前置: IDLE 且 SDK 已激活。"""
        return self._post("/api/takeoff_hover")

    def manual_climb(self, delta: float) -> Dict:
        """升降（/api/manual action=climb）。正升负降，单位米。前置: HOVERING。"""
        return self._post("/api/manual", {"action": "climb", "value": delta})

    def manual_move_left(self, distance: float) -> Dict:
        """左移（/api/manual action=move_left）。单位米。前置: HOVERING。"""
        return self._post("/api/manual", {"action": "move_left", "value": distance})

    def manual_move_right(self, distance: float) -> Dict:
        """右移（/api/manual action=move_right）。单位米。前置: HOVERING。"""
        return self._post("/api/manual", {"action": "move_right", "value": distance})

    def manual_rotate(self, degrees: float) -> Dict:
        """旋转（/api/manual action=rotate）。正右负左，单位度。前置: HOVERING。"""
        return self._post("/api/manual", {"action": "rotate", "value": degrees})

    # ---- 云台 / 相机（仅巡航中可用 cruiseActive==true） ----

    def gimbal(self, action: str, step: float = 7.5) -> Dict:
        """云台控制（/api/gimbal）。

        action ∈ {pitch_up, pitch_down, yaw_left, yaw_right, look_down, level}；
        step 单步角度（度），范围 0.5~180，缺省 7.5。
        """
        return self._post("/api/gimbal", {"action": action, "step": step})

    def gimbal_velocity(self, vpitch: float = 0.0, vroll: float = 0.0, vyaw: float = 0.0,
                        duration: float = 1.0) -> Dict:
        """云台 3DOF 角速度向量（°/s）→ 折算为总角度后走 /api/gimbal 步进下发（离散近似）。

        vpitch/vyaw：俯仰/偏航角速度（°/s，正 = 抬头/右转）；duration：持续秒数。
        总角度 = 角速度 × duration，映射到 step 步进动作（pitch_up/down、yaw_left/right）。
        vroll（横滚）API 不支持，忽略。

        注意：step 上限 180°、下限 0.5°；且 /api/gimbal 仅巡航中可用（cruiseActive==true）。
        """
        duration = max(float(duration), 0.0)
        dpitch = float(vpitch) * duration  # 度
        dyaw = float(vyaw) * duration      # 度
        ignored = ""
        if abs(float(vroll)) > 1e-6:
            ignored = " [已忽略 vroll：API 不支持云台横滚]"
        results = []
        if abs(dpitch) > 1e-6:
            action = "pitch_up" if dpitch > 0 else "pitch_down"
            step = min(180.0, max(0.5, abs(dpitch)))
            results.append((action, self.gimbal(action, step)))
        if abs(dyaw) > 1e-6:
            action = "yaw_right" if dyaw > 0 else "yaw_left"
            step = min(180.0, max(0.5, abs(dyaw)))
            results.append((action, self.gimbal(action, step)))
        if not results:
            return {"success": False, "message": "没有非零云台分量"}
        ok = all(r[1].get("success") is not False for r in results)
        return {
            "success": ok,
            "message": f"角速度→步进近似（{duration}s）{ignored} | " + " + ".join(a for a, _ in results),
            "moves": [{"action": a, **v} for a, v in results],
        }

    def gimbal_reset(self) -> Dict:
        """云台回中（平视）：对应 /api/gimbal {action:"level"}，仅巡航中可用。"""
        result = self.gimbal("level")
        base = result.get("message", "") if isinstance(result, dict) else ""
        result["message"] = "云台回中（平视）" + (f" | {base}" if base else "")
        return result

    def camera(self, action: str) -> Dict:
        """相机控制（/api/camera）。action ∈ {photo, start_record, stop_record, zoom_in, zoom_out}。"""
        return self._post("/api/camera", {"action": action})

    def camera_capture(self) -> Dict:
        """触发单张拍照（/api/camera action=photo），仅巡航中可用。"""
        return self.camera("photo")

    # ---- 速度向量（离散近似，映射到 /api/manual，需 HOVERING） ----

    def move_velocity(self, vx: float = 0.0, vy: float = 0.0, vz: float = 0.0,
                      wx: float = 0.0, wy: float = 0.0, wz: float = 0.0,
                      duration: float = 1.0) -> Dict:
        """机体系 6DOF 速度向量（twist）→ 折算为相对位移后走 /api/manual 离散下发。

        vy/vz/wz：左右/上下/偏航 生效（位移 = 速度 × duration；wz 弧度→度）。
        vx（前后）API v4.0 无移动端点、wx/wy（滚转/俯仰角速度）不可独立控制 → 忽略。

        注意：/api/manual 仅 HOVERING 悬停态可用，且为「速度→位移」离散近似。
        """
        duration = max(float(duration), 0.0)
        dy = float(vy) * duration              # 右为正
        dz = float(vz) * duration              # 上为正
        dyaw = math.degrees(float(wz) * duration)  # rad → deg，右转为正
        ignored = []
        if abs(float(vx)) > 1e-6:
            ignored.append("vx（前后，API v4.0 无端点）")
        if abs(float(wx)) > 1e-6 or abs(float(wy)) > 1e-6:
            ignored.append("wx/wy（滚转/俯仰角速度不可独立控制）")
        moves = []
        if abs(dz) > 1e-6:
            moves.append(("climb", self.manual_climb(dz)))
        if abs(dyaw) > 1e-6:
            moves.append(("rotate", self.manual_rotate(dyaw)))
        if abs(dy) > 1e-6:
            if dy > 0:
                moves.append(("move_right", self.manual_move_right(dy)))
            else:
                moves.append(("move_left", self.manual_move_left(-dy)))
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
                        duration: float = 1.0) -> Dict:
        """旋转（方向·角速度·持续时间）→ dyaw 折算后走 /api/manual rotate。

        direction：1 = 右转（顺时针），-1 = 左转（对齐 /api/manual rotate 正右负左）；
        angular_velocity：偏航角速度 (rad/s，取绝对值)；duration：持续秒数。
        dyaw(度) = direction × degrees(|angular_velocity| × duration)。
        """
        dyaw = float(direction) * math.degrees(abs(float(angular_velocity))) * max(float(duration), 0.0)
        if abs(dyaw) < 1e-6:
            return {"success": False, "message": "没有非零旋转分量"}
        result = self.manual_rotate(dyaw)
        result["message"] = f"旋转 dyaw={dyaw:.1f}°（{'右' if dyaw > 0 else '左'}转）"
        return result

    # ---- 内部 ----

    def _post(self, path: str, data: Optional[Dict] = None) -> Dict:
        try:
            r = requests.post(
                f"{self.base}{path}",
                json=data or {},
                timeout=self.timeout,
            )
            return r.json() if r.status_code == 200 else {"success": False, "message": f"HTTP {r.status_code}"}
        except requests.exceptions.ConnectionError:
            self._connected = False
            return {"success": False, "message": "连接失败：RC Pro 不可达"}
        except Exception as e:
            return {"success": False, "message": str(e)}


# ---------------------------------------------------------------------------
# 遥测轮询
# ---------------------------------------------------------------------------

class TelemetryPoller:
    """后台线程：定时从 RC Pro 拉取遥测"""

    def __init__(self, client: DroneClient, interval: float = 1.0):
        self.client = client
        self.interval = interval
        self._thread: Optional[threading.Thread] = None
        self._running = False
        self._lock = threading.Lock()
        self._latest: Dict[str, Any] = {}
        self._callbacks: list = []

    @property
    def latest(self) -> Dict[str, Any]:
        with self._lock:
            return dict(self._latest)

    def on_update(self, callback):
        """注册回调: callback(status_dict)"""
        self._callbacks.append(callback)

    def start(self):
        if self._running:
            return
        self._running = True
        self._thread = threading.Thread(target=self._loop, daemon=True, name="telemetry-poller")
        self._thread.start()
        log.info(f"遥测轮询已启动（间隔 {self.interval}s）")

    def stop(self):
        self._running = False
        if self._thread:
            self._thread.join(timeout=2)

    def _loop(self):
        while self._running:
            try:
                status = self.client.get_status()
                if "error" not in status:
                    with self._lock:
                        self._latest = status
                    for cb in self._callbacks:
                        try:
                            cb(status)
                        except Exception:
                            pass
                else:
                    log.warning(f"遥测获取失败: {status.get('error', '未知')}")
            except Exception as e:
                log.warning(f"遥测轮询异常: {e}")
            time.sleep(self.interval)


# ---------------------------------------------------------------------------
# Atlas 注册（Robonix 集成）
# ---------------------------------------------------------------------------

class AtlasRegistrar:
    """
    向 Atlas 注册 drone_bridge 能力和遥测数据。

    当前为简化实现：仅探测 Atlas gRPC 是否可达。
    能力声明与调度由 driver.py（Primitive + @provider.mcp）负责，
    由 executor 加载，不经过本类。
    """

    def __init__(self, endpoint: str = ATLAS_ENDPOINT):
        self.endpoint = endpoint
        self._registered = False

    def register(self) -> bool:
        """尝试连接 Atlas（如不可用则降级为 standalone 模式）"""
        try:
            import grpc
            channel = grpc.insecure_channel(self.endpoint)
            grpc.channel_ready_future(channel).result(timeout=3)
            log.info(f"✅ 已连接到 Atlas ({self.endpoint})")
            self._registered = True
            return True
        except ImportError:
            log.warning("⚠ grpcio 未安装，降级为 standalone 模式")
            return False
        except Exception as e:
            log.warning(f"⚠ Atlas 不可达 ({e})，降级为 standalone 模式")
            return False

    @property
    def registered(self) -> bool:
        return self._registered


# ---------------------------------------------------------------------------
# 命令处理器（供 standalone REPL 调用）
# ---------------------------------------------------------------------------

class CommandHandler:
    """将 11 个原语能力映射到 DroneClient 方法（独立测试用）"""

    def __init__(self, client: DroneClient):
        self.client = client

    def handle(self, capability: str, params: Optional[Dict] = None) -> Dict:
        params = params or {}
        method = CAPABILITY_MAP.get(capability)
        if method is None:
            return {"success": False, "message": f"未知能力: {capability}"}
        try:
            return method(self.client, params)
        except Exception as e:
            log.exception(f"命令执行异常: {capability}")
            return {"success": False, "message": str(e)}


def _cmd_takeoff(client: DroneClient, p: Dict) -> Dict:
    alt = float(p.get("altitude", 0.0) or 0.0)
    if alt <= 0:
        alt = 3.0
    return client.start_mission(climb=alt, move=0.0, yaw=0.0)

def _cmd_land(client: DroneClient, p: Dict) -> Dict:
    return client.land()

def _cmd_hover(client: DroneClient, p: Dict) -> Dict:
    return client.stop()

def _cmd_rth(client: DroneClient, p: Dict) -> Dict:
    return client.go_home()

def _cmd_move_velocity(client: DroneClient, p: Dict) -> Dict:
    return client.move_velocity(
        vx=float(p.get("vx", 0.0)),
        vy=float(p.get("vy", 0.0)),
        vz=float(p.get("vz", 0.0)),
        wx=float(p.get("wx", 0.0)),
        wy=float(p.get("wy", 0.0)),
        wz=float(p.get("wz", 0.0)),
        duration=float(p.get("duration", 1.0)),
    )

def _cmd_rotate_velocity(client: DroneClient, p: Dict) -> Dict:
    return client.rotate_velocity(
        direction=float(p.get("direction", 1.0)),
        angular_velocity=float(p.get("angular_velocity", 0.5)),
        duration=float(p.get("duration", 1.0)),
    )

def _cmd_gimbal_velocity(client: DroneClient, p: Dict) -> Dict:
    return client.gimbal_velocity(
        vpitch=float(p.get("vpitch", 0.0)),
        vroll=float(p.get("vroll", 0.0)),
        vyaw=float(p.get("vyaw", 0.0)),
        duration=float(p.get("duration", 1.0)),
    )

def _cmd_gimbal_reset(client: DroneClient, p: Dict) -> Dict:
    return client.gimbal_reset()

def _cmd_camera_capture(client: DroneClient, p: Dict) -> Dict:
    return client.camera_capture()

def _cmd_camera_video(client: DroneClient, p: Dict) -> Dict:
    return {
        "success": True,
        "video_url": client.get_video_url(),
        "format": "mjpeg",
        "resolution": "640px",
        "fps": 12,
        "note": "用浏览器 / curl / ffmpeg 拉流即可",
    }

def _cmd_state(client: DroneClient, p: Dict) -> Dict:
    return client.get_state()

CAPABILITY_MAP = {
    # ── 运动控制 ──
    "robonix/primitive/drone/takeoff":          _cmd_takeoff,
    "robonix/primitive/drone/land":             _cmd_land,
    "robonix/primitive/drone/move_velocity":    _cmd_move_velocity,
    "robonix/primitive/drone/rotate_velocity":  _cmd_rotate_velocity,
    "robonix/primitive/drone/hover":            _cmd_hover,
    "robonix/primitive/drone/rth":              _cmd_rth,
    # ── 云台 / 相机 ──
    "robonix/primitive/drone/gimbal_velocity":  _cmd_gimbal_velocity,
    "robonix/primitive/drone/gimbal_reset":     _cmd_gimbal_reset,
    "robonix/primitive/drone/camera_capture":   _cmd_camera_capture,
    "robonix/primitive/drone/camera_video":     _cmd_camera_video,
    # ── 状态查询 ──
    "robonix/primitive/drone/state":            _cmd_state,
}


# ---------------------------------------------------------------------------
# 简易 REPL（standalone 交互测试）
# ---------------------------------------------------------------------------

def _repl_loop(client: DroneClient):
    """Standalone 模式下的交互命令行（不需要 Robonix）"""
    print("\n" + "=" * 60)
    print("  DJI M3E Drone Bridge — Standalone REPL")
    print(f"  目标: {client.base}")
    print("=" * 60)
    print("\n命令:")
    print("  takeoff [alt]    — 起飞爬升至指定高度（默认 3m）")
    print("  land             — 原地降落（API 无端点，返回失败）")
    print("  hover            — 紧急悬停（/api/stop）")
    print("  rth              — 返航降落（/api/gohome）")
    print("  vel <vy> <vz> [wz] [dur] — 速度向量（左右/上下/偏航，m/s·rad/s，持续秒）")
    print("  rv [dir] [wz] [dur] — 旋转（1=右/-1=左，rad/s，秒；默认 右 0.5rad/s×1s）")
    print("  gv <vpitch> [vyaw] [dur] — 云台角速度（俯仰/偏航 °/s，持续秒）")
    print("  greset           — 云台回中（平视 /api/gimbal level）")
    print("  video            — 获取视频流 URL")
    print("  photo            — 拍照（/api/camera photo）")
    print("  state            — 查询完整状态（status + GPS）")
    print("  status           — 查看原始 /api/status")
    print("  q / quit         — 退出")
    print()

    handler = CommandHandler(client)

    while True:
        try:
            raw = input("drone> ").strip()
        except (EOFError, KeyboardInterrupt):
            break

        if not raw:
            continue
        parts = raw.split()
        cmd = parts[0].lower()

        if cmd in ("q", "quit", "exit"):
            break
        elif cmd == "status":
            s = client.get_status()
            print(json.dumps(s, indent=2, ensure_ascii=False))
        elif cmd == "takeoff":
            alt = float(parts[1]) if len(parts) > 1 else 3.0
            print(json.dumps(handler.handle("robonix/primitive/drone/takeoff", {"altitude": alt}), ensure_ascii=False))
        elif cmd == "land":
            print(json.dumps(handler.handle("robonix/primitive/drone/land"), ensure_ascii=False))
        elif cmd == "hover":
            print(json.dumps(handler.handle("robonix/primitive/drone/hover"), ensure_ascii=False))
        elif cmd == "rth":
            print(json.dumps(handler.handle("robonix/primitive/drone/rth"), ensure_ascii=False))
        elif cmd == "state":
            print(json.dumps(handler.handle("robonix/primitive/drone/state"), ensure_ascii=False))
        elif cmd in ("vel", "velocity"):
            vy = float(parts[1]) if len(parts) > 1 else 0.0
            vz = float(parts[2]) if len(parts) > 2 else 0.0
            wz = float(parts[3]) if len(parts) > 3 else 0.0
            dur = float(parts[4]) if len(parts) > 4 else 1.0
            print(json.dumps(handler.handle("robonix/primitive/drone/move_velocity",
                  {"vy": vy, "vz": vz, "wz": wz, "duration": dur}), ensure_ascii=False))
        elif cmd in ("rv", "rotate_velocity"):
            direction = float(parts[1]) if len(parts) > 1 else 1.0
            wz = float(parts[2]) if len(parts) > 2 else 0.5
            dur = float(parts[3]) if len(parts) > 3 else 1.0
            print(json.dumps(handler.handle("robonix/primitive/drone/rotate_velocity",
                  {"direction": direction, "angular_velocity": wz, "duration": dur}), ensure_ascii=False))
        elif cmd in ("greset", "gimbal_reset"):
            print(json.dumps(handler.handle("robonix/primitive/drone/gimbal_reset"), ensure_ascii=False))
        elif cmd in ("gv", "gimbal_velocity"):
            vpitch = float(parts[1]) if len(parts) > 1 else 0.0
            vyaw = float(parts[2]) if len(parts) > 2 else 0.0
            dur = float(parts[3]) if len(parts) > 3 else 1.0
            print(json.dumps(handler.handle("robonix/primitive/drone/gimbal_velocity",
                  {"vpitch": vpitch, "vyaw": vyaw, "duration": dur}), ensure_ascii=False))
        elif cmd == "video":
            print(json.dumps(handler.handle("robonix/primitive/drone/camera_video"), ensure_ascii=False))
        elif cmd == "photo":
            print(json.dumps(handler.handle("robonix/primitive/drone/camera_capture"), ensure_ascii=False))
        else:
            print(f"未知命令: {cmd}")

    print("\n[drone_bridge] 正在退出...")


# ---------------------------------------------------------------------------
# 主入口
# ---------------------------------------------------------------------------

def main():
    try:
        version = __import__('drone_bridge').__version__
    except Exception:
        version = "1.0.0"
    log.info(f"drone_bridge v{version} 启动")
    log.info(f"RC Pro 地址: {RC_PRO_IP}:{RC_PRO_PORT}")

    # 1. 创建 HTTP 客户端
    client = DroneClient(RC_PRO_IP, RC_PRO_PORT)

    # 2. 测试连接
    log.info("正在检测 RC Pro 连接...")
    if not client.check_connection():
        log.error(f"❌ 无法连接到 RC Pro ({client.base})")
        log.error("   请确认:")
        log.error("   1. RC Pro 已开机，Drone_test APK 正在运行")
        log.error("   2. PC 与 RC Pro 在同一 WiFi 下")
        log.error(f"   3. RC Pro IP 正确（当前设置: {RC_PRO_IP}）")
        log.error("   4. 可以尝试: curl http://<RC_Pro_IP>:8080/api/status")
        sys.exit(1)
    log.info(f"✅ 已连接到 RC Pro ({client.base})")

    # 3. 尝试注册 Atlas
    atlas = AtlasRegistrar()
    atlas_ok = atlas.register()

    # 4. 启动遥测轮询
    poller = TelemetryPoller(client, TELEMETRY_INTERVAL)

    if atlas_ok:
        # Robonix 集成模式：后台运行，wait for executor commands
        log.info("🚀 drone_bridge 运行中（Robonix 集成模式）")
        poller.start()

        def on_telemetry(status):
            pass  # 完整集成时通过 gRPC stream 上报

        poller.on_update(on_telemetry)

        stop_event = threading.Event()
        signal.signal(signal.SIGINT, lambda *_: stop_event.set())
        signal.signal(signal.SIGTERM, lambda *_: stop_event.set())

        try:
            while not stop_event.is_set():
                stop_event.wait(timeout=1.0)
        except KeyboardInterrupt:
            pass
    else:
        # Standalone 模式：交互 REPL
        log.info("🚀 drone_bridge 运行中（Standalone REPL 模式）")
        poller.start()
        _repl_loop(client)

    # 5. 清理
    log.info("正在关闭...")
    poller.stop()
    log.info("drone_bridge 已退出")


if __name__ == "__main__":
    main()
