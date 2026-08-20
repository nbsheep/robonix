#!/usr/bin/env python3
"""
drone_bridge 主入口 —— DJI M3E 无人机 Robonix 原语。

架构:
  Robonix (rbnx chat / executor)
      │ gRPC (Atlas)
      ▼
  drone_bridge (本模块)
      │ HTTP (REST API)
      ▼
  RC Pro (Drone_test APK :8080)
      │ MSDK
      ▼
  M3E 无人机

运行方式:
  1. 通过 rbnx boot 自动启动（推荐）
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
    """RC Pro HTTP API 客户端"""

    def __init__(self, host: str = RC_PRO_IP, port: int = RC_PRO_PORT):
        self.base = f"http://{host}:{port}"
        self.timeout = 5.0  # 秒
        self._connected = False
        # 客户端跟踪的云台当前姿态（度）。/api/status 不返回云台姿态，
        # 故在本地维护，供 gimbal_velocity 做「角速度×时长→角度增量」折算。
        self._gimbal = {"pitch": 0.0, "roll": 0.0, "yaw": 0.0}

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
        """获取完整飞行状态"""
        try:
            r = requests.get(f"{self.base}/api/status", timeout=self.timeout)
            return r.json() if r.status_code == 200 else {"error": f"HTTP {r.status_code}"}
        except Exception as e:
            return {"error": str(e)}

    def get_video_url(self) -> str:
        """返回 MJPEG 视频流 URL"""
        return f"{self.base}/api/video"

    # ---- 任务控制 ----

    def start_mission(self, climb: float = 1.0, move: float = 0.5, yaw: float = 0.0) -> Dict:
        """启动自动任务（待命模式）"""
        return self._post("/api/start", {
            "climbHeight": climb,
            "moveDistance": move,
            "yawAngle": yaw,
        })

    def stop(self) -> Dict:
        """紧急停止 → 悬停"""
        return self._post("/api/stop")

    def reset(self) -> Dict:
        """重置 UI 状态"""
        return self._post("/api/reset")

    def go_home(self) -> Dict:
        """返航降落"""
        return self._post("/api/gohome")

    def land(self) -> Dict:
        """原地降落（TODO: Drone_test APK 需新增 /api/land 端点）"""
        return self._post("/api/land")

    # ---- 模式切换 ----

    def switch_mode(self, mode: str) -> Dict:
        """切换模式: STANDBY / CRUISE / MANUAL"""
        return self._post("/api/mode", {"mode": mode})

    # ---- 巡航 ----

    def add_waypoint(self, lat: float, lng: float, alt: float = 5.0) -> Dict:
        """添加 GPS 航点"""
        return self._post("/api/add_waypoint", {
            "latitude": lat, "longitude": lng, "altitude": alt,
        })

    def clear_waypoints(self) -> Dict:
        return self._post("/api/clear_waypoints")

    def start_cruise(self) -> Dict:
        return self._post("/api/start_cruise")

    # ---- 手动操控 ----

    def takeoff_hover(self) -> Dict:
        return self._post("/api/takeoff_hover")

    def manual_climb(self, delta: float) -> Dict:
        return self._post("/api/manual", {"action": "climb", "value": delta})

    def manual_move_left(self, distance: float) -> Dict:
        return self._post("/api/manual", {"action": "move_left", "value": distance})

    def manual_move_right(self, distance: float) -> Dict:
        return self._post("/api/manual", {"action": "move_right", "value": distance})

    def manual_rotate(self, degrees: float) -> Dict:
        return self._post("/api/manual", {"action": "rotate", "value": degrees})

    def manual_move_forward(self, distance: float) -> Dict:
        return self._post("/api/manual", {"action": "move_forward", "value": distance})

    def manual_move_backward(self, distance: float) -> Dict:
        return self._post("/api/manual", {"action": "move_backward", "value": distance})

    # ---- 云台 / 相机 ----

    def gimbal_rotate(self, pitch: float = 0.0, roll: float = 0.0, yaw: float = 0.0) -> Dict:
        """设置云台姿态（绝对角度，度）"""
        self._gimbal = {"pitch": float(pitch), "roll": float(roll), "yaw": float(yaw)}
        return self._post("/api/gimbal", {"pitch": pitch, "roll": roll, "yaw": yaw})

    def gimbal_velocity(self, vpitch: float = 0.0, vroll: float = 0.0, vyaw: float = 0.0,
                        duration: float = 1.0) -> Dict:
        """云台 3DOF 角速度向量（°/s）→ 折算为角度增量，叠加当前姿态后走 /api/gimbal 绝对角度下发。

        vpitch/vyaw：俯仰/偏航角速度（°/s）；duration：持续秒数。角度增量 = 角速度 × duration。
        vroll（横滚）M3E 云台一般不支持，忽略。

        注意：当前为「角速度→角度」离散近似，客户端跟踪当前姿态（初始假设 0°）；
        真正连续云台速度控制需 APK 新增 /api/gimbal/velocity（KeyGimbalSpeed）。
        """
        duration = max(float(duration), 0.0)
        dpitch = float(vpitch) * duration
        dyaw = float(vyaw) * duration
        ignored = ""
        if abs(float(vroll)) > 1e-6:
            ignored = " [已忽略 vroll：M3E 云台不支持横滚角速度]"
        target = {
            "pitch": self._gimbal.get("pitch", 0.0) + dpitch,
            "roll": self._gimbal.get("roll", 0.0),
            "yaw": self._gimbal.get("yaw", 0.0) + dyaw,
        }
        result = self.gimbal_rotate(**target)
        base = result.get("message", "") if isinstance(result, dict) else ""
        result["message"] = f"角速度→角度近似（{duration}s）{ignored}" + (f" | {base}" if base else "")
        return result

    def camera_capture(self) -> Dict:
        """触发单张拍照"""
        return self._post("/api/camera/capture")

    def camera_zoom(self, factor: float = 1.0) -> Dict:
        """设置相机变焦倍率"""
        return self._post("/api/camera/zoom", {"factor": factor})

    def move_relative(self, dx: float = 0.0, dy: float = 0.0, dz: float = 0.0, dyaw: float = 0.0) -> Dict:
        """机体系相对移动（需悬停状态）。dx 前后(前+)、dy 左右(右+)、dz 上下(上+)、dyaw 偏航(度)。
        逐轴映射到 /api/manual；多轴分量按 上下→旋转→前后→左右 顺序依次下发。"""
        moves = []
        if abs(dz) > 1e-6:
            moves.append(("up" if dz > 0 else "down", self.manual_climb(dz)))
        if abs(dyaw) > 1e-6:
            moves.append(("rotate", self.manual_rotate(dyaw)))
        if abs(dx) > 1e-6:
            moves.append(("forward" if dx > 0 else "backward",
                          self.manual_move_forward(dx) if dx > 0 else self.manual_move_backward(-dx)))
        if abs(dy) > 1e-6:
            moves.append(("right" if dy > 0 else "left",
                          self.manual_move_right(dy) if dy > 0 else self.manual_move_left(-dy)))
        if not moves:
            return {"success": False, "message": "没有非零移动分量"}
        ok = all(m[1].get("success") is not False for m in moves)
        return {
            "success": ok,
            "message": " + ".join(f"{name}" for name, _ in moves),
            "moves": [{"axis": name, **v} for name, v in moves],
        }

    def move_velocity(self, vx: float = 0.0, vy: float = 0.0, vz: float = 0.0,
                      wx: float = 0.0, wy: float = 0.0, wz: float = 0.0,
                      duration: float = 1.0) -> Dict:
        """机体系 6DOF 速度向量（twist）→ 折算为相对位移，走 /api/manual 离散下发。

        vx/vy/vz：前后/左右/上下 线速度 (m/s)；wz：偏航角速度 (rad/s)；
        duration：持续秒数。位移 = 速度 × duration。
        wx/wy（滚转/俯仰角速度）四旋翼位姿模式下不可独立控制，忽略。

        注意：当前为「速度→位移」近似，非真正连续速度控制；
        真正连续速度控制需 APK 新增 /api/velocity（VirtualStick）。
        """
        duration = max(float(duration), 0.0)
        dx = float(vx) * duration
        dy = float(vy) * duration
        dz = float(vz) * duration
        dyaw = math.degrees(float(wz) * duration)  # rad → deg
        ignored = ""
        if abs(float(wx)) > 1e-6 or abs(float(wy)) > 1e-6:
            ignored = " [已忽略 wx/wy：四旋翼无法独立控制滚转/俯仰角速度]"
        result = self.move_relative(dx=dx, dy=dy, dz=dz, dyaw=dyaw)
        base = result.get("message", "") if isinstance(result, dict) else ""
        result["message"] = f"速度→位移近似（{duration}s）{ignored}" + (f" | {base}" if base else "")
        return result

    def rotate_velocity(self, direction: float = 1.0, angular_velocity: float = 0.5,
                        duration: float = 1.0) -> Dict:
        """旋转（方向·角速度·持续时间）→ dyaw 折算后走 /api/manual rotate。

        direction：1 = 左转，-1 = 右转；angular_velocity：偏航角速度 (rad/s，取绝对值)；
        duration：持续秒数。dyaw(度) = direction × degrees(|angular_velocity| × duration)。
        """
        dyaw = float(direction) * math.degrees(abs(float(angular_velocity))) * max(float(duration), 0.0)
        if abs(dyaw) < 1e-6:
            return {"success": False, "message": "没有非零旋转分量"}
        result = self.manual_rotate(dyaw)
        result["message"] = f"旋转 dyaw={dyaw:.1f}°（{'左' if dyaw > 0 else '右'}转）"
        return result

    def gimbal_reset(self, pitch: float = 0.0, roll: float = 0.0, yaw: float = 0.0) -> Dict:
        """云台回中：恢复到指定目标姿态（默认全 0 = 机头正前方水平）。

        gimbal_rotate 内部会同步回写 self._gimbal 跟踪状态，后续增量旋转基准不漂移。
        """
        result = self.gimbal_rotate(pitch=pitch, roll=roll, yaw=yaw)
        result["message"] = f"云台回中 → pitch={pitch}° roll={roll}° yaw={yaw}°"
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

    当前为简化实现：直接将遥测写入本地状态文件，
    供 executor / liaison 通过 Atlas gRPC 查询。
    完整版需引入 robonix-api 的 Primitive 基类。
    """

    def __init__(self, endpoint: str = ATLAS_ENDPOINT):
        self.endpoint = endpoint
        self._registered = False
        self._primitive_id = "drone_bridge"

    def register(self) -> bool:
        """尝试注册到 Atlas（如不可用则降级为 standalone 模式）"""
        try:
            # 尝试连接 Atlas gRPC
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
# 命令处理器（供 Robonix executor 调用）
# ---------------------------------------------------------------------------

