# 原语调用与验证指南

> 本文档说明如何调用 11 个无人机原语，以及如何逐项验证它们的正确性。
> 原语 ID 统一为 `robonix/primitive/drone/<name>`，共三层调用方式，从底层到上层。

---

## 0. 调用方式总览

| 层级 | 入口 | 适用场景 |
|------|------|---------|
| **① HTTP API 直连** | `POST http://<RC_PRO_IP>:8080/...` | 真机最底层验证、排除桥接/APK 问题 |
| **② drone_bridge REPL** | `python3 -m drone_bridge.main` | 不依赖 RoboNIX，快速调试原语映射 |
| **③ RoboNIX 原语** | gRPC `robonix/primitive/drone/*` | 正式集成，供 Atlas / skill 调度 |

调用链（正向）：

```
RoboNIX (③) → drone_bridge/driver.py (grpc handler)
            → main.py DroneClient (HTTP)
            → Drone_test APK WebServer (:8080) → MSDK v5 → M3E 无人机
```

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

#### state_battery — 电量

| | |
|--|--|
| ① HTTP | `curl http://10.225.57.15:8080/api/status` → 看 `batteryPercent` / `batteryVoltage` |
| ② REPL | `bat` |
| ③ RoboNIX | `robonix/primitive/drone/state_battery`（无输入） |

**验证判据**：`batteryPercent` 为 0–100 的合理值、`batteryVoltage` 约 40000–53000 mV（4S 电池 13–21V）。若都返回 0，说明飞机未连接（MSDK 读不到电池组件）——这正是之前排掉的问题。

#### state_position — 位置

| | |
|--|--|
| ② REPL | `pos` |
| ③ RoboNIX | `robonix/primitive/drone/state_position`（无输入） |

**验证判据**：`latitude/longitude` 与手机 GPS 大致一致，`altitude` 起飞前 ≈ 0，`heading` 0–360°。

---

### 2.2 运动控制（需真机，先在空旷场地低空测试）

> ⚠️ 运动类原语会真的让飞机动。**先在室内/低空（1–2m）、有人看护**下逐项验证。

#### takeoff — 起飞悬停

| | |
|--|--|
| ② REPL | `takeoff 3` |
| ③ RoboNIX | 输入 `altitude=3.0` |

**验证判据**：飞机起飞、爬升至约 3m 后悬停；`status` 显示 `missionState` 依次 TAKEOFF→CLIMBING→HOVERING。

#### move_relative — 机体系相对移动（场景②核心）

| | |
|--|--|
| ② REPL | `mv <dx> <dy> <dz> [dyaw]`，如 `mv 0 0 1 0`（上升 1m） |
| ③ RoboNIX | 输入 `dx/dy/dz/dyaw` 四个 float64 |

参数方向约定（机体系，即机头朝向为前）：

| 分量 | 正 | 负 |
|------|----|----|
| `dx` | 前进 | 后退 |
| `dy` | 右移 | 左移 |
| `dz` | 上升 | 下降 |
| `dyaw` | 右转（度） | 左转 |

**验证判据**：逐项下发 `mv 0 0 1 0`（上升）→ `mv 0 1 0 0`（右移）→ `mv 1 0 0 0`（前进）→ `mv 0 0 0 45`（右转 45°），观察飞机按对应方向移动。返回 JSON 里 `moves` 列出实际下发的轴。多轴串行执行（先上下→旋转→前后→左右）。

#### gimbal_rotate — 云台姿态（场景①核心）

| | |
|--|--|
| ② REPL | `gimbal -30`（俯仰向下 30°） |
| ③ RoboNIX | 输入 `pitch/roll/yaw`（度，绝对角度） |

**验证判据**：云台俯仰到指定角度（M3E 俯仰范围约 −90°~+30°）。摄像头朝下拍地面用 `pitch=-90`。**无需起飞也可验证**。

#### camera_capture — 拍照

| | |
|--|--|
| ② REPL | `photo` |
| ③ RoboNIX | `robonix/primitive/drone/camera_capture`（无输入） |

**验证判据**：相机快门触发（听快门声 / 看指示灯）；照片出现在相机 SD 卡。**无需起飞**。

#### camera_zoom — 变焦

| | |
|--|--|
| ② REPL | `zoom 5` |
| ③ RoboNIX | 输入 `factor`（约 1.0–28.0） |

