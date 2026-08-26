import os
import csv
import cv2
from datetime import datetime
from jinja2 import Template

class InspectionReporter:
    """
    巡检报告器:
    - log_event(): 每检测到一次异常调用一次,存截图 + 记录一行
    - save_report(): 巡检结束时调用,生成 HTML 报告
    """
    def __init__(self, out_dir="runs/reports"):
        # 每次巡检建一个带时间戳的独立文件夹
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.dir = os.path.join(out_dir, f"inspection_{stamp}")
        self.shots_dir = os.path.join(self.dir, "shots")
        os.makedirs(self.shots_dir, exist_ok=True)
        self.events = []            # 内存里存所有事件
        self.csv_path = os.path.join(self.dir, "events.csv")
        # 先写 CSV 表头
        with open(self.csv_path, "w", newline="", encoding="utf-8-sig") as f:
            csv.writer(f).writerow(["时间", "类别", "置信度", "GPS", "截图文件"])

    def log_event(self, frame, label, conf, gps=None):
        """
        frame : 画好框的图 (numpy 数组, 即 results[0].plot() 的返回值)
        label : 类别名, 如 'fire' / 'smoke'
        conf  : 置信度 0~1
        gps   : (lat, lon) 或 None
        """
        now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        fname = f"{len(self.events):04d}_{label}.jpg"
        cv2.imwrite(os.path.join(self.shots_dir, fname), frame)

        gps_str = f"{gps[0]:.6f}, {gps[1]:.6f}" if gps else "N/A"
        self.events.append({
            "time": now, "label": label,
            "conf": f"{conf:.2f}", "gps": gps_str,
            "shot": os.path.join("shots", fname),   # 相对路径,报告里能显示
        })
        with open(self.csv_path, "a", newline="", encoding="utf-8-sig") as f:
            csv.writer(f).writerow([now, label, f"{conf:.2f}", gps_str, fname])
        print(f"[事件] {now}  {label}  conf={conf:.2f}  {gps_str}")

    def save_report(self):
        """生成 HTML 报告,返回报告文件路径。"""
        html = Template(_HTML_TMPL).render(
            events=self.events,
            total=len(self.events),
            fire=sum(1 for e in self.events if e["label"] == "fire"),
            smoke=sum(1 for e in self.events if e["label"] == "smoke"),
            gen_time=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        )
        path = os.path.join(self.dir, "report.html")
        with open(path, "w", encoding="utf-8") as f:
            f.write(html)
        print(f"\n[报告] 已生成:{os.path.abspath(path)}")
        return path


# ------- HTML 模板(内嵌,零外部文件依赖) -------
_HTML_TMPL = """<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8">
<title>无人机火烟巡检报告</title>
<style>
  body{font-family:"Microsoft YaHei",Arial,sans-serif;margin:40px;color:#222;background:#f7f7f8}
  h1{border-left:5px solid #e8442b;padding-left:12px}
  .summary{display:flex;gap:16px;margin:20px 0}
  .card{background:#fff;border-radius:10px;padding:16px 24px;box-shadow:0 1px 4px rgba(0,0,0,.08)}
  .card .num{font-size:32px;font-weight:700}
  .fire{color:#e8442b}.smoke{color:#888}
  table{width:100%;border-collapse:collapse;background:#fff;border-radius:10px;overflow:hidden}
  th,td{padding:10px 14px;text-align:left;border-bottom:1px solid #eee}
  th{background:#fafafa}
  img{height:90px;border-radius:6px}
  .none{color:#2a9d5c;font-weight:700}
</style></head><body>
<h1>无人机火 / 烟巡检报告</h1>
<p>生成时间:{{ gen_time }}</p>
<div class="summary">
  <div class="card"><div class="num">{{ total }}</div>异常事件总数</div>
  <div class="card"><div class="num fire">{{ fire }}</div>火焰</div>
  <div class="card"><div class="num smoke">{{ smoke }}</div>烟雾</div>
</div>
{% if events %}
<table>
  <tr><th>#</th><th>时间</th><th>类别</th><th>置信度</th><th>GPS</th><th>截图</th></tr>
  {% for e in events %}
  <tr>
    <td>{{ loop.index }}</td><td>{{ e.time }}</td>
    <td>{{ e.label }}</td><td>{{ e.conf }}</td><td>{{ e.gps }}</td>
    <td><img src="{{ e.shot }}"></td>
  </tr>
  {% endfor %}
</table>
{% else %}
<p class="none">✔ 本次巡检未发现火焰或烟雾异常。</p>
{% endif %}
</body></html>"""