class CommandHandler:
    """将 Robonix 能力调用映射到 DroneClient HTTP 请求"""

    def __init__(self, client: DroneClient):
        self.client = client

    def handle(self, capability: str, params: Optional[Dict] = None) -> Dict:
        """分发能力调用"""
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
    return client.takeoff_hover()

def _cmd_land(client: DroneClient, p: Dict) -> Dict:
    return client.go_home()

def _cmd_hover(client: DroneClient, p: Dict) -> Dict:
    return client.stop()

def _cmd_start_mission(client: DroneClient, p: Dict) -> Dict:
    return client.start_mission(
        climb=float(p.get("climb", p.get("climbHeight", 1.0))),
        move=float(p.get("move", p.get("moveDistance", 0.5))),
        yaw=float(p.get("yaw", p.get("yawAngle", 0.0))),
    )

def _cmd_cruise(client: DroneClient, p: Dict) -> Dict:
    return client.start_cruise()

def _cmd_add_waypoint(client: DroneClient, p: Dict) -> Dict:
    return client.add_waypoint(
        lat=float(p["latitude"]),
        lng=float(p["longitude"]),
        alt=float(p.get("altitude", 5.0)),
    )

def _cmd_clear_waypoints(client: DroneClient, p: Dict) -> Dict:
    return client.clear_waypoints()

