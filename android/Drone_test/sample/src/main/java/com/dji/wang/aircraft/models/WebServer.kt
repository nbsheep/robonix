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
        fun onClearWaypoints(): Map<String, Any>
        fun onStartCruise(): Map<String, Any>
        fun onManualClimb(delta: Double): Map<String, Any>
        fun onManualMoveLeft(distance: Double): Map<String, Any>
        fun onManualMoveRight(distance: Double): Map<String, Any>
        fun onManualMoveForward(distance: Double): Map<String, Any>
        fun onManualMoveBackward(distance: Double): Map<String, Any>
        fun onManualRotate(degrees: Double): Map<String, Any>
        fun onTakeoffHover(): Map<String, Any>
        fun onGimbalRotate(pitch: Double, roll: Double, yaw: Double): Map<String, Any>
        fun onCameraCapture(): Map<String, Any>
        fun onCameraZoom(factor: Double): Map<String, Any>
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

            // MJPEG video stream - keep connection open for frame push
            if (path == "/api/video") {
                handleMjpegStream(socket)
                return
            }

            val response = route(method, path, body)
            writer.write(response.toHttpResponse()); writer.flush()
        } catch (_: SocketException) {} catch (e: Exception) { Log.w("DroneWebServer", "处理异常: ${e.message}") }
        finally { try { socket.close() } catch (_: Exception) {} }
    }

    /** MJPEG multipart stream */
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
            path == "/api/clear_waypoints" && method == "POST" -> {
                val r = actionHandler?.onClearWaypoints() ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/start_cruise" && method == "POST" -> {
                val r = actionHandler?.onStartCruise() ?: err("未初始化")
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
                    "move_forward" -> actionHandler?.onManualMoveForward(value)
                    "move_backward" -> actionHandler?.onManualMoveBackward(value)
                    "rotate" -> actionHandler?.onManualRotate(value)
                    else -> mapOf("success" to false, "message" to "未知操作: $action")
                } ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/gimbal" && method == "POST" -> {
                val p = parseJsonBody(body)
                val r = actionHandler?.onGimbalRotate(
                    p["pitch"] ?: 0.0, p["roll"] ?: 0.0, p["yaw"] ?: 0.0
                ) ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/camera/capture" && method == "POST" -> {
                val r = actionHandler?.onCameraCapture() ?: err("未初始化")
                Response(200, "application/json; charset=utf-8", mapToJson(r))
            }
            path == "/api/camera/zoom" && method == "POST" -> {
                val factor = parseJsonBody(body)["factor"] ?: 1.0
                val r = actionHandler?.onCameraZoom(factor) ?: err("未初始化")
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
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,user-scalable=no">
<title>M3E | 控制台</title>
<style>
:root{
--bg:#060d18;--panel:#0c1628;--card:#111d33;
--cyan:#00c6ff;--green:#00e676;--amber:#ffab00;--red:#ff3d5a;
--t1:#e0e8f0;--t2:#5a6d8a;--t3:#3a4d6a;
--b1:rgba(0,180,255,.12);--b2:rgba(0,180,255,.22);
--r:16px;--rs:10px;
--font:'PingFang SC','Microsoft YaHei',-apple-system,sans-serif;
--mono:'JetBrains Mono','Cascadia Code','SF Mono',monospace;
}

*{margin:0;padding:0;box-sizing:border-box}

body{
font-family:var(--font);background:var(--bg);color:var(--t1);
min-height:100vh;overflow-x:hidden;
background-image:
  radial-gradient(ellipse 600px 400px at 15% 0%,rgba(0,140,255,.05) 0%,transparent 70%),
  radial-gradient(ellipse 500px 350px at 85% 100%,rgba(0,200,150,.04) 0%,transparent 70%),
  radial-gradient(ellipse 400px 300px at 50% 40%,rgba(0,120,220,.03) 0%,transparent 70%);
}

/* grid */
body::before{
content:'';position:fixed;inset:0;pointer-events:none;z-index:0;opacity:.12;
background-image:linear-gradient(rgba(0,170,255,.05) 1px,transparent 1px),
  linear-gradient(90deg,rgba(0,170,255,.05) 1px,transparent 1px);
background-size:52px 52px;
}

/* ── HEADER ── */
.header{
display:flex;align-items:center;justify-content:space-between;
padding:0 20px;height:50px;
background:rgba(10,20,38,.96);border-bottom:1px solid var(--b1);
position:sticky;top:0;z-index:100;
backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);
}
.header-l{display:flex;align-items:center;gap:10px}
.header-l .logo{font-size:24px;line-height:1}
.header-l h1{font-size:15px;font-weight:700;color:#fff;letter-spacing:.5px}
.header-l h1 em{font-style:normal;color:var(--cyan);font-weight:800}
.header-r{display:flex;align-items:center;gap:10px;font-size:11px;color:var(--t2)}
.dot{display:inline-block;width:9px;height:9px;border-radius:50%;transition:all .4s}
.dot.on{background:var(--green);box-shadow:0 0 10px var(--green)}
.dot.off{background:var(--red);box-shadow:0 0 6px var(--red)}
.dot.warn{background:var(--amber);animation:pulse 1.2s infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.35}}

