# DJI M3E 无人机 RoboNIX 原语规格说明（v8）

> 命名空间 `robonix/primitive/drone/*`，通过 `rbnx codegen -p . --out-dir rbnx-build/codegen --mcp` 从
> `capabilities/lib/drone/srv/*.srv` + `capabilities/primitive/drone/*.toml` 生成 **MCP** dataclass
> （`drone_mcp` / `std_msgs_mcp`）。响应统一携带 `std_msgs/String status`，内容为 JSON 字符串。
>
> ⚠️ 传输类型是 **MCP**（`@drone.mcp(...)`），不是 gRPC：executor 的外部能力分发硬编码走 `Transport::Mcp`，
> `rbnx call` 只认 MCP 声明的能力。
>
> 底层 HTTP 以 `C:\Users\nice\Desktop\WEB_API.md`（无人机控制台 Web HTTP API **v4.0**，`WebServer.kt`）为准。
>
> v8 变更：删除 16 个旧原语中的 5 个（`move_ee` / `move_relative` / `gimbal_rotate` / `camera_zoom` / `state_position` / `state_battery`），
> 并对齐 v4.0 真实端点（`/api/camera`、`/api/gimbal {action,step}`、无 `/api/land`、无 `move_forward/backward`），
> 最终确定 **11 个原语**。标记说明：✅ = 已注册原语。

---

## 一、无人机（Drone）

### 1.1 起飞 ✅
- **原语**：`robonix/primitive/drone/takeoff`
- **输入**：`float32 altitude`（默认 3.0 m，≤0 取 3.0）
- **HTTP**：`POST /api/start` `{climbHeight: altitude, moveDistance: 0, yawAngle: 0}`
- **前置**：`missionState == IDLE` 且 SDK 已激活
- **GPS**：❌ 不需要（IMU / 视觉定位）

### 1.2 降落 ✅（⚠️ 无端点，固定返回失败）
- **原语**：`robonix/primitive/drone/land`
- **输入**：无
- **HTTP**：**API v4.0 无 `/api/land` 原地降落端点**，调用固定返回
  `{"success":false,"message":"API v4.0 无 /api/land 原地降落端点；请改用 rth 返航降落，或 stop 紧急悬停。"}`
- **GPS**：❌ 不需要
- **备注**：需降落用 `rth`（返航降落）或 `hover`（紧急悬停）

### 1.3 移动（上下左右 · 速度 · 持续时间）✅
- **原语**：`robonix/primitive/drone/move_velocity`（机体系 6DOF 速度向量 / twist）
- **输入**：
  - `float64 vy`：左右线速度（m/s，**右为正**）
  - `float64 vz`：上下线速度（m/s，**上为正**）
  - `float64 wz`：偏航角速度（rad/s，**右转为正**）
  - `float64 vx`：前后线速度（m/s）—— **API v4.0 无前后移动端点，忽略**
  - `float64 wx / wy`：滚转/俯仰角速度（rad/s）—— 四旋翼位姿模式不可独立控制，**忽略**
  - `float64 duration`：持续时间（秒）
- **HTTP**：`POST /api/manual`（`vy→move_left/right`、`vz→climb`、`wz→rotate`）
- **前置**：`missionState == HOVERING`（仅悬停态可操控）
- **GPS**：❌ 不需要（虚拟摇杆）
- **实现**：`位移 = 速度 × duration` 后离散下发
- **⚠️ 说明**：当前是「速度→位移」**离散近似**，非真正连续速度；真连续需 APK 新增 `/api/velocity`（VirtualStick）

### 1.4 旋转（方向 · 角速度 · 持续时间）✅
- **原语**：`robonix/primitive/drone/rotate_velocity`
- **输入**：`float64 direction / angular_velocity / duration`
  - `direction`：**方向**（`1` = 右转顺时针，`-1` = 左转，对齐 `/api/manual rotate` 正右负左）
  - `angular_velocity`：**角速度**（rad/s，取绝对值）
  - `duration`：**持续时间**（秒）
- **HTTP**：`POST /api/manual`（`rotate`，`dyaw = direction × degrees(|angular_velocity| × duration)`）
- **前置**：`missionState == HOVERING`
- **GPS**：❌ 不需要

### 1.5 紧急悬停 ✅
- **原语**：`robonix/primitive/drone/hover`
- **输入**：无
- **HTTP**：`POST /api/stop`（立即停止当前运动）

### 1.6 智能返航 ✅
- **原语**：`robonix/primitive/drone/rth`
- **输入**：无
- **HTTP**：`POST /api/gohome`（自动返航并降落）
- **GPS**：✅ **需要**（依赖 GPS 返航点）

### 1.7 完整状态 ✅
- **原语**：`robonix/primitive/drone/state`
- **输入**：无
- **HTTP**：`GET /api/status` + `POST /api/capture_gps`（合并 GPS 坐标）
- **返回字段**：`missionState / altitude / sdkRegistered / productConnected / statusMessage /
  vsEnabled / operationMode / waypointCount / waypoints / cruiseWaypointIndex / cruiseActive /
  cruisePaused / cruiseFeedback / cameraFeedback / climbHeight / moveDistance / yawAngle /
  sdkStatusText / sdkInitProcess / sdkInitComplete / sdkInitStarted`，外加 `latitude` / `longitude`
- **⚠️ 说明**：API v4.0 的 `/api/status` **不返回** 电量/电压/经纬度/航向；`latitude`/`longitude`
  由 `state` 额外调 `/api/capture_gps` 合并（GPS 未定位时缺失并附 `gpsError`）

---

## 二、云台（Gimbal）

> ⚠️ 云台/相机接口**仅巡航进行中可用**（`cruiseActive == true`）。

