#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os
import sys
import time
import subprocess
import json
import urllib.request
import psutil

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

def main():
    print("=======================================================")
    print("  🔄 正在执行在线相册服务一键强力重启...")
    print("=======================================================")
    
    print("1. 正在清理旧相册服务与看门狗进程...")
    killed = 0
    for p in psutil.process_iter(["pid", "name", "cmdline"]):
        try:
            cmd = " ".join(p.info["cmdline"] or [])
            if "online_gallery_service" in cmd or "online_gallery_supervisor" in cmd:
                p.kill()
                killed += 1
                print(f"   ✔ 已停止进程 PID={p.pid} ({p.info['name']})")
        except Exception:
            pass
    print(f"   共清理 {killed} 个相关进程。")

    time.sleep(1.0)
    print("2. 正在以脱壳高可用模式重新拉起守护神 (Supervisor)...")
    python_dir = os.path.dirname(sys.executable)
    pythonw = os.path.join(python_dir, "pythonw.exe")
    if not os.path.exists(pythonw):
        pythonw = sys.executable
    script_dir = os.path.dirname(os.path.abspath(__file__))
    sup_script = os.path.join(script_dir, "online_gallery_supervisor.py")
    
    creationflags = 0x08000000 if os.name == "nt" else 0
    subprocess.Popen([pythonw, sup_script], cwd=script_dir, creationflags=creationflags, close_fds=True)

    print("3. 正在等待服务就绪并验证货架响应...", end="", flush=True)
    for _ in range(40):
        time.sleep(0.5)
        print(".", end="", flush=True)
        try:
            req = urllib.request.Request("http://127.0.0.1:45835/api/online/status", headers={"User-Agent": "RestartCheck/1.0"})
            with urllib.request.urlopen(req, timeout=1.5) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
                    if data.get("ok"):
                        print("\n")
                        print("=======================================================")
                        print("  🎉 在线相册服务已满血重启成功并进入高可用常驻！")
                        print(f"  ● 局域网访问地址: http://{data.get('ip')}:{data.get('port')}")
                        print(f"  ● 在线作品总数:   {data.get('totalWorks')} 套")
                        print("  ● 守护进程状态:   崩溃秒级自愈与死锁探测已激活")
                        print("=======================================================")
                        return 0
        except Exception:
            pass

    print("\n❌ 启动等待超时，请检查 online_gallery_supervisor.log 日志。")
    return 1

if __name__ == "__main__":
    sys.exit(main())
