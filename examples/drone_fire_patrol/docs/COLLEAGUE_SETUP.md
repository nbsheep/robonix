# 无人机火灾检测 · 给同事的测试指引

> 目标：在你的电脑上把「无人机自动巡航火灾检测」跑起来。整个过程分**两阶段**：
> 1. **先离线跑通检测**（不需要无人机，几分钟）
> 2. **再接真机**（无人机 + 遥控器都在场）
>
> 建议**先做阶段一**，确认环境没问题、能检出火/烟，再碰无人机。这样排除问题更快。

---

## 0. 你手头要先准备好的东西

| 需要的东西 | 从哪来 | 阶段 |
|---|---|---|
| 一台能联网的 **Windows** 电脑 | 你的 | 全程 |
| **Python 3.10+**（推荐 Anaconda/miniconda） | 你装 | 全程 |
| robonix 仓库（`dev` 分支） | 克隆（见 §1） | 全程 |
| **模型权重 `models/best.pt`** | **找我（同事）要**，约 5.2MB | 阶段一就要 |
| （可选）一段**有火/烟的测试视频** | 找我要，或自己录 | 阶段一 |
| （真机）无人机 + RC Pro + 同一 WiFi | 现场 | 阶段二 |

> ⚠️ **模型权重 `best.pt` 最重要**：它没有提交到仓库里（仓库为了精简忽略了 `/``*.pt`` 权重文件）。没有它，检测跑不起来。记得先找我拷贝。

---

## 1. 克隆代码（dev 分支）

打开 Git Bash（或任意终端），执行：

```bash
git clone -b dev https://github.com/nbsheep/robonix.git
cd robonix
```

- 国内网络如果 clone 慢/超时，把地址换成代理版：
  `https://api.gitproxy.dev/github.com/nbsheep/robonix.git`
- 火情检测示例在 `examples/drone_fire_patrol/`。克隆完先进去：
  ```bash
  cd examples/drone_fire_patrol
  ```

---

## 2. 建 Python 环境

推荐用 conda 建一个独立环境，避免污染你已有的环境：

```bash
# 有 conda：
conda create -n fire-detect python=3.10 -y
conda activate fire-detect

# 安装依赖（torch 单独装，见下）
pip install -r requirements.txt
```

`requirements.txt` 内容（本目录已带）：`ultralytics`、`opencv-python`、`numpy`、`requests`、`jinja2`。

**关于 torch（必读）**：`ultralytics` 会自动装一个 torch，Windows 上默认是 **CPU 版**——足够跑检测，就是慢一点。
- 想用显卡加速（可选）：
  ```bash
  pip install torch torchvision --index-url https://download.pytorch.org/whl/cu128
  ```
  改完后代码里默认 `DEVICE=0` 用 CUDA；没显卡或报 CUDA 错就设 `DEVICE=cpu`（见 §6）。

**验证环境**：
```bash
python -c "import cv2, ultralytics, numpy, requests, jinja2; print('依赖OK')"
```

---

## 3. 放模型权重 best.pt（没有就跑不了）

把**我发给你的 `best.pt`** 拷到本目录下：

```bash
# 假设你把它下载到了 ~/Downloads/best.pt
mkdir -p models
cp ~/Downloads/best.pt models/best.pt
ls -lh models/best.pt      # 应显示 ~5.2M
```

> 若你暂时拿不到权重：先用 `data/dfire.yaml` + `ultralytics` 在 D-Fire 数据集上训练，训练出的 `best.pt` 放到同位置即可。但最省事是找我要现成的。

---

## 4. （可选）drone_bridge 客户端 —— 只接真机才需要

`drone_bridge` 是连无人机的 HTTP 客户端（负责云台/拍照/GPS）。**纯离线检测用不到它**，可以先跳过这一步，等阶段二再做。

（如果现在就要）它在仓库的 **`master`** 分支根目录，跨分支取出来：

```bash
cd ../..                    # 回到仓库根目录 robonix/
git fetch origin master
git checkout origin/master -- drone_bridge
ls drone_bridge/            # 应看到 main.py / driver.py / __init__.py
```

然后让程序能找到它（`DRONE_BRIDGE` 指向**含 `drone_bridge` 包的目录**，即仓库根目录）：

```bash
export DRONE_BRIDGE="$PWD"          # 在仓库根目录下执行
# CMD/PowerShell 等价： set DRONE_BRIDGE=%CD%  /  $env:DRONE_BRIDGE="$PWD"
```

---

## 5. 先跑零依赖自检（推荐第一步）

这个**不需要模型、不需要视频、不需要无人机**，只验证"偏移→云台动作"的映射逻辑是否正确：

```bash
python gimbal_aim.py
```

看到类似
```
[OK] gimbal_aim 偏移→动作 映射全部正确
```
就算通过（前面的乱码是终端编码问题，不影响结论）。

---

## 6. 离线跑检测（不需要无人机）

```bash
# 用一段有火/烟的视频；SOURCE 指向该视频文件
SOURCE=C:/Users/你的视频.mp4 SHOW=1 python fire_patrol.py
```

