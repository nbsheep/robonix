# DJI M3E 无人机 RoboNIX 原语规格说明（v7）

> 命名空间 `robonix/primitive/drone/*`，通过 `rbnx codegen` 从
> `capabilities/lib/drone/srv/*.srv` + `capabilities/primitive/drone/*.toml` 生成 gRPC 桩。
> 响应统一携带 `std_msgs/String`（普通原语为 `status`，状态类为 `position` / `battery`），内容为 JSON 字符串。
>
> 本 v7 版按「**无人机 / 云台 / 相机**」三组组织原语，对齐你的设计思路。
> v7 变更：`rotate_velocity`（旋转独立）、`gimbal_reset`（云台回中）、`camera_video`（视频流）三个原语落地，已注册原语 13 → **16** 个。
> 标记说明：✅ = 已注册原语；🔲 = 待补充（未实现，预留接口）。

---

## 一、无人机（Drone）

### 1.1 起飞 ✅
- **原语**：`robonix/primitive/drone/takeoff`
- **输入**：`float32 altitude`（默认 3.0 m）
- **HTTP**：`POST /api/start` `{climbHeight, moveDistance=0, yawAngle}`
- **GPS**：❌ 不需要（IMU / 视觉定位）

### 1.2 降落 ✅
- **原语**：`robonix/primitive/drone/land`
- **输入**：无
- **HTTP**：`POST /api/land` ⚠️ **APK 端尚未实现该端点（TODO）**
- **GPS**：❌ 不需要
- **备注**：落地前建议改用 `rth`，或先确认 APK 已实现 `/api/land`

### 1.3 移动（上下左右 · 速度 · 持续时间）✅
- **原语**：`robonix/primitive/drone/move_velocity`（机体系 6DOF 速度向量 / twist）
- **输入**：
  - `float64 vx / vy / vz`：**速度**（m/s）—— `vz` 上下（上+）、`vy` 左右（右+）、`vx` 前后（前+）
  - `float64 duration`：**持续时间**（秒）
  - `float64 wx / wy`：滚转/俯仰角速度（rad/s）—— 四旋翼位姿模式下不可独立控制，**忽略**
- **HTTP**：`POST /api/manual`（经 `move_relative` 拆解）
- **GPS**：❌ 不需要（虚拟摇杆）
- **实现**：`位移 = 速度 × duration` → `move_relative` 离散下发
- **⚠️ 说明**：当前是「速度→位移」**离散近似**，非真正连续速度；真连续需 APK 新增 `/api/velocity`（VirtualStick）

### 1.4 旋转（方向 · 角速度 · 持续时间）✅
- **原语**：`robonix/primitive/drone/rotate_velocity` ★ v7 新增
- **输入**：`float64 direction / angular_velocity / duration`
  - `direction`：**方向**（1 = 左转，-1 = 右转）
  - `angular_velocity`：**角速度**（rad/s，取绝对值）
  - `duration`：**持续时间**（秒）
- **输出**：`status`
- **HTTP**：`POST /api/manual`（`rotate`，`dyaw = direction × degrees(|angular_velocity| × duration)`）
- **GPS**：❌ 不需要
- **备注**：旧 `rotate`（绝对角度版，从未注册）已删除；`move_velocity` 的 `wz` 仍可旋转，两者并存

### 1.5 返回状态
- **电量** ✅ `robonix/primitive/drone/state_battery` → JSON `{percent, voltage}`，`GET /api/status`
- **当前位置** ✅ `robonix/primitive/drone/state_position` → JSON `{latitude, longitude, altitude, heading}`，`GET /api/status`（经纬度需 GPS，室内为 null）
- **机翼状态？** 🔲 **待补充** —— 当前 `/api/status` 未返回机臂/旋翼状态，需 APK 侧补充遥测字段
- **… …** 🔲 **待补充** —— 可扩展：飞行模式、GPS 星数、IMU 姿态、信号强度等（当前 `/api/status` 已含 `operationMode / missionState / vsEnabled` 等，尚未封装为独立原语）

### 1.6 紧急悬停 ✅
- **原语**：`robonix/primitive/drone/hover`
- **输入**：无
- **HTTP**：`POST /api/stop`（立即停止当前运动）

