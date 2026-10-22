# -*- coding: utf-8 -*-
"""DSH-188 + DSH-192 回归：iOS 在线图磁盘缓存淘汰

DSH-188: 目录原本只写不删，实测膨胀到 884MB / 3621 个文件，把 App 数据容器顶到 1GB+。
         加上限 400MB、超量按 mtime 裁到 300MB、每 30 次落盘体检一次。
DSH-192: 淘汰逻辑写在了 OnlineGalleryClient 的 private init() 里，而这个单例是懒加载的 ——
         只有用户点进「在线」页签才会初始化。实测 0.8.119 装上后 App 明明启动过
         （容器内 Library/Caches/com.apple.dyld 的 mtime 是 22:22:28，晚于安装时间 22:14），
         缓存目录却纹丝未动（仍 884MB、目录 mtime 停在 21:43:49）。
         ⇒ 兜底必须从「用户走到哪个页签」里解耦出来，挂到 didFinishLaunchingWithOptions。

本测试锁定两条不变量：淘汰逻辑存在且正确；淘汰在启动链路被触发。
"""

import os
import re
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
IOS_CLIENT = os.path.join(REPO_ROOT, "ios", "Album", "OnlineGalleryClient.swift")
IOS_APP = os.path.join(REPO_ROOT, "ios", "Album", "AlbumApp.swift")
IOS_PROJECT = os.path.join(REPO_ROOT, "ios", "project.yml")


def _read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def test_eviction_implementation():
    print("=== DSH-188: 磁盘缓存淘汰实现契约 ===")
    code = _read(IOS_CLIENT)

    assert "diskCacheMaxBytes" in code, "必须定义磁盘缓存上限"
    assert "diskCacheTrimBytes" in code, "必须定义裁剪目标水位"
    assert "func trimDiskCacheIfNeeded()" in code, "必须提供 trimDiskCacheIfNeeded"
    assert "private func maybeTrimAfterWrite()" in code, "必须有落盘后的低频体检入口"

    m = re.search(r"diskCacheMaxBytes:\s*Int64\s*=\s*(\d+)\s*\*\s*1024\s*\*\s*1024", code)
    assert m, "diskCacheMaxBytes 必须是 MB 量级常量"
    max_mb = int(m.group(1))
    t = re.search(r"diskCacheTrimBytes:\s*Int64\s*=\s*(\d+)\s*\*\s*1024\s*\*\s*1024", code)
    assert t, "diskCacheTrimBytes 必须是 MB 量级常量"
    trim_mb = int(t.group(1))
    assert 0 < trim_mb < max_mb, f"裁剪水位必须小于上限（{trim_mb} vs {max_mb}）"
    print(f"  PASS 1. 上限 {max_mb}MB / 裁到 {trim_mb}MB，水位关系正确")

    # 必须按 mtime 升序（最旧的先删），且循环终止条件是回到水位以下
    assert "items.sort { $0.mtime < $1.mtime }" in code, "必须按 mtime 升序淘汰最旧文件"
    assert "diskCacheTrimBytes else { break }" in code, "裁到水位以下就必须停手"
    assert "try fm.removeItem(at: it.url)" in code, "必须真的删除文件"
    print("  PASS 2. 按 mtime 升序淘汰、到水位即停")

    # 体检必须挂在两个落盘点之后
    assert code.count("maybeTrimAfterWrite()") >= 3, \
        "maybeTrimAfterWrite 必须在定义处 + 两处落盘点被调用"
    print("  PASS 3. 落盘后的体检钩子已挂到写入路径")

    # 淘汰必须跑在后台队列，不能挡启动与渲染
    idx = code.index("func trimDiskCacheIfNeeded()")
    body = code[idx:idx + 900]
    assert "imageIOQueue.async" in body, "淘汰必须异步执行，不能卡住主线程"
    print("  PASS 4. 淘汰异步执行，不阻塞启动")


def test_startup_hook():
    print("=== DSH-192: 淘汰必须挂到 App 启动链路 ===")
    app = _read(IOS_APP)

    start = app.index("didFinishLaunchingWithOptions")
    end = app.index("return true", start)
    body = app[start:end]
    assert "OnlineGalleryClient.shared.trimDiskCacheIfNeeded()" in body, \
        "didFinishLaunchingWithOptions 必须触发一次磁盘缓存体检（DSH-192 根因：单例懒加载导致淘汰永不执行）"
    print("  PASS 5. didFinishLaunchingWithOptions 已挂上启动体检")

    # 不能只在 applicationDidBecomeActive / background 里挂
    for hook in ("applicationDidBecomeActive", "applicationDidEnterBackground"):
        if hook in app:
            h_start = app.index(hook)
            h_end = app.index("\n    }", h_start)
            assert "trimDiskCacheIfNeeded" not in app[h_start:h_end], \
                f"{hook} 不该重复挂淘汰（启动一次足够，另有每 30 次落盘兜底）"
    print("  PASS 6. 其它生命周期钩子未重复挂载")

    # 启动体检不能挡住首屏：trim 内部本身就是 async，这里只要求调用位置在起 window 之前
    assert body.index("trimDiskCacheIfNeeded") < body.index("window.makeKeyAndVisible"), \
        "体检应在首屏渲染前发起（它内部异步执行，不占主线程）"
    print("  PASS 7. 体检发起时机早于首屏渲染")


def test_version_bumped():
    print("=== DSH-192: 版本真源已递增 ===")
    yml = _read(IOS_PROJECT)
    name = re.search(r"MARKETING_VERSION:\s*([0-9][0-9.]*)", yml).group(1)
    build = int(re.search(r"CURRENT_PROJECT_VERSION:\s*(\d+)", yml).group(1))
    assert build >= 231, f"改动了客户端代码就必须递增 build，当前 {build}"
    assert name != "0.8.119", f"改动了客户端代码就必须递增版本号，当前仍是 {name}"
    print(f"  PASS 8. iOS 版本真源 = {name} (build {build})")


if __name__ == "__main__":
    test_eviction_implementation()
    test_startup_hook()
    test_version_bumped()
    print("\nALL iOS DISK CACHE EVICTION TESTS PASSED")
