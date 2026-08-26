#!/usr/bin/env python3
"""fire_patrol.py —— 无人机自动巡航火灾巡检（对准拍摄 + 报告）。

离板（PC 端）方案：无人机通过 RC Pro 上的 Drone_test APK 提供 HTTP API
（视频 MJPEG `GET /api/video`、云台 `POST /api/gimbal`、相机 `POST /api/camera`、
GPS `POST /api/capture_gps`、巡航状态 `GET /api/status.cruiseActive`）。

本程序只做**监视 + 云台对准 + 拍照 + 打点 + 写报告**：
  直连 RC Pro → OpenCV 拉 MJPEG → YOLO 火/烟检测 → 逐类去抖 →
  (fire) 云台对准 → 拍照 → 读 GPS → 写入巡检报告。

安全边界（重要）：
  - 本程序**绝不发送飞行控制命令**（takeoff/land/move/rth 等不涉及）。
  - 只在 `cruiseActive==true`（无人机正在巡航）时才动云台/相机，否则跳过。
  - 发现火只做"对准拍摄 + 报告"，**不会自动返航**。

环境：anaconda3\\envs\\fire-detect（torch CUDA + cv2 + ultralytics + requests + jinja2）。
运行前无人机需已设置航点并 `start_cruise`。无无人机时也能用本地视频演练：
  SOURCE=samples/test.mp4 python scripts/fire_patrol.py
"""
from __future__ import annotations

import os
import sys
import time

# 复用 drone_bridge 的 HTTP 客户端（DroneClient 已封装 /api/gimbal、/api/camera、
# /api/capture_gps、/api/status）。这是独立包，直连 RC Pro，不依赖 rbnx boot。
# 默认取本机路径，可用环境变量 DRONE_BRIDGE 覆盖（README 有说明如何拿到 drone_bridge）。
_DRONE_BRIDGE = os.environ.get(
    "DRONE_BRIDGE",
    r"C:/Users/nice/Desktop/drone_bridge",
)
if _DRONE_BRIDGE not in sys.path:
    sys.path.insert(0, _DRONE_BRIDGE)

import cv2  # noqa: E402
from ultralytics import YOLO  # noqa: E402
from drone_bridge.main import DroneClient  # noqa: E402

# 本目录下的复用模块（reporter / alarm / gimbal_aim）
from reporter import InspectionReporter  # noqa: E402
from alarm import AlarmDebouncer  # noqa: E402
import gimbal_aim  # noqa: E402

# ===========================================================================
# 配置区
# ===========================================================================
RC_PRO_IP = os.environ.get("RC_PRO_IP", "10.225.57.15")        # RC Pro 局域网 IP
RC_PRO_PORT = int(os.environ.get("RC_PRO_PORT", "8080"))       # Drone_test APK 端口
WEIGHTS = os.environ.get("WEIGHTS", "models/best.pt")          # YOLO 权重（D-Fire：0=smoke,1=fire）
# 视频源：默认直连 Drone_test APK 的 MJPEG。
#   备选（需 ffmpeg→MediaMTX 推 RTSP）：SOURCE=rtsp://127.0.0.1:8554/live/drone
SOURCE = os.environ.get("SOURCE", f"http://{RC_PRO_IP}:{RC_PRO_PORT}/api/video")
CONF = float(os.environ.get("CONF", "0.35"))                   # 检测置信度阈值
TRIGGER = int(os.environ.get("TRIGGER", "5"))                  # 连续几帧检到才报警
RELEASE = int(os.environ.get("RELEASE", "15"))                 # 连续几帧没检到才解除
AIM_ON_FIRE = os.environ.get("AIM_ON_FIRE", "1") == "1"        # 发现火→云台对准
CAPTURE_ON_FIRE = os.environ.get("CAPTURE_ON_FIRE", "1") == "1"  # 对准后拍一张
SHOW = os.environ.get("SHOW", "1") == "1"                      # 是否弹窗实时预览
AIM_SLEEP = float(os.environ.get("AIM_SLEEP", "0.4"))          # 对准步进间等待（秒）
DEVICE = int(os.environ.get("DEVICE", "0"))                    # CUDA 设备号
# ===========================================================================


def is_cruising(client: DroneClient) -> bool:
    """读取 /api/status.cruiseActive；不可达异常按 False 处理（宁可不动作）。"""
    try:
        st = client.get_status()
        return bool(st.get("cruiseActive"))
    except Exception:
        return False


def get_gps(client: DroneClient):
    """读无人机 GPS（/api/capture_gps，经 get_state 合并）。成功返回 (lat, lng)，否则 None。"""
    try:
        st = client.get_state()
        if isinstance(st, dict) and st.get("latitude") is not None:
            return (float(st["latitude"]), float(st["longitude"]))
    except Exception:
        pass
    return None


