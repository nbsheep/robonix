# dji_msdk — 统一 drone 原语规格说明（v2）

> 命名空间 **`robonix/primitive/drone/*`**（SDK 无关）。
> 通过 `rbnx codegen --mcp` 从 `capabilities/lib/drone/srv/*.srv` +
> `capabilities/primitive/drone/*.toml` 生成 `drone_mcp` / `std_msgs_mcp` dataclass。
> 响应统一携带 `std_msgs/String status`，内容为 JSON 字符串（顶层 `ok` 字段表示成功与否）。

## 一、目标与架构（三层）

**目标**：把 DJI MSDK v5 封装成驱动，同级还有 PSDK / OSDK / PX4 等 SDK，
不同 SDK 通过**同一套原语**调用。

```
契约层  capabilities/lib/drone/srv/*.srv + capabilities/primitive/drone/*.toml
        （Schema A，SDK 无关：只有语义 id / version / idl / description）
   │  rbnx codegen --mcp 生成 drone_mcp / std_msgs_mcp dataclass
   ▼
驱动层  dji_msdk/driver.py
        （@drone.mcp("robonix/primitive/drone/<name>") handler：语义请求 → 后端语义方法 → 语义响应）
   │  只认 backend 的语义方法（takeoff/rth/gimbal/state…），不认识任何 MSDK key
   ▼
后端层  dji_msdk/backend.py  （MsdkBackend —— 「封装 MSDK」真正落地的地方）
        （语义方法 → MSDK key/manager → APK HTTP 端点）
   │  换 SDK 只需换后端
   ▼
传输    APK WebServer.kt :8080（MSDK v5 在 APK 内运行）
```

- **契约层、驱动层 SDK 无关**：只出现 `takeoff` / `rth` / `state` 这类语义名。
- **只有后端层知道 MSDK 术语**（`FlightControllerKey.KeyStartTakeoff` 等），
  全部作为 `MsdkBackend` 的**私有 helper**（`_perform_action` / `_get_value` /
  `_manager_call`）。将来 PSDK / OSDK / PX4 各写一个后端
  （`psdk_backend.py` / `mavlink_backend.py`），实现**同一组语义方法**，
  `driver.py` 与契约层完全不变。

> **必须诚实说明**：MSDK v5 是 *Android* SDK（`dji.v5.manager.*`），无法直接在
> Linux/Jetson 侧运行。因此「封装 MSDK」在 Linux 侧实际是「封装 APK 的 HTTP
> API（APK 内部才真正调 MSDK）」。PSDK / OSDK / PX4 是 Linux 原生 SDK，后端可直接调。
> 这个不对称不影响契约设计（契约都一样），只影响各驱动的后端实现方式。

---

## 二、契约层（Schema A）

每个 `.toml` 只声明语义，**不含任何 SDK 字段**。以 `takeoff` 为例：

```toml
[contract]
id      = "robonix/primitive/drone/takeoff"
version = "1"
kind    = "primitive"
idl     = "drone/srv/Takeoff.srv"
description = "控制无人机起飞并悬停至指定高度。"

[mode]
type = "rpc"
```

对应 `.srv`（响应统一 `std_msgs/String status`）：

```
float32 altitude  # 起飞高度（米），默认飞控内置高度
---
std_msgs/String status
```

生命周期契约 `driver.v1.toml` 引用内置的 `lifecycle/srv/Driver.srv`
（`rbnx boot` 通过 `Driver(CMD_INIT)` 拉起驱动），无需本地 `Driver.srv`。

---

## 三、驱动层（driver.py）

`@drone.mcp(...)` 装饰器是**框架真实的分发机制**（`rbnx call` 只认 MCP 声明的能力）。
每个 handler 只做「语义请求 → 后端语义方法 → 语义响应」三步，handler 里**不出现
任何 MSDK key 字符串**：

