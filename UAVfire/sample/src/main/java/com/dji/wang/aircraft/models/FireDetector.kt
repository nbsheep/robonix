package com.dji.wang.aircraft.models

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 端侧火/烟检测：用 ONNX Runtime Mobile 跑 YOLO26s（best.onnx，end2end）。
 *
 * 输入  images [1,3,640,640] float32 NCHW（RGB，归一化到 0-1，letterbox 灰边 114）
 * 输出  output0 [1,300,6]：每行 [x1,y1,x2,y2,score,cls]（640 像素坐标，NMS-free）
 */
class FireDetector(private val context: Context) {

    data class Detection(
        val cls: Int,
        val conf: Float,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    ) {
        val name: String get() = NAMES[cls] ?: "unknown"
        val isFire: Boolean get() = cls == 1
    }

    companion object {
        private const val TAG = "FireDetector"
        private const val ASSET_NAME = "best.onnx"
        private const val INPUT_NAME = "images"
        private const val OUTPUT_NAME = "output0"
        private const val INPUT_SIZE = 640
        private const val CONF_THRESHOLD = 0.35f
        private const val IOU_THRESHOLD = 0.45f
        private const val ERROR_REPORT_INTERVAL_MS = 5000L
        val NAMES = mapOf(0 to "smoke", 1 to "fire")
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var loaded = false

    // 推理输入缓冲区复用，避免每帧分配 3*640*640 的 FloatArray
    private val inputBuffer = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
    private var scaledPixels = IntArray(0)
    private val inputs = HashMap<String, OnnxTensorLike>(1)

    val isLoaded: Boolean get() = loaded

    /** 推理错误回调，供上层透出到状态栏；null 则只打 log。 */
    var onError: ((String) -> Unit)? = null

    private var inferenceFailures = 0
    private var lastErrorReportAt = 0L

    private fun reportInferenceError(e: Exception) {
        inferenceFailures++
        Log.w(TAG, "推理失败: ${e.message}", e)
        val now = System.currentTimeMillis()
        if (now - lastErrorReportAt >= ERROR_REPORT_INTERVAL_MS) {
            lastErrorReportAt = now
            onError?.invoke("火检推理失败：${e.message}（累计 $inferenceFailures 次）")
        }
    }

    fun load(): Boolean {
        if (loaded) return true
        return try {
            env = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
            session = env!!.createSession(bytes, OrtSession.SessionOptions())
            loaded = true
            Log.i(TAG, "ONNX 模型加载成功 (${bytes.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ONNX 模型加载失败: ${e.message}", e)
            close()
            false
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val s = session ?: return emptyList()
        val e = env ?: return emptyList()
        val info = preprocess(bitmap)
        val inputTensor = OnnxTensor.createTensor(
            e, FloatBuffer.wrap(inputBuffer), longArrayOf(1L, 3L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        inputs.clear()
        inputs[INPUT_NAME] = inputTensor
        return try {
            val result = s.run(inputs)
            try {
                val output = result.get(OUTPUT_NAME).orElse(null)
                    ?: throw IllegalStateException("模型输出缺少 $OUTPUT_NAME")
                parseOutput(output.value, info)
            } finally {
                result.close()
            }
        } catch (e: Exception) {
            reportInferenceError(e)
            emptyList()
        } finally {
            inputTensor.close()
        }
    }

    private data class Letterbox(val scale: Float, val dx: Int, val dy: Int, val origW: Int, val origH: Int)

    private fun preprocess(bitmap: Bitmap): Letterbox {
        val w = bitmap.width
        val h = bitmap.height
        val scale = min(INPUT_SIZE.toFloat() / w, INPUT_SIZE.toFloat() / h)
        val newW = max(1, (w * scale).roundToInt())
        val newH = max(1, (h * scale).roundToInt())
        val scaled = if (newW == w && newH == h) bitmap
            else Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val dx = (INPUT_SIZE - newW) / 2
        val dy = (INPUT_SIZE - newH) / 2
        if (scaledPixels.size != newW * newH) scaledPixels = IntArray(newW * newH)
        scaled.getPixels(scaledPixels, 0, newW, 0, 0, newW, newH)
        if (scaled !== bitmap) scaled.recycle()

        val chw = INPUT_SIZE * INPUT_SIZE
        // 灰边：整块填灰，再只覆盖实际图像区域，省掉逐像素边界判断
        inputBuffer.fill(114f / 255f)
        for (y in 0 until newH) {
            val srcRow = y * newW
            val dstRow = (y + dy) * INPUT_SIZE + dx
            for (x in 0 until newW) {
                val p = scaledPixels[srcRow + x]
                val idx = dstRow + x
                inputBuffer[idx] = ((p shr 16) and 0xFF) / 255f
                inputBuffer[chw + idx] = ((p shr 8) and 0xFF) / 255f
                inputBuffer[2 * chw + idx] = (p and 0xFF) / 255f
            }
        }
        return Letterbox(scale, dx, dy, w, h)
    }

    private fun parseOutput(value: Any, info: Letterbox): List<Detection> {
        // output0 形状 [1][300][6]，每行 [x1,y1,x2,y2,score,cls]（640 像素坐标，NMS-free）
        val raw = value as? Array<*> ?: return emptyList()
        val batch = raw[0] as? Array<*> ?: return emptyList()

        val boxes = ArrayList<Detection>()
        for (i in 0 until batch.size) {
            val d = batch[i] as? FloatArray ?: continue
            if (d.size < 6) continue
            val cls = d[5].roundToInt()
            val conf = d[4]
            if (conf < CONF_THRESHOLD) continue
            if (cls !in NAMES) continue
            val x1 = remapX(d[0], info).coerceIn(0f, info.origW.toFloat())
            val y1 = remapY(d[1], info).coerceIn(0f, info.origH.toFloat())
            val x2 = remapX(d[2], info).coerceIn(0f, info.origW.toFloat())
            val y2 = remapY(d[3], info).coerceIn(0f, info.origH.toFloat())
            if (x2 <= x1 || y2 <= y1) continue
            boxes.add(Detection(cls, conf, x1, y1, x2, y2))
        }
        return nms(boxes, IOU_THRESHOLD)
    }

    private fun remapX(v: Float, info: Letterbox): Float = (v - info.dx) / info.scale
    private fun remapY(v: Float, info: Letterbox): Float = (v - info.dy) / info.scale

    private fun nms(boxes: List<Detection>, iouThreshold: Float): List<Detection> {
        if (boxes.isEmpty()) return boxes
        val sorted = boxes.sortedByDescending { it.conf }
        val keep = BooleanArray(sorted.size) { true }
        val result = ArrayList<Detection>()
        for (i in sorted.indices) {
            if (!keep[i]) continue
            result.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                // 只抑制同类重叠框；smoke/fire 常在同一区域重叠，跨类不能互相压掉
                if (keep[j] && sorted[i].cls == sorted[j].cls && iou(sorted[i], sorted[j]) > iouThreshold) keep[j] = false
            }
        }
        return result
    }

    private fun iou(a: Detection, b: Detection): Float {
        val ix1 = max(a.x1, b.x1); val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2); val iy2 = min(a.y2, b.y2)
        val iw = max(0f, ix2 - ix1); val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        if (inter <= 0f) return 0f
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        return inter / (areaA + areaB - inter)
    }

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        // OrtEnvironment 是进程级全局单例，不关闭，否则会影响下一次 load
        env = null
        loaded = false
    }
}