def _cmd_go_home(client: DroneClient, p: Dict) -> Dict:
    return client.go_home()

def _cmd_rotate_velocity(client: DroneClient, p: Dict) -> Dict:
    """旋转（方向·角速度·持续时间）"""
    return client.rotate_velocity(
        direction=float(p.get("direction", 1.0)),
        angular_velocity=float(p.get("angular_velocity", 0.5)),
        duration=float(p.get("duration", 1.0)),
    )

def _cmd_switch_mode(client: DroneClient, p: Dict) -> Dict:
    return client.switch_mode(p.get("mode", "STANDBY"))

def _cmd_get_status(client: DroneClient, p: Dict) -> Dict:
    return client.get_status()

def _cmd_reset(client: DroneClient, p: Dict) -> Dict:
    return client.reset()

def _cmd_land(client: DroneClient, p: Dict) -> Dict:
    """原地降落"""
    return client.land()

def _cmd_move_ee(client: DroneClient, p: Dict) -> Dict:
    """飞行至目标 GPS 位姿（PoseStamped → add_waypoint + start_cruise）"""
    lat = float(p["latitude"])
    lng = float(p["longitude"])
    alt = float(p.get("altitude", 5.0))
    # 清空旧航点 → 添加新航点 → 启动巡航
    client.clear_waypoints()
    result = client.add_waypoint(lat, lng, alt)
    if result.get("success") is False:
        return result
    return client.start_cruise()

