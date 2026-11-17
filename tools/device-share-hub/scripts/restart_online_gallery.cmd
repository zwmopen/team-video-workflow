@echo off
title 在线相册服务 - 一键秒级重启
chcp 65001 >nul
cd /d "%~dp0"
echo =======================================================
echo   🔄 正在执行在线相册服务一键强力重启...
echo =======================================================
"C:\Users\z\AppData\Local\Programs\Python\Python311\python.exe" -c "
import os, sys, time, subprocess, psutil, urllib.request, json

print('1. 正在清理旧相册服务与看门狗进程...')
for p in psutil.process_iter(['pid', 'name', 'cmdline']):
    try:
        cmd = ' '.join(p.info['cmdline'] or [])
        if 'online_gallery_service' in cmd or 'online_gallery_supervisor' in cmd:
            p.kill()
            print(f'   ✔ 已停止进程 PID={p.pid}')
    except Exception:
        pass

time.sleep(1.0)
print('2. 正在以脱壳高可用模式重新拉起守护神 (Supervisor)...')
pythonw = r'C:\Users\z\AppData\Local\Programs\Python\Python311\pythonw.exe'
script_dir = r'D:\AICode\AI\repos\team-video-workflow\tools\device-share-hub\scripts'
sup_script = os.path.join(script_dir, 'online_gallery_supervisor.py')
subprocess.Popen([pythonw, sup_script], cwd=script_dir, creationflags=0x08000000, close_fds=True)

print('3. 正在等待服务响应 200 OK 并验证货架数据...', end='', flush=True)
for _ in range(25):
    time.sleep(0.5)
    print('.', end='', flush=True)
    try:
        req = urllib.request.Request('http://127.0.0.1:45835/api/online/status', headers={'User-Agent': 'Restart/1.0'})
        with urllib.request.urlopen(req, timeout=1.5) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode('utf-8'))
                if data.get('ok'):
                    print('\n')
                    print('=======================================================')
                    print('  🎉 在线相册服务已满血重启成功并进入高可用常驻！')
                    print(f'  ● 局域网访问地址: http://{data.get(\"ip\")}:{data.get(\"port\")}')
                    print(f'  ● 在线作品总数:   {data.get(\"totalWorks\")} 套')
                    print('  ● 守护进程状态:   崩溃秒级自愈已激活')
                    print('=======================================================')
                    sys.exit(0)
    except Exception:
        pass

print('\n❌ 启动等待超时，请检查 supervisor.log 日志。')
"
echo.
pause
