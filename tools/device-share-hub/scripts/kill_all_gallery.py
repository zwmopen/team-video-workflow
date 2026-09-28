import psutil
import os
import time

current_pid = os.getpid()
killed = []
for p in psutil.process_iter(['pid', 'name', 'cmdline']):
    try:
        if p.pid == current_pid:
            continue
        cmdline = p.info.get('cmdline') or []
        cmd_str = " ".join(cmdline)
        if "online_gallery" in cmd_str:
            print(f"Killing PID={p.pid}: {cmd_str[:80]}")
            p.kill()
            killed.append(p.pid)
    except Exception as e:
        pass

print(f"Total killed: {len(killed)}")
time.sleep(1)
remaining = []
for p in psutil.process_iter(['pid', 'name', 'cmdline']):
    try:
        if p.pid == current_pid:
            continue
        cmdline = p.info.get('cmdline') or []
        cmd_str = " ".join(cmdline)
        if "online_gallery" in cmd_str:
            remaining.append(p.pid)
    except Exception:
        pass
print(f"Remaining gallery processes: {remaining}")