def _cmd_state_position(client: DroneClient, p: Dict) -> Dict:
    """读取当前位置（从 status 提取 GPS + 高度 + 朝向）"""
    status = client.get_status()
    if "error" in status:
        return status
    # 从遥测数据中提取位置相关字段
    return {
        "latitude": status.get("latitude"),
        "longitude": status.get("longitude"),
        "altitude": status.get("altitude", 0.0),
        "heading": status.get("heading", 0.0),
    }

def _cmd_state_battery(client: DroneClient, p: Dict) -> Dict:
    """读取电池电量"""
    status = client.get_status()
    if "error" in status:
        return status
    return {
        "percent": status.get("batteryPercent", 0.0),
        "voltage": status.get("batteryVoltage", 0.0),
    }

def _cmd_move_relative(client: DroneClient, p: Dict) -> Dict:
    """机体系相对移动"""
    return client.move_relative(
        dx=float(p.get("dx", 0.0)),
        dy=float(p.get("dy", 0.0)),
        dz=float(p.get("dz", 0.0)),
        dyaw=float(p.get("dyaw", 0.0)),
    )

def _cmd_move_velocity(client: DroneClient, p: Dict) -> Dict:
    """机体系 6DOF 速度向量（twist）"""
    return client.move_velocity(
        vx=float(p.get("vx", 0.0)),
        vy=float(p.get("vy", 0.0)),
        vz=float(p.get("vz", 0.0)),
        wx=float(p.get("wx", 0.0)),
        wy=float(p.get("wy", 0.0)),
        wz=float(p.get("wz", 0.0)),
        duration=float(p.get("duration", 1.0)),
    )

def _cmd_gimbal_rotate(client: DroneClient, p: Dict) -> Dict:
    """设置云台姿态"""
    return client.gimbal_rotate(
        pitch=float(p.get("pitch", 0.0)),
        roll=float(p.get("roll", 0.0)),
        yaw=float(p.get("yaw", 0.0)),
    )

def _cmd_gimbal_velocity(client: DroneClient, p: Dict) -> Dict:
    """云台 3DOF 角速度向量（°/s）"""
    return client.gimbal_velocity(
        vpitch=float(p.get("vpitch", 0.0)),
        vroll=float(p.get("vroll", 0.0)),
        vyaw=float(p.get("vyaw", 0.0)),
        duration=float(p.get("duration", 1.0)),
    )

def _cmd_gimbal_reset(client: DroneClient, p: Dict) -> Dict:
    """云台回中（默认全 0 = 机头正前方）"""
    return client.gimbal_reset(
        pitch=float(p.get("pitch", 0.0)),
        roll=float(p.get("roll", 0.0)),
        yaw=float(p.get("yaw", 0.0)),
    )

