package com.dji.wang.aircraft.models

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

class WebServer(private val port: Int = 8080) {

    interface ActionHandler {
        fun onStart(params: Map<String, Double>): Map<String, Any>
        fun onStop(): Map<String, Any>
        fun onReset(): Map<String, Any>
        fun onGoHome(): Map<String, Any>
        fun onSwitchMode(mode: String): Map<String, Any>
        fun onAddWaypoint(lat: Double, lng: Double, alt: Double): Map<String, Any>
        fun onCaptureCurrentGps(): Map<String, Any>
        fun onClearWaypoints(): Map<String, Any>
        fun onStartCruise(): Map<String, Any>
        fun onPauseCruise(): Map<String, Any>
        fun onResumeCruise(): Map<String, Any>
        fun onManualClimb(delta: Double): Map<String, Any>
        fun onManualMoveLeft(distance: Double): Map<String, Any>
        fun onManualMoveRight(distance: Double): Map<String, Any>
        fun onManualRotate(degrees: Double): Map<String, Any>
        fun onTakeoffHover(): Map<String, Any>
        fun onGimbal(action: String, step: Double): Map<String, Any>
        fun onCamera(action: String): Map<String, Any>
    }

    fun interface StatusProvider {
        fun getStatus(): Map<String, Any>
    }

    var actionHandler: ActionHandler? = null
    var statusProvider: StatusProvider? = null

