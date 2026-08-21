#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
无人机 Web HTTP API 控制脚本
============================

通过遥控器上运行的嵌入式 Web 服务器（默认端口 8080）的 HTTP API，
在外部（如 Windows 电脑）对无人机进行控制。

依赖：
    pip install requests

用法示例：
    # 查看状态
    python drone_control.py --host 192.168.1.100 status

    # 开始任务（待命模式）：爬升2m、平移-1m(左)、旋转90°
    python drone_control.py --host 192.168.1.100 start --climb 2.0 --move -1.0 --yaw 90

    # 切换模式 / 起飞悬停 / 手动操控
    python drone_control.py --host 192.168.1.100 mode manual
    python drone_control.py --host 192.168.1.100 takeoff
    python drone_control.py --host 192.168.1.100 manual climb --value 1.5

    # 航点巡航
    python drone_control.py --host 192.168.1.100 waypoint add --lat 30.123456 --lng 120.654321 --alt 5
    python drone_control.py --host 192.168.1.100 cruise

    # 返航 / 紧急停止 / 重置
    python drone_control.py --host 192.168.1.100 home
    python drone_control.py --host 192.168.1.100 stop
    python drone_control.py --host 192.168.1.100 reset

    # 持续监视状态（1秒刷新）
    python drone_control.py --host 192.168.1.100 watch

    # 交互式控制台
    python drone_control.py --host 192.168.1.100 shell

    # 作为库导入复用
    from drone_control import DroneClient
    drone = DroneClient("192.168.1.100", 8080)
    print(drone.get_status())
"""

import argparse
import json
import sys
import time
from typing import Any, Dict, List, Optional

try:
    import requests
except ImportError:
    print("缺少依赖 requests，请先执行: pip install requests")
    sys.exit(1)


# ============================================================
#  客户端类（可复用）
# ============================================================

class DroneClient:
    """封装遥控器 Web 服务器的 HTTP API。"""

    def __init__(self, host: str = "192.168.1.100", port: int = 8080, timeout: float = 8.0):
        self.base = f"http://{host}:{port}"
        self.timeout = timeout

    # ---------- 底层请求 ----------

    def _get(self, path: str) -> Dict[str, Any]:
        resp = requests.get(self.base + path, timeout=self.timeout)
        resp.raise_for_status()
        return resp.json()

    def _post(self, path: str, payload: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        resp = requests.post(self.base + path, json=payload or {}, timeout=self.timeout)
        resp.raise_for_status()
        return resp.json()

    # ---------- 状态 ----------

    def get_status(self) -> Dict[str, Any]:
        """获取无人机/服务器完整状态。"""
        return self._get("/api/status")

    def wait_until(
        self,
        field: str,
        target,
        timeout: float = 60.0,
        interval: float = 1.0,
        verbose: bool = True,
    ) -> bool:
        """轮询等待某个状态字段达到目标值。

        例：drone.wait_until("missionState", "HOVERING", timeout=30)
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                st = self.get_status()
                cur = st.get(field)
                if cur == target:
                    return True
                if verbose:
                    print(f"    等待 {field}={target} ... 当前={cur} ({st.get('missionState')})")
            except Exception as e:
                if verbose:
                    print(f"    状态查询失败: {e}")
            time.sleep(interval)
        return False

    # ---------- 任务 / 模式 ----------

    def start_mission(self, climb: float = 1.0, move: float = 0.5, yaw: float = 0.0) -> Dict[str, Any]:
        """待命模式：开始简单任务（爬升/平移/旋转）。"""
        return self._post("/api/start", {
            "climbHeight": climb,
            "moveDistance": move,
            "yawAngle": yaw,
        })

    def switch_mode(self, mode: str) -> Dict[str, Any]:
        """切换模式：standby / cruise / manual。"""
        return self._post("/api/mode", {"mode": mode.upper()})

    def takeoff_hover(self) -> Dict[str, Any]:
        """手动模式：一键起飞并悬停。"""
        return self._post("/api/takeoff_hover")

    # ---------- 手动操控 ----------

    def manual_climb(self, delta: float) -> Dict[str, Any]:
        """手动升降，正=上升，负=下降。"""
        return self._post("/api/manual", {"action": "climb", "value": delta})

    def manual_move_left(self, distance: float) -> Dict[str, Any]:
        return self._post("/api/manual", {"action": "move_left", "value": distance})

    def manual_move_right(self, distance: float) -> Dict[str, Any]:
        return self._post("/api/manual", {"action": "move_right", "value": distance})

    def manual_rotate(self, degrees: float) -> Dict[str, Any]:
        """手动旋转，正=右转，负=左转。"""
        return self._post("/api/manual", {"action": "rotate", "value": degrees})

    # ---------- 航点巡航 ----------

    def add_waypoint(self, lat: float, lng: float, alt: float = 5.0) -> Dict[str, Any]:
        return self._post("/api/add_waypoint", {
            "latitude": lat,
            "longitude": lng,
            "altitude": alt,
        })

    def clear_waypoints(self) -> Dict[str, Any]:
        return self._post("/api/clear_waypoints")

    def start_cruise(self) -> Dict[str, Any]:
        return self._post("/api/start_cruise")

    # ---------- 其他 ----------

    def go_home(self) -> Dict[str, Any]:
        """返航降落。"""
        return self._post("/api/gohome")

    def emergency_stop(self) -> Dict[str, Any]:
        """紧急停止。"""
        return self._post("/api/stop")

    def reset(self) -> Dict[str, Any]:
        """重置 UI。"""
        return self._post("/api/reset")