/* ── MAIN LAYOUT ── */
.main{
display:grid;grid-template-columns:1fr 390px;gap:14px;
padding:14px 20px;max-width:1540px;margin:0 auto;position:relative;z-index:1
}

/* ── VIDEO ── */
.vid-panel{
position:relative;border-radius:var(--r);overflow:hidden;
background:#000;border:1px solid rgba(0,190,255,.1);
box-shadow:0 0 60px rgba(0,150,240,.04),inset 0 0 80px rgba(0,0,0,.4);
}
.vid-panel .inner{position:relative;aspect-ratio:16/9;min-height:320px}
.vid-panel img{width:100%;height:100%;object-fit:contain;display:block}
.vid-panel .no-vid{
position:absolute;inset:0;display:flex;flex-direction:column;
align-items:center;justify-content:center;color:var(--t2);gap:14px
}
.vid-panel .no-vid .ico{font-size:60px;opacity:.3}
.vid-panel .no-vid span{font-size:13px;opacity:.5}
.vid-badge{
position:absolute;top:12px;left:12px;z-index:3;display:none;
align-items:center;gap:6px;padding:5px 12px;border-radius:20px;
background:rgba(0,0,0,.8);border:1px solid rgba(255,40,70,.35);
font-size:10px;font-weight:700;color:#fff;letter-spacing:.8px;
}
.vid-badge::before{content:'';width:7px;height:7px;border-radius:50%;background:var(--red)}
.vid-badge.live{display:flex}
.vid-badge.live::before{animation:pulse .8s infinite}

/* ── TELEMETRY ── */
.telem{display:grid;grid-template-columns:repeat(5,1fr);gap:10px;margin-top:12px}
.tcard{
background:linear-gradient(180deg,rgba(16,28,50,.75),rgba(10,20,40,.85));
border:1px solid var(--b1);border-radius:var(--rs);padding:14px 10px;
text-align:center;transition:all .25s;position:relative;overflow:hidden;
}
.tcard::after{
content:'';position:absolute;top:0;left:0;right:0;height:1px;
background:linear-gradient(90deg,transparent,var(--cyan),transparent);
opacity:0;transition:opacity .3s;
}
.tcard:hover::after{opacity:1}
.tcard:hover{border-color:var(--b2);transform:translateY(-2px);box-shadow:0 8px 25px rgba(0,0,0,.3)}
.tcard .tl{font-size:9px;color:var(--t2);text-transform:uppercase;letter-spacing:1.5px;margin-bottom:4px;font-weight:700}
.tcard .tv{font-size:24px;font-weight:700;color:#fff;line-height:1.1}
.tcard .tv.cn{color:var(--cyan)}
.tcard .tv.gn{color:var(--green)}
.tcard .tv.am{color:var(--amber)}
.tcard .tu{font-size:10px;color:var(--t2);font-weight:400}

/* ── RIGHT PANEL ── */
.rp{display:flex;flex-direction:column;gap:10px}

.card{
background:rgba(12,22,42,.75);border:1px solid var(--b1);
border-radius:var(--r);padding:15px;
backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);
}
.card h3{
font-size:10px;color:var(--t2);text-transform:uppercase;
letter-spacing:2px;margin-bottom:11px;font-weight:700;
display:flex;align-items:center;gap:8px;
}
.card h3::after{content:'';flex:1;height:1px;background:linear-gradient(90deg,var(--b2),transparent)}

