# UAVfire 构建与运行说明

UAVfire 是在 `UAVtest`（DJI MSDK v5 示例 App）基础上，**纯增量**加入端侧火情检测的版本：
所有原有功能（Web 控制台/HTTP API、MJPEG 推流、巡航、云台/相机手动控制、返航等）保持不变。

## 新增内容

| 文件 | 作用 |
|------|------|
| `sample/src/main/assets/best.onnx` | YOLO11n 火/烟检测模型（端侧推理） |
| `models/FireDetector.kt` | ONNX Runtime Mobile 推理（letterbox 预处理 + NMS 后处理） |
| `models/FireDebouncer.kt` | 火情去抖（连续 5 帧触发 / 15 帧解除） |
| `models/FireGimbalAimer.kt` | 检测框中心 → 云台步进角度换算 |
| `models/FireReporter.kt` | 事件 CSV + 标注截图 + HTML 报告 |
| `models/FirePatrolController.kt` | 后台检测主循环（拉帧→检测→对准/拍照/GPS/报告） |
| `models/VideoFrameProvider.kt` | 增加 `frameSeq` 帧序号（供检测线程跳过重复帧） |
| `activity_automated_flight.xml` | 右列底部新增「火情检测」开关区 |
| `AutomatedFlightActivity.kt` | 接线开关、叠加显示、生命周期管理 |

依赖新增：`com.microsoft.onnxruntime:onnxruntime-android:1.22.0`（`dependencies.gradle` + `sample/build.gradle`）。

## 运行逻辑（与 fire_patrol.py 对齐）

1. 无人机巡航时，点「🔥 开启火情检测」启动后台线程。
2. 线程从 `VideoFrameProvider.latestJpeg` 取最新帧，`frameSeq` 变化才处理。
3. ONNX 检测 → 目标取「优先火类、再取置信度最高」。
4. 去抖确认火情后：**仅在巡航中**执行云台对准（节流 1s）+ 自动拍照 + 记录 GPS；非巡航只写日志不动作。
5. 画面叠加检测框，报告写入 `fire_reports/inspection_<时间戳>/`（events.csv + images/ + report.html）。

## 构建前提

- 本机安装 JDK 17、Android Studio（或 Gradle 7.x/8.x 环境）。
- `UAVfire/gradle.properties` 不存在（默认不提交）。从 `gradle.properties.example` 复制一份并填：
  - `AIRCRAFT_API_KEY`（DJI 开发者中心申请的 MSDK App Key，**勿提交真实值**）
  - `STORE_FILE` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`（本地签名 keystore；debug 构建可留空用默认调试签名）
  - 按机器填 `org.gradle.java.home`（JDK 路径，如 `D:/Java/jdk-17`）。

## 构建命令

```bash
cd UAVfire
./gradlew :sample:assembleDebug      # 生成 sample/build/outputs/apk/debug/sample-debug.apk
```

（Windows 下为 `gradlew.bat`。首次构建会联网拉取 MSDK、onnxruntime 等依赖，耗时较长。）

## 安装与使用

1. 安装 debug APK 到 DJI RC Pro 遥控器（`adb install -r sample-debug.apk`）。
2. 打开 App，连接无人机，添加航点并开始巡航。
3. 在右列底部点「🔥 开启火情检测」，观察画面叠加框与状态文字。
4. 发现火情时自动云台对准 + 拍照；报告路径显示在状态栏。

## 注意事项

- **端侧推理性能**：YOLO11n 640×640 在 RC Pro 上约 100–300ms/帧，火情检测无需实时，足够。
- **报告路径**：应用外部私有目录（`Android/data/com.dji.wang.aircraft/files/fire_reports/`），
  可用 adb 或文件管理器导出。
- **云台对准仅在巡航中**生效；手动/待命模式下检测到火情只记录日志。
- 如 `best.onnx` 需替换，重新导出后放到 `sample/src/main/assets/best.onnx` 即可（输入输出结构需一致：
  输入 `images` [1,3,640,640] float32，输出 `output0` [1,6,8400]，channel 顺序 x1,y1,x2,y2,smoke,fire）。