# ============================================================
#  状态展示
# ============================================================

MISSION_STATE_CN = {
    "IDLE": "就绪", "TAKEOFF": "起飞中", "CLIMBING": "爬升中",
    "YAW_ROTATE": "旋转中", "MOVE_LEFT": "左移", "MOVE_RIGHT": "右移",
    "HOVERING": "悬停", "LANDING": "降落中", "COMPLETED": "完成",
    "ERROR": "错误", "CRUISE_TAKEOFF": "巡航起飞", "WAYPOINT_YAW": "对准航点",
    "WAYPOINT_FLY": "飞向航点",
}
MODE_CN = {"STANDBY": "待命", "AUTO_CRUISE": "自动巡航", "MANUAL": "手动操控"}


def print_status(st: Dict[str, Any]) -> None:
    """格式化打印状态。"""
    mission = st.get("missionState", "IDLE")
    mode = st.get("operationMode", "STANDBY")
    print("-" * 52)
    print(f"  模式       : {MODE_CN.get(mode, mode)}")
    print(f"  任务状态   : {MISSION_STATE_CN.get(mission, mission)}")
    print(f"  高度       : {st.get('altitude', 0.0):.1f} 米")
    print(f"  SDK        : {'已激活' if st.get('sdkRegistered') else '未激活'}")
    print(f"  无人机     : {'已连接' if st.get('productConnected') else '等待连接'}")
    print(f"  虚拟摇杆   : {'已启用' if st.get('vsEnabled') else '未启用'}")
    print(f"  航点数     : {st.get('waypointCount', 0)}")
    if st.get("statusMessage"):
        print(f"  消息       : {st.get('statusMessage')}")
    wps = st.get("waypoints", [])
    if wps:
        print("  航点列表   :")
        for w in wps:
            print(f"      {w.get('label', '')}")
    print("-" * 52)


def do_watch(client: DroneClient, interval: float = 1.0) -> None:
    """持续监视状态直到 Ctrl+C。"""
    print("持续监视中，按 Ctrl+C 退出 ...")
    try:
        while True:
            try:
                st = client.get_status()
                print(f"[{time.strftime('%H:%M:%S')}] "
                      f"模式={MODE_CN.get(st.get('operationMode'), st.get('operationMode'))} "
                      f"状态={MISSION_STATE_CN.get(st.get('missionState'), st.get('missionState'))} "
                      f"高度={st.get('altitude', 0.0):.1f}m "
                      f"航点={st.get('waypointCount', 0)} "
                      f"VS={'开' if st.get('vsEnabled') else '关'}")
            except Exception as e:
                print(f"[{time.strftime('%H:%M:%S')}] 连接失败: {e}")
            time.sleep(interval)
    except KeyboardInterrupt:
        print("\n已停止监视")


def do_shell(client: DroneClient) -> None:
    """交互式控制台。"""
    help_text = """
命令:
  status / s          查看状态
  start [爬升] [平移] [旋转]   开始任务(待命模式)
  mode <standby|cruise|manual>  切换模式
  takeoff             起飞并悬停
  climb <值>          手动升降
  left <值> / right <值>       手动左/右移
  rotate <值>         手动旋转
  wp add <lat> <lng> [alt]     添加航点
  wp clear            清空航点
  wp list             查看航点
  cruise              开始巡航
  home                返航降落
  stop                紧急停止
  reset               重置
  watch               持续监视
  help / ?            显示本帮助
  quit / exit / q     退出
"""
    print(help_text)
    while True:
        try:
            raw = input("drone> ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n再见")
            break
        if not raw:
            continue
        parts = raw.split()
        cmd = parts[0].lower()
        try:
            if cmd in ("quit", "exit", "q"):
                break
            elif cmd in ("status", "s"):
                print_status(client.get_status())
            elif cmd == "start":
                c = float(parts[1]) if len(parts) > 1 else 1.0
                m = float(parts[2]) if len(parts) > 2 else 0.5
                y = float(parts[3]) if len(parts) > 3 else 0.0
                print(client.start_mission(c, m, y))
            elif cmd == "mode":
                print(client.switch_mode(parts[1]))
            elif cmd == "takeoff":
                print(client.takeoff_hover())
            elif cmd == "climb":
                print(client.manual_climb(float(parts[1])))
            elif cmd == "left":
                print(client.manual_move_left(float(parts[1])))
            elif cmd == "right":
                print(client.manual_move_right(float(parts[1])))
            elif cmd == "rotate":
                print(client.manual_rotate(float(parts[1])))
            elif cmd == "wp" and len(parts) >= 2:
                sub = parts[1].lower()
                if sub == "add":
                    lat = float(parts[2]); lng = float(parts[3])
                    alt = float(parts[4]) if len(parts) > 4 else 5.0
                    print(client.add_waypoint(lat, lng, alt))
                elif sub == "clear":
                    print(client.clear_waypoints())
                elif sub == "list":
                    st = client.get_status()
                    for w in st.get("waypoints", []):
                        print("  " + w.get("label", ""))
            elif cmd == "cruise":
                print(client.start_cruise())
            elif cmd == "home":
                print(client.go_home())
            elif cmd == "stop":
                print(client.emergency_stop())
            elif cmd == "reset":
                print(client.reset())
            elif cmd == "watch":
                do_watch(client)
            elif cmd in ("help", "?"):
                print(help_text)
            else:
                print("未知命令，输入 help 查看帮助")
        except (IndexError, ValueError):
            print("参数错误，输入 help 查看用法")
        except Exception as e:
            print(f"执行失败: {e}")