/* ── STATUS ── */
.sgrid{display:grid;grid-template-columns:1fr 1fr;gap:6px}
.sitem{
background:rgba(14,32,56,.5);border:1px solid rgba(255,255,255,.03);
border-radius:var(--rs);padding:10px 12px;text-align:center;transition:all .2s;
}
.sitem:hover{background:rgba(18,40,66,.65)}
.sitem .sl{font-size:9px;color:var(--t2);margin-bottom:3px;font-weight:500}
.sitem .sv{font-size:14px;font-weight:700}
.sv.ok{color:var(--green)}.sv.wa{color:var(--amber)}.sv.err{color:var(--red)}

/* ── MODE TABS ── */
.mtabs{display:flex;gap:3px;margin-bottom:11px;background:rgba(6,16,34,.7);border-radius:22px;padding:3px}
.mtab{
flex:1;padding:8px 4px;border:none;border-radius:20px;
font-size:10px;font-weight:700;cursor:pointer;transition:all .25s;
background:transparent;color:var(--t2);letter-spacing:.4px;
font-family:var(--font);
}
.mtab:hover{color:#ced8e8}
.mtab.on{
background:linear-gradient(135deg,#00b4e8,#0078c0);
color:#fff;box-shadow:0 2px 14px rgba(0,180,235,.4);
}
.msec{margin-top:2px}

/* ── BUTTONS ── */
.brow{display:flex;gap:5px;flex-wrap:wrap}
button{
padding:8px 13px;border:none;border-radius:20px;font-size:10px;
font-weight:700;cursor:pointer;transition:all .2s;color:#fff;
letter-spacing:.4px;font-family:var(--font);
background:rgba(18,36,60,.8);border:1px solid rgba(255,255,255,.05);
}
button:hover{transform:translateY(-1px);filter:brightness(1.15)}
button:active{transform:scale(.95)}
button:disabled{opacity:.22;cursor:not-allowed;filter:grayscale(.3);transform:none!important}

.btn-go{background:linear-gradient(135deg,#00c853,#008838);border:1px solid rgba(0,200,80,.3);box-shadow:0 2px 12px rgba(0,180,60,.2)}
.btn-go:hover{box-shadow:0 4px 22px rgba(0,210,70,.4)}
.btn-cruise{background:linear-gradient(135deg,#e87a00,#b85a00);border:1px solid rgba(230,120,0,.3);box-shadow:0 2px 12px rgba(220,110,0,.2)}
.btn-cruise:hover{box-shadow:0 4px 22px rgba(240,130,0,.4)}
.btn-land{background:linear-gradient(135deg,#e83050,#b81a38);border:1px solid rgba(230,40,70,.3);box-shadow:0 2px 12px rgba(220,30,60,.2)}
.btn-land:hover{box-shadow:0 4px 22px rgba(255,40,70,.4)}
.btn-hover{background:linear-gradient(135deg,#e89600,#c47000);color:#000;box-shadow:0 2px 12px rgba(230,150,0,.2)}
.btn-act{background:linear-gradient(135deg,#1e6fd9,#1450a0);border:1px solid rgba(30,110,210,.3);box-shadow:0 2px 12px rgba(25,100,200,.2)}
.btn-warn{background:linear-gradient(135deg,#c05020,#903010);border:1px solid rgba(190,70,20,.3);box-shadow:0 2px 12px rgba(180,60,10,.2)}
.btn-ghost{background:rgba(20,40,66,.7);font-size:9px;padding:5px 10px;border:1px solid rgba(255,255,255,.06)}
.btn-ghost:hover{background:rgba(28,52,82,.85);border-color:rgba(255,255,255,.15)}

/* ── INPUTS ── */
.irow{display:flex;align-items:center;gap:5px;margin-bottom:5px;flex-wrap:wrap}
.irow label{font-size:10px;color:var(--t2);min-width:30px;text-align:right;font-weight:500}
.irow input{
width:58px;padding:7px 3px;text-align:center;outline:none;font-family:var(--font);
background:rgba(8,20,40,.7);border:1px solid rgba(255,255,255,.06);
border-radius:18px;color:var(--cyan);font-size:13px;font-weight:700;
transition:all .2s;
}
.irow input:focus{border-color:var(--cyan);box-shadow:0 0 0 3px rgba(0,190,255,.08)}
.irow .unit{font-size:9px;color:var(--t2)}

.wprow{display:grid;grid-template-columns:1fr 1fr .55fr .3fr;gap:4px;margin-bottom:6px}
.wprow input{
background:rgba(8,20,40,.7);border:1px solid rgba(255,255,255,.06);
border-radius:var(--rs);color:var(--cyan);font-size:10px;padding:7px 4px;
text-align:center;outline:none;transition:all .2s;font-family:var(--font);
}
.wprow input:focus{border-color:var(--cyan);box-shadow:0 0 0 3px rgba(0,190,255,.06)}
.wprow input::placeholder{color:var(--t3)}
.wplist{
background:rgba(4,10,24,.8);border:1px solid rgba(255,255,255,.03);
border-radius:var(--rs);padding:8px;min-height:32px;max-height:76px;
overflow-y:auto;font-size:9px;color:var(--t2);font-family:var(--mono);
margin-bottom:4px;white-space:pre-wrap;line-height:1.5;
}

/* ── LOG ── */
.logbox{
background:rgba(2,8,20,.88);border:1px solid rgba(255,255,255,.04);
border-radius:var(--rs);padding:10px;height:105px;overflow-y:auto;
font-size:9px;font-family:var(--mono);line-height:1.65;
}
.le{padding:1px 0;border-bottom:1px solid rgba(255,255,255,.012)}
.le.info{color:#00cc66}.le.warn{color:var(--amber)}.le.error{color:var(--red)}.le.dim{color:var(--t3)}

/* ── FOOTER ── */
.ft{
text-align:center;padding:10px;font-size:9px;color:var(--t2);
border-top:1px solid rgba(255,255,255,.03);margin-top:14px;
letter-spacing:.5px;position:relative;z-index:1;
}

/* ── SCROLLBAR ── */
::-webkit-scrollbar{width:4px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:rgba(255,255,255,.05);border-radius:3px}
::-webkit-scrollbar-thumb:hover{background:rgba(255,255,255,.1)}

@media(max-width:940px){
.main{grid-template-columns:1fr;padding:10px 14px}
.telem{grid-template-columns:repeat(3,1fr)}
.header{padding:0 14px}
}
@media(max-width:560px){
.telem{grid-template-columns:repeat(2,1fr)}
}
</style>
</head>
<body>

<div class="header">
<div class="header-l">
<span class="logo">&#128760;</span>
<h1>M3E <em>控制台</em></h1>
</div>
<div class="header-r">
<span class="dot off" id="connDot"></span>
<span id="connText" style="font-weight:500">未连接</span>
<span style="opacity:.3">|</span>
<span id="connTime">--:--:--</span>
</div>
</div>

<div class="main">

<!-- ═══ LEFT: Video + Telemetry ═══ -->
<div>

<div class="vid-panel">
<div class="inner" id="videoPanel">
<div class="no-vid" id="noVideo">
<span class="ico">&#128247;</span>
<span>等待无人机连接...</span>
</div>
<div class="vid-badge" id="videoOverlay">LIVE</div>
</div>
</div>

<div class="telem">
<div class="tcard"><div class="tl">高度 ALT</div><div class="tv cn" id="tAlt">0.0<span class="tu">m</span></div></div>
<div class="tcard"><div class="tl">GPS 纬度</div><div class="tv" id="tLat" style="font-size:15px">--</div></div>
<div class="tcard"><div class="tl">GPS 经度</div><div class="tv" id="tLng" style="font-size:15px">--</div></div>
<div class="tcard"><div class="tl">电量</div><div class="tv gn" id="tBat">--<span class="tu">%</span></div></div>
<div class="tcard"><div class="tl">任务</div><div class="tv am" id="tMission" style="font-size:14px">就绪</div></div>
</div>

</div>

<!-- ═══ RIGHT: Controls ═══ -->
<div class="rp">

<div class="card">
<h3>系统状态</h3>
<div class="sgrid">
<div class="sitem"><div class="sl">SDK 注册</div><div class="sv" id="sdkStatus">--</div></div>
<div class="sitem"><div class="sl">无人机</div><div class="sv" id="droneStatus">--</div></div>
<div class="sitem"><div class="sl">虚拟摇杆</div><div class="sv" id="vsStatus">--</div></div>
<div class="sitem"><div class="sl">飞行模式</div><div class="sv" id="flightMode">--</div></div>
</div>
</div>

<div class="card">
<h3>操作模式</h3>
<div class="mtabs">
<button class="mtab on" id="tabStandby" onclick="onMode('STANDBY')">待命</button>
<button class="mtab" id="tabCruise" onclick="onMode('CRUISE')">巡航</button>
<button class="mtab" id="tabManual" onclick="onMode('MANUAL')">手动</button>
</div>

<div class="msec" id="secStandby">
<div class="irow">
<label>爬升</label><input id="inpClimb" type="number" value="1.0" step="0.1" min="0.1"><span class="unit">m</span>
<label>平移</label><input id="inpMove" type="number" value="0.5" step="0.1"><span class="unit">m</span>
<label>旋转</label><input id="inpYaw" type="number" value="0" step="5"><span class="unit">&deg;</span>
</div>
<div class="brow"><button class="btn-go" id="btnStart" onclick="startMission()">开始任务</button></div>
</div>

<div class="msec" id="secCruise" style="display:none">
<div class="wprow">
<input id="inpWpLat" type="number" placeholder="纬度" step="0.000001">
<input id="inpWpLng" type="number" placeholder="经度" step="0.000001">
<input id="inpWpAlt" type="number" value="5.0" placeholder="高度" step="0.1">
<button class="btn-ghost" onclick="addWaypoint()">+</button>
</div>
<div class="wplist" id="wpList">尚未添加航点</div>
<div class="brow">
<button class="btn-ghost" onclick="clearWaypoints()">清空</button>
<button class="btn-cruise" id="btnStartCruise" onclick="startCruise()">开始巡航</button>
</div>
</div>

<div class="msec" id="secManual" style="display:none">
<div class="brow" style="margin-bottom:6px">
<button class="btn-go" id="btnTakeoffHover" onclick="takeoffHover()">起飞悬停</button>
</div>
<div class="irow">
<label>升降</label><input id="inpManClimb" type="number" value="1.0" step="0.1"><span class="unit">m</span>
<button class="btn-act" id="btnManClimb" onclick="manCmd('climb')">执行</button>
</div>
<div class="irow">
<label>平移</label><input id="inpManMove" type="number" value="1.0" step="0.1"><span class="unit">m</span>
<button class="btn-act" id="btnManMoveL" onclick="manCmd('move_left')">左</button>
<button class="btn-act" id="btnManMoveR" onclick="manCmd('move_right')">右</button>
</div>
<div class="irow">
<label>旋转</label><input id="inpManRotate" type="number" value="90" step="5"><span class="unit">&deg;</span>
<button class="btn-warn" id="btnManRotate" onclick="manCmd('rotate')">执行</button>
</div>
</div>
</div>

<div class="card">
<h3>安全</h3>
<div class="brow">
<button class="btn-hover" id="btnStop" onclick="send('stop')">紧急悬停</button>
<button class="btn-land" id="btnGoHome" onclick="send('gohome')" style="display:none">返航降落</button>
<button class="btn-ghost" id="btnReset" onclick="send('reset')">重置</button>
</div>
</div>

<div class="card">
<h3>日志</h3>
<div class="logbox" id="logArea"><div class="le info">就绪</div></div>
</div>

</div>

</div>

<div class="ft">M3E Drone Console &middot; <span id="ftStatus">在线</span></div>

<script>
/* ============================================================
   M3E Dashboard JS — 精简版
   ============================================================ */
var currentMode='STANDBY',lastState='',pollFail=0,MAX_FAIL=8;

function L(m,t){
t=t||'info';var b=document.getElementById('logArea');
var e=document.createElement('div');e.className='le '+t;
var n=new Date();e.textContent=n.toTimeString().slice(0,8)+' '+m;
b.insertBefore(e,b.firstChild);while(b.children.length>100)b.removeChild(b.lastChild);
}

function setConn(ok){
var d=document.getElementById('connDot'),x=document.getElementById('connText');
if(ok){d.className='dot on';x.textContent='已连接';pollFail=0}
else{d.className='dot off';x.textContent='未连接'}
document.getElementById('connTime').textContent=new Date().toTimeString().slice(0,8);
}

function selTabs(){
var tabs=document.querySelectorAll('.mtab');
for(var i=0;i<tabs.length;i++)tabs[i].classList.remove('on');
if(currentMode==='STANDBY')document.getElementById('tabStandby').classList.add('on');
else if(currentMode==='AUTO_CRUISE')document.getElementById('tabCruise').classList.add('on');
else if(currentMode==='MANUAL')document.getElementById('tabManual').classList.add('on');
}

function api(path,opt){
var u=path,o=opt||{};
if(!o.method)o.method='GET';
if(o.body){o.body=JSON.stringify(o.body);o.headers=o.headers||{};o.headers['Content-Type']='application/json'}
return fetch(u,o).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json()});
}

function updateUI(d){
pollFail=0;setConn(true);document.getElementById('ftStatus').textContent='在线';

var se=document.getElementById('sdkStatus');
se.textContent=d.sdkRegistered?'已激活':(d.sdkStatusText||'等待');
se.className='sv '+(d.sdkRegistered?'ok':'wa');

var de=document.getElementById('droneStatus');
de.textContent=d.productConnected?'已连接':'等待中';
de.className='sv '+(d.productConnected?'ok':'wa');

var ve=document.getElementById('vsStatus');
ve.textContent=d.vsEnabled?'已启用':'未启用';
ve.className='sv '+(d.vsEnabled?'ok':'');

document.getElementById('flightMode').textContent=d.operationMode||'--';

/* telemetry */
document.getElementById('tAlt').innerHTML=parseFloat(d.altitude||0).toFixed(1)+'<span class="tu">m</span>';

var lat='--',lng='--';
if(d.waypoints&&d.waypoints.length>0){
var w=d.waypoints[d.waypoints.length-1];lat=w.latitude.toFixed(5);lng=w.longitude.toFixed(5);
}
document.getElementById('tLat').textContent=lat;
document.getElementById('tLng').textContent=lng;
document.getElementById('tBat').innerHTML=(d.batteryPercent||'--')+'<span class="tu">%</span>';

var sm={
'IDLE':'就绪','TAKEOFF':'起飞中','CLIMBING':'上升','YAW_ROTATE':'旋转中',
'MOVE_LEFT':'左移','MOVE_RIGHT':'右移','HOVERING':'悬停','LANDING':'降落',
'COMPLETED':'完成','ERROR':'错误','CRUISE_TAKEOFF':'巡航起飞','WAYPOINT_YAW':'对准航点','WAYPOINT_FLY':'飞往航点'
};
var ms=document.getElementById('tMission'),mst=d.missionState,msv=sm[mst]||mst||'--';
ms.textContent=msv;

if(mst==='COMPLETED'){ms.style.color='var(--green)';ms.className='tv gn'}
else if(mst==='ERROR'){ms.style.color='var(--red)';ms.className='tv'}
else if(mst==='IDLE'||mst==='HOVERING'){ms.style.color='var(--cyan)';ms.className='tv cn'}
else{ms.style.color='var(--amber)';ms.className='tv am'}

/* video */
var vp=document.getElementById('videoPanel'),noV=document.getElementById('noVideo'),vO=document.getElementById('videoOverlay');
if(d.productConnected&&!document.getElementById('vidStream')){
noV.style.display='none';vO.classList.add('live');
var img=document.createElement('img');img.id='vidStream';img.src='/api/video';
img.onerror=function(){this.style.display='none'};
img.onload=function(){this.style.display='block'};
img.style.width='100%';img.style.height='100%';img.style.objectFit='contain';
vp.appendChild(img);
}else if(!d.productConnected){
var vid=document.getElementById('vidStream');if(vid){vid.remove();noV.style.display='flex';vO.classList.remove('live')}
}

/* mode */
if(d.operationMode&&d.operationMode!==currentMode){currentMode=d.operationMode;selTabs()}

/* button states */
var alt=parseFloat(d.altitude||0),isIdle=mst==='IDLE',isHover=mst==='HOVERING';
var isRun=!isIdle&&mst!=='COMPLETED'&&mst!=='ERROR'&&!isHover;
var isDone=mst==='COMPLETED'||mst==='ERROR';
var ready=d.sdkRegistered&&d.productConnected&&isIdle;

document.getElementById('secStandby').style.display=(currentMode==='STANDBY'&&isIdle)?'':'none';
document.getElementById('secCruise').style.display=(currentMode==='AUTO_CRUISE'&&isIdle)?'':'none';
document.getElementById('secManual').style.display=(currentMode==='MANUAL')?'':'none';

/* waypoints */
var wpEl=document.getElementById('wpList');
if(d.waypoints&&d.waypoints.length>0){
var lines='';
for(var i=0;i<d.waypoints.length;i++){
var w=d.waypoints[i];lines+=w.label||('WP'+(i+1)+': '+w.latitude.toFixed(6)+','+w.longitude.toFixed(6)+' @'+w.altitude.toFixed(1)+'m');
if(i<d.waypoints.length-1)lines+='\n';
}
wpEl.textContent=lines;
if(d.cruiseWaypointIndex>=0&&d.cruiseWaypointIndex<d.waypoints.length)wpEl.textContent+='\n\n> '+(d.cruiseWaypointIndex+1)+'/'+d.waypointCount;
}else{wpEl.textContent='尚未添加航点'}

document.getElementById('btnStart').disabled=!(ready&&currentMode==='STANDBY');
document.getElementById('btnStartCruise').disabled=!(ready&&currentMode==='AUTO_CRUISE'&&(d.waypointCount||0)>0);
var th=document.getElementById('btnTakeoffHover');if(th){th.disabled=!(ready&&isIdle&&currentMode==='MANUAL');th.style.display=isIdle?'':'none'}
var gh=document.getElementById('btnGoHome');gh.style.display=(isRun||isHover||alt>0.5)?'':'none';gh.disabled=!(isRun||isHover||alt>0.5);
var br=document.getElementById('btnReset');br.style.display=(isIdle||isDone||alt<=0.1)?'':'none';br.disabled=!(isIdle||isDone||alt<=0.1);

if(mst!==lastState){lastState=mst;if(d.statusMessage)L(d.statusMessage,mst==='ERROR'?'error':'info')}
}

/* ── actions ── */
function onMode(m){
api('/api/mode',{method:'POST',body:{mode:m}}).then(function(d){
if(d.success!==false)L('模式切换: '+m);
}).catch(function(e){L('切换失败: '+e.message,'error')});
}

function startMission(){
var c=parseFloat(document.getElementById('inpClimb').value)||1.0;
var m=parseFloat(document.getElementById('inpMove').value)||0.5;
var y=parseFloat(document.getElementById('inpYaw').value)||0;
L('任务启动: 爬升'+c+'m 平移'+m+'m 旋转'+y+'deg','warn');
api('/api/start',{method:'POST',body:{climbHeight:c,moveDistance:m,yawAngle:y}})
.then(function(d){L(d.message||JSON.stringify(d),d.success!==false?'info':'error')})
.catch(function(e){L('失败: '+e.message,'error')});
}

function addWaypoint(){
var lat=parseFloat(document.getElementById('inpWpLat').value);
var lng=parseFloat(document.getElementById('inpWpLng').value);
var alt=parseFloat(document.getElementById('inpWpAlt').value)||5.0;
if(isNaN(lat)||isNaN(lng)){L('请输入有效的经纬度','error');return}
api('/api/add_waypoint',{method:'POST',body:{latitude:lat,longitude:lng,altitude:alt}})
.then(function(d){
L('航点 + (总计:'+(d.total||'?')+')');
document.getElementById('inpWpLat').value='';document.getElementById('inpWpLng').value='';
}).catch(function(e){L('失败: '+e.message,'error')});
}

function clearWaypoints(){
api('/api/clear_waypoints',{method:'POST'}).then(function(d){L('航点已清空')}).catch(function(e){L(e.message,'error')});
}

function startCruise(){
api('/api/start_cruise',{method:'POST'}).then(function(d){L(d.message||JSON.stringify(d))}).catch(function(e){L(e.message,'error')});
}

function takeoffHover(){
api('/api/takeoff_hover',{method:'POST'}).then(function(d){L(d.message||JSON.stringify(d))}).catch(function(e){L(e.message,'error')});
}

function manCmd(a){
var v;
if(a==='climb')v=parseFloat(document.getElementById('inpManClimb').value)||1.0;
else if(a==='move_left'||a==='move_right')v=parseFloat(document.getElementById('inpManMove').value)||1.0;
else v=parseFloat(document.getElementById('inpManRotate').value)||90;
L('手动: '+a+' '+v,'warn');
api('/api/manual',{method:'POST',body:{action:a,value:v}})
.then(function(d){L(d.message||JSON.stringify(d))}).catch(function(e){L(e.message,'error')});
}

function send(c){
api('/api/'+c,{method:'POST'}).then(function(d){L(d.message||JSON.stringify(d))}).catch(function(e){L(e.message,'error')});
}

/* ── poll ── */
function tick(){
api('/api/status').then(updateUI).catch(function(e){
pollFail++;document.getElementById('ftStatus').textContent='重连('+pollFail+')';
if(pollFail>MAX_FAIL){setConn(false);if(pollFail===MAX_FAIL+1)L('连续连接失败，请检查无人机','error')}
});
}

setInterval(tick,1000);tick();
L('控制台就绪');
</script>
</body>
</html>

""".trimIndent()
    }
}
