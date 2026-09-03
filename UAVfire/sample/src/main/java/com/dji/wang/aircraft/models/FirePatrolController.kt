package com.dji.wang.aircraft.models

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * 火情巡检控制器：独立后台线程拉最新视频帧 → ONNX 检测 → 去抖 → 云台对准/拍照/GPS/报告。
 * 与 fire_patrol.py 的 run_patrol 流程一致。仅在巡航中执行云台/拍照动作，非巡航只记录。
 */
class FirePatrolController(
    private val context: Context,
    private val vm: AutomatedFlightVM,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "FirePatrolController"
        private const val TRIGGER = 5
        private const val RELEASE = 15
        private const val GIMBAL_INTERVAL_MS = 1000L
        private const val AIM_ON_FIRE = true
        private const val CAPTURE_ON_FIRE = true

        // 目标选择：优先火类，其次置信度最高（复用，避免每帧新建 Comparator）
        private val TARGET_COMPARATOR = Comparator<FireDetector.Detection> { a, b ->
            val af = if (a.isFire) 1 else 0
            val bf = if (b.isFire) 1 else 0
            if (af != bf) af - bf else a.conf.compareTo(b.conf)
        }
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private val generation = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 上一帧是否仍待 UI 消费；为 true 时丢弃新帧，避免主线程堆积位图
    @Volatile
    private var framePending = false

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        val gen = generation.incrementAndGet()
        thread = Thread({ runLoop(gen) }, "fire-patrol").apply { start() }
    }

    fun stop() {
        running = false
        generation.incrementAndGet()  // 使仍在运行中的旧线程立即失效
        thread?.interrupt()
        thread = null
    }

    fun toggle() {
        if (running) stop() else start()
    }

    private fun runLoop(gen: Int) {
        val detector = FireDetector(context)
        detector.onError = { postStatus("⚠ $it") }
        if (!detector.load()) {
            postStatus("⚠ 火检模型加载失败，请确认 best.onnx 已打包")
            if (generation.get() == gen) running = false
            return
        }
        postStatus("🔥 火检已启动，等待巡航画面...")
        val debouncer = FireDebouncer(TRIGGER, RELEASE)
        var reporter: FireReporter? = null
        var aimer: FireGimbalAimer? = null
        var lastSeq = -1L
        var lastAim = 0L
        var capturedThisAlarm = false
        var loggedThisAlarm = false
        var frameIdx = 0L

        fun ensureReporter(): FireReporter {
            var r = reporter
            if (r == null) {
                r = FireReporter(context)
                reporter = r
            }
            return r
        }

        try {
            while (running && generation.get() == gen) {
                val jpeg = VideoFrameProvider.latestJpeg
                val seq = VideoFrameProvider.frameSeq
                if (jpeg == null || jpeg.isEmpty() || seq == lastSeq) {
                    Thread.sleep(30)
                    continue
                }
                lastSeq = seq

                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                if (aimer == null || aimer.frameW != bmp.width || aimer.frameH != bmp.height) {
                    aimer = FireGimbalAimer(bmp.width, bmp.height)
                }

                val dets = detector.detect(bmp)
                // 目标统一取「优先火类、再取置信度最高」
                val target = dets.maxWithOrNull(TARGET_COMPARATOR)
                val alarmed = debouncer.update(dets.isNotEmpty())
                val cruise = vm.isCruiseActive.value ?: false

                if (alarmed && target != null) {
                    if (cruise) {
                        val pos = vm.currentPosition.value
                        val lat = pos?.latitude
                        val lng = pos?.longitude
                        if (AIM_ON_FIRE) {
                            val now = System.currentTimeMillis()
                            if (now - lastAim >= GIMBAL_INTERVAL_MS) {
                                lastAim = now
                                for ((action, step) in aimer?.aim(target) ?: emptyList()) {
                                    postGimbal(action, step)
                                }
                            }
                        }
                        if (CAPTURE_ON_FIRE && !capturedThisAlarm) {
                            capturedThisAlarm = true
                            postCapture()
                            val annotated = drawOverlay(bmp, dets, true)
                            val rel = ensureReporter().saveFrame(annotated, "alarm_%06d.jpg".format(frameIdx))
                            ensureReporter().log("capture", target.cls, target.conf, target, lat, lng, "火情确认，触发拍照（$rel）")
                        }
                    } else if (!loggedThisAlarm) {
                        loggedThisAlarm = true
                        ensureReporter().log("detect", target.cls, target.conf, target, null, null, "非巡航，仅记录不动作")
                    }
                } else {
                    capturedThisAlarm = false
                    loggedThisAlarm = false
                    if (dets.isNotEmpty() && target != null && frameIdx % 5 == 0L) {
                        ensureReporter().log("detect", target.cls, target.conf, target, null, null, "hit")
                    }
                }
                frameIdx++

                postFrame(bmp, dets, alarmed)
                bmp.recycle()
            }
        } catch (e: InterruptedException) {
            // 正常退出
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "检测内存不足: ${e.message}", e)
            postStatus("⚠ 火检内存不足，已停止：${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "检测循环异常: ${e.message}", e)
        } finally {
            detector.close()
            if (generation.get() == gen) {
                val reportPath = reporter?.writeReport()
                postStatus(if (reportPath != null) "火检已停止，报告：$reportPath" else "火检已停止")
                running = false
            }
        }
    }

    private fun postGimbal(action: String, step: Double) {
        mainHandler.post {
            when (action) {
                "pitch_up" -> vm.gimbalPitchUp(step)
                "pitch_down" -> vm.gimbalPitchDown(step)
                "yaw_left" -> vm.gimbalYawLeft(step)
                "yaw_right" -> vm.gimbalYawRight(step)
            }
        }
    }

    private fun postCapture() {
        mainHandler.post { vm.cameraTakePhoto(fromWeb = false) }
    }

    private fun postFrame(src: Bitmap, dets: List<FireDetector.Detection>, alarmed: Boolean) {
        if (framePending) return  // 上一帧未被 UI 消费，丢弃本帧（调用方负责回收 src）
        framePending = true
        val overlay = drawOverlay(src, dets, alarmed)
        mainHandler.post {
            try {
                onFrame(overlay)
            } finally {
                framePending = false
            }
        }
    }

    private fun postStatus(text: String) {
        mainHandler.post { onStatus(text) }
    }

    private fun drawOverlay(
        src: Bitmap,
        dets: List<FireDetector.Detection>,
        alarmed: Boolean
    ): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 12f
            setShadowLayer(2f, 0f, 1f, Color.BLACK)
        }
        for (d in dets) {
            boxPaint.color = if (d.isFire) Color.RED else Color.rgb(0, 165, 255)
            canvas.drawRect(d.x1, d.y1, d.x2, d.y2, boxPaint)
            canvas.drawText(
                "${d.name} ${"%.2f".format(d.conf)}",
                d.x1,
                (d.y1 - 4).coerceAtLeast(12f),
                textPaint
            )
        }
        if (alarmed) {
            val alarmPaint = Paint().apply {
                color = Color.RED
                textSize = 24f
                setShadowLayer(3f, 0f, 1f, Color.BLACK)
            }
            canvas.drawText("FIRE ALARM", 10f, 34f, alarmPaint)
        }
        return out
    }
}
