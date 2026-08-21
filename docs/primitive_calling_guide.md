# 原语调用与验证指南

> 本文档说明如何调用 11 个无人机原语，以及如何逐项验证它们的正确性。
> 原语 ID 统一为 `robonix/primitive/drone/<name>`，共三层调用方式，从底层到上层。
> 底层 HTTP 以 `C:\Users\nice\Desktop\WEB_API.md`（无人机控制台 Web HTTP API v4.0）为准。

---

## 0. 调用方式总览

| 层级 | 入口 | 适用场景 |
|------|------|---------|
| **① HTTP API 直连** | `POST http://<RC_PRO_IP>:8080/...` | 真机最底层验证、排除桥接/APK 问题 |
| **② drone_bridge REPL** | `python -m drone_bridge.main` | 不依赖 RoboNIX，快速调试原语映射 |
| **③ RoboNIX 原语** | MCP `robonix/primitive/drone/*` | 正式集成，供 executor / `rbnx call` 调度 |

调用链（正向）：

```
RoboNIX (③) → drone_bridge/driver.py (MCP handler)
            → main.py DroneClient (HTTP)
            → Drone_test APK WebServer (:8080) → MSDK v5 → M3E 无人机
```

> ⚠️ 关键：executor 的外部能力分发硬编码走 `Transport::Mcp`，所以 ③ 层是 **MCP**（`@drone.mcp(...)`），
> 不是 gRPC；`rbnx call` 只认 MCP 声明的能力。

验证时建议**从 ① 往 ③ 逐层确认**，哪一层断了一目了然。

---

## 1. 前置条件

无论用哪一层，都需要：

- [ ] 无人机 M3E 开机，与 RC Pro 已对频连接
- [ ] RC Pro 上已启动 **Drone_test APK**（且 SDK 注册成功）
- [ ] RC Pro 与 PC 在同一 WiFi 网络
- [ ] 飞行前 GPS 已锁定（≥ 6 星）、返航点已刷新
- [ ] 无人机组装完整、桨叶安装、电量充足（> 30%）

确认连通性（方式 ①）：

```bash
curl http://10.225.57.15:8080/api/status
```

返回 `{"sdkRegistered":true,"productConnected":true,...}` 即链路 OK。
若 `productConnected:false`，说明 APK 起来了但飞机没连上；若 HTTP 都连不上，说明 APK 没启动或 IP 不对。

---

## 2. 逐原语调用与验证

### 2.1 状态查询（先做，最安全）

#### state — 完整状态

| | |
|--|--|
| ① HTTP | `curl http://10.225.57.15:8080/api/status` |
| ② REPL | `state`（`status` 看原始 /api/status） |
| ③ RoboNIX | `robonix/primitive/drone/state`（无输入） |

**验证判据**：`missionState` 为合法枚举（IDLE/TAKEOFF/CLIMBING/HOVERING/…）、`altitude` 合理、
`sdkRegistered=true`、`productConnected=true`。`state` 原语还会合并 `/api/capture_gps` 的
`latitude`/`longitude`（GPS 未定位时缺失，并附 `gpsError`）。

> 注意：API v4.0 的 `/api/status` **不返回** 电量/电压/经纬度/航向，别再依赖 `batteryPercent` 等旧字段。

---

### 2.2 运动控制（需真机，先在空旷场地低空测试）

> ⚠️ 运动类原语会真的让飞机动。**先在室内/低空（1–2m）、有人看护**下逐项验证。

#### takeoff — 起飞爬升

| | |
|--|--|
| ② REPL | `takeoff 3` |
| ③ RoboNIX | 输入 `altitude=3.0` |

**验证判据**：飞机起飞、爬升至约 3m 后悬停；`status` 显示 `missionState` 依次 TAKEOFF→CLIMBING→HOVERING。
底层走 `/api/start {climbHeight, moveDistance:0, yawAngle:0}`。

#### move_velocity — 机体系 6DOF 速度向量

| | |
|--|--|
| ② REPL | `vel <vy> <vz> [wz] [dur]`，如 `vel 0 1 0 2`（上升 1 m/s × 2s） |
| ③ RoboNIX | 输入 `vx/vy/vz/wx/wy/wz/duration` |

参数方向约定（机体系）：

| 分量 | 含义 | 正 | 生效性 |
|------|------|----|--------|
| `vy` | 左右线速度 (m/s) | 右移 | ✅ 走 `/api/manual` move_left/right |
| `vz` | 上下线速度 (m/s) | 上升 | ✅ 走 `/api/manual` climb |
| `wz` | 偏航角速度 (rad/s) | 右转 | ✅ 走 `/api/manual` rotate |
| `vx` | 前后线速度 (m/s) | 前进 | ❌ API 无前后端点，忽略 |
| `wx/wy` | 滚转/俯仰角速度 | — | ❌ 四旋翼不可独立控制，忽略 |

**验证判据**：逐项下发 `vel 0 1 0 2`（上升）→ `vel 0.5 0 0 2`（右移）→ `vel 0 0 0.5 2`（右转），
观察飞机按对应方向移动。返回 JSON 里 `moves` 列出实际下发的轴。位移 = 速度 × 时长（离散近似）。
**前置：必须在 HOVERING 悬停态**（先 `takeoff` 再 `vel`）。

#### rotate_velocity — 旋转

| | |
|--|--|
| ② REPL | `rv [dir] [wz] [dur]`，如 `rv 1 0.5 1`（右转 0.5 rad/s × 1s） |
| ③ RoboNIX | 输入 `direction/angular_velocity/duration` |

`direction`：`1`=右转、`-1`=左转；`angular_velocity` 取绝对值 (rad/s)。实际转角 = `direction × degrees(|ω| × duration)`。

