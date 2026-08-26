#!/usr/bin/env python3
"""gimbal_aim.py —— 无人机云台对准火源控制（离散步进）。

背景：Drone_test APK 的 `/api/gimbal` 只支持**离散步进**动作
（pitch_up/pitch_down/yaw_left/yaw_right/level，step 0.5~180°），没有连续速度闭环。
所以这里把画面里最明显的"火"框相对画面中心的偏移，映射成"朝向中心的一小步"，
逐帧把镜头收拢到火源附近，供后续拍照打点用。这是**粗对准**（离线板方案），不接管飞行。

映射规则（图像坐标：x 向右为 +，y 向下为 +）：
  - 火在画面中部右侧（dx>0）→ 相机向右偏航     yaw_right
  - 火在画面中部左侧（dx<0）→ 相机向左偏航     yaw_left
  - 火在画面中部下方（dy>0）→ 相机向下俯仰     pitch_down
  - 火在画面中部上方（dy<0）→ 相机向上俯仰     pitch_up
步长按偏移比例缩放，并 clamp 到 [0.5, max_step]，避免过冲。

安全：**只在 `cruiseActive==true` 时下发**（云台/相机仅在巡航中可用），否则直接跳过。
本模块**绝不发送任何飞行控制命令**（不涉及 takeoff/land/move/rth）。

独立自测（无需真机）：  python scripts/gimbal_aim.py
"""
from __future__ import annotations

from typing import Any, List, Optional, Sequence, Tuple


def clamp(x: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, x))


def offset_to_moves(
    bbox_xyxy: Sequence[float],
    frame_shape: Sequence[int],
    base_step_deg: float = 7.5,
    max_step: float = 30.0,
    deadband_px: float = 40.0,
) -> List[Tuple[str, float]]:
    """把火框中心相对画面中心的偏移，映射成 [(action, step_deg), ...]。

    bbox_xyxy: 检测框 (x1, y1, x2, y2)，像素坐标。
    frame_shape: (H, W, ...) 或 (H, W)。
    返回：需要下发的云台动作列表；已居中（偏移在 deadband 内）则返回 []。
    """
    x1, y1, x2, y2 = (float(v) for v in bbox_xyxy[:4])
    H = float(frame_shape[0])
    W = float(frame_shape[1])
    cx = (x1 + x2) / 2.0
    cy = (y1 + y2) / 2.0
    dx = cx - W / 2.0  # 画面右为 +
    dy = cy - H / 2.0  # 画面下为 +
    # 归一化到 [-1, 1]
    nx = dx / (W / 2.0) if W else 0.0
    ny = dy / (H / 2.0) if H else 0.0

    moves: List[Tuple[str, float]] = []
    if abs(dx) > deadband_px:
        step = clamp(base_step_deg * abs(nx), 0.5, max_step)
        moves.append(("yaw_right" if dx > 0 else "yaw_left", round(step, 1)))
    if abs(dy) > deadband_px:
        step = clamp(base_step_deg * abs(ny), 0.5, max_step)
        moves.append(("pitch_down" if dy > 0 else "pitch_up", round(step, 1)))
    return moves


def aim_steps(
    bbox_xyxy: Sequence[float],
    frame_shape: Sequence[int],
    client: Any,
    base_step_deg: float = 7.5,
    max_step: float = 30.0,
    deadband_px: float = 40.0,
    require_cruise: bool = True,
) -> List[Tuple[str, float, Optional[Any]]]:
    """根据当前火框下发一次对准步进。

    返回实际执行的 [(action, step, response), ...]；未在巡航（或无 gimbal 方法）返回 []。
    """
    if require_cruise:
        # 云台只在巡航中可用：先确认 cruiseActive，不满足则跳过（避免无效/越权操作）。
        status = client.get_status() if hasattr(client, "get_status") else {}
        if not isinstance(status, dict) or status.get("cruiseActive") is not True:
            return []

    moves = offset_to_moves(bbox_xyxy, frame_shape, base_step_deg, max_step, deadband_px)
    executed: List[Tuple[str, float, Optional[Any]]] = []
    for action, step in moves:
        resp = client.gimbal(action, step) if hasattr(client, "gimbal") else None
        executed.append((action, step, resp))
    return executed


# ---------------------------------------------------------------------------
# 离线自测：校验"偏移 → 动作/步长"映射（不连接无人机）
# ---------------------------------------------------------------------------


def _self_test() -> int:
    # frame_shape = (H=720, W=1280) → 画面中心 (640, 360)，deadband=40px
    def case(name: str, bbox: Tuple[int, int, int, int], expect: List[str]) -> None:
        got = [m[0] for m in offset_to_moves(bbox, (720, 1280))]
        assert got == expect, f"{name}: got {got}, expect {expect}"
        print(f"[OK] {name:<18} -> {[m[0] for m in offset_to_moves(bbox, (720, 1280))] if got else '中心(无动作)'}")

    case("火在右侧中央", (900, 300, 1100, 500), ["yaw_right"])      # cx=1000 > 640+40
    case("火在左侧", (100, 300, 250, 420), ["yaw_left"])            # cx=175 < 640-40, cy=360(居中)
    case("火在左上", (100, 100, 250, 250), ["yaw_left", "pitch_up"])  # cx=175, cy=175
    case("火在右下", (900, 500, 1100, 700), ["yaw_right", "pitch_down"])  # cx=1000, cy=600
    case("火已居中", (610, 320, 670, 380), [])                      # 中心(640,350) 在 deadband 内
    print("\n[OK] gimbal_aim 偏移→动作 映射全部正确")
    return 0


if __name__ == "__main__":
    raise SystemExit(_self_test())