# ============================================================
#  命令行入口
# ============================================================

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="无人机 Web HTTP API 控制脚本",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例: python drone_control.py --host 192.168.1.100 status",
    )
    p.add_argument("--host", default="192.168.1.100", help="遥控器 Web 服务器 IP")
    p.add_argument("--port", type=int, default=8080, help="端口 (默认 8080)")

    sub = p.add_subparsers(dest="command")

    # status
    sub.add_parser("status", help="查看状态")

    # start
    sp = sub.add_parser("start", help="开始任务(待命模式)")
    sp.add_argument("--climb", type=float, default=1.0, help="爬升高度(m)")
    sp.add_argument("--move", type=float, default=0.5, help="平移距离(m, 左负右正)")
    sp.add_argument("--yaw", type=float, default=0.0, help="旋转角度(°)")

    # mode
    mp = sub.add_parser("mode", help="切换模式")
    mp.add_argument("value", choices=["standby", "cruise", "manual"], help="模式")

    # takeoff
    sub.add_parser("takeoff", help="起飞并悬停")

    # manual
    mcp = sub.add_parser("manual", help="手动操控")
    mcp.add_argument("action", choices=["climb", "move_left", "move_right", "rotate"])
    mcp.add_argument("--value", type=float, required=True, help="数值")

    # waypoint
    wp = sub.add_parser("waypoint", help="航点管理")
    wsub = wp.add_subparsers(dest="wcmd")
    wa = wsub.add_parser("add", help="添加航点")
    wa.add_argument("--lat", type=float, required=True)
    wa.add_argument("--lng", type=float, required=True)
    wa.add_argument("--alt", type=float, default=5.0)
    wsub.add_parser("clear", help="清空航点")
    wsub.add_parser("list", help="查看航点")

    # cruise
    sub.add_parser("cruise", help="开始巡航")

    # 简单命令
    sub.add_parser("home", help="返航降落")
    sub.add_parser("stop", help="紧急停止")
    sub.add_parser("reset", help="重置UI")

    # watch / shell
    wp2 = sub.add_parser("watch", help="持续监视状态")
    wp2.add_argument("--interval", type=float, default=1.0, help="刷新间隔秒")
    sub.add_parser("shell", help="交互式控制台")

    return p


def main() -> None:
    args = build_parser().parse_args()
    if not args.command:
        build_parser().print_help()
        return

    client = DroneClient(args.host, args.port)

    if args.command == "status":
        print_status(client.get_status())

    elif args.command == "start":
        print(client.start_mission(args.climb, args.move, args.yaw))

    elif args.command == "mode":
        print(client.switch_mode(args.value))

    elif args.command == "takeoff":
        print(client.takeoff_hover())

    elif args.command == "manual":
        fn = {
            "climb": client.manual_climb,
            "move_left": client.manual_move_left,
            "move_right": client.manual_move_right,
            "rotate": client.manual_rotate,
        }[args.action]
        print(fn(args.value))

    elif args.command == "waypoint":
        if args.wcmd == "add":
            print(client.add_waypoint(args.lat, args.lng, args.alt))
        elif args.wcmd == "clear":
            print(client.clear_waypoints())
        elif args.wcmd == "list":
            st = client.get_status()
            wps = st.get("waypoints", [])
            if wps:
                for w in wps:
                    print(w.get("label", ""))
            else:
                print("尚未添加航点")
        else:
            print("请指定子命令: add / clear / list")

    elif args.command == "cruise":
        print(client.start_cruise())

    elif args.command == "home":
        print(client.go_home())

    elif args.command == "stop":
        print(client.emergency_stop())

    elif args.command == "reset":
        print(client.reset())

    elif args.command == "watch":
        do_watch(client, args.interval)

    elif args.command == "shell":
        do_shell(client)


if __name__ == "__main__":
    main()
