package com.dji.wang.aircraft.models

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 火情巡检报告：把事件写入 CSV，保存标注截图，结束时生成 HTML。
 * 保存位置：应用外部私有目录 fire_reports/inspection_<时间戳>/。
 */
class FireReporter(private val context: Context) {

    private val baseDir: File
    private val imageDir: File
    private val eventsFile: File
    private val reportFile: File
    private val writer: BufferedWriter
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        baseDir = File(root, "fire_reports/inspection_$ts")
        imageDir = File(baseDir, "images")
        imageDir.mkdirs()
        eventsFile = File(baseDir, "events.csv")
        reportFile = File(baseDir, "report.html")
        if (!eventsFile.exists()) {
            eventsFile.writeText(
                "time,event,class,conf,x1,y1,x2,y2,latitude,longitude,note\n"
            )
        }
        writer = BufferedWriter(FileWriter(eventsFile, true))
    }

    val reportDir: String get() = baseDir.absolutePath

    private fun csvField(v: String): String =
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            "\"" + v.replace("\"", "\"\"") + "\""
        } else v

    @Synchronized
    fun log(
        event: String,
        cls: Int,
        conf: Float,
        box: FireDetector.Detection?,
        lat: Double?,
        lng: Double?,
        note: String = ""
    ) {
        val line = listOf(
            timeFormat.format(Date()),
            event,
            FireDetector.NAMES[cls] ?: "?",
            "%.2f".format(conf),
            box?.let { "%.0f".format(it.x1) } ?: "",
            box?.let { "%.0f".format(it.y1) } ?: "",
            box?.let { "%.0f".format(it.x2) } ?: "",
            box?.let { "%.0f".format(it.y2) } ?: "",
            lat?.let { "%.6f".format(it) } ?: "",
            lng?.let { "%.6f".format(it) } ?: "",
            csvField(note)
        ).joinToString(",")
        try {
            writer.write(line)
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            Log.w("FireReporter", "写事件失败: ${e.message}")
        }
    }

    fun saveFrame(bitmap: Bitmap, name: String): String {
        val f = File(imageDir, name)
        return try {
            FileOutputStream(f).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out) }
            f.absolutePath
        } catch (e: Exception) {
            Log.w("FireReporter", "保存图片失败: ${e.message}")
            ""
        }
    }

    fun writeReport(): String {
        try { writer.flush() } catch (_: Exception) {}
        val events = try {
            eventsFile.readText()
        } catch (_: Exception) {
            ""
        }
        val html = buildString {
            append("<html><head><meta charset='utf-8'><title>火情巡检报告</title></head><body>")
            append("<h2>火情巡检报告</h2>")
            append("<p>生成时间: ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            append("</p><h3>事件记录</h3><pre>")
            append(events)
            append("</pre></body></html>")
        }
        try {
            reportFile.writeText(html)
        } catch (e: Exception) {
            Log.w("FireReporter", "写报告失败: ${e.message}")
        }
        return reportFile.absolutePath
    }
}
