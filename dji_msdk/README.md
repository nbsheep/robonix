# dji_msdk — DJI MSDK v5 RoboNIX primitive package

把 DJI Mobile SDK v5 的硬件能力（飞控 / 云台 / 相机 / 电池 / 图传 / RTK /
感知 / 航点任务）封装为 RoboNIX 可调用的**统一 drone 原语**
（`robonix/primitive/drone/*`）。同级还有 PSDK / OSDK / PX4，不同 SDK 通过
**同一套原语**调用。

## 三层架构

```
契约层  capabilities/lib/drone/srv/*.srv + capabilities/primitive/drone/*.toml
        （Schema A，SDK 无关：只有语义 id / version / idl / description）
   │  rbnx codegen --mcp 生成 drone_mcp / std_msgs_mcp dataclass
   ▼
驱动层  dji_msdk/driver.py
        （@drone.mcp handler：语义请求 → 后端语义方法 → 语义响应）
   │  只认 backend 语义方法，不认识任何 MSDK key
   ▼
后端层  dji_msdk/backend.py  （MsdkBackend —— 「封装 MSDK」的落点）
        （语义方法 → MSDK key/manager → APK HTTP 端点）
   │  换 SDK 只需换后端
   ▼
传输    APK WebServer.kt :8080（MSDK v5 在 APK 内运行）
```

契约层、驱动层 SDK 无关；只有后端层知道 MSDK 术语。PSDK/OSDK/PX4 各写一个后端
（`psdk_backend.py` / `mavlink_backend.py`），实现同一组语义方法，`driver.py` 与
契约层不变。详见 `primitives_spec.md`。

## 目录结构

```
dji_msdk/
├── package_manifest.yaml        # 包清单（capabilities 契约面 + config bridge_url）
├── primitives_spec.md           # 原语规格 v2（三层架构 + 语义→MSDK→HTTP 映射）
├── README.md
├── dji_msdk/                    # Python 包
│   ├── __init__.py
│   ├── driver.py                # 驱动层（@drone.mcp 分发 + 生命周期）
│   └── backend.py               # 后端层（MsdkBackend：语义方法 → MSDK → HTTP）
├── capabilities/
│   ├── lib/drone/srv/*.srv      # codegen 输入：每个原语的 ROS2 风格服务定义
│   └── primitive/drone/*.toml   # codegen 输入：原语 → srv 映射（Schema A）
└── scripts/
    ├── build.sh                 # rbnx codegen --mcp
    └── start.sh                 # python3 -m dji_msdk.driver
```

## 命名空间

- 原语：`robonix/primitive/drone/*`（SDK 无关，46 个：核心 11 + 扩展 35）
- 驱动 capability：`robonix/primitive/drone/driver`
- 驱动 provider：`capability_id: dji_msdk`

## 构建 / 启动

```bash
bash scripts/build.sh   # rbnx codegen --mcp（生成 atlas_pb2 + drone_mcp/std_msgs_mcp）
bash scripts/start.sh   # 启动驱动（连接 APK 桥接 + 注册原语 + 生命周期）
```

环境变量：

- `ROBONIX_DJI_BRIDGE`：APK 桥接地址，默认 `http://127.0.0.1:8080`
  （也可通过 manifest `config.bridge_url` 配置）

## 验证

```bash
# 本机（无框架）—— 语法级检查
python -c "import ast; ast.parse(open('dji_msdk/driver.py', encoding='utf-8').read()); ast.parse(open('dji_msdk/backend.py', encoding='utf-8').read()); print('OK')"

# 后端可独立运行（纯 stdlib，不依赖 robonix_api）—— 起一个 mock HTTP 或连真机
python -c "from dji_msdk.backend import MsdkBackend; print(MsdkBackend('http://127.0.0.1:8080').ping())"
```

Jetson 侧（框架）需执行：`bash scripts/build.sh` 确认 `--mcp` 生成 MCP dataclass，
`rbnx caps` 看 `robonix/primitive/drone/*` 注册，`rbnx call
robonix/primitive/drone/takeoff '{"altitude":3.0}'` 端到端验证。

## 当前 APK 适配状态

`MsdkBackend`（`dji_msdk/backend.py`）把语义动作映射到当前 M3E APK
（`WebServer.kt`）暴露的语义端点。受 APK 能力所限，46 个原语按映射程度分三档
（明细见 `primitives_spec.md` 第七节）：

- ✅ 语义对等可用（14）：takeoff / move_velocity / rotate_velocity / hover / rth /
  gimbal_velocity / gimbal_reset / camera_capture / state / camera_record_start /
  camera_record_stop / waypoint_start / waypoint_pause / waypoint_resume
- 🟡 近似降级可用（6）：camera_video / state_position / state_flight_mode /
  state_is_flying / move_ee / gimbal_rotate
- ❌ APK 未暴露（26）：land / set_home / state_attitude 等，调用返回
  `{"ok": false, "error": "app 端未实现该 MSDK 能力: ..."}`

APK 端补充端点后，只需在 `backend.py` 补映射即可启用对应原语，其余文件无需改动。