### 1.7 GPS ✅
- **原语**：`robonix/primitive/drone/move_ee`（飞至目标 GPS 位姿）+ `robonix/primitive/drone/rth`（智能返航，依赖 GPS 返航点）
- **move_ee 输入**：`float64 latitude / longitude / altitude`
- **HTTP**：`POST /api/clear_waypoints` → `/api/add_waypoint` → `/api/start_cruise`；`rth` → `POST /api/gohome`
- **GPS**：✅ **需要**（航点巡航 / 返航）

---

## 二、云台（Gimbal，多维向量）

### 2.1 旋转（上下左右）✅
- **原语**：`robonix/primitive/drone/gimbal_velocity`（云台 3DOF 角速度向量）
- **输入**：
  - `float64 vpitch`：俯仰角速度（°/s）—— **上下**（正=抬头向上）
  - `float64 vyaw`：偏航角速度（°/s）—— **左右**（正=向右）
  - `float64 vroll`：横滚角速度（°/s）—— M3E 云台一般不支持，**忽略**
  - `float64 duration`：**持续时间**（秒）
- **HTTP**：`POST /api/gimbal`（经 `gimbal_rotate` 绝对角度下发）
- **GPS**：❌ 不需要
- **实现**：`角度增量 = 角速度 × duration`，叠加客户端跟踪的当前姿态（初始 0°）→ 绝对角度
- **⚠️ 说明**：当前是「角速度→角度」**离散近似**（`/api/status` 不返回云台姿态）；真连续需 APK 新增 `/api/gimbal/velocity`（`KeyGimbalSpeed`）

> **`gimbal_rotate`（绝对姿态）** ✅ 已注册：`pitch / roll / yaw`（度），直接设云台绝对角；是 `gimbal_velocity` 的底层实现，也单独可用。

### 2.2 云台回中 ✅
- **原语**：`robonix/primitive/drone/gimbal_reset` ★ v7 新增
- **功能**：把云台恢复到初始中心姿态（机头正前方，`pitch=0° / roll=0° / yaw=0°`）
- **输入**：无（可选 `float64 pitch / roll / yaw` 自定义回中目标角，默认全 0）
- **输出**：`status`
- **HTTP**：`POST /api/gimbal` `{pitch, roll, yaw}`（复用绝对姿态端点）；APK 侧亦可用 MSDK `GimbalKey.KeyGimbalReset` 直接复位
- **实现**：调 `gimbal_rotate` 下发目标角，其内部会**同步回写**客户端 `self._gimbal` 跟踪状态（后续 `gimbal_velocity` 增量旋转基准不漂移）
- **适用场景**：起飞前云台校准、任务结束云台复位、目标丢失后重新对正

---

## 三、相机（Camera）

### 3.1 拍照 ✅
- **原语**：`robonix/primitive/drone/camera_capture`
- **输入**：无
- **HTTP**：`POST /api/camera/capture`

### 3.2 传视频流 ✅
- **原语**：`robonix/primitive/drone/camera_video` ★ v7 新增
- **功能**：获取相机实时视频流，供网页仪表盘 / 地面站 / 监控使用
- **输入**：无
- **输出**：`status`（JSON：`{video_url, format: "mjpeg", resolution: "640px", fps: 12}`）
- **方案**：采用**方案 A** —— RPC 返回视频流 URL，调用方用浏览器 / curl / ffmpeg 自行拉流（方案 B 的 gRPC 逐帧推流留作后续）
- **现状**：底层是 APK 已有的 `GET /api/video`（MJPEG 流，640px，~12fps）+ 网页仪表盘 `web/dashboard.html`
- **需求要点**：
  - **分辨率 / 帧率**：当前固定 640px ~12fps，后续可做成可配置（`resolution / fps` 参数）
  - **时延**：MJPEG 约百毫秒级，够监控用；若需低时延可后续改 RTSP / H.264
  - **用途**：实时图传监控、目标观察、多机地面站接入

---

## 四、HTTP API 映射

