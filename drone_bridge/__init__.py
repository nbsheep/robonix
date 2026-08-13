"""
drone_bridge — DJI M3E 无人机 Robonix 原语

通过 HTTP API 桥接 RC Pro (Drone_test APK)，提供：
- 飞行控制（起飞/降落/悬停/虚拟摇杆）
- 遥测数据（高度/GPS/电池/飞行模式）
- 视频流代理（MJPEG → Robonix）
"""
__version__ = "1.0.0"
