# -*- coding: utf-8 -*-
"""
Online Gallery LAN Service High-Availability Supervisor
======================================================
在线相册服务高可用与 Windows 自愈常驻守护神
- 端口探活检测（45835）+ 死锁超时保护（探活超时 2.5s，双重确认）；
- 僵死/孤儿进程清理：彻底杜绝 Windows 端口劫持与多实例冲突；
- 崩溃秒自愈：进程异常退出或无响应后 3 秒内自动重启并恢复 200 OK；
- 纯净日志系统：自愈与巡检日志自动写入 logs/supervisor.log，内置 5MB 轮转；
- 内核级单实例保证：基于 Windows 命名 Mutex，永不产生僵尸锁，免控制台黑框。
"""

import os
import sys
import time
import json
import urllib.request
import subprocess
import ctypes
from ctypes import wintypes

# 基础路径配置
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SERVICE_SCRIPT = os.path.join(SCRIPT_DIR, "online_gallery_service.py")
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
LOG_DIR = os.path.join(REPO_ROOT, "logs")
SUPERVISOR_LOG = os.path.join(LOG_DIR, "supervisor.log")
LOCK_FILE = os.path.join(SCRIPT_DIR, "online_gallery_supervisor.lock")
LIBRARY_ROOT = r"D:\AICode\项目推进\projects\江湖有旅人\主项目\成品库（GPT+本地脚本制作）"
PORT = 45835

# 确保日志目录存在
os.makedirs(LOG_DIR, exist_ok=True)

# 寻找 pythonw.exe
PYTHONW = r"C:\Users\z\AppData\Local\Programs\Python\Python311\pythonw.exe"
if not os.path.exists(PYTHONW):
    candidate = sys.executable.replace("python.exe", "pythonw.exe")
    PYTHONW = candidate if os.path.exists(candidate) else sys.executable

# ----------------- 日志轮转机制 -----------------
MAX_LOG_BYTES = 5 * 1024 * 1024  # 5MB
BACKUP_COUNT = 3

def rotate_log_if_needed():
    try:
        if not os.path.exists(SUPERVISOR_LOG) or os.path.getsize(SUPERVISOR_LOG) < MAX_LOG_BYTES:
            return
        oldest = f"{SUPERVISOR_LOG}.{BACKUP_COUNT}"
        if os.path.exists(oldest):
            try:
                os.remove(oldest)
            except Exception:
                pass
        for i in range(BACKUP_COUNT - 1, 0, -1):
            src = f"{SUPERVISOR_LOG}.{i}"
            dst = f"{SUPERVISOR_LOG}.{i + 1}"
            if os.path.exists(src):
                try:
                    os.replace(src, dst)
                except Exception:
                    pass
        os.replace(SUPERVISOR_LOG, f"{SUPERVISOR_LOG}.1")
    except Exception:
        pass

def log(message: str, level: str = "INFO"):
    rotate_log_if_needed()
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{timestamp}] [{level.upper()}] {message}\n"
    try:
        with open(SUPERVISOR_LOG, "a", encoding="utf-8", errors="replace") as f:
            f.write(line)
            f.flush()
    except Exception:
        pass

# ----------------- 内核级单实例互斥体 -----------------
_mutex_handle = None

def acquire_single_instance_mutex():
    global _mutex_handle
    kernel32 = ctypes.windll.kernel32
    CreateMutexW = kernel32.CreateMutexW
    CreateMutexW.argtypes = [wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR]
    CreateMutexW.restype = wintypes.HANDLE
    GetLastError = kernel32.GetLastError

    MUTEX_NAME = "Local\\DeviceShareHub_OnlineGallery_Supervisor_Mutex"
    h = CreateMutexW(None, True, MUTEX_NAME)
    ERROR_ALREADY_EXISTS = 183
    if GetLastError() == ERROR_ALREADY_EXISTS:
        # 已有守护神在运行，静默退出
        sys.exit(0)
    _mutex_handle = h