```python
@drone.mcp("robonix/primitive/drone/takeoff")
def takeoff(req: drone_mcp.Takeoff_Request) -> drone_mcp.Takeoff_Response:
    result = _get_backend().takeoff(float(req.altitude))
    return drone_mcp.Takeoff_Response(status=_status(result))
```

`@drone.on_init` 建后端并 ping；`@drone.on_shutdown` 清理。

---

## 四、后端层（backend.py —— 封装 MSDK）

`MsdkBackend` 公开**一个原语一个语义方法**（返回 JSON 可序列化 dict，顶层 `ok`），
MSDK key/manager 映射降为私有 helper：

| 公开语义方法 | 私有 helper（MSDK 术语 → HTTP） |
|---|---|
| `takeoff()` | `_perform_action("FlightControllerKey.KeyStartTakeoff")` → `/api/takeoff_hover` |
| `rth()` | `_perform_action("FlightControllerKey.KeyStartGoHome")` → `/api/gohome` |
| `hover()` | `_perform_action("FlightControllerKey.KeyStopTakeoff")` → `/api/stop` |
| `move_velocity(...)` | 速度→位移近似 → `/api/manual`（climb/rotate/move_left/right） |
| `gimbal_velocity(...)` | 角速度→角度近似 → `/api/gimbal`（pitch/yaw step） |
| `state_position()` | `_get_value("FlightControllerKey.KeyAircraftLocation3D")` |
| `waypoint_start()` | `_manager_call("IWaypointMissionManager", "startMission")` → `/api/start_cruise` |

APK 尚未暴露的 MSDK 能力返回 `{"ok": false, "error": "app 端未实现该 MSDK 能力: ..."}`，
调用方可探测可用性而无需硬失败；APK 补上端点后，**只改 backend.py**，驱动层与契约层不动。

---

## 五、跨 SDK（PSDK / OSDK / PX4）

| SDK | 性质 | 后端实现方式 |
|---|---|---|
| DJI MSDK v5 | Android-only | 本包：`backend.py` 封装 APK HTTP（APK 内部调 MSDK） |
| DJI PSDK | Linux 原生（负载 SDK） | `psdk_backend.py` 直接调 PSDK C API |
| DJI OSDK | Linux 原生（飞控 SDK） | `osdk_backend.py` 直接调 OSDK |
| PX4（MAVLink） | Linux 原生（开源飞控） | `mavlink_backend.py` 通过 MAVLink/UART |

**每个后端实现同一组语义方法**（核心 11 必实现；扩展 35 按能力实现，未实现返回
`{"ok": false, "error": "未实现"}`），复用同一套 `driver.py` 与契约。换 SDK =
换后端 + 换 `package_manifest.yaml` 的 `capability_id`，契约不变。

---

## 六、原语清单（46 primitives + driver）

### 核心（11，所有 SDK 必实现）

`takeoff` · `land` · `move_velocity` · `rotate_velocity` · `hover` · `rth` ·
`gimbal_velocity` · `gimbal_reset` · `camera_capture` · `camera_video` · `state`

| 原语 | 请求字段 | 说明 |
|------|----------|------|
| `takeoff` | `float32 altitude` | 起飞悬停 |
| `land` | — | 原地降落 |
| `move_velocity` | `vx,vy,vz,wx,wy,wz,duration`（float64） | 机体系 6DOF 速度向量 |
| `rotate_velocity` | `direction,angular_velocity,duration` | 定轴旋转 |
| `hover` | — | 紧急悬停 |
| `rth` | — | 智能返航 |
| `gimbal_velocity` | `vpitch,vroll,vyaw,duration` | 云台 3DOF 角速度 |
| `gimbal_reset` | — | 云台回中 |
| `camera_capture` | — | 单张拍照 |
| `camera_video` | — | 视频流 URL |
| `state` | — | 完整状态（status + GPS） |

### 扩展（35，各 SDK 按能力实现）

