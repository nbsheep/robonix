#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RC Pro IP 一键更新脚本
=======================
每天 RC Pro 的 IP 变化后，运行此脚本即可自动更新 drone_bridge 项目中所有相关文件。

用法:
  python update_rc_ip.py 192.168.1.50           # 手动指定新 IP
  python update_rc_ip.py --scan                  # 自动扫描局域网找 RC Pro (:8080)
  python update_rc_ip.py --scan --apply          # 扫描并自动应用（无需确认）
  python update_rc_ip.py --show                  # 仅显示当前所有 IP 配置
  python update_rc_ip.py 192.168.1.50 --dry-run  # 预览变更，不实际写入

依赖: Python 3.6+ (无第三方库)
"""

import argparse
import ipaddress
import os
import re
import socket
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Dict, List, Tuple

# ── Windows 控制台 UTF-8 编码修复 ──────────────────────────────────────
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# ── 项目根目录（脚本所在目录） ──────────────────────────────────────────
PROJECT_DIR = Path(__file__).parent.resolve()

# ── 需要更新的文件及其替换规则 ─────────────────────────────────────────
# 每条规则包含: file(相对路径), desc(描述), find/replace(函数)

RULES: List[dict] = [
    # 1. drone_bridge/main.py — RC_PRO_IP 默认值
    {
        "file": "drone_bridge/main.py",
        "desc": "main.py RC_PRO_IP default",
        "find": lambda content: re.search(
            r'(RC_PRO_IP\s*=\s*os\.environ\.get\("RC_PRO_IP",\s*")(\d+\.\d+\.\d+\.\d+)("\))',
            content,
        ),
        "replace": lambda content, old, new: content.replace(
            f'RC_PRO_IP = os.environ.get("RC_PRO_IP", "{old}")',
            f'RC_PRO_IP = os.environ.get("RC_PRO_IP", "{new}")',
        ),
    },
    # 2. scripts/start.sh — config JSON fallback (first)
    {
        "file": "scripts/start.sh",
        "desc": "start.sh config fallback IP (rc_pro_ip)",
        "find": lambda content: re.search(
            r"(get\('rc_pro_ip',')(\d+\.\d+\.\d+\.\d+)('\))",
            content,
        ),
        "replace": lambda content, old, new: re.sub(
            r"(get\('rc_pro_ip',')\d+\.\d+\.\d+\.\d+('\))",
            rf"\g<1>{new}\g<2>",
            content,
            count=1,
        ),
    },
    # 3. scripts/start.sh — else branch fallback
    {
        "file": "scripts/start.sh",
        "desc": "start.sh else branch fallback IP",
        "find": lambda content: re.search(
            r'(\$\{RC_PRO_IP:-)(\d+\.\d+\.\d+\.\d+)(\})',
            content,
        ),
        "replace": lambda content, old, new: re.sub(
            r'(\$\{RC_PRO_IP:-)\d+\.\d+\.\d+\.\d+(\})',
            rf'\g<1>{new}\g<2>',
            content,
            count=1,
        ),
    },
    # 4. scripts/start.sh — || echo fallback on same line as config parse
    {
        "file": "scripts/start.sh",
        "desc": "start.sh || echo fallback IP",
        "find": lambda content: re.search(
            r'(echo ")(\d+\.\d+\.\d+\.\d+)("\))',
            content,
        ),
        "replace": lambda content, old, new: re.sub(
            r'(echo ")\d+\.\d+\.\d+\.\d+("\))',
            rf'\g<1>{new}\g<2>',
            content,
            count=1,
        ),
    },
    # 6. robonix_manifest.yaml — rc_pro_ip config
    {
        "file": "robonix_manifest.yaml",
        "desc": "robonix_manifest.yaml rc_pro_ip",
        "find": lambda content: re.search(
            r'(rc_pro_ip:\s*")(\d+\.\d+\.\d+\.\d+)(")',
            content,
        ),
        "replace": lambda content, old, new: re.sub(
            r'(rc_pro_ip:\s*")\d+\.\d+\.\d+\.\d+(")',
            rf'\g<1>{new}\g<2>',
            content,
            count=1,
        ),
    },
    # 7. package_manifest.yaml — config default
    {
        "file": "package_manifest.yaml",
        "desc": "package_manifest.yaml config default",
        "find": lambda content: re.search(
            r'(default:\s*")(\d+\.\d+\.\d+\.\d+)(")',
            content,
        ),
        "replace": lambda content, old, new: re.sub(
            r'(default:\s*")\d+\.\d+\.\d+\.\d+(")',
            rf'\g<1>{new}\g<2>',
            content,
            count=1,
        ),
    },
    # 8. proxy_server.py — RC_HOST default
    {
        "file": "proxy_server.py",
        "desc": "proxy_server.py RC_HOST default",
        "find": lambda content: re.search(
            r'(RC_HOST\s*=\s*sys\.argv\[1\]\s*if\s*len\(sys\.argv\)\s*>\s*1\s*else\s*")(\d+\.\d+\.\d+\.\d+)(")',
            content,
        ),
        "replace": lambda content, old, new: re.sub(
            r'(RC_HOST\s*=\s*sys\.argv\[1\]\s*if\s*len\(sys\.argv\)\s*>\s*1\s*else\s*")\d+\.\d+\.\d+\.\d+(")',
            rf'\g<1>{new}\g<2>',
            content,
            count=1,
        ),
    },
]


# ═══════════════════════════════════════════════════════════════════════════
# 核心功能
# ═══════════════════════════════════════════════════════════════════════════

def is_valid_ip(ip: str) -> bool:
    """验证是否为合法 IPv4 地址"""
    try:
        ipaddress.IPv4Address(ip)
        return True
    except (ipaddress.AddressValueError, ValueError):
        return False


def is_private_ip(ip: str) -> bool:
    """检查是否为私有/局域网 IP"""
    try:
        return ipaddress.IPv4Address(ip).is_private
    except Exception:
        return False


def get_current_ips() -> Dict[str, List[Tuple[str, str]]]:
    """
    扫描所有规则文件，提取当前配置的 IP。
    返回: {文件路径: [(描述, 当前IP), ...]}
    """
    result: Dict[str, List[Tuple[str, str]]] = {}
    for rule in RULES:
        file_path = PROJECT_DIR / rule["file"]
        if not file_path.exists():
            result[str(file_path)] = [("[!] file not found", "")]
            continue
        content = file_path.read_text(encoding="utf-8")
        match = rule["find"](content)
        if match:
            ip = match.group(2)
            result.setdefault(str(file_path), []).append((rule["desc"], ip))
        else:
            result.setdefault(str(file_path), []).append(
                (f"[!] pattern not matched: {rule['desc']}", "")
            )
    return result


def update_files(new_ip: str, dry_run: bool = False) -> Dict[str, Tuple[str, str]]:
    """
    将所有文件中的旧 IP 替换为新 IP。
    返回: {文件路径: (旧IP, 新IP)} 变更记录
    """
    changes: Dict[str, Tuple[str, str]] = {}
    for rule in RULES:
        file_path = PROJECT_DIR / rule["file"]
        if not file_path.exists():
            print(f"  [!] skip (not found): {rule['file']}")
            continue
        content = file_path.read_text(encoding="utf-8")
        match = rule["find"](content)
        if not match:
            print(f"  [!] skip (no match): {rule['file']} -- {rule['desc']}")
            continue
        old_ip = match.group(2)
        if old_ip == new_ip:
            print(f"  =  already {new_ip}, skip: {rule['file']}")
            continue
        new_content = rule["replace"](content, old_ip, new_ip)
        if not dry_run:
            file_path.write_text(new_content, encoding="utf-8")
        changes[str(file_path)] = (old_ip, new_ip)
    return changes


# ═══════════════════════════════════════════════════════════════════════════
# 网络扫描
# ═══════════════════════════════════════════════════════════════════════════

def get_local_subnets() -> List[str]:
    """获取本机所有局域网子网（如 192.168.1.0/24）"""
    subnets = []
    try:
        import subprocess
        output = subprocess.check_output(
            ["ipconfig"], shell=True, encoding="utf-8", errors="ignore"
        )
        for match in re.finditer(r"IPv4[^:]*:\s*(\d+\.\d+\.\d+\.\d+)", output):
            ip = match.group(1)
            if is_private_ip(ip) and not ip.startswith("127."):
                subnet = ".".join(ip.split(".")[:3]) + ".0/24"
                if subnet not in subnets:
                    subnets.append(subnet)
    except Exception:
        pass

    if not subnets:
        subnets = ["192.168.1.0/24", "192.168.0.0/24", "10.0.0.0/24"]

    return subnets


def check_host(ip: str, port: int = 8080, timeout: float = 1.0) -> Tuple[str, bool, str]:
    """
    检查指定 IP:port 是否运行着 RC Pro 服务。
    返回: (ip, 可达, 额外信息)
    """
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        result = sock.connect_ex((ip, port))
        sock.close()
        if result == 0:
            try:
                import urllib.request
                req = urllib.request.Request(f"http://{ip}:{port}/api/status")
                with urllib.request.urlopen(req, timeout=2) as r:
                    data = r.read().decode()
                    if "drone" in data.lower() or "aircraft" in data.lower():
                        return (ip, True, f"[OK] RC Pro / Drone_test confirmed ({data[:60]}...)")
                    return (ip, True, f"[OK] port open (response: {data[:60]}...)")
            except Exception as e:
                return (ip, True, f"[OK] port open (but /api/status unreachable: {e})")
        return (ip, False, "")
    except Exception as e:
        return (ip, False, str(e))


def scan_network(subnets: List[str] = None, port: int = 8080, workers: int = 50) -> List[str]:
    """
    并行扫描局域网内所有 IP 的 8080 端口，寻找 RC Pro。
    返回找到的 IP 列表。
    """
    if subnets is None:
        subnets = get_local_subnets()

    all_ips = []
    for subnet in subnets:
        try:
            net = ipaddress.IPv4Network(subnet, strict=False)
            hosts = [str(h) for h in net.hosts()]
            all_ips.extend(hosts)
        except Exception:
            continue

    print(f"\n[*] scanning {len(subnets)} subnet(s), {len(all_ips)} IP(s) on port {port}...")
    print("    (may take 1-2 minutes)\n")

    found = []
    checked = 0
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(check_host, ip, port): ip for ip in all_ips}
        for future in as_completed(futures):
            ip, ok, info = future.result()
            checked += 1
            if ok:
                found.append(ip)
                print(f"  --> {ip} -- {info}")
            elif checked % 50 == 0:
                print(f"  ... checked {checked}/{len(all_ips)} ...", end="\r")

    print(f"\n  scan done: {len(found)} device(s) responding on port {port}")
    return found


# ═══════════════════════════════════════════════════════════════════════════
# 命令行界面
# ═══════════════════════════════════════════════════════════════════════════

def cmd_show():
    """显示当前所有 IP 配置"""
    print("=== Current IP Configuration in drone_bridge ===\n")
    current = get_current_ips()
    for file_path, entries in current.items():
        try:
            rel_path = Path(file_path).relative_to(PROJECT_DIR)
        except ValueError:
            rel_path = file_path
        print(f"  [{rel_path}]")
        for desc, ip in entries:
            print(f"      {desc}:  {ip}")
        print()


def cmd_update(new_ip: str, dry_run: bool = False):
    """执行 IP 更新"""
    if not is_valid_ip(new_ip):
        print(f"[ERROR] invalid IP address: {new_ip}")
        sys.exit(1)

    if not is_private_ip(new_ip):
        print(f"[WARN] {new_ip} is not a private/LAN IP address.")
        print("       RC Pro is usually on LAN (192.168.x.x / 10.x.x.x).")
        try:
            ans = input("       Continue? [y/N] ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            ans = "n"
        if ans != "y":
            print("Cancelled.")
            sys.exit(0)

    tag = "[DRY-RUN]" if dry_run else "[UPDATE]"
    print(f"\n{tag} setting IP -> {new_ip}\n")

    changes = update_files(new_ip, dry_run=dry_run)

    if not changes:
        print("  No files need updating (all already set to this IP).")
        return

    for file_path, (old_ip, new_ip) in changes.items():
        try:
            rel_path = Path(file_path).relative_to(PROJECT_DIR)
        except ValueError:
            rel_path = file_path
        arrow = "-->"
        print(f"  {arrow} {rel_path}:  {old_ip} -> {new_ip}")

    if dry_run:
        print(f"\n[DONE] Preview only -- no files modified.")
        print("       Re-run without --dry-run to apply changes.")
    else:
        print(f"\n[DONE] Updated {len(changes)} file(s).")
        print("       Verify with: python update_rc_ip.py --show")


def cmd_scan(apply_ip: bool = False):
    """扫描网络找 RC Pro"""
    print("[*] Detecting local network...")
    subnets = get_local_subnets()
    print(f"    Subnet(s): {', '.join(subnets)}")

    found = scan_network(subnets)

    if not found:
        print("\n[FAIL] No RC Pro device found.")
        print("       Tips:")
        print("       1. Ensure RC Pro is powered on and on the same network")
        print("       2. Ensure Drone_test APK is running (WebServer :8080)")
        print("       3. Or specify IP manually: python update_rc_ip.py <ip>")
        sys.exit(1)

    print(f"\nFound {len(found)} candidate(s):")
    for i, ip in enumerate(found):
        print(f"  [{i+1}] {ip}")

    if len(found) == 1:
        selected = found[0]
        if not apply_ip:
            print(f"\nAuto-selected: {selected}")
            try:
                ans = input("    Confirm update to this IP? [Y/n] ").strip().lower()
            except (EOFError, KeyboardInterrupt):
                ans = "y"
            if ans in ("n", "no"):
                print("Cancelled.")
                return
    else:
        if apply_ip:
            selected = found[0]
            print(f"\nAuto-selected first: {selected}")
        else:
            try:
                choice = input(f"\nChoose [1-{len(found)}] or enter IP: ").strip()
            except (EOFError, KeyboardInterrupt):
                print("Cancelled.")
                return
            if choice.isdigit() and 1 <= int(choice) <= len(found):
                selected = found[int(choice) - 1]
            elif is_valid_ip(choice):
                selected = choice
            else:
                print("Invalid choice.")
                return

    cmd_update(selected)


def main():
    parser = argparse.ArgumentParser(
        description="RC Pro IP Updater -- update all drone_bridge IP configs at once",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python update_rc_ip.py 192.168.1.50           # manually specify new IP
  python update_rc_ip.py --scan                  # scan LAN to find RC Pro
  python update_rc_ip.py --scan --apply          # scan and auto-apply (no prompt)
  python update_rc_ip.py --show                  # show current config only
  python update_rc_ip.py 192.168.1.50 --dry-run  # preview changes without writing
        """,
    )
    parser.add_argument(
        "ip", nargs="?", default=None,
        help="new RC Pro IP address (e.g. 192.168.1.50)",
    )
    parser.add_argument(
        "--scan", "-s", action="store_true",
        help="scan LAN to auto-detect RC Pro device",
    )
    parser.add_argument(
        "--apply", "-a", action="store_true",
        help="use with --scan: auto-apply first found device without confirmation",
    )
    parser.add_argument(
        "--show", action="store_true",
        help="show current IP configuration in all files",
    )
    parser.add_argument(
        "--dry-run", "-n", action="store_true",
        help="preview mode: show what would change, don't write",
    )

    args = parser.parse_args()

    os.chdir(PROJECT_DIR)

    if args.show:
        cmd_show()
        return

    if args.scan:
        cmd_scan(apply_ip=args.apply)
        return

    if args.ip:
        cmd_update(args.ip, dry_run=args.dry_run)
        return

    parser.print_help()
    print("\nQuick start:")
    print("  python update_rc_ip.py --show          # view current config")
    print("  python update_rc_ip.py --scan          # auto-scan for RC Pro")
    print("  python update_rc_ip.py 192.168.1.50    # manual update")


if __name__ == "__main__":
    main()