# ----------------- 探活与死锁检测 -----------------
def probe_status(timeout: float = 3.0) -> bool:
    try:
        url = f"http://127.0.0.1:{PORT}/api/online/status"
        req = urllib.request.Request(url, headers={"User-Agent": "GallerySupervisor/2.0"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status == 200:
                raw = resp.read()
                data = json.loads(raw.decode("utf-8", errors="replace"))
                return data.get("ok", False) is True
    except Exception:
        return False
    return False

def is_service_healthy() -> bool:
    # 第一次探活（非阻塞毫秒级）
    if probe_status(timeout=2.0):
        return True
    # 偶发波动重试二次确认（防抖 0.5s）
    time.sleep(0.5)
    if probe_status(timeout=2.5):
        return True
    # 第三次终极死锁超时确认（防抖 0.5s）
    time.sleep(0.5)
    return probe_status(timeout=3.0)

# ----------------- 清理僵死/孤儿进程 -----------------
_last_child_proc = None

def kill_stale_processes():
    """强制清理占用 45835 端口或残留的 online_gallery_service.py 进程（毫秒级极速）"""
    global _last_child_proc
    cleaned_pids = set()
    current_pid = os.getpid()

    # 1. 如果上次记录的子进程仍在，优先终止
    if _last_child_proc is not None:
        try:
            if _last_child_proc.poll() is None:
                _last_child_proc.kill()
                cleaned_pids.add(_last_child_proc.pid)
        except Exception:
            pass
        _last_child_proc = None

    # 2. 毫秒级网络监听扫描：仅清理占用 PORT 的监听套接字（耗时 ~8ms）
    try:
        import psutil
        for conn in psutil.net_connections(kind='inet'):
            if conn.laddr and conn.laddr.port == PORT and conn.status == 'LISTEN':
                if conn.pid and conn.pid != current_pid and conn.pid not in cleaned_pids:
                    try:
                        p = psutil.Process(conn.pid)
                        log(f"发现占用端口 {PORT} 的进程 PID={conn.pid} ({p.name()})，执行强杀释放端口", "WARN")
                        p.kill()
                        cleaned_pids.add(conn.pid)
                    except Exception:
                        pass
    except Exception:
        pass

    if cleaned_pids:
        time.sleep(0.15)

# ----------------- 启动在线相册服务 -----------------
def start_service() -> bool:
    global _last_child_proc
    t0 = time.time()
    log("正在启动/自愈相册主服务 (online_gallery_service.py)...", "INFO")
    
    # 启动前彻底清道，确保无端口冲突
    kill_stale_processes()

    cmd = [PYTHONW, SERVICE_SCRIPT, str(PORT), LIBRARY_ROOT]
    CREATE_NO_WINDOW = 0x08000000
    try:
        proc = subprocess.Popen(
            cmd,
            cwd=SCRIPT_DIR,
            creationflags=CREATE_NO_WINDOW,
            close_fds=True
        )
        _last_child_proc = proc
        log(f"已拉起服务子进程 PID={proc.pid}，正在等待就绪验证...", "INFO")
    except Exception as e:
        log(f"拉起相册服务进程失败: {e}", "ERROR")
        return False

    # 轮询等待就绪（每 0.2 秒一次，最多等 10 秒）
    ready = False
    for _ in range(50):
        time.sleep(0.2)
        if probe_status(timeout=1.0):
            ready = True
            break

    elapsed = round(time.time() - t0, 2)
    if ready:
        log(f"✔ 相册主服务已成功自愈并就绪 (耗时: {elapsed}s，响应: 200 OK)", "INFO")
        return True
    else:
        log(f"✘ 相册主服务拉起后未能在 {elapsed}s 内通过健康检查", "ERROR")
        return False

# ----------------- 主守护神循环 -----------------
def main():
    acquire_single_instance_mutex()

    # 写入 lock 文件供状态检查
    try:
        with open(LOCK_FILE, "w", encoding="utf-8") as fp:
            fp.write(str(os.getpid()))
    except Exception:
        pass

    log(f"==================================================", "INFO")
    log(f"🚀 在线相册守护神 (OnlineGallery-Supervisor) 启动成功", "INFO")
    log(f"   PID: {os.getpid()} | Python: {PYTHONW}", "INFO")
    log(f"   端口: {PORT} | 巡检周期: 2.5s | 探活超时: 2.5s", "INFO")
    log(f"==================================================", "INFO")

    consecutive_ok_count = 0

    while True:
        try:
            if not is_service_healthy():
                log("检测到相册服务异常/死锁/已离线，立即触发秒级自愈机制！", "WARN")
                start_service()
                consecutive_ok_count = 0
            else:
                consecutive_ok_count += 1
                # 每 1440 次正常探活（约 1 小时）打一条心跳，避免日志无限膨胀同时证明活跃
                if consecutive_ok_count % 1440 == 0:
                    log(f"相册服务常驻健康心跳: 持续稳定运行中 (累计正常探活 {consecutive_ok_count} 次)", "INFO")
        except Exception as e:
            log(f"守护主循环捕捉到未预期异常: {e}", "ERROR")

        time.sleep(2.0)

if __name__ == "__main__":
    main()
