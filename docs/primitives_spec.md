# DJI M3E 无人机 RoboNIX 原语规格说明（v1 精简版）

> 命名空间 `robonix/primitive/drone/*`，通过 `rbnx codegen` 从
> `capabilities/lib/drone/srv/*.srv` + `capabilities/primitive/drone/*.toml` 生成 gRPC 桩。
> 所有响应统一携带 `std_msgs/String status`（JSON 字符串）。

---

## 一、运动控制

### 🔹 robonix/primitive/drone/takeoff
- **功能**：起飞并悬停至指定高度
- **输入**：`float64 altitude`（默认 3.0 m）
- **输出**：`status`
- **备注**：内部走「起飞→离地确认→虚拟摇杆→爬升」闭环

### 🔹 robonix/primitive/drone/land
- **功能**：原地降落并锁定电机
- **输入**：无
- **输出**：`status`

### 🔹 robonix/primitive/drone/move_ee
- **功能**：飞行至目标 GPS 位姿（经纬度 + 高度）
- **输入**：`float64 latitude / longitude / altitude`
- **输出**：`status`
- **备注**：内部「清空航点→添加目标点→启动巡航」

### 🔹 robonix/primitive/drone/hover
- **功能**：紧急悬停，立即停止当前运动
- **输入**：无
- **输出**：`status`

### 🔹 robonix/primitive/drone/rth
- **功能**：智能返航
- **输入**：无
- **输出**：`status`

### 🔹 robonix/primitive/drone/move_relative
- **功能**：机体系相对移动（**场景②核心**）
- **输入**：`float64 dx / dy / dz / dyaw`
  - `dx` 前后（+前进 / −后退）、`dy` 左右（+右 / −左）、`dz` 上下（+升 / −降）、`dyaw` 偏航
- **输出**：`status`（含逐轴移动结果 `moves`）
- **备注**：支持「往上一点/往下一点/往左挪一点/往右挪一点」等相对位移指令

---

## 二、云台 / 相机

### 🔹 robonix/primitive/drone/gimbal_rotate
- **功能**：设置云台绝对姿态（**场景①核心**）
- **输入**：`float64 pitch / roll / yaw`（度）
- **输出**：`status`
- **备注**：用于「检查窗户是否关闭」时调整观察角度

### 🔹 robonix/primitive/drone/camera_capture
- **功能**：触发单张拍照（**场景①核心**）
- **输入**：无
- **输出**：`status`
- **备注**：先切单拍模式再触发快门，供视觉模型判断目标状态

### 🔹 robonix/primitive/drone/camera_zoom
- **功能**：设置相机混合变焦倍率（**场景①核心**）
- **输入**：`float64 factor`（约 1.0~28.0）
- **输出**：`status`
- **备注**：拉近观察远处目标

---

## 三、状态查询

### 🔹 robonix/primitive/drone/state_position
- **功能**：获取当前位置
- **输入**：无
- **输出**：`status`（JSON：`latitude/longitude/altitude/heading`）

### 🔹 robonix/primitive/drone/state_battery
- **功能**：获取电池状态
- **输入**：无
- **输出**：`status`（JSON：`percent/voltage`）
- **备注**：电量低于 20% 应触发 RTH

---

## 四、汇总

| 类别 | 原语 ID | 场景 |
|------|---------|:--:|
| 运动 | `takeoff` / `land` / `move_ee` / `hover` / `rth` | 通用 |
| 运动 | `move_relative` | ② 相对位移 |
| 云台 | `gimbal_rotate` | ① 观察 |
| 相机 | `camera_capture` / `camera_zoom` | ① 观察 |
| 状态 | `state_position` / `state_battery` | 通用 |

**总计 11 个原语**，支撑两个目标场景：

- **场景①**「检查三楼东侧窗户是否关闭」：`takeoff` → `move_ee`（抵近）→ `gimbal_rotate`（对准）→ `camera_zoom`（拉近）→ `camera_capture`（拍照判读）→ `rth`。
- **场景②**「往上一点/往下一点/往左挪/往右挪」：`takeoff` → 连续 `move_relative`（机体系相对位移）→ `hover`。

---

## 五、与 Drone_test HTTP API 的映射

| 原语 | Drone_test HTTP API |
|------|---------------------|
| `takeoff` | `POST /api/start` |
| `land` / `rth` | `POST /api/gohome` |
| `move_ee` | `POST /api/add_waypoint` + `POST /api/start_cruise` |
| `hover` | `POST /api/stop` |
| `move_relative` | `POST /api/manual`（`move_forward/move_backward/move_left/move_right/climb/rotate`） |
| `gimbal_rotate` | `POST /api/gimbal` |
| `camera_capture` | `POST /api/camera/capture` |
| `camera_zoom` | `POST /api/camera/zoom` |
| `state_position` | `GET /api/status` |
| `state_battery` | `GET /api/status` |