def _cmd_camera_video(client: DroneClient, p: Dict) -> Dict:
    """获取视频流 URL（方案 A：调用方自行拉流）"""
    return {
        "success": True,
        "video_url": client.get_video_url(),
        "format": "mjpeg",
        "resolution": "640px",
        "fps": 12,
        "note": "用浏览器 / curl / ffmpeg 拉流即可",
    }

def _cmd_camera_capture(client: DroneClient, p: Dict) -> Dict:
    """触发拍照"""
    return client.camera_capture()

def _cmd_camera_zoom(client: DroneClient, p: Dict) -> Dict:
    """设置变焦"""
    return client.camera_zoom(factor=float(p.get("factor", 1.0)))

CAPABILITY_MAP = {
    # ── 运动控制 ──
    "robonix/primitive/drone/takeoff":          _cmd_takeoff,
    "robonix/primitive/drone/land":             _cmd_land,
    "robonix/primitive/drone/move_ee":          _cmd_move_ee,
    "robonix/primitive/drone/move_relative":    _cmd_move_relative,
    "robonix/primitive/drone/move_velocity":    _cmd_move_velocity,
    "robonix/primitive/drone/rotate_velocity":  _cmd_rotate_velocity,
    "robonix/primitive/drone/hover":            _cmd_hover,
    "robonix/primitive/drone/rth":              _cmd_go_home,
    # ── 云台 / 相机 ──
    "robonix/primitive/drone/gimbal_rotate":    _cmd_gimbal_rotate,
    "robonix/primitive/drone/gimbal_velocity":  _cmd_gimbal_velocity,
    "robonix/primitive/drone/gimbal_reset":     _cmd_gimbal_reset,
    "robonix/primitive/drone/camera_capture":   _cmd_camera_capture,
    "robonix/primitive/drone/camera_video":     _cmd_camera_video,
    "robonix/primitive/drone/camera_zoom":      _cmd_camera_zoom,
    # ── 状态查询 ──
    "robonix/primitive/drone/state_position":   _cmd_state_position,
    "robonix/primitive/drone/state_battery":    _cmd_state_battery,
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
    print("  takeoff [alt]    — 起飞悬停（默认 3m）")
    print("  land             — 原地降落")
    print("  move_ee <lat> <lng> [alt] — 飞到目标GPS点")
    print("  mv <dx> <dy> <dz> [dyaw] — 相对移动")
    print("  vel <vx> <vy> <vz> [wz] [dur] — 6DOF 速度向量（前后/左右/上下 + 偏航，持续秒数）")
    print("  rv [dir] [wz] [dur] — 旋转（1=左/-1=右，rad/s，秒；默认 左 0.5rad/s×1s）")
    print("  gimbal <pitch> [roll] [yaw] — 云台姿态")
    print("  gv <vpitch> [vyaw] [dur] — 云台角速度向量（俯仰/偏航 °/s，持续秒数）")
    print("  greset [p] [r] [y] — 云台回中（默认全 0 = 机头正前方）")
    print("  video            — 获取视频流 URL")
    print("  photo            — 拍照")
    print("  zoom <factor>    — 变焦")
    print("  hover            — 紧急悬停")
    print("  rth              — 智能返航")
    print("  pos              — 查询当前位置")
    print("  bat              — 查询电量")
    print("  status           — 查看完整状态")
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
        elif cmd == "move_ee":
            if len(parts) < 3:
                print("用法: move_ee <lat> <lng> [alt]")
                continue
            lat, lng = float(parts[1]), float(parts[2])
            alt = float(parts[3]) if len(parts) > 3 else 5.0
            print(json.dumps(handler.handle("robonix/primitive/drone/move_ee", {"latitude": lat, "longitude": lng, "altitude": alt}), ensure_ascii=False))
        elif cmd == "hover":
            print(json.dumps(handler.handle("robonix/primitive/drone/hover"), ensure_ascii=False))
        elif cmd == "rth":
            print(json.dumps(handler.handle("robonix/primitive/drone/rth"), ensure_ascii=False))
        elif cmd == "pos":
            print(json.dumps(handler.handle("robonix/primitive/drone/state_position"), ensure_ascii=False))
        elif cmd == "bat":
            print(json.dumps(handler.handle("robonix/primitive/drone/state_battery"), ensure_ascii=False))
        elif cmd == "mv":
            dx = float(parts[1]) if len(parts) > 1 else 0.0
            dy = float(parts[2]) if len(parts) > 2 else 0.0
            dz = float(parts[3]) if len(parts) > 3 else 0.0
            dyaw = float(parts[4]) if len(parts) > 4 else 0.0
            print(json.dumps(handler.handle("robonix/primitive/drone/move_relative", {"dx": dx, "dy": dy, "dz": dz, "dyaw": dyaw}), ensure_ascii=False))
        elif cmd in ("vel", "velocity"):
            vx = float(parts[1]) if len(parts) > 1 else 0.0
            vy = float(parts[2]) if len(parts) > 2 else 0.0
            vz = float(parts[3]) if len(parts) > 3 else 0.0
            wz = float(parts[4]) if len(parts) > 4 else 0.0
            dur = float(parts[5]) if len(parts) > 5 else 1.0
            print(json.dumps(handler.handle("robonix/primitive/drone/move_velocity",
                  {"vx": vx, "vy": vy, "vz": vz, "wz": wz, "duration": dur}), ensure_ascii=False))
        elif cmd in ("rv", "rotate_velocity"):
            direction = float(parts[1]) if len(parts) > 1 else 1.0
            wz = float(parts[2]) if len(parts) > 2 else 0.5
            dur = float(parts[3]) if len(parts) > 3 else 1.0
            print(json.dumps(handler.handle("robonix/primitive/drone/rotate_velocity",
                  {"direction": direction, "angular_velocity": wz, "duration": dur}), ensure_ascii=False))
        elif cmd in ("greset", "gimbal_reset"):
            pitch = float(parts[1]) if len(parts) > 1 else 0.0
            roll = float(parts[2]) if len(parts) > 2 else 0.0
            yaw = float(parts[3]) if len(parts) > 3 else 0.0
            print(json.dumps(handler.handle("robonix/primitive/drone/gimbal_reset", {"pitch": pitch, "roll": roll, "yaw": yaw}), ensure_ascii=False))
        elif cmd == "video":
            print(json.dumps(handler.handle("robonix/primitive/drone/camera_video"), ensure_ascii=False))
        elif cmd == "gimbal":
            pitch = float(parts[1]) if len(parts) > 1 else 0.0
            roll = float(parts[2]) if len(parts) > 2 else 0.0
            yaw = float(parts[3]) if len(parts) > 3 else 0.0
            print(json.dumps(handler.handle("robonix/primitive/drone/gimbal_rotate", {"pitch": pitch, "roll": roll, "yaw": yaw}), ensure_ascii=False))
        elif cmd in ("gv", "gimbal_velocity"):
            vpitch = float(parts[1]) if len(parts) > 1 else 0.0
            vyaw = float(parts[2]) if len(parts) > 2 else 0.0
            dur = float(parts[3]) if len(parts) > 3 else 1.0
            print(json.dumps(handler.handle("robonix/primitive/drone/gimbal_velocity",
                  {"vpitch": vpitch, "vyaw": vyaw, "duration": dur}), ensure_ascii=False))
        elif cmd == "photo":
            print(json.dumps(handler.handle("robonix/primitive/drone/camera_capture"), ensure_ascii=False))
        elif cmd == "zoom":
            factor = float(parts[1]) if len(parts) > 1 else 1.0
            print(json.dumps(handler.handle("robonix/primitive/drone/camera_zoom", {"factor": factor}), ensure_ascii=False))
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

        # 在遥测更新时上报到 Atlas（简化版：通过文件）
        def on_telemetry(status):
            pass  # 完整集成时通过 gRPC stream 上报

        poller.on_update(on_telemetry)

        # 等待信号
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
