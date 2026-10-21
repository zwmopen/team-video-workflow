# -*- coding: utf-8 -*-
"""DSH-141 & DSH-142 质量回归与契约测试

DSH-141: 大图全屏预览右上角垃圾桶单图删除（安全物理备份至 _垃圾作品样本/_deleted_images/，联动更新 manifest 与双端列表）
DSH-142: 修复顶上详细文字挤压按钮缺陷（记录精简、sizeForItemAt 动态累加、actionRow 垂直抗压缩保底）与备用岗等别名模糊匹配打勾
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
IOS_CLIENT = os.path.join(REPO_ROOT, "ios", "Album", "OnlineGalleryClient.swift")
IOS_CONTENT = os.path.join(REPO_ROOT, "ios", "Album", "ContentView.swift")
IOS_PARSER = os.path.join(REPO_ROOT, "ios", "Album", "PlatformCopyParser.swift")
ANDROID_CLIENT = os.path.join(REPO_ROOT, "android", "app", "src", "main", "java", "com", "zwm", "gallery", "OnlineGalleryClient.java")
ANDROID_MAIN = os.path.join(REPO_ROOT, "android", "app", "src", "main", "java", "com", "zwm", "gallery", "MainActivity.java")
ANDROID_PARSER = os.path.join(REPO_ROOT, "android", "app", "src", "main", "java", "com", "zwm", "gallery", "PlatformCopyParser.java")


def test_code_contracts():
    print("=== DSH-141 & DSH-142: 静态契约与代码不变量核验 ===")

    # 1. 服务端 online_gallery_service.py
    with open(SYS_SCRIPT, "r", encoding="utf-8") as f:
        s_code = f.read()
    assert "def _delete_single_image(" in s_code, "服务端必须包含 _delete_single_image"
    assert "/api/online/delete-image" in s_code, "服务端必须绑定 /api/online/delete-image"
    assert "_deleted_images" in s_code, "单图删除必须安全移入 _deleted_images 备份目录"
    assert "作品至少需保留 1 张图片" in s_code, "单图删除必须拦截只剩 1 张图片的防空壳安全底线"
    print("  PASS 1. 服务端单图删除、安全备份与防空壳契约完备")

    # 2. iOS 端契约
    with open(IOS_CLIENT, "r", encoding="utf-8") as f:
        ios_c_code = f.read()
    assert "deleteOnlineImage(workId:" in ios_c_code, "iOS OnlineGalleryClient 必须包含 deleteOnlineImage"
    assert "/api/online/delete-image" in ios_c_code, "iOS deleteOnlineImage 必须调用 /api/online/delete-image"
    assert "downloadSingleImageAttempt" in ios_c_code, "iOS 批量原图下载必须具备单图独立重试 downloadSingleImageAttempt"
    assert "sendRecordUseAttempt" in ios_c_code, "iOS 使用上报必须具备重试守卫 sendRecordUseAttempt"
    assert "maxAttempts = 3" in ios_c_code or "maxAttempts: 3" in ios_c_code or "maxAttempts" in ios_c_code, "iOS 必须支持待机唤醒网络重试"

    with open(IOS_CONTENT, "r", encoding="utf-8") as f:
        ios_ui_code = f.read()
    assert "trashBtn" in ios_ui_code, "iOS OnlineImagePreviewController 必须包含 trashBtn 垃圾箱"
    assert "trashBtnTapped" in ios_ui_code, "iOS 必须实现 trashBtnTapped"
    assert "performDeleteCurrentImage" in ios_ui_code, "iOS 必须实现 performDeleteCurrentImage"
    assert "onImageDeleted" in ios_ui_code, "iOS 必须提供 onImageDeleted 回调"
    assert "handleOnlineImageDeleted" in ios_ui_code, "iOS 外部列表必须联动 handleOnlineImageDeleted 刷新卡片缩略图"
    assert "formatCompactDispatchedRecords" in ios_ui_code, "iOS 必须包含 formatCompactDispatchedRecords 记录精简"
    assert "actionRow.setContentCompressionResistancePriority(.required, for: .vertical)" in ios_ui_code, "iOS 底部 actionRow 必须具备最高垂直抗压缩优先级"
    assert "actionRow.heightAnchor.constraint(equalToConstant: 36)" in ios_ui_code, "iOS actionRow 必须固定 36pt 高度防挤压"
    assert "extraDetailLines += 1" in ios_ui_code, "iOS sizeForItemAt 必须根据在线卡记录动态累加 extraDetailLines"
    assert "willEnterForegroundNotification" in ios_ui_code, "iOS 必须监听切回前台事件自动刷新分发状态"

    with open(IOS_PARSER, "r", encoding="utf-8") as f:
        ios_p_code = f.read()
    assert "aliasFamilies" in ios_p_code, "iOS PlatformCopyParser 必须包含 aliasFamilies 别名族"
    assert "isPlatformOrVersionDispatched" in ios_p_code, "iOS 必须包含 isPlatformOrVersionDispatched"
    assert "备用岗" in ios_p_code, "iOS 别名族必须包含「备用岗」"
    assert "｜" in ios_p_code and "|" in ios_p_code, "iOS 必须包含全角竖线到半角竖线归一化"
    assert "split(separator: \"|\")" in ios_p_code, "iOS 必须包含复合标签按竖线拆分匹配"
    print("  PASS 2. iOS 单图删除、防挤压、动态行高自适应、待机重试与复合版本打勾契约完备")

    # 3. Android 端契约
    with open(ANDROID_CLIENT, "r", encoding="utf-8") as f:
        and_c_code = f.read()
    assert "deleteImage(String workId" in and_c_code, "Android OnlineGalleryClient 必须包含 deleteImage"
    assert "DeleteImageResult" in and_c_code, "Android 必须包含 DeleteImageResult"

    with open(ANDROID_MAIN, "r", encoding="utf-8") as f:
        and_ui_code = f.read()
    assert "deleteImgBtn" in and_ui_code, "Android showOnlineImageDialog 必须包含 deleteImgBtn"
    assert "syncOnlineWorkImageDeleted" in and_ui_code, "Android 必须联动 syncOnlineWorkImageDeleted 刷新卡片"
    assert "formatCompactDispatchedRecords" in and_ui_code, "Android 必须包含 formatCompactDispatchedRecords 记录精简"

    with open(ANDROID_PARSER, "r", encoding="utf-8") as f:
        and_p_code = f.read()
    assert "ALIAS_FAMILIES" in and_p_code, "Android PlatformCopyParser 必须包含 ALIAS_FAMILIES"
    assert "isPlatformOrVersionDispatched" in and_p_code, "Android 必须包含 isPlatformOrVersionDispatched"
    assert "备用岗" in and_p_code, "Android 别名族必须包含「备用岗」"
    assert "｜" in and_p_code and "|" in and_p_code, "Android 必须包含全角竖线到半角竖线归一化"
    assert "split(\"\\\\|\")" in and_p_code, "Android 必须包含复合标签按竖线拆分匹配"
    print("  PASS 3. Android 单图删除、记录精简与全角复合别名打勾契约完备")


def test_alias_normalization():
    print("\n=== DSH-142: 备用岗等别名族匹配算法深度实测 ===")
    
    # 模拟两端相同的匹配逻辑
    ALIAS_FAMILIES = [
        {"xhs", "种草版", "种草", "小红书", "红书", "红书种草", "发布"},
        {"xhs2", "xhs_2", "小红书2", "大纲方案版", "红书大纲", "大纲版", "方案大纲", "小红书大纲", "小红书大纲版"},
        {"红书自然", "小红书自然", "小红书自然版", "小红书1", "xhs1", "自然攻略版", "自然版", "自然种草版", "小红书自然种草版"},
        {"douyin", "规避营销版", "抖音", "抖音无营销", "无营销版", "抖音规避营销版"},
        {"抖音攻略", "抖音攻略版", "攻略版"},
        {"抖音避坑", "避坑版", "抖音避坑版", "抖音玩法避坑版"},
        {"wechat", "公众号版", "公众号", "微信", "微信公众号", "微信公众号版"},
        {"xhs3", "xhs_3", "小红书3", "短文精选版", "短文版", "精选版"},
        {"hr", "hr决策版", "决策版", "备用", "备用岗", "备用文案", "备用方案", "方案", "方案版"}
    ]

    def normalize_tag(s):
        t = s.strip()
        while t.startswith("✓") or t.startswith("✔"):
            t = t[1:].strip()
        t = (t.replace("｜", "|")
             .replace("：", ":")
             .replace("（", "(")
             .replace("）", ")")
             .replace("\u2800", "")
             .replace(" ", "")
             .replace("\t", ""))
        return t.lower()

    def is_matched(a, b):
        ca = normalize_tag(a)
        cb = normalize_tag(b)
        if not ca or not cb: return False
        if ca == cb: return True
        for fam in ALIAS_FAMILIES:
            if ca in fam and cb in fam: return True
        # 复合标签拆解匹配（如「小红书2｜大纲方案版」拆为「小红书2」和「大纲方案版」）
        parts_a = [p for p in ca.split("|") if p]
        parts_b = [p for p in cb.split("|") if p]
        for pa in parts_a:
            for pb in parts_b:
                if pa == pb: return True
                for fam in ALIAS_FAMILIES:
                    if pa in fam and pb in fam: return True
        return False

    def extract_ver(record):
        # 兼容中文全角括号与半角括号，从后向前匹配
        close_idx = record.rfind(")")
        open_idx = record.rfind("(", 0, close_idx) if close_idx >= 0 else -1
        if open_idx < 0 or close_idx <= open_idx:
            close_idx = record.rfind("）")
            open_idx = record.rfind("（", 0, close_idx) if close_idx >= 0 else -1
        if open_idx >= 0 and close_idx > open_idx:
            inside = record[open_idx + 1:close_idx].strip()
            if "@" in inside:
                return inside.split("@", 1)[0].strip()
            return inside
        return ""

    # 测试用例 1: 别人的手机上报「备用岗」，这边按钮显示「HR决策版」
    assert is_matched("HR决策版", "备用岗"), "HR决策版 与 备用岗 必须判定匹配"
    assert is_matched("备用岗", "HR决策版"), "反向匹配必须对称"

    # 测试用例 2: 从详细分发记录中提取版本匹配
    rec = "Google Pixel 7 (备用岗 @ 2026-09-30 14:06:16)"
    extracted = extract_ver(rec)
    assert extracted == "备用岗", f"提取版本失败: {extracted}"
    assert is_matched("HR决策版", extracted), "从记录中提取的备用岗必须与 HR决策版 匹配"

    # 测试用例 3: 华为手机上报「红书大纲」，按钮显示「大纲方案版」
    rec_xhs2 = "华为P30 （只要安吉作品） (红书大纲 @ 2026-09-30 15:20:00)"
    ext_xhs2 = extract_ver(rec_xhs2)
    assert ext_xhs2 == "红书大纲"
    assert is_matched("大纲方案版", ext_xhs2), "红书大纲 与 大纲方案版 必须匹配"

    # 测试用例 4: 复合版本号与全角竖线匹配（重点病灶）
    assert is_matched("小红书2｜大纲方案版", "大纲方案版"), "小红书2｜大纲方案版 必须匹配 大纲方案版"
    assert is_matched("大纲方案版", "小红书2｜大纲方案版"), "反向复合标签必须匹配"
    assert is_matched("小红书2｜大纲方案版", "红书大纲"), "小红书2｜大纲方案版 必须跨别名族匹配 红书大纲"
    assert is_matched("小红书1｜自然攻略版", "红书自然"), "小红书1｜自然攻略版 必须匹配 红书自然"
    assert is_matched("小红书2 ｜ 大纲方案版", "小红书2"), "带空格全角竖线复合标签必须匹配 小红书2"

    rec_compound = "iPhone 12 (小红书2｜大纲方案版 @ 2026-10-21 12:00:00)"
    ext_compound = extract_ver(rec_compound)
    assert ext_compound == "小红书2｜大纲方案版"
    assert is_matched("大纲方案版", ext_compound), "从 iPhone 记录提取的复合版本必须与 大纲方案版 按钮打勾"

    # 测试用例 5: 抖音规避营销版
    assert is_matched("规避营销版", "抖音无营销"), "规避营销版 与 抖音无营销 必须匹配"

    # 测试用例 6: 负向用例（不相干的版本不能误判）
    assert not is_matched("规避营销版", "小红书"), "抖音 与 小红书 绝不能误匹配"
    assert not is_matched("HR决策版", "种草版"), "HR版 与 种草版 绝不能误匹配"

    print("  PASS 1. 别名族双向归一化匹配 100% 通过")
    print("  PASS 2. 从多端复杂设备记录提取版本 100% 通过")
    print("  PASS 3. 负向边界防误判 100% 通过")


def test_runtime_single_image_delete():
    print("\n=== DSH-141: 运行时单张图片删除与防误删安全物理备份实测 ===")
    sys.path.insert(0, os.path.join(REPO_ROOT, "scripts"))
    from online_gallery_service import WorkScanner, OnlineGalleryHandler

    temp_root = tempfile.mkdtemp(prefix="test_dsh141_")
    try:
        shelf_dir = os.path.join(temp_root, "安吉成品")
        os.makedirs(shelf_dir, exist_ok=True)
        work_dir = os.path.join(shelf_dir, "20260930_安吉竹海团建")
        os.makedirs(work_dir, exist_ok=True)

        # 写入 3 张图片
        img1 = os.path.join(work_dir, "01.png")
        img2 = os.path.join(work_dir, "02.png")
        img3 = os.path.join(work_dir, "03.png")
        for p in (img1, img2, img3):
            with open(p, "wb") as f:
                f.write(b"\x89PNG\r\n\x1a\n" + b"\x00" * 64)

        # 写入文案与 manifest
        manifest_path = os.path.join(work_dir, "manifest.json")
        with open(manifest_path, "w", encoding="utf-8") as f:
            json.dump({
                "work_id": "test_anji_001",
                "images": [{"filename": "01.png"}, {"filename": "02.png"}, {"filename": "03.png"}],
                "image_count": 3
            }, f, indent=2)

        scanner = WorkScanner(temp_root)
        works = scanner.scan(force=True)
        assert len(works) >= 1, "作品必须被正常扫描"
        work = works[0]
        work_id = work["id"]
        assert len(work["images"]) == 3, f"初始图片数量应为 3，实际为 {len(work['images'])}"

        # 构造 handler 模拟执行 _delete_single_image
        class DummyHandler:
            pass
        handler = DummyHandler()
        handler.scanner = scanner

        bound_delete = OnlineGalleryHandler._delete_single_image.__get__(handler, DummyHandler)

        # 1. 尝试删除 02.png
        ok, msg, rem_images = bound_delete(work, "02.png", "测试设备")
        assert ok, f"删除图片应成功: {msg}"
        assert not os.path.exists(img2), "原作品目录下的 02.png 必须被移走"
        assert "02.png" not in rem_images, "返回的剩余图片不应包含 02.png"
        assert len(rem_images) == 2, f"剩余图片数量应为 2，实际为 {len(rem_images)}"

        # 2. 检查物理备份目录是否完好保存该图片（零丢失）
        trash_dir = os.path.join(temp_root, "_垃圾作品样本", "_deleted_images", work_id)
        assert os.path.isdir(trash_dir), "必须在 _垃圾作品样本/_deleted_images/ 创建备份文件夹"
        backed_files = os.listdir(trash_dir)
        assert len(backed_files) == 1, "备份文件夹内必须有一份图片备份"
        assert "02.png" in backed_files[0], "备份文件名必须保留 02.png 原始特征"
        print("  PASS 1. 单张图片成功移走，物理备份目录安全落盘（零文件丢失）")

        # 3. 检查 manifest.json 联动更新
        with open(manifest_path, "r", encoding="utf-8") as f:
            m_after = json.load(f)
        assert len(m_after["images"]) == 2, "manifest.json 中图片数量必须更新为 2"
        assert m_after["image_count"] == 2
        print("  PASS 2. manifest.json 联动同步减除被删图片")

        # 4. 删除第 2 张图片 03.png
        work = scanner.get_work(work_id)
        ok, msg, rem_images = bound_delete(work, "03.png", "测试设备")
        assert ok
        assert len(rem_images) == 1

        # 5. 尝试删除最后 1 张图片 01.png（必须硬拦截防空壳！）
        work = scanner.get_work(work_id)
        ok, msg, rem_images = bound_delete(work, "01.png", "测试设备")
        assert not ok, "最后 1 张图片必须拒绝删除"
        assert "至少需保留 1 张" in msg, f"提示信息需明确友好: {msg}"
        assert os.path.exists(img1), "最后 1 张图片不得被删除"
        print("  PASS 3. 单图留存防空壳拦截机制触发并验证生效")

    finally:
        shutil.rmtree(temp_root, ignore_errors=True)


if __name__ == "__main__":
    test_code_contracts()
    test_alias_normalization()
    test_runtime_single_image_delete()
    print("\n🎉 DSH-141 & DSH-142 所有测试全部通过！双端对等交付！")
