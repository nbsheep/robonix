#!/usr/bin/env python3
"""M3E 控制台代理 — API + MJPEG 视频流转发，解决 CORS"""

import http.server
import json
import os
import socket
import sys
from urllib.parse import urlparse

RC_HOST = sys.argv[1] if len(sys.argv) > 1 else "10.225.57.15"
RC_PORT = 8080
PROXY_PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 5500
RC_BASE = f"http://{RC_HOST}:{RC_PORT}"

class ProxyHandler(http.server.SimpleHTTPRequestHandler):

    def do_GET(self):
        if self.path == "/api/video":
            self._proxy_mjpeg()
        elif self.path.startswith("/api/"):
            self._proxy_get()
        else:
            self._serve_dashboard()

    def do_POST(self):
        if self.path.startswith("/api/"):
            self._proxy_post()
        else:
            self.send_error(404)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    # ── API 转发 ──

    def _proxy_get(self):
        import urllib.request
        try:
            req = urllib.request.Request(f"{RC_BASE}{self.path}")
            with urllib.request.urlopen(req, timeout=10) as r:
                data = r.read()
            self._respond_json(200, data)
        except Exception as e:
            self._respond_json(502, json.dumps({"error": str(e)}).encode())

    def _proxy_post(self):
        import urllib.request
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length) if length > 0 else None
            req = urllib.request.Request(f"{RC_BASE}{self.path}", data=body, method="POST")
            req.add_header("Content-Type", "application/json")
            with urllib.request.urlopen(req, timeout=10) as r:
                data = r.read()
            self._respond_json(r.status, data)
        except Exception as e:
            self._respond_json(502, json.dumps({"error": str(e)}).encode())

    def _respond_json(self, code, data):
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)

    # ── MJPEG 视频流 — raw socket 直转 ──

    def _proxy_mjpeg(self):
        """用 raw socket 连 RC Pro，把 MJPEG 流逐字节转发给浏览器"""
        sock = None
        try:
            # 连接 RC Pro
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(5)
            sock.connect((RC_HOST, RC_PORT))

            # 发送 HTTP 请求
            req = (
                f"GET /api/video HTTP/1.1\r\n"
                f"Host: {RC_HOST}:{RC_PORT}\r\n"
                f"Connection: close\r\n"
                f"\r\n"
            )
            sock.sendall(req.encode())

            # 读上游响应头（读到 \r\n\r\n）
            header = b""
            while b"\r\n\r\n" not in header:
                chunk = sock.recv(1)
                if not chunk:
                    raise ConnectionError("RC Pro 未返回视频流")
                header += chunk

            # 转发响应头给浏览器
            self.send_response(200)
            self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=FRAME")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()

            # 转发 body（视频帧数据）
            sock.settimeout(60)
            while True:
                try:
                    chunk = sock.recv(16384)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
                except socket.timeout:
                    break
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    break

        except Exception as e:
            print(f"[MJPEG] 错误: {e}")
        finally:
            if sock:
                try:
                    sock.close()
                except Exception:
                    pass

    # ── 仪表盘页面 ──

    def _serve_dashboard(self):
        html_path = os.path.join(os.path.dirname(__file__), "web", "dashboard.html")
        try:
            with open(html_path, "r", encoding="utf-8") as f:
                html = f.read()
            # 注入 RC Pro 地址，让视频 img 标签可以直连（img 不受 CORS 限制）
            inject = '<script>window.__RC_HOST__="%s";window.__RC_PORT__=%d;</script>\n' % (RC_HOST, RC_PORT)
            html = html.replace("<!-- ═══ Script ═══ -->", inject + "<!-- ═══ Script ═══ -->", 1)
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(html.encode())
        except FileNotFoundError:
            self.send_error(404, "dashboard.html not found")


def main():
    print(f"""
╔══════════════════════════════════════════════╗
║  🚁 M3E 无人机控制台                          ║
║                                              ║
║  👉 http://localhost:{PROXY_PORT}              ║
║  📡 RC Pro: {RC_HOST}:{RC_PORT}              ║
╚══════════════════════════════════════════════╝
""")
    httpd = http.server.HTTPServer(("0.0.0.0", PROXY_PORT), ProxyHandler)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已关闭")


if __name__ == "__main__":
    main()
