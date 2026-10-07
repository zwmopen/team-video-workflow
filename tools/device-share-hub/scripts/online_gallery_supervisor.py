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
PAUSE_FLAG = r"D:\AICode\运行数据\应用状态\后台服务控制\online-gallery.paused"

def supervision_paused() -> bool:
    """An existing flag blocks recovery; only its owner creates/removes it."""
    try:
        os.stat(PAUSE_FLAG)
        return True
    except FileNotFoundError:
        return False
    except OSError:
        # An unreadable control state is not permission to restart a service.
        return True

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
def get_listening_pid(port: int = PORT):
    try:
        import psutil
        for conn in psutil.net_connections(kind='inet'):
            if conn.laddr and conn.laddr.port == port and conn.status == 'LISTEN':
                return conn.pid
    except Exception:
        pass
    return None

def is_child_proc_running(proc) -> bool:
    if proc is None:
        return False
    if hasattr(proc, 'poll'):
        return proc.poll() is None
    if hasattr(proc, 'is_running'):
        try:
            import psutil
            return proc.is_running() and proc.status() != psutil.STATUS_ZOMBIE
        except Exception:
            return False
    return False

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
    if probe_status(timeout=4.0):
        return True
    # 偶发波动重试二次确认（防抖 2.0s）
    time.sleep(2.0)
    if probe_status(timeout=5.0):
        return True
    # 第三次防抖确认（容忍后台磁盘全量扫描期 3.0s）
    time.sleep(3.0)
    if probe_status(timeout=8.0):
        return True
    # 第四次终极死锁超时确认（防抖 3.0s）
    time.sleep(3.0)
    return probe_status(timeout=12.0)

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
            if is_child_proc_running(_last_child_proc):
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
    if supervision_paused():
        return False
    t0 = time.time()
    log("正在启动/自愈相册主服务 (online_gallery_service.py)...", "INFO")
    
    # 启动前彻底清道，确保无端口冲突
    kill_stale_processes()

    # The UI may pause while cleanup is in progress. Do not resurrect it.
    if supervision_paused():
        return False

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

    # 轮询等待就绪（每 0.5 秒一次，最多等 180 秒）
    # 2026-09-29 修复：原为 60 秒。冷启动首扫要重建缩略图（磁盘缓存被淘汰时尤甚），
    # 实测超过 60 秒 → 被误判「拉起失败」→ 杀掉重来 → 永远起不来的死循环。
    # 同时：等待期间若子进程已退出，立即放弃（避免干等）。
    ready = False
    for _ in range(360):
        time.sleep(0.5)
        if supervision_paused():
            return False
        if not is_child_proc_running(_last_child_proc):
            log("服务子进程在就绪等待期间已退出，放弃本次等待", "WARN")
            break
        if probe_status(timeout=2.0):
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
    global _last_child_proc
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
    log(f"   端口: {PORT} | 巡检周期: 5.0s | 梯次探活超时: 4s~12s", "INFO")
    log(f"==================================================", "INFO")

    consecutive_ok_count = 0

    while True:
        try:
            if supervision_paused():
                consecutive_ok_count = 0
                time.sleep(5.0)
                continue
            # 优先检查子进程是否真实存活：如果子进程还在跑，绝不盲目强杀
            if is_child_proc_running(_last_child_proc):
                # 子进程仍在运行，执行宽容防抖探活
                if not is_service_healthy():
                    # 2026-09-29 修复：子进程活着 + 端口仍在监听 ≠ 死锁，
                    # 很可能只是「忙」（冷启动首扫 / 缩略图重建 / watchdog force scan）。
                    # 原逻辑只要 HTTP 探活超时就 kill 重启，导致首扫永远完不成的死循环。
                    busy_pid = get_listening_pid(PORT)
                    if busy_pid:
                        log(f"服务进程存活且端口 {PORT} 仍在监听(PID={busy_pid})，"
                            f"判定为「忙」而非死锁（首扫/缩略图重建中），本轮跳过自愈重启", "INFO")
                    else:
                        log("相册服务无响应且多次防抖确认全部超时，判定为真正死锁，触发自愈重启！", "WARN")
                        start_service()
                        consecutive_ok_count = 0
                else:
                    consecutive_ok_count += 1
                    if consecutive_ok_count % 720 == 0:
                        log(f"相册服务常驻健康心跳: 持续稳定运行中 (累计正常探活 {consecutive_ok_count} 次)", "INFO")
            else:
                # 检查端口上是否已有健康的相册服务实例在运行（如外部/前任拉起）
                if is_service_healthy():
                    pid = get_listening_pid(PORT)
                    if pid:
                        try:
                            import psutil
                            _last_child_proc = psutil.Process(pid)
                            log(f"发现已有健康相册服务在端口 {PORT} 运行 (PID={pid})，直接接管守护，无需重启！", "INFO")
                        except Exception:
                            pass
                    consecutive_ok_count += 1
                else:
                    # 2026-09-29 修复（真正的循环点）：守护每次被计划任务重启后
                    # _last_child_proc 都是 None，必然走这里；若服务正在首扫（HTTP 不响应）
                    # 就会 start_service() → kill_stale_processes() 把正在干活的服务杀掉，
                    # 形成「永远起不来」的死循环。
                    # 兜底：只要端口上已有进程在监听，就视为「首扫中」，接管而不自愈。
                    busy_pid = get_listening_pid(PORT)
                    if busy_pid:
                        log(f"端口 {PORT} 已有服务在监听(PID={busy_pid})但暂未响应，"
                            f"判定为首扫/重建中，本轮跳过自愈并直接接管", "INFO")
                        try:
                            import psutil
                            _last_child_proc = psutil.Process(busy_pid)
                        except Exception:
                            pass
                    else:
                        # 端口无服务且探活失败，立即自愈拉起！
                        log("检测到相册服务进程已离线且端口无响应，立即启动自愈！", "INFO")
                        start_service()
                        consecutive_ok_count = 0
        except Exception as e:
            log(f"守护主循环捕捉到未预期异常: {e}", "ERROR")

        time.sleep(5.0)

if __name__ == "__main__":
    main()