### 2.1 旋转（上下左右 · 角速度 · 持续时间）✅
- **原语**：`robonix/primitive/drone/gimbal_velocity`（云台 3DOF 角速度向量）
- **输入**：
  - `float64 vpitch`：俯仰角速度（°/s），正 = 抬头向上 → `pitch_up`
  - `float64 vyaw`：偏航角速度（°/s），正 = 向右 → `yaw_right`
  - `float64 vroll`：横滚角速度（°/s）—— API 不支持，**忽略**
  - `float64 duration`：持续时间（秒）
- **HTTP**：`POST /api/gimbal` `{action, step}`（step 0.5~180 自动钳位）
- **GPS**：❌ 不需要
- **实现**：`角度 = 角速度 × duration` → 映射到 `pitch_up/pitch_down`、`yaw_left/yaw_right` 步进下发
- **⚠️ 说明**：当前是「角速度→步进角度」**离散近似**；真连续需 APK 新增 `/api/gimbal/velocity`（`KeyGimbalSpeed`）

### 2.2 云台回中 ✅
- **原语**：`robonix/primitive/drone/gimbal_reset`
- **功能**：云台回中到平视
- **输入**：无
- **HTTP**：`POST /api/gimbal` `{action: "level"}`
- **前置**：`cruiseActive == true`

---

## 三、相机（Camera）

### 3.1 拍照 ✅
- **原语**：`robonix/primitive/drone/camera_capture`
- **输入**：无
- **HTTP**：`POST /api/camera` `{action: "photo"}`
- **前置**：`cruiseActive == true`

### 3.2 传视频流 ✅
- **原语**：`robonix/primitive/drone/camera_video`
- **输入**：无
- **输出**：`status`（JSON：`{video_url, format: "mjpeg", resolution: "640px", fps: 12}`）
- **HTTP**：`GET /api/video`（MJPEG，~12fps，640px）—— 返回 URL，调用方自行拉流（方案 A）
- **前置**：无（可随时调用）

---

## 四、HTTP API 映射

| 原语 | Drone_test HTTP API（v4.0） |
|------|---------------------|
| `takeoff` | `POST /api/start` `{climbHeight, moveDistance:0, yawAngle:0}` |
| `land` | ⚠️ 无端点（固定返回失败） |
| `move_velocity` | `POST /api/manual`（vy→左右 / vz→上下 / wz→rotate；vx/wx/wy 忽略） |
| `rotate_velocity` | `POST /api/manual`（`rotate`，正右负左） |
| `hover` | `POST /api/stop` |
| `rth` | `POST /api/gohome` |
| `gimbal_velocity` | `POST /api/gimbal` `{action, step}`（pitch_up/down、yaw_left/right） |
| `gimbal_reset` | `POST /api/gimbal` `{action:"level"}` |
| `camera_capture` | `POST /api/camera` `{action:"photo"}` |
| `camera_video` | `GET /api/video`（返回 URL，调用方拉流） |
| `state` | `GET /api/status` + `POST /api/capture_gps` |

---

## 五、已注册原语清单（11 个）

| 组 | 原语 ID | 说明 |
|----|---------|------|
| 无人机 | `takeoff` | 起飞爬升至指定高度 |
| 无人机 | `land` | 原地降落（⚠️ 无端点，返回失败） |
| 无人机 | `move_velocity` | 移动（上下左右 · 速度 · 持续时间） |
| 无人机 | `rotate_velocity` | 旋转（方向 · 角速度 · 持续时间） |
| 无人机 | `hover` | 紧急悬停 |
| 无人机 | `rth` | 智能返航 |
| 无人机 | `state` | 完整状态查询（status + GPS） |
| 云台 | `gimbal_velocity` | 云台旋转（上下左右 · 角速度 · 持续时间） |
| 云台 | `gimbal_reset` | 云台回中（平视） |
| 相机 | `camera_capture` | 拍照 |
| 相机 | `camera_video` | 获取视频流 URL（MJPEG） |

---

## 六、待补充清单

| 项 | 归属 | 说明 |
|----|------|------|
| `/api/land` 端点 | 无人机 | APK 侧未实现（当前 `land` 固定返回失败） |
| `/api/velocity`（真连续速度） | 无人机 | VirtualStick，升级 `move_velocity` |
| `/api/gimbal/velocity`（真连续云台） | 云台 | `KeyGimbalSpeed`，升级 `gimbal_velocity` |
| 电池/电压/航向遥测 | 无人机·状态 | 当前 v4.0 `/api/status` 不返回 |
| 机翼/旋翼状态 | 无人机·状态 | 需 APK 侧补充遥测字段 |
| 视频流参数可配置（resolution / fps） | 相机 | 当前固定 640px ~12fps |

---

## 七、变更记录

| 版本 | 变更 |
|------|------|
| v2 | 定义 18 个原语（含 6 个方向移动 + rotate） |
| v3 | 6 个方向原语合并为 `move_velocity`（6DOF 速度向量）；`driver.py` 注册 12 个原语 |
| v4 | 新增 `gimbal_velocity`（云台 3DOF 角速度向量）；`driver.py` 注册 13 个原语 |
| v5 | 按「无人机 / 云台 / 相机」三组重新组织；对齐用户设计思路 |
| v6 | 章节顺序调整；新增「云台回中」；完善「传视频流」描述 |
| v7 | 新增 `rotate_velocity`、`gimbal_reset`、`camera_video`，注册 16 个原语 |
| v8 | **对齐真实 API v4.0**：删除 `move_ee`/`move_relative`/`gimbal_rotate`/`camera_zoom`/`state_position`/`state_battery`；修正端点（`/api/camera` 统一、`/api/gimbal {action,step}`、无 `/api/land`、无 `move_forward/backward`）；传输类型 gRPC → **MCP**；确定 **11 个原语** |
