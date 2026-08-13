#!/usr/bin/env python3
"""将新的 dashboard HTML 注入到 WebServer.kt 中"""
import re
import sys

web_server_path = r"C:\Users\nice\Desktop\Drone_test\sample\src\main\java\com\dji\wang\aircraft\models\WebServer.kt"
html_path = r"C:\Users\nice\Desktop\drone_bridge\web\dashboard_new.html"

with open(html_path, 'r', encoding='utf-8') as f:
    new_html = f.read()

with open(web_server_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 找到 DASHBOARD_HTML 的起止行
start_marker = 'private val DASHBOARD_HTML = """'
end_marker = '""".trimIndent()'

start_idx = content.find(start_marker)
end_idx = content.find(end_marker, start_idx)

if start_idx == -1 or end_idx == -1:
    print("ERROR: cannot find DASHBOARD_HTML block boundaries")
    sys.exit(1)

# 替换：保留 """ 开头和 """.trimIndent() 结尾
before = content[:start_idx + len(start_marker)]
after = content[end_idx:]

# 新内容需要确保没有裸 $ 符号
if '${' in new_html:
    print("WARNING: found '${' in new HTML — may cause Kotlin template issues")

new_content = before + "\n" + new_html + "\n" + after

with open(web_server_path, 'w', encoding='utf-8') as f:
    f.write(new_content)

print(f"OK — replaced DASHBOARD_HTML ({len(new_html)} chars)")
