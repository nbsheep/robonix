#!/usr/bin/env python3
"""
Mavic HTTP Control Primitive - Robonix 原语服务入口
"""

import sys
import os

# 将父目录加入Python路径，以便导入 drone_control
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import robonix_api
from drone_control import DroneClient


class MavicHttpPrimitive:
    def __init__(self, config):
        host = config.get('host', '192.168.1.100')
        port = config.get('port', 8080)
        self.client = DroneClient(host, port)
        print(f"[MavicHttpPrimitive] Initialized with host={host}:{port}")

    # ---------- 状态查询 ----------
    def get_status(self):
        return self.client.get_status()

    # ---------- 任务控制 ----------
    def takeoff(self):
        return self.client.takeoff_hover()

    def start_mission(self, climb: float = 1.0, move: float = 0.5, yaw: float = 0.0):
        return self.client.start_mission(climb, move, yaw)

    def switch_mode(self, mode: str):
        return self.client.switch_mode(mode)

    # ---------- 手动操控 ----------
    def manual_climb(self, value: float):
        return self.client.manual_climb(value)

    def manual_move_left(self, value: float):
        return self.client.manual_move_left(value)

    def manual_move_right(self, value: float):
        return self.client.manual_move_right(value)

    def manual_rotate(self, value: float):
        return self.client.manual_rotate(value)

    # ---------- 航点管理 ----------
    def add_waypoint(self, lat: float, lng: float, alt: float = 5.0):
        return self.client.add_waypoint(lat, lng, alt)

    def clear_waypoints(self):
        return self.client.clear_waypoints()

    def list_waypoints(self):
        status = self.client.get_status()
        return status.get("waypoints", [])

    def start_cruise(self):
        return self.client.start_cruise()

    # ---------- 安全控制 ----------
    def go_home(self):
        return self.client.go_home()

    def emergency_stop(self):
        return self.client.emergency_stop()

    def reset(self):
        return self.client.reset()


if __name__ == "__main__":
    config = robonix_api.load_config()
    driver = MavicHttpPrimitive(config)

    robonix_api.run_primitive(
        driver=driver,
        capabilities=[
            "robonix/primitive/drone/status",
            "robonix/primitive/drone/takeoff",
            "robonix/primitive/drone/mission/start",
            "robonix/primitive/drone/mode/switch",
            "robonix/primitive/drone/manual/climb",
            "robonix/primitive/drone/manual/move_left",
            "robonix/primitive/drone/manual/move_right",
            "robonix/primitive/drone/manual/rotate",
            "robonix/primitive/drone/waypoint/add",
            "robonix/primitive/drone/waypoint/clear",
            "robonix/primitive/drone/waypoint/list",
            "robonix/primitive/drone/cruise/start",
            "robonix/primitive/drone/home",
            "robonix/primitive/drone/emergency_stop",
            "robonix/primitive/drone/reset",
        ]
    )
