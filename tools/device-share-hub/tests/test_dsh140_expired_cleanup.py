# -*- coding: utf-8 -*-
"""DSH-140 回归测试：作品倒计时到期后自动移库下架与双端回收站同步守护

验证点：
1. 服务端 Watchdog 与 cleanup_expired_works() 支持自动识别已到期作品并物理移库；
2. 服务端 /api/online/cleanup-expired 接口契约正常；
3. Android MainActivity.java 对已到期作品进行强制货架过滤与 ensureTrashed 沉淀；
4. iOS OnlineWorkLifecycle.swift 对已到期作品进行货架剔除。
"""

import os
import sys
import json
import shutil
import tempfile
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
SYS_SCRIPT = os.path.join(REPO_ROOT, "scripts", "online_gallery_service.py")
ANDROID_MAIN = os.path.join(REPO_ROOT, "android", "app", "src", "main", "java", "com", "zwm", "gallery", "MainActivity.java")
ANDROID_LIFECYCLE = os.path.join(REPO_ROOT, "android", "app", "src", "main", "java", "com", "zwm", "gallery", "OnlineWorkLifecycle.java")
IOS_LIFECYCLE = os.path.join(REPO_ROOT, "ios", "Album", "OnlineWorkLifecycle.swift")

def test_dsh140_code_invariants():
    print("=== DSH-140: 静态契约与代码不变量核验 ===")
    
    # 1. 检查 online_gallery_service.py
    with open(SYS_SCRIPT, "r", encoding="utf-8") as f:
        service_code = f.read()
    assert "def cleanup_expired_works(self)" in service_code, "服务端必须包含 cleanup_expired_works 方法"
    assert "/api/online/cleanup-expired" in service_code, "服务端必须暴露 /api/online/cleanup-expired 接口"
    assert "self.cleanup_expired_works()" in service_code, "Watchdog 必须周期性调用 cleanup_expired_works"
    assert "self.scanner.cleanup_expired_works()" in service_code, "works 接口请求时必须自动即时巡检"
    print("  PASS 1. 服务端主动到期巡检、移库逻辑与接口定义完整")

    # 2. 检查 Android MainActivity.java
    with open(ANDROID_MAIN, "r", encoding="utf-8") as f:
        android_code = f.read()
    assert "work.expireAtMs > 0 && nowMs >= work.expireAtMs" in android_code, "Android 货架过滤必须判定 expireAtMs 到期"
    assert "OnlineWorkLifecycle.ensureTrashed(this, work, work.expireAtMs)" in android_code, "Android 过滤时必须自动补齐回收站"
    assert "已到期 · 正在移入回收站…" in android_code, "Android 倒计时结束必须平滑显示过渡文案"
    print("  PASS 2. Android 客户端到期强制剔除与平滑文案过渡闭环")

    # 3. 检查 Android OnlineWorkLifecycle.java
    with open(ANDROID_LIFECYCLE, "r", encoding="utf-8") as f:
        lifecycle_code = f.read()
    assert "public static synchronized void ensureTrashed" in lifecycle_code, "OnlineWorkLifecycle 必须包含 ensureTrashed"
    print("  PASS 3. Android OnlineWorkLifecycle 具备到期作品自动沉淀回收站能力")

    # 4. 检查 iOS OnlineWorkLifecycle.swift
    with open(IOS_LIFECYCLE, "r", encoding="utf-8") as f:
        ios_code = f.read()
    assert "entry.expireAtMs > 0 && nowMs >= entry.expireAtMs" in ios_code, "iOS 必须支持按 expireAtMs 过滤到期作品"
    print("  PASS 4. iOS 客户端对等过滤到期作品")

def test_dsh140_runtime_cleanup():
    print("\n=== DSH-140: 运行时主动巡检与物理移库实测 ===")
    sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))
    from online_gallery_service import WorkScanner

    temp_root = tempfile.mkdtemp(prefix="dsh140_test_")
    try:
        # 创建待发货架与已发送1次
        shelf_dir = os.path.join(temp_root, "杭州成品")
        work_dir = os.path.join(shelf_dir, "20260930_120000-杭州秋季团建爆款")
        os.makedirs(work_dir, exist_ok=True)
        with open(os.path.join(work_dir, "P1.png"), "wb") as f:
            f.write(b"\x89PNG\r\n\x1a\nfake")
        with open(os.path.join(work_dir, "文案.txt"), "w", encoding="utf-8") as f:
            f.write("杭州秋季团建超棒！这是一段测试文案大于30字用于满足质检要求的完整内容。")

        now_ms = int(time.time() * 1000)
        # 写入已经过期的倒计时 (10 分钟前到期)
        tag_data = {
            "distribution": {
                "useCount": 2,
                "firstSharedAtMs": now_ms - 70 * 60 * 1000,
                "expireAtMs": now_ms - 10 * 60 * 1000,
                "originDevice": "Redmi 9A",
                "dispatchedTo": ["Redmi 9A", "小米 15"]
            }
        }
        with open(os.path.join(work_dir, "作品标签.json"), "w", encoding="utf-8") as f:
            json.dump(tag_data, f, ensure_ascii=False)

        scanner = WorkScanner(temp_root)
        works = scanner.scan(force=True)
        assert len(works) == 1, f"初次扫描应有 1 套作品，实际 {len(works)}"
        assert works[0]["expireAtMs"] == tag_data["distribution"]["expireAtMs"]

        # 执行主动巡检
        cleaned = scanner.cleanup_expired_works()
        assert len(cleaned) == 1, f"巡检应清理 1 套过期作品，实际 {len(cleaned)}"
        assert cleaned[0]["type"] == "moved_to_stage1"

        # 验证物理文件已移入 _已发送1次
        stage1_dest = cleaned[0]["path"]
        assert os.path.exists(stage1_dest), "目标物理路径必须存在"
        assert "_已发送1次" in stage1_dest, "必须移入 _已发送1次 目录"
        assert not os.path.exists(work_dir), "原货架目录必须已被移除"

        # 验证再次扫描货架为空
        new_works = scanner.scan(force=True)
        assert len(new_works) == 0, "过期移库后，货架待发列表必须为 0 套"
        print("  PASS 运行时到期作品被 100% 自动安全物理移库并从货架下架")

    finally:
        shutil.rmtree(temp_root, ignore_errors=True)

if __name__ == "__main__":
    test_dsh140_code_invariants()
    test_dsh140_runtime_cleanup()
    print("\n✅ DSH-140 全部验证通过！")
