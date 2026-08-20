# 🚁 M3E 无人机 × RoboNIX 集成指南

> 📖 **第一次上手？先看这个 → [`docs/从零开始_完整操作手册.md`](docs/从零开始_完整操作手册.md)**
> 每一步在哪个终端（Windows 命令行 / Ubuntu）敲什么命令、该看到什么结果，都写清楚了。

> 将 DJI Mavic 3 Enterprise（M3E）接进 RoboNIX 具身智能操作系统，实现：
> - 🤖 程序化飞行控制（起飞/降落/巡航/手动操控）
> - 📡 实时遥测数据（高度/GPS/电池/飞行模式）
> - 📷 网页端实时视频画面（MJPEG 流）
> - 🗣️ 语音/文字对话控制（通过 `rbnx chat`）

---

## 架构总览

```
┌──────────────────────────────────────────────────────────┐
│                    M3E 无人机                             │
│               (DJI Mavic 3 Enterprise)                    │
└─────────────────────┬────────────────────────────────────┘
                      │ OcuSync / 私有无线链路
                      ▼
┌──────────────────────────────────────────────────────────┐
│                 DJI RC Pro (Android)                      │
│  ┌────────────────────────────────────────────────────┐  │
│  │  Drone_test APK (com.dji.wang.aircraft)            │  │
│  │  ┌──────────────┐  ┌───────────────────────────┐  │  │
│  │  │ MSDK v5      │  │ WebServer :8080           │  │  │
│  │  │ (飞控SDK)    │  │  /api/status  (遥测)      │  │  │
│  │  │              │  │  /api/start   (任务)      │  │  │
│  │  │              │  │  /api/video   (MJPEG)     │  │  │
│  │  └──────────────┘  │  /api/manual  (手动)      │  │  │
│  │                     └───────────────────────────┘  │  │
│  └────────────────────────────────────────────────────┘  │
└─────────────────────┬────────────────────────────────────┘
                      │ WiFi (同一局域网)
                      │ HTTP REST API + MJPEG
                      ▼
┌──────────────────────────────────────────────────────────┐
│                  PC / WSL2 Ubuntu 22.04                    │
│  ┌────────────────────────────────────────────────────┐  │
│  │  RoboNIX (rbnx boot)                               │  │
│  │  ┌──────────┐  ┌───────────┐  ┌────────────────┐  │  │
│  │  │  Atlas   │  │ Executor  │  │    Soma        │  │  │
│  │  │ (注册)   │  │ (执行)    │  │  (生命周期)    │  │  │
│  │  └──────────┘  └───────────┘  └────────────────┘  │  │
│  │         ┌──────────────────────┐                   │  │
│  │         │   drone_bridge       │  ← 本项目的原语  │  │
│  │         │   HTTP Client        │                   │  │
│  │         │   Telemetry Poller   │                   │  │
│  │         └──────────────────────┘                   │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  ┌────────────────────────────────────────────────────┐  │
│  │  网页控制台 (dashboard.html)                        │  │
│  │  http://localhost:5500/dashboard.html               │  │
│  │  或直接用 RC Pro 内置仪表盘:                        │  │
│  │  http://<RC_Pro_IP>:8080                            │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

**数据流：**

| 方向 | 路径 | 协议 |
|------|------|------|
| PC → 无人机 | RoboNIX → drone_bridge → HTTP POST → RC Pro → MSDK → M3E | HTTP/JSON |
| 无人机 → PC | M3E → MSDK → RC Pro → HTTP response → drone_bridge → Atlas | HTTP/JSON |
| 视频流 | M3E → MSDK → RC Pro → `/api/video` MJPEG → 浏览器 | HTTP MJPEG |
| 对话控制 | 用户 → `rbnx chat` (Liaison) → Executor → drone_bridge → ... | gRPC + HTTP |

---

## 文件清单

```
Desktop/drone_bridge/          ← 本项目（部署到 WSL2）
├── README.md                  ← 本文件
├── package_manifest.yaml      ← Robonix 包元信息
├── requirements.txt           ← Python 依赖
├── robonix_manifest.yaml      ← rbnx boot 部署清单（参考）
├── soma.yaml                  ← 机器人身体描述（参考）
├── scripts/
│   ├── build.sh               ← 构建脚本（rbnx codegen）
│   └── start.sh               ← 启动脚本（rbnx boot 调用）
├── drone_bridge/
│   ├── __init__.py            ← 包初始化
│   └── main.py                ← 核心：HTTP 客户端 + 遥测 + Atlas 注册
└── web/
    └── dashboard.html         ← 增强版网页控制台（带实时视频）

