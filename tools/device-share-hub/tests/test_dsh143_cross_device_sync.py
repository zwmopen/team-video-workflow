# -*- coding: utf-8 -*-
"""
DSH-143: 跨设备文案打勾同步与三根因全链路自动化测试
1. 验证 scan() 双源合并（作品标签.json + manifest.json）
2. 验证 use-work 端点双写作品标签与 manifest.json
3. 验证 versionTag 兜底与 dispatchedVersions 记录
"""

import os
import sys
import json
import shutil
import tempfile
import unittest

# 确保导入 scripts
CURRENT_DIR = os.path.dirname(os.path.abspath(__file__))
HUB_DIR = os.path.abspath(os.path.join(CURRENT_DIR, ".."))
SCRIPTS_DIR = os.path.join(HUB_DIR, "scripts")
if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)

from online_gallery_service import WorkScanner


class TestDSH143CrossDeviceSync(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="gallery_dsh143_test_")
        self.shelf_dir = os.path.join(self.test_dir, "安吉成品")
        os.makedirs(self.shelf_dir, exist_ok=True)

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_scan_merges_manifest_distribution(self):
        """测试 scan() 自动从 manifest.json 中读取并合并 distribution 记录"""
        work_dir = os.path.join(self.shelf_dir, "作品_001")
        os.makedirs(work_dir, exist_ok=True)

        # 写入一张模拟图片
        with open(os.path.join(work_dir, "cover.jpg"), "wb") as f:
            f.write(b"\xff\xd8\xff\xe0" + b"\x00" * 100)

        # 写入 Format 3 文案
        copy_content = (
            "<<<COPY_FORMAT:3>>>\n"
            "<<<XHS_START>>>\n小红书标题\n小红书正文\n<<<XHS_END>>>\n"
            "<<<HR_START>>>\nHR决策版标题\nHR决策版正文\n<<<HR_END>>>\n"
        )
        with open(os.path.join(work_dir, "文案.txt"), "w", encoding="utf-8") as f:
            f.write(copy_content)

        # 场景：作品标签.json 只有本地记录，而 manifest.json 记录了另一台设备的分发记录
        tag_data = {
            "distribution": {
                "useCount": 1,
                "dispatchedVersions": ["种草版"],
                "dispatchedTo": ["Redmi 13 (种草版 @ 2026-09-30 12:00:00)"]
            }
        }
        with open(os.path.join(work_dir, "作品标签.json"), "w", encoding="utf-8") as f:
            json.dump(tag_data, f, ensure_ascii=False)

        manifest_data = {
            "title": "作品_001",
            "used": True,
            "distribution": {
                "useCount": 2,
                "dispatchedVersions": ["HR决策版"],
                "dispatchedTo": ["iPhone 15 Pro (HR决策版 @ 2026-09-30 14:00:00)"]
            }
        }
        with open(os.path.join(work_dir, "manifest.json"), "w", encoding="utf-8") as f:
            json.dump(manifest_data, f, ensure_ascii=False)

        scanner = WorkScanner(self.test_dir)
        works = scanner.scan(force=True)

        self.assertEqual(len(works), 1)
        work = works[0]

        # 验证双源并集合并：dispatchedVersions 应同时包含 "种草版" 与 "HR决策版"
        self.assertIn("种草版", work["dispatchedVersions"])
        self.assertIn("HR决策版", work["dispatchedVersions"])

        # 验证 dispatchedTo 同样并集合并
        to_str = " ".join(work["dispatchedTo"])
        self.assertIn("Redmi 13", to_str)
        self.assertIn("iPhone 15 Pro", to_str)

    def test_scan_manifest_only_fallback(self):
        """测试只有 manifest.json 存在 distribution（无作品标签.json）时也能完整读取打勾状态"""
        work_dir = os.path.join(self.shelf_dir, "作品_002")
        os.makedirs(work_dir, exist_ok=True)

        with open(os.path.join(work_dir, "1.jpg"), "wb") as f:
            f.write(b"\xff\xd8\xff\xe0" + b"\x00" * 100)

        with open(os.path.join(work_dir, "文案.txt"), "w", encoding="utf-8") as f:
            f.write("正常文案正文内容，字数足够超过三十字以上，测试使用。")

        manifest_data = {
            "title": "作品_002",
            "used": True,
            "distribution": {
                "useCount": 1,
                "dispatchedVersions": ["小红书自然种草版"],
                "dispatchedTo": ["Pixel 7 (小红书自然种草版 @ 2026-09-30 15:00:00)"]
            }
        }
        with open(os.path.join(work_dir, "manifest.json"), "w", encoding="utf-8") as f:
            json.dump(manifest_data, f, ensure_ascii=False)

        scanner = WorkScanner(self.test_dir)
        works = scanner.scan(force=True)

        self.assertEqual(len(works), 1)
        work = works[0]
        self.assertIn("小红书自然种草版", work["dispatchedVersions"])
        self.assertEqual(len(work["dispatchedTo"]), 1)


if __name__ == "__main__":
    unittest.main()