    private val running = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            serverSocket = ServerSocket()
            serverSocket?.reuseAddress = true
            serverSocket?.bind(InetSocketAddress("0.0.0.0", port), 10)
            running.set(true)
            serverThread = Thread({ acceptLoop() }, "DroneWebServer").apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            Log.e("DroneWebServer", "启动失败: ${e.message}", e)
            false
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
    }

    fun getAllLocalIps(): Pair<List<Pair<String, String>>, List<Pair<String, String>>> {
        val wifi = mutableListOf<Pair<String, String>>()
        val other = mutableListOf<Pair<String, String>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (name.startsWith("wlan") || name.contains("wifi")) wifi.add(name to ip)
                        else other.add(name to ip)
                    }
                }
            }
        } catch (_: Exception) {}
        return wifi to other
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val client = serverSocket?.accept() ?: continue
                Thread({ handleClient(client) }, "WebClient-${client.inetAddress}").apply { isDaemon = true; start() }
            } catch (_: SocketException) {
                if (!running.get()) break
                try { serverSocket?.close() } catch (_: Exception) {}
                try {
                    serverSocket = ServerSocket()
                    serverSocket?.reuseAddress = true
                    serverSocket?.bind(InetSocketAddress("0.0.0.0", port), 10)
                } catch (e: Exception) { try { Thread.sleep(1000) } catch (_: Exception) {} }
            } catch (_: Exception) {}
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
            val writer = OutputStreamWriter(socket.getOutputStream(), "UTF-8")
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]; val path = parts[1]
            var contentLength = 0
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                if (line.lowercase().startsWith("content-length:"))
                    contentLength = line.substring("content-length:".length).trim().toIntOrNull() ?: 0
                line = reader.readLine()
            }
            val body = if (contentLength > 0) { val buf = CharArray(contentLength); reader.read(buf, 0, contentLength); String(buf) } else ""

            // MJPEG视频流 —— 需要保持连接持续推送帧，不能走普通Response
            if (path == "/api/video") {
                handleMjpegStream(socket)
                return
            }

            val response = route(method, path, body)
            writer.write(response.toHttpResponse()); writer.flush()
        } catch (_: SocketException) {} catch (e: Exception) { Log.w("DroneWebServer", "处理异常: ${e.message}") }
        finally { try { socket.close() } catch (_: Exception) {} }
    }

    /** MJPEG multipart 流式推送 */
    private fun handleMjpegStream(socket: Socket) {
        try {
            val out = socket.getOutputStream()
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: multipart/x-mixed-replace; boundary=FRAME\r\n")
                append("Connection: close\r\n")
                append("Cache-Control: no-cache\r\n")
                append("\r\n")
                append("--FRAME\r\n")
            }
            out.write(header.toByteArray())
            out.flush()

            var failCount = 0
            val maxFail = 30
            while (failCount < maxFail) {
                val jpeg = VideoFrameProvider.latestJpeg
                if (jpeg != null && jpeg.isNotEmpty()) {
                    val frame = buildString {
                        append("Content-Type: image/jpeg\r\n")
                        append("Content-Length: ${jpeg.size}\r\n")
                        append("\r\n")
                    }
                    out.write(frame.toByteArray())
                    out.write(jpeg)
                    out.write("\r\n--FRAME\r\n".toByteArray())
                    out.flush()
                    failCount = 0
                } else {
                    failCount++
                }
                try { Thread.sleep(80) } catch (_: Exception) { break }  // ~12fps
            }
        } catch (_: Exception) {}
        finally { try { socket.close() } catch (_: Exception) {} }
    }

    private fun route(method: String, path: String, body: String): Response {
        return when {
            path == "/" || path == "/index.html" -> Response(200, "text/html; charset=utf-8", DASHBOARD_HTML)
            path == "/api/status" -> {
                val s = statusProvider?.getStatus() ?: emptyMap()
                Response(200, "application/json; charset=utf-8", mapToJson(s))
            }
            path == "/api/start" && method == "POST" -> {
                val r = actionHandler?.onStart(parseJsonBody(body)) ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/stop" && method == "POST" -> {
                val r = actionHandler?.onStop() ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/reset" && method == "POST" -> {
                val r = actionHandler?.onReset() ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/gohome" && method == "POST" -> {
                val r = actionHandler?.onGoHome() ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/mode" && method == "POST" -> {
                val mode = extractStringField(body, "mode")
                val r = actionHandler?.onSwitchMode(mode) ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/add_waypoint" && method == "POST" -> {
                val params = parseJsonBody(body)
                val lat = params["latitude"] ?: 0.0
                val lng = params["longitude"] ?: 0.0
                val alt = params["altitude"] ?: 5.0
                val r = actionHandler?.onAddWaypoint(lat, lng, alt) ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/capture_gps" && method == "POST" -> {
                val r = actionHandler?.onCaptureCurrentGps() ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/clear_waypoints" && method == "POST" -> {
                val r = actionHandler?.onClearWaypoints() ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/start_cruise" && method == "POST" -> {
                val r = actionHandler?.onStartCruise() ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/pause_cruise" && method == "POST" -> {
                val r = actionHandler?.onPauseCruise() ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/resume_cruise" && method == "POST" -> {
                val r = actionHandler?.onResumeCruise() ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/takeoff_hover" && method == "POST" -> {
                val r = actionHandler?.onTakeoffHover() ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/manual" && method == "POST" -> {
                val action = extractStringField(body, "action")
                val value = parseJsonBody(body)["value"] ?: 0.0
                val r = when (action) {
                    "climb" -> actionHandler?.onManualClimb(value)
                    "move_left" -> actionHandler?.onManualMoveLeft(value)
                    "move_right" -> actionHandler?.onManualMoveRight(value)
                    "rotate" -> actionHandler?.onManualRotate(value)
                    else -> mapOf("success" to false, "message" to "未知操作: $action")
                } ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/gimbal" && method == "POST" -> {
                val action = extractStringField(body, "action")
                val step = parseJsonBody(body)["step"] ?: 7.5
                val r = actionHandler?.onGimbal(action, step) ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/camera" && method == "POST" -> {
                val action = extractStringField(body, "action")
                val r = actionHandler?.onCamera(action) ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            else -> Response(404, "text/plain; charset=utf-8", "404")
        }
    }

    data class Response(val code: Int, val contentType: String, val body: String) {
        fun toHttpResponse(): String = buildString {
            val st = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
            append("HTTP/1.1 $code $st\r\nContent-Type: $contentType\r\n")
            append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n\r\n$body")
        }
    }

    private fun parseJsonBody(body: String): Map<String, Double> {
        val r = mutableMapOf<String, Double>()
        if (body.isBlank()) return r
        try {
            for (pair in body.trim().removeSurrounding("{", "}").split(",")) {
                val colon = pair.indexOf(":")
                if (colon < 0) continue
                val k = pair.substring(0, colon).trim().removeSurrounding("\"")
                val v = pair.substring(colon + 1).trim().toDoubleOrNull() ?: continue
                r[k] = v
            }
        } catch (_: Exception) {}
        return r
    }

    private fun extractStringField(body: String, field: String): String {
        try {
            val m = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"").find(body)
            return m?.groupValues?.get(1) ?: ""
        } catch (_: Exception) { return "" }
    }

    private fun err(msg: String) = mapOf("success" to false, "message" to msg)

    @Suppress("DEPRECATION")
    private fun mapToJson(map: Map<String, Any>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(","); first = false
            sb.append("\"$k\":")
            appendJsonValue(sb, v)
        }
        sb.append("}")
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun appendJsonValue(sb: StringBuilder, v: Any?) {
        when (v) {
            is String -> sb.append("\"${v.replace("\"", "\\\"")}\"")
            is Number, is Boolean -> sb.append(v)
            is Map<*, *> -> {
                sb.append("{")
                var first = true
                for ((k2, v2) in v) {
                    if (!first) sb.append(","); first = false
                    sb.append("\"$k2\":")
                    appendJsonValue(sb, v2)
                }
                sb.append("}")
            }
            is List<*> -> {
                sb.append("[")
                var first = true
                for (item in v) {
                    if (!first) sb.append(","); first = false
                    appendJsonValue(sb, item)
                }
                sb.append("]")
            }
            else -> sb.append("\"$v\"")
        }
    }

    companion object {
        private val DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>无人机控制台 v4.0</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'PingFang SC','Microsoft YaHei',sans-serif;background:#0b0f1a;color:#e6edf3;height:100vh;display:flex;flex-direction:column}
header{background:#121826;border-bottom:1px solid #1f2a3d;padding:12px 20px;display:flex;flex-direction:column;gap:10px}
.top-row{display:flex;align-items:center;gap:16px;flex-wrap:wrap}
.brand{font-weight:600;font-size:16px;white-space:nowrap}
.brand span{color:#00e5ff}
.chip{padding:7px 14px;border-radius:20px;font-size:15px;background:#0e1420;border:1px solid #1f2a3d;white-space:nowrap}
.chip .dot{display:inline-block;width:9px;height:9px;border-radius:50%;background:#5c6b7d;margin-right:7px}
.chip.ok .dot{background:#00c853}
.alt{margin-left:auto;font-size:32px;font-weight:700;color:#00e5ff;font-variant-numeric:tabular-nums;line-height:1}
.alt small{font-size:15px;color:#8b98a9;font-weight:400}
.feedback{display:flex;align-items:center;gap:18px;flex-wrap:wrap;background:#0e1420;border:1px solid #1f2a3d;border-radius:10px;padding:12px 16px;font-size:20px}
.fb-label{color:#8b98a9;font-size:15px;letter-spacing:1px}
.fb-state{font-weight:700;font-size:22px;color:#ffb300}
.fb-msg{font-size:19px;color:#e6edf3}
.fb-msg b{color:#00e5ff}
main{flex:1;display:grid;grid-template-columns:1.55fr 1fr;gap:16px;padding:16px;min-height:0}
.video-panel{background:#121826;border:1px solid #1f2a3d;border-radius:12px;display:flex;flex-direction:column;overflow:hidden}
.video-head{display:flex;align-items:center;justify-content:space-between;padding:10px 14px;border-bottom:1px solid #1f2a3d;font-size:13px;color:#8b98a9}
.video-head b{color:#e6edf3}
.video-box{flex:1;position:relative;display:flex;align-items:center;justify-content:center;background:#000}
.video-box img{max-width:100%;max-height:100%;object-fit:contain}
.video-box .placeholder{color:#5c6b7d;font-size:14px;text-align:center}
.video-tag{position:absolute;top:10px;left:12px;background:rgba(0,0,0,.55);border:1px solid #1f2a3d;border-radius:6px;padding:4px 10px;font-size:12px;color:#00c853}
.control{background:#121826;border:1px solid #1f2a3d;border-radius:12px;display:flex;flex-direction:column;overflow:hidden;min-height:0}
.tabs{display:grid;grid-template-columns:repeat(3,1fr);gap:6px;padding:12px;border-bottom:1px solid #1f2a3d}
.tab{padding:11px 0;text-align:center;border-radius:8px;background:#0e1420;border:1px solid #1f2a3d;color:#8b98a9;cursor:pointer;font-size:15px;transition:.15s;user-select:none}
.tab.on{background:#00e5ff;color:#04222b;font-weight:600;border-color:#00e5ff}
.tab.disabled{opacity:.4;pointer-events:none}
.scroll{flex:1;overflow-y:auto;padding:14px}
.section{display:none}
.section.on{display:block}
.field{margin-bottom:10px}
.field label{display:block;font-size:13px;color:#8b98a9;margin-bottom:5px}
.field input{width:100%;padding:10px 11px;border-radius:8px;border:1px solid #1f2a3d;background:#0e1420;color:#e6edf3;font-size:15px;font-family:inherit}
.field input::placeholder{color:#555}
.row{display:flex;gap:10px}
.row .field{flex:1;margin-bottom:0}
.btn{width:100%;padding:12px;border-radius:9px;border:none;cursor:pointer;font-size:15px;font-weight:600;font-family:inherit;transition:.12s;background:#0e1420;color:#e6edf3}
.btn:active{transform:scale(.98)}
.btn:disabled{opacity:.4;cursor:not-allowed}
.btn.primary{background:#00e5ff;color:#04222b}
.btn.green{background:#00c853;color:#04220b}
.btn.amber{background:#ffb300;color:#221a04}
.btn.ghost{background:#0e1420;color:#e6edf3;border:1px solid #1f2a3d}
.btn.grp{width:auto;flex:1}
.group-title{font-size:13px;color:#00e5ff;letter-spacing:1px;margin:6px 0 4px}
.divider{height:1px;background:#1f2a3d;margin:12px 0}
.wp-list{background:#0e1420;border:1px solid #1f2a3d;border-radius:8px;padding:8px;max-height:150px;overflow-y:auto;font-size:13px;color:#8b98a9}
.wp-item{display:flex;justify-content:space-between;padding:5px 6px;border-bottom:1px dashed #1f2a3d}
.wp-item:last-child{border-bottom:none}
.wp-progress{color:#00e5ff;padding:6px;font-weight:700}
.empty{text-align:center;color:#5c6b7d;padding:8px;font-size:13px}
.cam-feedback{color:#ffb300;font-size:13px;font-weight:700;text-align:center;margin:8px 0}
.global{border-top:1px solid #1f2a3d;padding:12px 14px;display:flex;gap:10px;background:#0e1420}
.log{border-top:1px solid #1f2a3d;padding:10px 14px;font-size:13px;color:#5c6b7d;max-height:80px;overflow-y:auto;background:#0e1420;font-family:monospace}
.log .line{white-space:nowrap}
.log .info{color:#00c853}.log .warn{color:#ffb300}.log .error{color:#ff5252}
.footer{text-align:center;font-size:11px;color:#5c6b7d;padding:8px}
@media(max-width:900px){main{grid-template-columns:1fr}}
</style>
</head>
<body>
<header>
<div class="top-row">
<div class="brand">🚁 无人机<span>控制台</span></div>
<div class="chip" id="chipSdk"><span class="dot"></span><span id="sdkStatus">--</span></div>
<div class="chip" id="chipDrone"><span class="dot"></span><span id="droneStatus">--</span></div>
<div class="alt"><span id="altitude">0.0</span><small> 米</small></div>
</div>
<div class="feedback">
<span class="fb-label">状态反馈</span>
<span class="fb-state" id="missionState">等待连接</span>
<span class="fb-msg" id="fbMsg"></span>
</div>
</header>

<main>
<section class="video-panel">
<div class="video-head"><b>📷 视频流</b><span>MJPEG / 实时</span></div>
<div class="video-box" id="videoBox"><div class="placeholder">📷 视频流等待连接...</div></div>
</section>

<section class="control">
<div class="tabs" id="tabs">
<div class="tab on" id="tabStandby" onclick="clickMode('STANDBY','STANDBY')">待命</div>
<div class="tab" id="tabCruise" onclick="clickMode('CRUISE','AUTO_CRUISE')">巡航</div>
<div class="tab" id="tabManual" onclick="clickMode('MANUAL','MANUAL')">手动</div>
</div>

<div class="scroll">
<div class="section on" id="secStandby">
<div class="group-title">任务参数</div>
<div class="row">
<div class="field"><label>爬升 (m)</label><input id="inpClimb" type="number" value="1.0" step="0.1" min="0.1" max="50"></div>
<div class="field"><label>平移 (m)</label><input id="inpMove" type="number" value="0.5" step="0.1" min="-10" max="10"></div>
<div class="field"><label>旋转 (°)</label><input id="inpYaw" type="number" value="0" step="5" min="-360" max="360"></div>
</div>
<button class="btn primary" id="btnStart" onclick="startMission()" style="margin-top:12px">🚀 开始任务</button>
</div>

<div class="section" id="secCruise">
<div class="group-title">航点</div>
<button class="btn ghost" onclick="captureCurrentGps()" style="margin-bottom:10px">📍 获取当前GPS</button>
<div class="row">
<div class="field"><label>纬度</label><input id="inpWpLat" type="number" step="0.000001" placeholder="纬度"></div>
<div class="field"><label>经度</label><input id="inpWpLng" type="number" step="0.000001" placeholder="经度"></div>
<div class="field"><label>高度 (m)</label><input id="inpWpAlt" type="number" value="5.0" step="0.1"></div>
</div>
<div class="row" style="margin-top:10px">
<button class="btn ghost grp" onclick="addWaypoint()">＋ 添加航点</button>
<button class="btn ghost grp" onclick="clearWaypoints()">🗑 清空</button>
</div>
<div class="wp-list" id="wpList" style="margin-top:10px"></div>
<button class="btn green" id="btnStartCruise" style="margin-top:10px" onclick="startCruise()">🚀 开始巡航</button>
</div>

<div class="section" id="secGimbalCamera">
<button class="btn" id="btnCruiseToggle" style="background:#ff5252;color:#fff;margin-bottom:12px" onclick="toggleCruise()">⏸ 紧急悬停</button>
<div class="group-title">🎥 云台 &amp; 📷 相机（巡航中）</div>
<div class="field"><label>云台单步角度 (°)</label><input id="inpGimbalStep" type="number" value="7.5" step="0.5" min="0.5" max="180"></div>
<div class="row">
<button class="btn ghost grp" onclick="gimbal('pitch_up')">▲ 上仰</button>
<button class="btn ghost grp" onclick="gimbal('pitch_down')">▼ 下俯</button>
<button class="btn ghost grp" onclick="gimbal('look_down')">⤓ 俯视</button>
<button class="btn ghost grp" onclick="gimbal('level')">➖ 平视</button>
</div>
<div class="row" style="margin-top:8px">
<button class="btn ghost grp" onclick="gimbal('yaw_left')">◀ 左转</button>
<button class="btn ghost grp" onclick="gimbal('yaw_right')">▶ 右转</button>
</div>
<div class="divider"></div>
<div class="cam-feedback" id="cameraFeedback"></div>
<div class="row">
<button class="btn ghost grp" onclick="camera('photo')">📸 拍照</button>
<button class="btn ghost grp" onclick="camera('start_record')">⏺ 录像</button>
<button class="btn ghost grp" onclick="camera('stop_record')">⏹ 停止</button>
</div>
<div class="row" style="margin-top:8px">
<button class="btn ghost grp" onclick="camera('zoom_in')">🔍＋ 放大</button>
<button class="btn ghost grp" onclick="camera('zoom_out')">🔍－ 缩小</button>
</div>
</div>

<div class="section" id="secManual">
<div class="group-title">手动操控（需先悬停）</div>
<button class="btn green" id="btnTakeoffHover" onclick="takeoffHover()">🛫 起飞并悬停</button>
<div class="divider"></div>
<div class="field"><label>升降 ± (m)</label><input id="inpManClimb" type="number" value="1.0" step="0.1"></div>
<button class="btn ghost" onclick="manualCmd('climb')">执行升降</button>
<div class="field" style="margin-top:10px"><label>平移 (m)</label><input id="inpManMove" type="number" value="1.0" step="0.1"></div>
<div class="row">
<button class="btn ghost grp" onclick="manualCmd('move_left')">⬅ 左移</button>
<button class="btn ghost grp" onclick="manualCmd('move_right')">右移 ➡</button>
</div>
<div class="field" style="margin-top:10px"><label>旋转 ± (°)</label><input id="inpManRotate" type="number" value="90" step="5"></div>
<button class="btn ghost" onclick="manualCmd('rotate')">执行旋转</button>
</div>
</div>

<div class="global">
<button class="btn amber grp" id="btnGoHome" onclick="sendCmd('gohome')" style="display:none">🛬 返航降落</button>
<button class="btn ghost grp" id="btnReset" onclick="sendCmd('reset')">🔄 重置</button>
</div>

<div class="log" id="logArea"><div class="line info">系统就绪 v4.0</div></div>
<div class="footer" id="footer">无人机控制台 v4.0 | 在线</div>
</section>
</main>

<script>
var lastState='',pollFail=0,currentMode='STANDBY',lastData=null;
function addLog(m,t){t=t||'info';var l=document.getElementById('logArea'),e=document.createElement('div');e.className='line '+t;var n=new Date();e.textContent='['+n.toTimeString().slice(0,8)+'] '+m;l.insertBefore(e,l.firstChild);while(l.children.length>50)l.removeChild(l.lastChild)}
function setActiveTab(m){
var map={'STANDBY':'tabStandby','AUTO_CRUISE':'tabCruise','MANUAL':'tabManual'};
var tabs=document.querySelectorAll('.tab');
for(var i=0;i<tabs.length;i++)tabs[i].classList.remove('on');
var id=map[m];if(id)document.getElementById(id).classList.add('on');
}
function applyVisibility(d){
if(!d)return;
var isIdle=d.missionState==='IDLE';
var isHover=d.missionState==='HOVERING';
var isRunning=!isIdle&&d.missionState!=='COMPLETED'&&d.missionState!=='ERROR'&&!isHover;
var isFinished=d.missionState==='COMPLETED'||d.missionState==='ERROR';
var ready=d.sdkRegistered&&d.productConnected&&isIdle;
var alt=parseFloat(d.altitude||0);
var tabs=document.querySelectorAll('.tab');
for(var i=0;i<tabs.length;i++)tabs[i].classList.toggle('disabled',!isIdle);
document.getElementById('secStandby').classList.toggle('on',currentMode==='STANDBY'&&isIdle);
document.getElementById('secCruise').classList.toggle('on',currentMode==='AUTO_CRUISE'&&isIdle);
document.getElementById('secManual').classList.toggle('on',currentMode==='MANUAL');
document.getElementById('secGimbalCamera').classList.toggle('on',d.cruiseActive===true);
document.getElementById('btnStart').disabled=!(ready&&currentMode==='STANDBY');
document.getElementById('btnStartCruise').disabled=!(ready&&currentMode==='AUTO_CRUISE'&&(d.waypointCount||0)>0);
var th=document.getElementById('btnTakeoffHover');
if(th){th.disabled=!(ready&&isIdle&&currentMode==='MANUAL');th.style.display=isIdle?'':'none';}
var gh=document.getElementById('btnGoHome');
gh.style.display=(isRunning||isHover||alt>0.5)?'':'none';
var br=document.getElementById('btnReset');
br.style.display=(isIdle||isFinished||alt<=0.1)?'':'none';
var bt=document.getElementById('btnCruiseToggle');
if(bt){var paused=d.cruisePaused===true;bt.textContent=paused?'▶ 重启巡航':'⏸ 紧急悬停';bt.style.background=paused?'#00c853':'#ff5252';}
}
function renderWaypoints(d){
var el=document.getElementById('wpList');
el.innerHTML='';
if(!d.waypoints||d.waypoints.length===0){
var e=document.createElement('div');e.className='empty';e.textContent='尚未添加航点';el.appendChild(e);return;
}
for(var i=0;i<d.waypoints.length;i++){
var w=d.waypoints[i];
var row=document.createElement('div');row.className='wp-item';
var lb=document.createElement('span');lb.textContent=w.label||('#'+(i+1));
var an=document.createElement('span');an.textContent=w.altitude.toFixed(1)+'m';
row.appendChild(lb);row.appendChild(an);el.appendChild(row);
}
if(d.cruiseWaypointIndex>=0&&d.cruiseWaypointIndex<d.waypoints.length){
var p=document.createElement('div');p.className='wp-progress';p.textContent='▶ '+(d.cruiseWaypointIndex+1)+'/'+d.waypointCount;el.appendChild(p);
}
}
function updateUI(d){
lastData=d;pollFail=0;
document.getElementById('footer').textContent='无人机控制台 v4.0 | 在线';
document.getElementById('sdkStatus').textContent='SDK '+(d.sdkRegistered?'已激活':(d.sdkStatusText||'等待'));
document.getElementById('chipSdk').className='chip'+(d.sdkRegistered?' ok':'');
document.getElementById('droneStatus').textContent='无人机 '+(d.productConnected?'已连接':'等待中');
document.getElementById('chipDrone').className='chip'+(d.productConnected?' ok':'');
document.getElementById('altitude').textContent=parseFloat(d.altitude||0).toFixed(1);
var sm={'IDLE':'就绪','TAKEOFF':'正在起飞','CLIMBING':'正在上升','YAW_ROTATE':'正在旋转','MOVE_LEFT':'向左平移','MOVE_RIGHT':'向右平移','HOVERING':'悬停中','LANDING':'正在降落','COMPLETED':'✓ 完成','ERROR':'✗ 错误','CRUISE_TAKEOFF':'巡航起飞','WAYPOINT_YAW':'对准航点','WAYPOINT_FLY':'飞向航点'};
var ms=document.getElementById('missionState');
ms.textContent=sm[d.missionState]||d.missionState;
if(d.missionState==='COMPLETED')ms.style.color='#00c853';
else if(d.missionState==='ERROR')ms.style.color='#ff5252';
else if(d.missionState==='IDLE'||d.missionState==='HOVERING')ms.style.color='#00e5ff';
else ms.style.color='#ffb300';
var fb=d.cruiseActive&&d.cruiseFeedback?d.cruiseFeedback:(d.statusMessage||'');
document.getElementById('fbMsg').textContent=fb;
var cf=document.getElementById('cameraFeedback');
if(cf)cf.textContent=d.cameraFeedback||'';
var vb=document.getElementById('videoBox');
if(d.productConnected&&!document.getElementById('vidStream')){
vb.innerHTML='<span class="video-tag">● LIVE</span><img id="vidStream" src="/api/video" onerror="this.style.display=\'none\'" onload="this.style.display=\'block\'" style="max-width:100%;max-height:100%;object-fit:contain">';
}else if(!d.productConnected){
vb.innerHTML='<div class="placeholder">📷 视频流等待连接...</div>';
}
if(d.operationMode&&d.operationMode!==currentMode){currentMode=d.operationMode;setActiveTab(currentMode);}
renderWaypoints(d);
applyVisibility(d);
if(d.missionState!==lastState){lastState=d.missionState;if(d.statusMessage)addLog(d.statusMessage,d.missionState==='ERROR'?'error':'info')}
}
function clickMode(short,enumName){
if(lastData&&lastData.missionState!=='IDLE'){addLog('飞行中不可切换模式','warn');return;}
currentMode=enumName;setActiveTab(enumName);applyVisibility(lastData);
fetch('/api/mode',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"mode":"'+short+'"}'})
.then(function(r){return r.json()}).then(function(d){if(d.success!==false)addLog('模式: '+short)})
.catch(function(e){addLog('切换失败: '+e.message,'error')})
}
function startMission(){
var c=parseFloat(document.getElementById('inpClimb').value)||1.0,m=parseFloat(document.getElementById('inpMove').value)||0.5,y=parseFloat(document.getElementById('inpYaw').value)||0;
addLog('任务: 爬'+c+'m 移'+m+'m 转'+y+'°','warn');
fetch('/api/start',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"climbHeight":'+c+',"moveDistance":'+m+',"yawAngle":'+y+'}'})
.then(function(r){return r.json()}).then(function(d){addLog(d.message||JSON.stringify(d),d.success!==false?'info':'error')}).catch(function(e){addLog(e.message,'error')})
}
function addWaypoint(){
var lat=parseFloat(document.getElementById('inpWpLat').value),lng=parseFloat(document.getElementById('inpWpLng').value),alt=parseFloat(document.getElementById('inpWpAlt').value)||5.0;
if(isNaN(lat)||isNaN(lng)){addLog('请输入有效经纬度','error');return}
fetch('/api/add_waypoint',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"latitude":'+lat+',"longitude":'+lng+',"altitude":'+alt+'}'})
.then(function(r){return r.json()}).then(function(d){addLog('航点+'+(d.total||'?'));document.getElementById('inpWpLat').value='';document.getElementById('inpWpLng').value=''}).catch(function(e){addLog(e.message,'error')})
}
function clearWaypoints(){fetch('/api/clear_waypoints',{method:'POST'}).then(function(r){return r.json()}).then(function(d){addLog('航点已清空')}).catch(function(e){addLog(e.message,'error')})}
function captureCurrentGps(){fetch('/api/capture_gps',{method:'POST'}).then(function(r){return r.json()}).then(function(d){if(d.success!==false&&d.latitude!=null){document.getElementById('inpWpLat').value=d.latitude.toFixed(6);document.getElementById('inpWpLng').value=d.longitude.toFixed(6);addLog('已填入当前GPS，设置高度后点 添加航点','info')}else{addLog(d.message||'获取失败',d.success!==false?'info':'error')}}).catch(function(e){addLog(e.message,'error')})}
function startCruise(){fetch('/api/start_cruise',{method:'POST'}).then(function(r){return r.json()}).then(function(d){addLog(d.message||JSON.stringify(d))}).catch(function(e){addLog(e.message,'error')})}
function toggleCruise(){
var paused=lastData&&lastData.cruisePaused===true;
var c=paused?'resume_cruise':'pause_cruise';
addLog(paused?'重启巡航...':'紧急悬停：暂停巡航','warn');
fetch('/api/'+c,{method:'POST'}).then(function(r){return r.json()}).then(function(d){addLog(d.message||JSON.stringify(d),d.success!==false?'info':'error')}).catch(function(e){addLog(e.message,'error')})
}
function takeoffHover(){fetch('/api/takeoff_hover',{method:'POST'}).then(function(r){return r.json()}).then(function(d){addLog(d.message||JSON.stringify(d))}).catch(function(e){addLog(e.message,'error')})}
function manualCmd(a){
var v;
if(a==='climb')v=parseFloat(document.getElementById('inpManClimb').value)||1.0;
else if(a==='move_left'||a==='move_right')v=parseFloat(document.getElementById('inpManMove').value)||1.0;
else v=parseFloat(document.getElementById('inpManRotate').value)||90;
addLog('手动: '+a+' '+v,'warn');
fetch('/api/manual',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"action":"'+a+'","value":'+v+'}'})
.then(function(r){return r.json()}).then(function(d){addLog(d.message||JSON.stringify(d))}).catch(function(e){addLog(e.message,'error')})
}
function gimbal(a){
var s=parseFloat(document.getElementById('inpGimbalStep').value)||7.5;
addLog('云台: '+a+' '+s+'°','warn');
fetch('/api/gimbal',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"action":"'+a+'","step":'+s+'}'})
.then(function(r){return r.json()}).then(function(d){addLog(d.message||JSON.stringify(d),d.success!==false?'info':'error')}).catch(function(e){addLog(e.message,'error')})
}
function camera(a){
fetch('/api/camera',{method:'POST',headers:{'Content-Type':'application/json'},body:'{"action":"'+a+'"}'})
.then(function(r){return r.json()}).then(function(d){addLog(d.message||JSON.stringify(d),d.success!==false?'info':'error')}).catch(function(e){addLog(e.message,'error')})
}
function sendCmd(c){
fetch('/api/'+c,{method:'POST'}).then(function(r){return r.json()}).then(function(d){addLog(d.message||JSON.stringify(d))}).catch(function(e){addLog(e.message,'error')})
}
function poll(){fetch('/api/status').then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json()}).then(updateUI).catch(function(e){pollFail++;document.getElementById('footer').textContent='连接失败('+pollFail+'): '+e.message;if(pollFail>5)addLog('⚠ 连续连接失败','error')})}
setInterval(poll,1000);poll();
</script>
</body>
</html>
""".trimIndent()
    }
}