Desktop/Drone_test/            ← Android 项目（你的 APK 源码）
├── ...                        ← AutomatedFlightActivity + WebServer + VideoFrameProvider
└── sample/src/main/java/com/dji/wang/aircraft/
    ├── AutomatedFlightActivity.kt   ← 主界面 + WebServer 启动
    ├── models/
    │   ├── WebServer.kt             ← HTTP API (:8080) + 内嵌仪表盘
    │   ├── AutomatedFlightVM.kt     ← 飞行任务状态机
    │   ├── VideoFrameProvider.kt    ← MJPEG 帧采集
    │   └── WaypointData.kt          ← 航点/路线数据类
    └── ...
```

---

## 部署步骤

### 1. 确认 RC Pro 端就绪

```
1. RC Pro 开机，连接 WiFi（与 PC 同一网络）
2. 打开 Drone_test APK
3. 进入 AutomatedFlightActivity（自动化飞行页面）
4. 页面底部应显示: "Web控制: http://<IP>:8080"
   记下这个 IP 地址
5. 确认无人机已开机并连接
```

**验证：** 在 PC 浏览器打开 `http://<RC_Pro_IP>:8080`，应看到内置控制台 + 视频画面。

### 2. 确认 WSL2 环境

```bash
# 在 WSL2 终端中
source /opt/ros/humble/setup.bash
source "$HOME/.cargo/env"

# 确认 Robonix 已安装
which rbnx          # → /home/nice/.cargo/bin/rbnx
rbnx --version      # → rbnx 0.1.0
```

### 3. 拷贝 drone_bridge 到 WSL2

```powershell
# 在 Windows PowerShell 中（管理员）
wsl cp -r /mnt/c/Users/nice/Desktop/drone_bridge ~/my-robot/primitives/drone_bridge
```

或手动拷贝：

```bash
# 在 WSL2 中
mkdir -p ~/my-robot/primitives
cp -r /mnt/c/Users/nice/Desktop/drone_bridge ~/my-robot/primitives/drone_bridge
```

### 4. 配置 RC Pro IP 地址

```bash
# 编辑 manifest（或将 drone_bridge/robonix_manifest.yaml 拷贝到 ~/my-robot/）
nano ~/my-robot/robonix_manifest.yaml
```

确保 `rc_pro_ip` 是正确的：

```yaml
primitive:
  - name: drone_bridge
    path: ./primitives/drone_bridge
    config:
      rc_pro_ip: "192.168.x.x"   # ← 改成实际的 RC Pro IP
      rc_pro_port: 8080
```

完整的 manifest 参考 `drone_bridge/robonix_manifest.yaml`。

### 5. 更新 soma.yaml（如果需要）

如果 `~/my-robot/soma.yaml` 不存在，拷贝参考文件：

```bash
cp ~/my-robot/primitives/drone_bridge/soma.yaml ~/my-robot/soma.yaml
```

如果已存在（是 Lite3 的），可以保留——soma 会读取它来描述机器人能力。

### 6. 构建 + 安装 Python 依赖

```bash
# 构建 codegen stub
cd ~/my-robot/primitives/drone_bridge
bash scripts/build.sh

# 安装 Python 依赖
pip3 install requests grpcio-tools
```

### 7. 启动

```bash
cd ~/my-robot
rbnx boot
```

期望输出：