---

### 2.3 云台 / 相机（⚠️ 仅巡航进行中可用 `cruiseActive==true`）

> 云台/相机三个原语底层都要求巡航中。想不开飞机验证云台/相机，需先切到巡航模式并起飞巡航。

#### gimbal_velocity — 云台角速度

| | |
|--|--|
| ② REPL | `gv <vpitch> [vyaw] [dur]`，如 `gv 20 0 2`（抬头 20°/s × 2s） |
| ③ RoboNIX | 输入 `vpitch/vroll/vyaw/duration` |

- `vpitch` 正=抬头（pitch_up）、`vyaw` 正=右转（yaw_right）；`vroll` 忽略。
- 折算「角度 = 角速度 × 时长」后走 `/api/gimbal {action, step}`（step 0.5~180 自动钳位）。

#### gimbal_reset — 云台回中（平视）

| | |
|--|--|
| ② REPL | `greset` |
| ③ RoboNIX | `robonix/primitive/drone/gimbal_reset`（无输入） |

对应 `/api/gimbal {action:"level"}`。

#### camera_capture — 拍照

| | |
|--|--|
| ② REPL | `photo` |
| ③ RoboNIX | `robonix/primitive/drone/camera_capture`（无输入） |

对应 `/api/camera {action:"photo"}`。**验证判据**：相机快门触发；照片出现在相机 SD 卡。

#### camera_video — 视频流

| | |
|--|--|
| ② REPL | `video` |
| ③ RoboNIX | `robonix/primitive/drone/camera_video`（无输入） |

返回 MJPEG 流 URL（`http://<ip>:8080/api/video`），浏览器 / ffmpeg 拉流即可。

---

### 2.4 安全原语

| 原语 | REPL | 底层 | 判据 |
|------|------|------|------|
| `hover` | `hover` | `/api/stop` | 飞机立即停止、原地悬停 |
| `rth` | `rth` | `/api/gohome` | 自动返回起飞点并降落 |
| `land` | `land` | ⚠️ 无端点 | 固定返回失败（见下） |

**验证判据**：`hover` 能立刻停下、`rth` 能返航降落，**这是最关键的验证项**。

> `land` 原语：API v4.0 无 `/api/land` 端点，调用固定返回 `{"success":false,"message":"API v4.0 无 /api/land ..."}`。
> 需降落用 `rth`。

---

## 3. 三层调用示例（同一原语对比）

以 `state` 为例，三层等价：

**① HTTP 直连：**
```bash
curl -s http://10.225.57.15:8080/api/status | python3 -m json.tool
# 输出里看 "missionState": "IDLE", "altitude": 0.0, "sdkRegistered": true
```

**② REPL：**
```bash
RC_PRO_IP=10.225.57.15 python -m drone_bridge.main
drone> state
# {"missionState":"IDLE","altitude":0.0,...}
```

**③ RoboNIX MCP：** 由 `driver.py` 的 `@drone.mcp("robonix/primitive/drone/state")` handler 处理，
返回 `State_Response(status=<JSON 字符串>)`。上游通过 `rbnx call` 调用：

```bash
rbnx call robonix/primitive/drone/state
```

> 说明：① ② 是 `main.py` 的 `CommandHandler` 本地映射，不需要 RoboNIX 运行时；
> ③ 走 `driver.py` 的 `@drone.mcp(...)` 注册，需先 `rbnx boot`。

---

## 4. 端到端验证流程（推荐顺序）

```
第 1 步  连通性    curl /api/status            → sdkRegistered=true, productConnected=true
第 2 步  状态      state                       → missionState / altitude / GPS 合理
第 3 步  起飞      takeoff 3                   → 爬升悬停
第 4 步  相对移动  vel 0 1 0 2 / vel 0 0 0.5 2 → 悬停态下上升/右移/右转
第 5 步  安全      hover / rth                 → 务必实测
第 6 步  云台/相机 gv / greset / photo / video → 需先巡航（cruiseActive=true）
```

完成第 4 步即证明「相对位移」链路通；完成第 6 步即证明「云台+相机」链路通。

---

## 5. 无真机时的验证边界

没有飞机/RC Pro 在跟前时，只能做**静态 + 连通性**验证：

- [ ] `python -m py_compile drone_bridge/main.py drone_bridge/driver.py` 通过（语法）
- [ ] `bash scripts/build.sh` 里 `rbnx codegen --mcp` 能生成 `drone_mcp`（契约合法）
- [ ] `rbnx caps` 列出 12 个 caps（含 driver + 11 原语）
- [ ] `curl /api/status` 返回 200（APK 侧 WebServer 起来，即使飞机没连）

**无法无真机验证**：一切真正让飞机动的行为（takeoff/move/gimbal/photo 的实际效果），
这些必须真机实测。

---

## 6. 常见问题排错

| 现象 | 原因 | 处理 |
|------|------|------|
| HTTP 连不上 8080 | APK 未启动 / IP 错误 | RC Pro 上打开 APK，核对 IP |
| `sdkRegistered=false` | SDK 未激活 | 等 APK 页面显示「SDK已激活」 |
| `productConnected=false` | 飞机未连接 | 确认无人机开机并对频 |
| `vel` 下发无响应 | 不在悬停态 | 先 `takeoff` 进入 HOVERING 再 `vel` |
| 云台/相机无反应 | 未巡航 | 需 `cruiseActive==true`（巡航中） |
| `state` 缺 latitude/longitude | GPS 未定位 | 户外开阔地、等待 GPS 锁定 |
| 运动类都不动 | 虚拟摇杆未启用 | 查看 `vsEnabled`，重启 APK 重试 |