def focus_fire_box(boxes, model, cls_id):
    """返回当前帧里面积最大的 fire 检测框 xyxy；没有则 None。"""
    if boxes is None or len(boxes) == 0:
        return None
    xyxy = boxes.xyxy.cpu().numpy()
    cls = boxes.cls.cpu().numpy().astype(int)
    best, best_area = None, 0.0
    for b, c in zip(xyxy, cls):
        if c != cls_id:
            continue
        area = (b[2] - b[0]) * (b[3] - b[1])
        if area > best_area:
            best, best_area = b, area
    return best


def open_stream(src: str):
    """打开视频流；失败返回 None。MJPEG 减小缓冲避免延迟堆积。"""
    cap = cv2.VideoCapture(src)
    if cap.isOpened():
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        return cap
    cap.release()
    return None


def main() -> int:
    client = DroneClient(RC_PRO_IP, RC_PRO_PORT)
    ok = client.check_connection()
    print(f"[drone] RC Pro: {client.base}  {'[OK] 已连接' if ok else '[警告] 不可达（仍可跑检测，仅云台/拍照会跳过）'}")
    if not ok:
        print("[drone] 检查：无人机+RC Pro 开机、Drone_test APK 在自动化飞行页、PC 与 RC Pro 同一 WiFi、"
              f"或 curl http://{RC_PRO_IP}:{RC_PRO_PORT}/api/status 是否能返回 JSON。")

    model = YOLO(WEIGHTS)
    fire_cls = int(model.names.get("fire", 1))  # D-Fire: 0=smoke, 1=fire
    reporter = InspectionReporter()
    alarms = {
        "fire": AlarmDebouncer(TRIGGER, RELEASE),
        "smoke": AlarmDebouncer(TRIGGER, RELEASE),
    }

    cap = open_stream(SOURCE)
    if cap is None:
        print(f"[错误] 打不开视频源：{SOURCE}")
        return 1
    print(f"[巡检] 开始  source={SOURCE}  conf={CONF}  trigger={TRIGGER}  release={RELEASE}  "
          f"aim={AIM_ON_FIRE}  capture={CAPTURE_ON_FIRE}  窗口激活时按 q 退出")

    try:
        while True:
            ret, frame = cap.read()
            if not ret:
                if SOURCE.startswith(("http", "rtsp", "rtmp")):
                    print("[巡检] 读帧失败，重连中…")
                    cap.release()
                    time.sleep(2)
                    cap = open_stream(SOURCE)
                    if cap is None:
                        time.sleep(2)
                    continue
                break  # 本地离线视频读完

            # ---- YOLO 检测（复现 patrol.py 的访问方式，取每类最高置信度） ----
            results = model.predict(frame, conf=CONF, device=DEVICE, verbose=False)
            r = results[0]
            annotated = r.plot()
            per_class = {"fire": 0.0, "smoke": 0.0}
            for box in r.boxes:
                name = model.names[int(box.cls[0])]
                if name in per_class:
                    per_class[name] = max(per_class[name], float(box.conf[0]))

            # ---- 逐类去抖 + 动作 ----
            for label, deb in alarms.items():
                state = deb.update(per_class[label] > 0)
                if state == "RAISE":
                    print(f"[报警] 确认 {label}！conf={per_class[label]:.2f}")
                    cruise = is_cruising(client)
                    if label == "fire":
                        if AIM_ON_FIRE and cruise:
                            box = focus_fire_box(r.boxes, model, fire_cls)
                            if box is not None:
                                moves = gimbal_aim.aim_steps(
                                    box, frame.shape, client, require_cruise=False)
                                for action, step, resp in moves:
                                    print(f"    云台 {action} {step}° -> {resp}")
                                time.sleep(AIM_SLEEP)
                        if CAPTURE_ON_FIRE and cruise:
                            shot = client.camera_capture()
                            print(f"    拍照 -> {shot}")
                        elif CAPTURE_ON_FIRE and not cruise:
                            print("    未巡航，跳过拍照")
                    gps = get_gps(client)
                    reporter.log_event(annotated, label, per_class[label], gps=gps)
                elif state == "CLEAR":
                    print(f"[解除] {label} 报警解除")

            if SHOW:
                cv2.imshow("Fire Patrol - 按 q 退出", annotated)
                if cv2.waitKey(1) & 0xFF == ord("q"):
                    print("[巡检] 检测到按 q，退出")
                    break
    except KeyboardInterrupt:
        print("\n[巡检] 手动中断")
    finally:
        cap.release()
        cv2.destroyAllWindows()
        reporter.save_report()
        print("[巡检] 报告已生成")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