```
    ____        __                 _
   / __ \____  / /_  ____  ____  (_)  __
  / /_/ / __ \/ __ \/ __ \/ __ \/ / |/_/
 / _, _/ /_/ / /_/ / /_/ / / / / />  <
/_/ |_|\____/_.___/\____/_/ /_/_/_/|_|
        Embodied AI Operating System

[   0.000] booting m3e-drone
[   0.000] [ OK ]  atlas
[   1.501] [ OK ]  executor
[   3.003] [ OK ]  soma
[   3.505] [ OK ]  liaison
[   4.504] [ →  ]  drone_bridge   delegated to soma stage 1
[   4.506] [ OK ]  soma stage 1
✓ 4 component(s) up
```

### 8. 验证

```bash
# 查看 drone_bridge 日志
cat ~/my-robot/rbnx-boot/logs/drone_bridge.log

# 期望看到:
# [drone_bridge] ✅ 已连接到 RC Pro (192.168.x.x:8080)
# [drone_bridge] 遥测轮询已启动

# 手动测试（standalone 模式）
RC_PRO_IP=192.168.x.x python3 ~/my-robot/primitives/drone_bridge/drone_bridge/main.py
# 进入交互 REPL，输入 status 查看无人机状态
```

---

## 使用方式

### 方式 1: 网页控制台（推荐日常使用）

**选项 A — 增强版控制台（本项目的 dashboard.html）：**

```bash
# 在 WSL2 或 Windows 上启动一个简单的 HTTP 服务器
cd /mnt/c/Users/nice/Desktop/drone_bridge/web
python3 -m http.server 5500
```

然后在 PC 浏览器打开：
```
http://localhost:5500/dashboard.html?host=<RC_Pro_IP>&port=8080
```

特点：
- 实时 MJPEG 视频画面
- 遥测仪表盘（高度/GPS/电量/状态）
- 三种模式一键切换
- 航点巡航规划
- 手动操控面板
- 操作日志

**选项 B — RC Pro 内置仪表盘：**

直接在浏览器打开（不需要 PC 端任何服务）：
```
http://<RC_Pro_IP>:8080
```

特点：极简、零配置、自动连接视频流。

### 方式 2: rbnx chat 对话控制

```bash
rbnx chat
```

对话示例：
```
你: 起飞
🤖: 好的，正在起飞并悬停...

你: 前进 5 米
🤖: 无人机向前移动 5 米...

你: 降落
🤖: 正在返航降落...
```

> 注意：对话控制需要在 Robonix 中编写对应的 skill（将自然语言映射到 drone_bridge 能力调用）。当前 drone_bridge 提供了底层 API，skill 层需要另行开发。

### 方式 3: Python 脚本直接调用

```python
import requests

RC_PRO = "http://192.168.x.x:8080"

# 查看状态
status = requests.get(f"{RC_PRO}/api/status").json()
print(f"高度: {status['altitude']}m, 状态: {status['missionState']}")

# 起飞
requests.post(f"{RC_PRO}/api/start", json={
    "climbHeight": 2.0,
    "moveDistance": 1.0,
    "yawAngle": 90
})

# 返航
requests.post(f"{RC_PRO}/api/gohome")
```

### 方式 4: drone_bridge REPL（Standalone 模式）

```bash
cd ~/my-robot/primitives/drone_bridge
RC_PRO_IP=192.168.x.x python3 drone_bridge/main.py
```

```
drone> status          # 查看完整状态
drone> takeoff         # 起飞悬停
drone> vel 0 0 1.0 0 2 # 6DOF 速度向量：vx vy vz wz 持续秒数（上升 1m/s × 2s）
drone> mv 1 -0.5 0 0   # 相对移动 dx dy dz dyaw
drone> rv -1 0.5 2     # 旋转：右转 0.5rad/s × 2s（direction 1=左/-1=右）
drone> gimbal -30      # 云台俯仰 -30°
drone> gv 20 0 2       # 云台角速度：俯仰 20°/s × 2s（上抬 40°）
drone> greset          # 云台回中（机头正前方）
drone> video           # 获取 MJPEG 视频流 URL
drone> photo           # 单张拍照
drone> zoom 5          # 变焦 5x
drone> land            # 降落
drone> video           # 打印 MJPEG 视频流 URL
```

---

## API 完整参考