- **飞控状态（9）**：`state_attitude` · `state_velocity` · `state_home` ·
  `state_flight_mode` · `state_gps` · `state_compass` · `state_is_flying` ·
  `state_low_battery` · `state_position`
- **飞控动作（4）**：`move_ee`(`latitude,longitude,altitude`) · `set_home` ·
  `set_go_home_height`(`height`) · `stop_rth`
- **云台（4）**：`gimbal_rotate`(`pitch,roll,yaw,duration`) · `gimbal_mode`(`mode`) ·
  `gimbal_attitude` · `gimbal_calibrate`
- **相机（6）**：`camera_zoom`(`zoom`) · `camera_exposure`(`mode,iso,shutter`) ·
  `camera_white_balance`(`mode`) · `camera_state` · `camera_record_start` · `camera_record_stop`
- **其他（7）**：`battery_state` · `airlink_signal` · `rtk_state` · `rtk_start` ·
  `rtk_stop` · `perception_state` · `radar_state`
- **航点任务（5）**：`waypoint_push`(`kmz_path`) · `waypoint_start` · `waypoint_stop` ·
  `waypoint_pause` · `waypoint_resume`

---

## 七、语义 → MSDK → HTTP 映射总表

> 状态：✅ 语义对等可用 · 🟡 近似/降级 · ❌ APK 未暴露（返回明确错误）。
> 「MSDK 调用」列是 backend.py 私有 helper 中记录的官方 MSDK key/manager。

### 核心（11）

| 语义原语 | MSDK 调用 | APK 端点 | 状态 |
|----------|-----------|----------|------|
| `takeoff` | `FlightControllerKey.KeyStartTakeoff` | `/api/takeoff_hover` | ✅ |
| `land` | `KeyStartAutoLanding` | 无 | ❌ |
| `move_velocity` | `IVirtualStickManager`（语义→位移） | `/api/manual` | ✅ |
| `rotate_velocity` | 语义→旋转位移 | `/api/manual` rotate | ✅ |
| `hover` | `KeyStopTakeoff` | `/api/stop` | ✅ |
| `rth` | `KeyStartGoHome` | `/api/gohome` | ✅ |
| `gimbal_velocity` | `GimbalKey.KeyRotateBySpeed` | `/api/gimbal` step | ✅ |
| `gimbal_reset` | `GimbalKey.KeyGimbalReset` | `/api/gimbal` level | ✅ |
| `camera_capture` | `CameraKey.KeyStartShootPhoto` | `/api/camera` photo | ✅ |
| `camera_video` | 视频流 URL | `/api/video`（需 APK） | 🟡 |
| `state` | 状态合并 | `/api/status` + `/api/capture_gps` | ✅ |

### 扩展（35）

