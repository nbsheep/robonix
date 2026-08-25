# capabilities — 统一 drone 原语契约面（Schema A）

`rbnx codegen --mcp` 从这里生成 MCP dataclass（`drone_mcp` / `std_msgs_mcp`）：

- `lib/drone/srv/*.srv` —— ROS2 风格服务定义（请求参数 + `std_msgs/String status`）
- `primitive/drone/*.toml` —— 原语元数据（语义 id / version / idl / description）

契约**只声明语义，不含任何 SDK 字段**。MSDK 术语全部下沉到 `dji_msdk/backend.py`
的私有 helper，契约层与驱动层完全 SDK 无关。

## .srv 约定

```
float32 altitude          # 请求：输入参数（无输入则省略，直接 `---`）
---
std_msgs/String status    # 响应：JSON 字符串（{"ok": true, ...} / {"ok": false, "error": ...}）
```

## .toml 约定（Schema A）

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

- `id`：完全限定原语 id，命名空间 `robonix/primitive/drone/`（SDK 无关）
- `idl`：相对 `capabilities/lib/` 的服务定义路径
- 生命周期契约 `driver.v1.toml` 引用内置 `lifecycle/srv/Driver.srv`（无本地 `Driver.srv`）

## 原语集

- **核心（11）**：`takeoff` · `land` · `move_velocity` · `rotate_velocity` ·
  `hover` · `rth` · `gimbal_velocity` · `gimbal_reset` · `camera_capture` ·
  `camera_video` · `state`
- **扩展（35）**：飞控状态（9）/ 飞控动作（4）/ 云台（4）/ 相机（6）/
  其他（7）/ 航点任务（5），详见 `primitives_spec.md` 第六节