| 原语 | Drone_test HTTP API |
|------|---------------------|
| `takeoff` | `POST /api/start` `{climbHeight, moveDistance, yawAngle}` |
| `land` | `POST /api/land`（⚠️ 未实现） |
| `move_ee` | `POST /api/clear_waypoints` + `POST /api/add_waypoint` + `POST /api/start_cruise` |
| `move_velocity` | `POST /api/manual`（速度×时长→位移） |
| `rotate_velocity` | `POST /api/manual`（方向×角速度×时长→`rotate`） |
| `rth` | `POST /api/gohome` |
| `hover` | `POST /api/stop` |
| `gimbal_rotate` | `POST /api/gimbal` |
| `gimbal_velocity` | `POST /api/gimbal`（角速度×时长→角度增量 → `gimbal_rotate`） |
| `gimbal_reset` | `POST /api/gimbal` `{pitch, roll, yaw}`（默认全 0） |
| `camera_capture` | `POST /api/camera/capture` |
| `camera_video` | `GET /api/video`（返回 URL，调用方拉流） |
| `camera_zoom` | `POST /api/camera/zoom` |
| `state_position` | `GET /api/status` |
| `state_battery` | `GET /api/status` |

---

## 五、已注册原语清单（16 个）

| 组 | 原语 ID | 说明 |
|----|---------|------|
| 无人机 | `takeoff` | 起飞并悬停至指定高度 |
| 无人机 | `land` | 原地降落（APK 端点 TODO） |
| 无人机 | `move_velocity` | 移动（上下左右前后 · 速度 · 持续时间） |
| 无人机 | `rotate_velocity` | 旋转（方向 · 角速度 · 持续时间） |
| 无人机 | `move_relative` | 机体系相对位移（底层） |
| 无人机 | `move_ee` | GPS 航点巡航 |
| 无人机 | `rth` | 智能返航 |
| 无人机 | `hover` | 紧急悬停 |
| 无人机 | `state_position` | 当前位置 |
| 无人机 | `state_battery` | 电量 |
| 云台 | `gimbal_velocity` | 旋转（上下左右 · 角速度 · 持续时间） |
| 云台 | `gimbal_rotate` | 云台绝对姿态（底层） |
| 云台 | `gimbal_reset` | 云台回中（默认机头正前方） |
| 相机 | `camera_capture` | 拍照 |
| 相机 | `camera_video` | 获取视频流 URL（MJPEG） |
| 相机 | `camera_zoom` | 变焦（代码已注册，正文未单列，是否移除待定） |

---

## 六、待补充清单

| 项 | 归属 | 说明 |
|----|------|------|
| 机翼/旋翼状态 | 无人机·返回状态 | APK `/api/status` 需新增遥测字段 |
| 更多状态（飞行模式/GPS 星数/姿态…） | 无人机·返回状态 | 已部分存在于 `/api/status`，未封装 |
| 云台状态查询 / 跟随模式 | 云台 | 待补充 |
| 视频流参数可配置（resolution / fps） | 相机 | 当前固定 640px ~12fps |
| `/api/land` 端点 | 无人机 | APK 侧未实现 |
| `/api/velocity`（真连续速度） | 无人机 | VirtualStick |
| `/api/gimbal/velocity`（真连续云台） | 云台 | `KeyGimbalSpeed` |

---

## 七、变更记录

| 版本 | 变更 |
|------|------|
| v2 | 定义 18 个原语（含 6 个方向移动 + rotate） |
| v3 | 6 个方向原语合并为 `move_velocity`（6DOF 速度向量）；`driver.py` 注册 12 个原语 |
| v4 | 新增 `gimbal_velocity`（云台 3DOF 角速度向量）；`driver.py` 注册 13 个原语 |
| v5 | 按「无人机 / 云台 / 相机」三组重新组织；对齐用户设计思路；标注待补充项 |
| v6 | 章节顺序调整（紧急悬停 ↔ GPS 互换）；`2.2` 由「待补充」改为「云台回中」并补全描述与需求；删除相机「变焦」正文节；完善「传视频流」描述与需求 |
| v7 | 三个待补充原语落地：新增 `rotate_velocity`（旋转独立，删旧 `rotate`）、`gimbal_reset`（云台回中，同步清零跟踪状态）、`camera_video`（视频流方案 A，返回 URL）；`driver.py` 现注册 16 个原语 |