- **有**无人机会经历：连 RC Pro → 拉流 → YOLO 检出火/烟 → 报警 → 云台对准 + 拍照 + 打点 → 写报告。
- **没有**无人机：程序会打印 `drone_bridge 未安装 → 纯检测模式` 或 `RC Pro 不可达`，但**照常检测**，只是跳过云台/拍照。窗口上能看到带框的画面。

**看结果**：程序结束后，`runs/reports/inspection_*/` 下会生成：
- `report.html` —— 巡检报告
- `events.csv` —— 事件表
- `shots/` —— 报警截图

**退出**：窗口激活时按 `q`，或终端 `Ctrl+C`。

> 没有视频？找同事要，或手机随便录一段有明火的视频；也可以用真实无人机流（见 §7）。

---

## 7. 上真机（连无人机）

前提：无人机 + RC Pro 开机，Drone_test APK 打开到**自动化飞行页**，电脑和 RC Pro 连**同一台路由器**。

1. **确认连通**：
   ```bash
   curl http://10.225.57.15:8080/api/status
   ```
   能返回 JSON（含 `cruiseActive` 等字段）就通了。**如果这台遥控器 IP 不一样，用 `RC_PRO_IP` 覆盖**。

2. **无人机先开始巡航**（在 RC Pro 上设好航点、点"开始巡航"，无人机起飞进入自动巡航）。

3. **跑检测**（先做好 §3 §4，即权重 + drone_bridge）：
   ```bash
   export DRONE_BRIDGE=/path/to/robonix        # 指向含 drone_bridge 包的目录
   python fire_patrol.py
   ```
   日志里会看到 `[OK] 已连接`、检测到火且 `cruiseActive=true` 时，触发云台对准 + 拍照 + 打点。

---

## 8. 常见问题排查

| 现象 | 原因 / 解决 |
|---|---|
| `No module named 'drone_bridge'` | 只有真机才需要它。离线检测可忽略；真机则按 §4 设 `DRONE_BRIDGE` |
| `No module named 'cv2'/'ultralytics'` | 没进 conda 环境，或没装依赖（`conda activate fire-detect` + `pip install -r requirements.txt`） |
| `models/best.pt` 找不到 / `does not exist` | 权重没放对位置：`examples/drone_fire_patrol/models/best.pt`；或用 `WEIGHTS=<path>` 指定 |
| CUDA 报错、显存不足 | 改 `DEVICE=cpu`（`DEVICE=cpu python fire_patrol.py`），或用 CPU 版 torch |
| 视频打不开 / 读不到帧 | `SOURCE` 路径不对；MJPEG/RTSP 流需要网络通 |
| 连不上 RC Pro（timeout/000） | 没同网 / 无人机没开机 / APK 不在自动化飞行页 / IP 不对（试 `RC_PRO_IP`） |
| 只检测、不拍照 | 无人机没在巡航（`cruiseActive=false`，程序自动跳过）；先"开始巡航"再跑 |
| 日志中文乱码 | 终端编码问题：窗口里 `chcp 65001` 或设 `PYTHONUTF8=1`，不影响输出内容 |

---

## 9. 安全须知（真机务必遵守）

1. **室外空旷、有人看护**，飞机始终在视线内。
2. 首次**先小角度手动试**云台，确认方向映射正确（`yaw_right` 会让火往画面里靠）。
3. 云台/相机**只在无人机"正在巡航"时可用**；程序逐帧读 `cruiseActive`，非巡航自动跳过。
4. 程序**不发送任何飞行控制命令**（不涉及起飞/降落/移动/返航），不改变航迹。
5. 发现火**只做"对准拍摄 + 报告"，不会自动返航**；要处置由飞手决定。
6. 一旦异常，**先用遥控器接管**。

---

## 10. 环境变量速查表

| 变量 | 默认 | 说明 |
|---|---|---|
| `DRONE_BRIDGE` | `C:/Users/nice/Desktop/drone_bridge` | drone_bridge 包所在目录（真机才用） |
| `RC_PRO_IP` | `10.225.57.15` | RC Pro 局域网 IP |
| `SOURCE` | `http://<IP>:8080/api/video` | 视频源；也支持本地视频、`rtsp://...` |
| `WEIGHTS` | `models/best.pt` | YOLO 权重路径 |
| `CONF` | `0.35` | 检测置信度阈值 |
| `TRIGGER` / `RELEASE` | `5` / `15` | 报警/解除所需连续帧数 |
| `AIM_ON_FIRE` | `1` | 发现火 → 云台对准 |
| `CAPTURE_ON_FIRE` | `1` | 对准后拍照 |
| `SHOW` | `1` | 实时预览窗口 |
| `DEVICE` | `0` | 推理设备（`0`=CUDA，`cpu`=CPU） |

---

遇到卡住的地方，把**终端报错**和**日志里的 `[drone]`/`[巡检]` 行**发我（同事）就行。祝顺利 🔥
