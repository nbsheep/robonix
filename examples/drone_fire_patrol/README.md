# drone_fire_patrol —— 大疆 M3E 自动巡航火灾检测（对准拍摄 + 报告）

RoboNIX 的**无人机业务示例**：把离板的火/烟 YOLO 检测接到 DJI M3E 上。无人机自动巡航时，电脑端对实况画面做实时检测，**确认火情后自动把云台对准火源、拍照、打 GPS、写巡检报告**。无人机只负责巡航，检测与拍摄全部在电脑端完成（离板方案）。

> 检测跑 PC，不跑无人机；后端不接管飞行轨迹，只做监视 + 云台/相机/打点。

---

## 依赖

- **无人机 + RC Pro**：Drone_test APK（`/api/*` HTTP 接口），PC 与 RC Pro 同一局域网。
- **drone_bridge 客户端**：本仓库 `master` 分支（无人机线）里的 `drone_bridge` 包（`DroneClient`）。用 `DRONE_BRIDGE` 环境变量指向它所在目录，默认 `C:/Users/nice/Desktop/drone_bridge`。
- **Python 环境**（推荐独立 conda 环境）：

```bash
conda create -n fire-detect python=3.10 -y
conda activate fire-detect
pip install -r requirements.txt          # torch 视 CUDA 版本自行安装
```

- **YOLO 权重**：`models/best.pt`（D-Fire 数据集，类别 `0=smoke, 1=fire`）。**不随本仓库提交**（robonix 的 `.gitignore` 忽略了 `*.pt` 以保持 monorepo 精简）。运行前把权重拷到 `models/best.pt`（从 fire-detect 项目拿，或用 `data/dfire.yaml` + 你的 YOLO 训练脚本生成）。

## 运行

**① 无人机先开始自动巡航** → **② 再跑本程序**（顺序反了云台/相机不可用，只能检测不能拍）。

```bash
# 直连 Drone_test APK 的 MJPEG
python fire_patrol.py

# 本地演练（无无人机，验证"检测→去抖→报警→报告"，云台/拍照自动跳过）
SOURCE=samples/test.mp4 SHOW=0 python fire_patrol.py
```

窗口激活时按 `q` 或终端 `Ctrl+C` 退出，退出自动生成 `runs/reports/inspection_*/report.html`。

### 关键环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `DRONE_BRIDGE` | `C:/Users/nice/Desktop/drone_bridge` | drone_bridge 客户端所在目录 |
| `RC_PRO_IP` | `10.225.57.15` | RC Pro 局域网 IP |
| `SOURCE` | `http://<IP>:8080/api/video` | 视频源；也可 `rtsp://127.0.0.1:8554/live/drone`（需 ffmpeg→MediaMTX） |
| `CONF` | `0.35` | 检测置信度阈值 |
| `TRIGGER` / `RELEASE` | `5` / `15` | 报警/解除所需连续帧数 |
| `AIM_ON_FIRE` / `CAPTURE_ON_FIRE` | `1` / `1` | 发现火→对准 / 拍照 |
| `SHOW` | `1` | 实时预览窗口 |

## 安全须知（务必先读）

1. **室外空旷、有人看护**，飞机始终在视线内。
2. 首次**先小步验证**云台方向映射（`yaw_right` 让火往画面里靠等）。
3. 云台/相机**仅在无人机「正在巡航」时可用**；本程序逐帧读 `/api/status.cruiseActive`，非巡航自动跳过，不越权动作。
4. **绝不发送飞行控制命令**（不涉及 takeoff/land/move/rth），不改无人机航迹。
5. 发现火**只做「对准拍摄 + 报告」，不会自动返航**；处置仍由飞手决定。
6. 一旦异常，**先用遥控器接管**。

## 文件结构

| 文件 | 作用 |
|------|------|
| `fire_patrol.py` | 主巡检：拉流 + 检测 + 去抖 + 云台对准/拍照/GPS/报告 |
| `gimbal_aim.py` | 云台对准控制器：火框偏移→离散步进，守卫巡航 |
| `alarm.py` | 去抖器（连续 N 帧才报警/解除） |
| `reporter.py` | 巡检报告（截图 + CSV + HTML） |
| `models/` | YOLO 权重目录（`best.pt` 需自备，见「依赖」） |
| `data/dfire.yaml` | 数据集类别映射（`0=smoke, 1=fire`） |