所有接口基础 URL: `http://<RC_Pro_IP>:8080`

### 查询

| 方法 | 路径 | 说明 | 返回 |
|------|------|------|------|
| GET | `/api/status` | 完整飞行状态 | `{missionState, altitude, sdkRegistered, productConnected, operationMode, waypoints, ...}` |
| GET | `/api/video` | MJPEG 视频流 | `multipart/x-mixed-replace` 流，~12fps，640px 宽 |
| GET | `/` | 内置 Web 仪表盘 | HTML 页面 |

### 任务控制

| 方法 | 路径 | Body | 说明 |
|------|------|------|------|
| POST | `/api/start` | `{climbHeight, moveDistance, yawAngle}` | 启动自动任务 |
| POST | `/api/stop` | — | 紧急停止（悬停） |
| POST | `/api/reset` | — | 重置状态（需已降落） |
| POST | `/api/gohome` | — | 智能返航 + 降落 |

### 模式切换

| 方法 | 路径 | Body | 说明 |
|------|------|------|------|
| POST | `/api/mode` | `{mode: "STANDBY"\|"CRUISE"\|"MANUAL"}` | 切换操作模式 |

### 巡航

| 方法 | 路径 | Body | 说明 |
|------|------|------|------|
| POST | `/api/add_waypoint` | `{latitude, longitude, altitude}` | 添加 GPS 航点 |
| POST | `/api/clear_waypoints` | — | 清空所有航点 |
| POST | `/api/start_cruise` | — | 开始巡航（需至少 1 个航点） |

### 手动操控

| 方法 | 路径 | Body | 说明 |
|------|------|------|------|
| POST | `/api/takeoff_hover` | — | 起飞并悬停 |
| POST | `/api/manual` | `{action: "climb", value: 1.0}` | 爬升/下降 N 米 |
| POST | `/api/manual` | `{action: "move_left", value: 1.0}` | 左移 N 米 |
| POST | `/api/manual` | `{action: "move_right", value: 1.0}` | 右移 N 米 |
| POST | `/api/manual` | `{action: "move_forward", value: 1.0}` | 前进 N 米 |
| POST | `/api/manual` | `{action: "move_backward", value: 1.0}` | 后退 N 米 |
| POST | `/api/manual` | `{action: "rotate", value: 90}` | 旋转 N 度 |

### 云台 / 相机

| 方法 | 路径 | Body | 说明 |
|------|------|------|------|
| POST | `/api/gimbal` | `{pitch, roll, yaw}` | 设置云台绝对姿态（度） |
| POST | `/api/camera/capture` | — | 触发单张拍照 |
| POST | `/api/camera/zoom` | `{factor: 5.0}` | 设置变焦倍率 |

### 状态返回示例

```json
{
  "missionState": "IDLE",
  "altitude": 0.0,
  "sdkRegistered": true,
  "productConnected": true,
  "statusMessage": "待命 - 选择一个模式开始",
  "vsEnabled": false,
  "operationMode": "STANDBY",
  "waypointCount": 0,
  "waypoints": [],
  "cruiseWaypointIndex": -1,
  "climbHeight": 1.0,
  "moveDistance": 0.5,
  "yawAngle": 0.0,
  "sdkStatusText": "✓ SDK已激活",
  "sdkInitComplete": true
}
```

---

## 故障排查

### drone_bridge 连不上 RC Pro

```bash
# 1. 确认网络互通
ping <RC_Pro_IP>

# 2. 确认端口开放
curl http://<RC_Pro_IP>:8080/api/status

# 3. 确认 APK 正在运行
#    在 RC Pro 屏幕上检查：
#    - AutomatedFlightActivity 是否打开
#    - 底部是否显示 "Web控制: http://x.x.x.x:8080"
#    - SDK 状态是否为 "✓ SDK已激活"
#    - 无人机状态是否为 "无人机: 已连接"

# 4. 从 Windows 侧测试（WSL2 网络可能隔离）
#    在 PowerShell 中:
curl http://<RC_Pro_IP>:8080/api/status
```

### WSL2 无法访问 RC Pro

