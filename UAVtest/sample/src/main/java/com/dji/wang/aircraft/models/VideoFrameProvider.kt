package com.dji.wang.aircraft.models

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.io.ByteArrayOutputStream

/**
 * 从无人机摄像头获取解码后的YUV帧，转为JPEG，供Web MJPEG推流使用。
 *
 * 自动监听可用摄像头列表，选取第一个非FPV摄像头作为视频源。
 *
 * 用法:
 *   VideoFrameProvider.start()
 *   // ... 读取 latestJpeg ...
 *   VideoFrameProvider.stop()
 */
object VideoFrameProvider {
    private const val TAG = "VideoFrameProvider"
    private const val JPEG_QUALITY = 60
    private const val MAX_WIDTH = 640

    @Volatile
    var latestJpeg: ByteArray? = null
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    private var frameListener: ICameraStreamManager.CameraFrameListener? = null
    private var availableCameraListener: ICameraStreamManager.AvailableCameraUpdatedListener? = null

    fun start() {
        if (isRunning) return
        try {
            val streamMgr = MediaDataCenter.getInstance().cameraStreamManager

            // 通过监听器获取可用摄像头列表，选择非UNKNOWN摄像头启动帧采集
            availableCameraListener =
                object : ICameraStreamManager.AvailableCameraUpdatedListener {
                    override fun onAvailableCameraUpdated(list: MutableList<ComponentIndexType>) {
                        if (list.isEmpty()) {
                            Log.w(TAG, "可用摄像头列表为空，等待...")
                            return
                        }
                        val cameraIdx = list.firstOrNull { it != ComponentIndexType.UNKNOWN }
                            ?: list[0]
                        Log.i(TAG, "选择摄像头: $cameraIdx")
                        removeAvailableCameraListener()
                        startFrameListener(cameraIdx)
                    }

                    override fun onCameraStreamEnableUpdate(
                        cameraStreamEnableMap: MutableMap<ComponentIndexType, Boolean>
                    ) {}
                }

            streamMgr.addAvailableCameraUpdatedListener(availableCameraListener!!)
            Log.i(TAG, "已注册摄像头可用监听器，等待列表回调...")
        } catch (e: Exception) {
            Log.e(TAG, "启动视频帧采集失败: ${e.message}", e)
            isRunning = false
        }
    }

    private fun startFrameListener(cameraIdx: ComponentIndexType) {
        try {
            val streamMgr = MediaDataCenter.getInstance().cameraStreamManager
            val listener = ICameraStreamManager.CameraFrameListener { data, offset, length, width, height, format ->
                try {
                    // 仅处理NV21格式
                    if (format != ICameraStreamManager.FrameFormat.NV21) {
                        return@CameraFrameListener
                    }

                    val yuvData = if (offset == 0 && length == data.size) data
                    else data.copyOfRange(offset, offset + length)

                    val jpeg = yuvToJpeg(yuvData, ImageFormat.NV21, width, height)
                    if (jpeg != null) {
                        latestJpeg = jpeg
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "帧处理异常: ${e.message}")
                }
            }

            frameListener = listener
            streamMgr.addFrameListener(
                cameraIdx,
                ICameraStreamManager.FrameFormat.NV21,
                listener
            )
            isRunning = true
            Log.i(TAG, "视频帧采集已启动 (摄像头: $cameraIdx)")
        } catch (e: Exception) {
            Log.e(TAG, "添加帧监听器失败: ${e.message}", e)
            isRunning = false
        }
    }

    private fun removeAvailableCameraListener() {
        availableCameraListener?.let {
            try {
                MediaDataCenter.getInstance().cameraStreamManager
                    .removeAvailableCameraUpdatedListener(it)
            } catch (_: Exception) {}
        }
        availableCameraListener = null
    }

    fun stop() {
        if (!isRunning) return
        try {
            frameListener?.let {
                MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(it)
            }
        } catch (_: Exception) {}
        removeAvailableCameraListener()
        frameListener = null
        latestJpeg = null
        isRunning = false
        Log.i(TAG, "视频帧采集已停止")
    }

    /** YUV(NV21) → JPEG，缩放到MAX_WIDTH */
    private fun yuvToJpeg(yuvData: ByteArray, format: Int, width: Int, height: Int): ByteArray? {
        return try {
            val yuvImage = YuvImage(yuvData, format, width, height, null)
            val jpegBos = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, jpegBos)
            val jpegBytes = jpegBos.toByteArray()
            jpegBos.close()

            if (width > MAX_WIDTH) {
                val scale = MAX_WIDTH.toFloat() / width
                val newW = MAX_WIDTH
                val newH = (height * scale).toInt()
                val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
                if (bmp != null) {
                    val scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true)
                    bmp.recycle()
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    scaled.recycle()
                    return out.toByteArray().also { out.close() }
                }
            }
            jpegBytes
        } catch (e: Exception) {
            Log.w(TAG, "YUV→JPEG转换失败: ${e.message}")
            null
        }
    }
}