**验证判据**：视频画面拉近，`/api/video` 画面或 DJI 图传可见倍率变化。**无需起飞**。

#### move_ee — 飞到 GPS 点

| | |
|--|--|
| ② REPL | `move_ee <lat> <lng> [alt]` |
| ③ RoboNIX | 输入 `latitude/longitude/altitude` |

**验证判据**：飞机飞到目标经纬度（误差 < 2m）并到指定高度。**需 GPS 良好**，不要在室内测。

#### hover / land / rth — 安全原语

| 原语 | REPL | 判据 |
|------|------|------|
| `hover` | `hover` | 飞机立即停止、原地悬停 |
| `land` | `land` | 原地降落并锁定 |
| `rth` | `rth` | 自动返回起飞点并降落 |

**验证判据**：这三个是安全兜底，务必在飞行中实测 `hover` 能立刻停下、`rth` 能返航降落。**这是最关键的验证项。**

---

## 3. 三层调用示例（同一原语对比）

以 `state_battery` 为例，三层等价：

**① HTTP 直连：**
```bash
curl -s http://10.225.57.15:8080/api/status | python3 -m json.tool
# 输出里找 "batteryPercent": 87, "batteryVoltage": 16800
```

**② REPL：**
```bash
RC_PRO_IP=10.225.57.15 python3 -m drone_bridge.main
drone> bat
# {"percent": 87.0, "voltage": 16800.0}
```

**③ RoboNIX gRPC：** 由 `driver.py` 的 handler 处理，返回 `GetBattery_Response(battery=<JSON 字符串>)`。
上游（Atlas / skill）通过 `robonix/primitive/drone/state_battery` 这个 capability 路径调用，
响应体 `status` / `battery` 字段为 JSON 字符串（`percent` + `voltage`）。

> 说明：① ② 是 `main.py` 的 `CommandHandler` 本地映射，不需要 RoboNIX 运行时；
> ③ 走 `driver.py` 的 `@drone.grpc(...)` 注册，需先 `rbnx boot`。

---

## 4. 端到端验证流程（推荐顺序）

```
第 1 步  连通性    curl /api/status  → sdkRegistered=true, productConnected=true
第 2 步  电量      bat / status      → percent > 0（之前的问题是这里返回 0）
第 3 步  位置      pos               → 经纬度合理
第 4 步  云台/相机 gimbal -30 / photo / zoom 5  （无需起飞，最安全）
第 5 步  起飞      takeoff 3         → 爬升悬停
第 6 步  相对移动  mv 0 0 1 0 / mv 1 0 0 0 ...  （场景②）
第 7 步  航点      move_ee <lat> <lng> 5      （场景①抵近，需 GPS）
第 8 步  安全      hover / rth / land           （务必实测）
```

完成第 4 步即证明「云台+相机」链路通（场景①硬件原语到位）；
完成第 6 步即证明「相对位移」链路通（场景②硬件原语到位）。

---

## 5. 无真机时的验证边界

没有飞机/RC Pro 在跟前时，只能做**静态 + 连通性**验证：

- [ ] `python3 -m py_compile drone_bridge/main.py drone_bridge/driver.py` 通过（语法）
- [ ] `bash scripts/build.sh` 里 `rbnx codegen` 能生成 `drone_pb2`（契约合法）
- [ ] `rbnx caps` 列出 12 个 caps（含 driver + 11 原语）
- [ ] `curl /api/status` 返回 200（APK 侧 WebServer 起来，即使飞机没连）

**无法无真机验证**：一切真正让飞机动的行为（takeoff/move/gimbal/photo/zoom 的实际效果），
这些必须真机实测。

---

## 6. 常见问题排错

| 现象 | 原因 | 处理 |
|------|------|------|
| `batteryPercent` 恒为 0 | 飞机未连接/未开机 | 确认 APK 里 `productConnected=true` |
| HTTP 连不上 8080 | APK 未启动 / IP 错误 | RC Pro 上打开 APK，核对 IP |
| `mv` 下发无响应 | 不在悬停态 | 先 `takeoff` 进入 HOVERING 再 `mv` |
| 云台/相机无反应 | 相机组件未就绪 | 稍等几秒或重启 APK |
| `move_ee` 不动 | GPS 弱 / 航点距离过近 | 户外开阔地、目标点 > 5m |
| 运动类都不动 | 虚拟摇杆未启用 | 查看 `vsEnabled`，重启 APK 重试 |