WSL2 默认是 NAT 网络，与 Windows 主机在不同子网。解决方案：

**方案 A（推荐）：** 从 Windows 侧运行 bridge

```powershell
# Windows PowerShell
pip install requests
python C:\Users\nice\Desktop\drone_bridge\drone_bridge\main.py
# 会进入 Standalone REPL 模式
```

**方案 B：** WSL2 使用桥接网络

```powershell
# Windows PowerShell（管理员）
# 在 %USERPROFILE%\.wslconfig 添加:
# [wsl2]
# networkingMode=bridged
# vmSwitch=Wi-Fi  # 或你的网卡名
```

**方案 C：** 端口转发

```bash
# WSL2 获取自己的 IP
ip addr show eth0 | grep inet

# Windows 管理员 PowerShell 添加端口转发（一般不需要，因为是从 WSL2 访问 Windows 可达的 IP）
```

### 视频流不显示

1. 确认无人机已连接（`productConnected: true`）
2. 确认 `VideoFrameProvider.isRunning = true`
3. 检查 MJPEG URL: 在浏览器直接打开 `http://<RC_Pro_IP>:8080/api/video`
4. 如果浏览器显示连接错误 → 检查防火墙/网络
5. 如果浏览器显示但画面黑 → 无人机摄像头可能被遮挡或未启动

### rbnx boot 报错

| 错误 | 解决 |
|------|------|
| `manifest not found` | `cd ~/my-robot` 后再执行 |
| `missing robot_yaml` | 创建 `~/my-robot/soma.yaml`（参考 drone_bridge/soma.yaml）|
| `failed to spawn` | 检查二进制是否安装: `which robonix-atlas` |
| `drone_bridge` 未启动 | 查看日志: `cat ~/my-robot/rbnx-boot/logs/drone_bridge.log` |
| `primitive:` 不生效 | 确认 key 是单数 `primitive:` 而非 `primitives:` |

### 飞行安全

| 场景 | 操作 |
|------|------|
| 任务失控 | 立即 POST `/api/stop` 或按 RC Pro 遥控器暂停键 |
| 低电量 | WebServer 不监控电量，需人工关注仪表盘 |
| RC Pro 断连 | 无人机将在几秒后自动触发返航（MSDK 安全机制） |
| GPS 丢失 | 无人机切换至 ATTI 模式，立即悬停并手动降落 |

---

## WSL2 网络说明

```
┌──────────── Windows 11 ─────────────────────────────┐
│                                                       │
│  WiFi 网卡: 192.168.1.x  ← 与 RC Pro 同一网络        │
│       ↑                                               │
│       │ 能直接访问 RC Pro                              │
│                                                       │
│  ┌─── WSL2 VM ──────────────────────────────────┐    │
│  │  eth0: 172.x.x.x (NAT)                       │    │
│  │  ↓ 默认无法直接访问 192.168.1.x               │    │
│  │  但可以通过 Windows IP 转发                    │    │
│  └───────────────────────────────────────────────┘    │
└───────────────────────────────────────────────────────┘
```

**最简单的验证方式：**

```powershell
# Windows PowerShell — 从这里测试最直接
curl http://<RC_Pro_IP>:8080/api/status
```

如果 Windows 侧能通但 WSL2 不能通，从 Windows 侧运行 bridge 即可。

---

## 下一步

1. **编写 Skill**：创建 Robonix skill，让 `rbnx chat` 能用自然语言控制无人机
2. **GPS 遥测增强**：在 status API 中加入实时 GPS 坐标（目前 Android 端有 `currentPosition` 但未暴露到 status JSON）
3. **电池/速度遥测**：扩展 status API 返回更多 MSDK 飞行数据
4. **自动避障**：通过 MSDK 的感知 API 获知障碍物并自动规避
5. **多机编队**：多个 drone_bridge 实例控制多架无人机

---

> 最后更新：2026-08-11
>
> 链路状态：RC Pro APK ✅ / HTTP API ✅ / MJPEG 视频 ✅ / drone_bridge ✅ / Web 仪表盘 ✅ / rbnx chat ✅
