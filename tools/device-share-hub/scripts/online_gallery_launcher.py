# -*- coding: utf-8 -*-
"""
Online Gallery Desktop Control Center & Standalone Launcher
===========================================================
在线相册桌面独立启动器与运维控制台
- 脱离 AI 独立运行：双击即启，支持启动、重启、状态体检、停止与物理目录直达；
- 智能识别状态：服务未启动时一键拉起守护神；已启动时展示清晰的交互菜单；
- 100% 本地原生：无外部依赖，UTF-8 控制台色彩，原生 Windows 体验。
"""

import os
import sys
import time
import json
import urllib.request
import subprocess

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SERVICE_SCRIPT = os.path.join(SCRIPT_DIR, "online_gallery_service.py")
SUPERVISOR_SCRIPT = os.path.join(SCRIPT_DIR, "online_gallery_supervisor.py")
LIBRARY_ROOT = r"D:\AICode\项目推进\projects\江湖有旅人\主项目\成品库（GPT+本地脚本制作）"
PORT = 45835
PYTHONW = r"C:\Users\z\AppData\Local\Programs\Python\Python311\pythonw.exe"
if not os.path.exists(PYTHONW):
    PYTHONW = sys.executable.replace("python.exe", "pythonw.exe")


def get_service_status():
    url = f"http://127.0.0.1:{PORT}/api/online/status"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "GalleryLauncher/1.0"})
        with urllib.request.urlopen(req, timeout=2.0) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode("utf-8", errors="replace"))
                return data if data.get("ok") else None
    except Exception:
        return None
    return None


def is_supervisor_running():
    try:
        import psutil
        for p in psutil.process_iter(['name', 'cmdline']):
            if 'python' in p.info['name'].lower():
                cmdline = " ".join(p.info['cmdline'] or [])
                if 'online_gallery_supervisor' in cmdline:
                    return True
    except Exception:
        pass
    return False


def start_supervisor_and_service():
    print("⏳ 正在启动在线相册守护神 (OnlineGallery-Supervisor)...")
    CREATE_NO_WINDOW = 0x08000000
    try:
        subprocess.Popen(
            [PYTHONW, SUPERVISOR_SCRIPT],
            cwd=SCRIPT_DIR,
            creationflags=CREATE_NO_WINDOW,
            close_fds=True
        )
    except Exception as e:
        print(f"❌ 启动失败: {e}")
        return False

    print("⏳ 正在等待相册服务就绪...", end="", flush=True)
    for _ in range(30):
        time.sleep(0.5)
        print(".", end="", flush=True)
        status = get_service_status()
        if status:
            print("\n")
            print("==================================================")
            print("🚀 在线相册服务启动成功！")
            print(f"   局域网地址: http://{status.get('ip')}:{PORT}")
            print(f"   总作品数:   {status.get('totalWorks')} 套 (24 大标准货架)")
            print(f"   守护状态:   高可用常驻中 (崩溃秒自愈)")
            print("==================================================")
            return True
    print("\n❌ 服务启动超时，请检查日志。")
    return False


def stop_service_and_supervisor():
    print("⏳ 正在停止在线相册服务与守护神...")
    try:
        import psutil
        stopped = 0
        for p in psutil.process_iter(['pid', 'name', 'cmdline']):
            if 'python' in p.info['name'].lower():
                cmdline = " ".join(p.info['cmdline'] or [])
                if 'online_gallery_service' in cmdline or 'online_gallery_supervisor' in cmdline:
                    try:
                        p.kill()
                        stopped += 1
                    except Exception:
                        pass
        time.sleep(0.5)
        print(f"✔ 已停止 {stopped} 个相关进程，相册服务已安全下线。")
    except Exception as e:
        print(f"❌ 停止进程异常: {e}")


def restart_service():
    stop_service_and_supervisor()
    time.sleep(1.0)
    start_supervisor_and_service()


def open_library_folder():
    if os.path.exists(LIBRARY_ROOT):
        os.startfile(LIBRARY_ROOT)
        print(f"✔ 已在资源管理器中打开成品库：{LIBRARY_ROOT}")
    else:
        print(f"❌ 路径不存在：{LIBRARY_ROOT}")


def open_browser_test():
    url = f"http://127.0.0.1:{PORT}/api/online/categories"
    os.system(f"start {url}")
    print(f"✔ 已在浏览器中打开分类接口：{url}")


def main_menu():
    try:
        if hasattr(sys.stdout, 'reconfigure'):
            sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        if hasattr(sys.stdin, 'reconfigure'):
            sys.stdin.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

    while True:
        status = get_service_status()
        sup_running = is_supervisor_running()

        print("\n" + "=" * 54)
        print("      📷 在线相册电脑端启动与控制中枢 (Launcher)")
        print("=" * 54)

        if status:
            ip = status.get("ip", "127.0.0.1")
            total = status.get("totalWorks", 0)
            sup_str = "🟢 正常常驻" if sup_running else "🟡 未检测到(仅服务运行)"
            print(f"  ● 运行状态: 🟢 正常运行中")
            print(f"  ● 局域网IP: http://{ip}:{PORT}")
            print(f"  ● 成品总数: {total} 套作品 (24 大货架 1:1 镜像)")
            print(f"  ● 高可用守护: {sup_str}")
        else:
            print("  ● 运行状态: 🔴 未启动 / 已离线")
            print(f"  ● 目标端口: {PORT}")
            print("  ● 提示: 手机端相册需要此服务运行才能连上电脑")

        print("-" * 54)
        print("  [1] 一键启动 / 重新启动服务 (自动拉起高可用守护神)")
        print("  [2] 打开成品库物理文件夹 (24 大实体货架)")
        print("  [3] 浏览器打开测试 (查看 24 个分类与作品数据)")
        print("  [4] 停止相册服务与守护神")
        print("  [0] 退出本控制台 (服务仍会在后台静默保持运行)")
        print("=" * 54)

        try:
            choice = input("👉 请输入数字操作 [0-4] (直接回车刷新状态): ").strip()
        except (KeyboardInterrupt, EOFError):
            break

        if choice == "1":
            restart_service()
            time.sleep(1.5)
        elif choice == "2":
            open_library_folder()
            time.sleep(1.0)
        elif choice == "3":
            open_browser_test()
            time.sleep(1.0)
        elif choice == "4":
            stop_service_and_supervisor()
            time.sleep(1.5)
        elif choice == "0":
            print("👋 控制台已退出，相册服务在后台继续稳定为您服务！")
            break
        else:
            # 直接回车刷新
            continue


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--start-silent":
        # 静默模式：只管确保服务拉起
        if not get_service_status():
            start_supervisor_and_service()
        sys.exit(0)
    main_menu()