| 语义原语 | MSDK 调用 | APK 端点 | 状态 |
|----------|-----------|----------|------|
| `state_position` | `KeyAircraftLocation3D` | status + capture_gps | 🟡 |
| `state_attitude` | `KeyAircraftAttitude` | 无 | ❌ |
| `state_velocity` | `KeyAircraftVelocity` | 无 | ❌ |
| `state_home` | `KeyHomeLocation` | 无 | ❌ |
| `state_flight_mode` | `KeyFlightMode` | status `operationMode` | 🟡 |
| `state_gps` | `KeyGPSSatelliteCount` | 无 | ❌ |
| `state_compass` | `KeyCompassHeading` | 无 | ❌ |
| `state_is_flying` | `KeyIsFlying` | status `missionState` | 🟡 |
| `state_low_battery` | `KeyIsLowBatteryWarning` | 无 | ❌ |
| `move_ee` | `IWaypointMissionManager.flyTo` | clear+add_waypoint+start_cruise | 🟡 |
| `set_home` | `KeyHomeLocationUsingCurrentAircraftLocation` | 无 | ❌ |
| `set_go_home_height` | `KeyGoHomeHeight` | 无 | ❌ |
| `stop_rth` | `KeyStopGoHome` | 无 | ❌ |
| `gimbal_rotate` | `GimbalKey.KeyRotateByAngle` | `/api/gimbal` step | 🟡 |
| `gimbal_mode` | `KeyGimbalMode` | 无 | ❌ |
| `gimbal_attitude` | `KeyGimbalAttitude` | 无 | ❌ |
| `gimbal_calibrate` | `KeyGimbalCalibrate` | 无 | ❌ |
| `camera_zoom` | `KeyCameraZoomRatios` | 无 | ❌ |
| `camera_exposure` | `KeyExposureMode/ISO/ShutterSpeed` | 无 | ❌ |
| `camera_white_balance` | `KeyWhiteBalance` | 无 | ❌ |
| `camera_state` | `KeyIsRecording` | 无 | ❌ |
| `camera_record_start` | `KeyStartRecord` | `/api/camera` start_record | ✅ |
| `camera_record_stop` | `KeyStopRecord` | `/api/camera` stop_record | ✅ |
| `battery_state` | `BatteryKey.KeyChargeRemainingInPercent` | 无 | ❌ |
| `airlink_signal` | `AirLinkKey.KeySignalQuality` | 无 | ❌ |
| `rtk_state` | `IRTKCenter` | 无 | ❌ |
| `rtk_start` | `INetworkRTKManager.startNetworkRTKService` | 无 | ❌ |
| `rtk_stop` | `INetworkRTKManager.stopNetworkRTKService` | 无 | ❌ |
| `perception_state` | `IPerceptionManager` | 无 | ❌ |
| `radar_state` | `IRadarManager.getObstacleAvoidanceEnabled` | 无 | ❌ |
| `waypoint_push` | `IWaypointMissionManager.pushKMZFileToAircraft` | 无 | ❌ |
| `waypoint_start` | `startMission` | `/api/start_cruise` | ✅ |
| `waypoint_stop` | `stopMission` | 无 | ❌ |
| `waypoint_pause` | `pauseMission` | `/api/pause_cruise` | ✅ |
| `waypoint_resume` | `resumeMission` | `/api/resume_cruise` | ✅ |

**统计**：✅ 14 · 🟡 6 · ❌ 26（共 46）。APK 端补充端点后，只需在 `backend.py`
补映射即可启用对应原语，`driver.py` 与 `.srv`/`.toml` 无需改动。

---

## 八、待补充（跨 SDK 预留）

| 项 | 归属 | 说明 |
|----|------|------|
| `camera_video` 推流方案 B | 相机 | gRPC server-streaming（当前方案 A 返回 URL） |
| `media_pull` | 相机 | 拉取媒体文件列表 / 原图 |
| `rc_sticks` / `rc_buttons` | 遥控器 | 摇杆 / 按键状态 |
| `fly_zone` | 飞控 | 限飞区查询 |
| `payload` | 载荷 | 第三方载荷（PSDK） |
| `simulator` | 飞控 | 模拟器（测试用） |

---

## 九、变更记录

| 版本 | 变更 |
|------|------|
| v1 | 首版：按 DJI MSDK v5 定义原语，命名空间 `robonix/primitive/dji/*`，映射到 KeyManager/Manager 接口 |
| v1.1 | `MsdkBridge` 适配 APK 语义端点；补全 RTK/感知/任务等原语实现；标注三档映射状态 |
| v2 | **架构重构**：命名空间收敛为 `robonix/primitive/drone/*`（SDK 无关）；三层拆分（契约/驱动/后端）；契约改 Schema A（去 msdk 字段）；分发改 `@drone.mcp`；`bridge.py → backend.py`（语义后端，MSDK 术语下沉为私有 helper）；`main.py → driver.py`；build 加 `--mcp`；`go_home→rth`、`stop_go_home→stop_rth`；新增 `rotate_velocity`/`camera_video`/`state`/`driver` 契约；补全 `move_velocity`/`rotate_velocity`/`gimbal_velocity` 速度→位移映射 |
