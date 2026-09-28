#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Unit tests for Cross Shelf Junction Linking and Service Integration
==================================================================
验证：
1. 软链接建立后，双货架双目录正常呈现（唯一 workId、is_symlink 标记、原图文案直通）；
2. 软链接镜像使用与删除：仅解绑副货架镜像，主货架物理本体完好不受破坏；
3. 物理本体使用与删除：移走本体的同时，所有指向该本体的副货架软链接镜像自动解除；
4. 外部异常导致死链时，服务扫描自动自愈清理死链，零报错零崩溃。
"""

import os
import sys
import json
import time
import shutil
import tempfile
import unittest
import urllib.request
import urllib.error
import threading

# 注入 scripts 目录
SCRIPTS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)

from cross_shelf_linker import (
    create_junction,
    unlink_junction,
    is_junction_or_symlink,
    resolve_junction_source,
    cleanup_junctions_for_source,
    prune_dangling_junctions,
    load_registry,
)
from online_gallery_service import WorkScanner, ThreadingHTTPServer, OnlineGalleryHandler

# 1x1 PNG bytes
PNG_1PX = (b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
           b"\x08\x06\x00\x00\x00\x1f\x15c4\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00"
           b"\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82")


class TestJunctionLinking(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp_root = tempfile.mkdtemp(prefix="test_junction_gallery_")
        cls.shelf_anji = os.path.join(cls.temp_root, "安吉成品")
        cls.shelf_midautumn = os.path.join(cls.temp_root, "中秋国庆成品")
        cls.stage1 = os.path.join(cls.temp_root, "已发送1次（微信公众号可发）")
        cls.garbage = os.path.join(cls.temp_root, "_垃圾作品样本")

        os.makedirs(cls.shelf_anji, exist_ok=True)
        os.makedirs(cls.shelf_midautumn, exist_ok=True)
        os.makedirs(cls.stage1, exist_ok=True)
        os.makedirs(cls.garbage, exist_ok=True)

        # 启动测试 HTTP 服务
        cls.scanner = WorkScanner(cls.temp_root)
        OnlineGalleryHandler.scanner = cls.scanner
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), OnlineGalleryHandler)
        cls.port = cls.server.server_port
        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        # 清理可能残留的 junctions
        try:
            prune_dangling_junctions(cls.temp_root)
        except Exception:
            pass
        shutil.rmtree(cls.temp_root, ignore_errors=True)

    def _create_sample_work(self, shelf_dir: str, folder_name: str) -> str:
        work_dir = os.path.join(shelf_dir, folder_name)
        os.makedirs(work_dir, exist_ok=True)
        with open(os.path.join(work_dir, "P1_封面.png"), "wb") as f:
            f.write(PNG_1PX)
        with open(os.path.join(work_dir, "P2_详情.png"), "wb") as f:
            f.write(PNG_1PX)
        with open(os.path.join(work_dir, "文案.txt"), "w", encoding="utf-8") as f:
            f.write("安吉秋日中秋国庆团建全套方案！HR速速收藏！")
        with open(os.path.join(work_dir, "作品标签.json"), "w", encoding="utf-8") as f:
            json.dump({"distribution": {"useCount": 0, "dispatchedTo": []}}, f)
        self.scanner.scan(force=True)
        return work_dir

    def test_01_create_junction_and_dual_shelf_presentation(self):
        """测试 1：创建 Junction 软链接后，双货架同时呈现且独立访问原图文案"""
        work_name = "20260920_100000_安吉中秋露营团建大方案"
        src_path = self._create_sample_work(self.shelf_anji, work_name)

        # 在中秋国庆副货架创建软链接
        ok, link_path, msg = create_junction(src_path, self.shelf_midautumn, root_dir=self.temp_root)
        self.assertTrue(ok, f"创建软链接失败: {msg}")
        self.assertTrue(is_junction_or_symlink(link_path), "目标必须是 Junction")
        resolved = resolve_junction_source(link_path)
        self.assertEqual(os.path.normcase(os.path.abspath(resolved)), os.path.normcase(os.path.abspath(src_path)))

        # 扫描服务
        works = self.scanner.scan(force=True)
        # 必须同时有两个条目：一个来自安吉，一个来自中秋国庆
        matching = [w for w in works if w["rawTitle"] == work_name]
        self.assertEqual(len(matching), 2, f"双货架应呈现 2 个条目，实际: {len(matching)}")

        src_work = next(w for w in matching if not w.get("is_symlink"))
        link_work = next(w for w in matching if w.get("is_symlink"))

        self.assertEqual(src_work["id"], work_name)
        self.assertTrue(link_work["id"].startswith(work_name + "__link_"))
        self.assertEqual(link_work["source_path"], os.path.abspath(src_path))
        self.assertEqual(src_work["imageCount"], 2)
        self.assertEqual(link_work["imageCount"], 2)
        self.assertEqual(src_work["copyText"], link_work["copyText"])

        # 验证 HTTP 访问两者的原图
        for w in (src_work, link_work):
            img_url = f"http://127.0.0.1:{self.port}/api/online/image?id={urllib.parse.quote(w['id'])}&file={urllib.parse.quote(w['images'][0])}"
            with urllib.request.urlopen(img_url) as resp:
                self.assertEqual(resp.status, 200)
                self.assertEqual(resp.read(), PNG_1PX)

    def test_02_use_junction_link_only_unlinks_mirror(self):
        """测试 2：使用副货架软链接镜像，仅解绑移除该软链接，主货架本体完好保留"""
        work_name = "20260920_100000_安吉中秋露营团建大方案"
        link_id = f"{work_name}__link_中秋国庆"
        link_path = os.path.join(self.shelf_midautumn, work_name)
        src_path = os.path.join(self.shelf_anji, work_name)

        # 确保前置状态
        self.assertTrue(os.path.exists(link_path))
        self.assertTrue(os.path.exists(src_path))

        # 调用 /api/online/use-work 使用软链接
        req_data = json.dumps({"workId": link_id, "device": "iPhone15", "platform": "小红书"}).encode("utf-8")
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/api/online/use-work", data=req_data,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data.get("ok"))
            self.assertTrue(data.get("isSymlink"))
            self.assertFalse(data.get("moved"))

        # 验证副货架软链接已解绑解除
        self.assertFalse(os.path.lexists(link_path), "副货架软链接镜像必须已解除")

        # 验证主货架物理本体原封不动保留
        self.assertTrue(os.path.exists(src_path), "主货架物理本体必须完好保留在原货架")
        self.assertTrue(os.path.exists(os.path.join(src_path, "P1_封面.png")), "原图不可受损")
        # 且源本体已记录一次使用
        with open(os.path.join(src_path, "作品标签.json"), "r", encoding="utf-8") as fp:
            tag = json.load(fp)
            self.assertEqual(tag.get("distribution", {}).get("useCount"), 1)

    def test_03_delete_junction_link_only_unlinks_mirror(self):
        """测试 3：删除副货架软链接镜像，仅解除副货架软链接，主货架本体绝对不可进入垃圾库"""
        work_name = "20260921_110000_安吉山野徒步作品"
        src_path = self._create_sample_work(self.shelf_anji, work_name)

        ok, link_path, _ = create_junction(src_path, self.shelf_midautumn, root_dir=self.temp_root)
        self.assertTrue(ok)
        self.scanner.scan(force=True)

        link_id = f"{work_name}__link_中秋国庆"
        # 调用 /api/online/delete-work 删除该软链接镜像
        req_data = json.dumps({"workId": link_id, "deviceName": "iPad", "remark": "副货架下架"}).encode("utf-8")
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/api/online/delete-work", data=req_data,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data.get("ok"))
            self.assertEqual(data.get("action"), "junction_unlinked")

        # 软链接解除
        self.assertFalse(os.path.lexists(link_path))
        # 主货架物理本体完好在原位，垃圾库里没有它
        self.assertTrue(os.path.exists(src_path), "主货架物理本体必须原位保留")
        self.assertFalse(os.path.exists(os.path.join(self.garbage, work_name)), "绝对不可进入垃圾库")

    def test_04_use_physical_work_cleans_up_linked_junctions(self):
        """测试 4：使用物理本体，本体移入已发送1次，副货架上的软链接镜像自动清除（防死链）"""
        work_name = "20260922_120000_双重挂载本体使用测试"
        src_path = self._create_sample_work(self.shelf_anji, work_name)

        ok, link_path, _ = create_junction(src_path, self.shelf_midautumn, root_dir=self.temp_root)
        self.assertTrue(ok)
        self.assertTrue(os.path.exists(link_path))

        # 使用主货架本体
        req_data = json.dumps({"workId": work_name, "device": "iPhone16", "platform": "抖音"}).encode("utf-8")
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/api/online/use-work", data=req_data,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data.get("ok"))
            self.assertTrue(data.get("moved"))

        # 本体已从主货架移走
        self.assertFalse(os.path.exists(src_path))
        # 副货架软链接镜像必须被自动清除，绝对不产生死链！
        self.assertFalse(os.path.lexists(link_path), "副货架软链接镜像必须已被联动清除")

    def test_05_delete_physical_work_cleans_up_linked_junctions(self):
        """测试 5：删除物理本体，本体移入垃圾库，副货架上的软链接镜像自动清除（防死链）"""
        work_name = "20260923_130000_双重挂载本体删除测试"
        src_path = self._create_sample_work(self.shelf_anji, work_name)

        ok, link_path, _ = create_junction(src_path, self.shelf_midautumn, root_dir=self.temp_root)
        self.assertTrue(ok)
        self.assertTrue(os.path.exists(link_path))

        # 删除主货架物理本体
        req_data = json.dumps({"workId": work_name, "deviceName": "Tester", "remark": "人工废弃"}).encode("utf-8")
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/api/online/delete-work", data=req_data,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data.get("ok"))
            self.assertEqual(data.get("action"), "garbage_deleted")

        # 本体已移入垃圾库
        self.assertFalse(os.path.exists(src_path))
        self.assertTrue(os.path.exists(os.path.join(self.garbage, work_name)))
        # 副货架软链接镜像自动清除
        self.assertFalse(os.path.lexists(link_path), "副货架软链接镜像必须已被联动清除")

    def test_06_dangling_junction_auto_healing(self):
        """测试 6：死链软链接自愈：外部强行删除物理源目录时，扫描器自动清理死链且服务零崩溃"""
        work_name = "20260924_140000_外部意外删除死链测试"
        src_path = self._create_sample_work(self.shelf_anji, work_name)

        ok, link_path, _ = create_junction(src_path, self.shelf_midautumn, root_dir=self.temp_root)
        self.assertTrue(ok)
        self.assertTrue(os.path.lexists(link_path))

        # 模拟外部意外将物理源目录完全移除
        shutil.rmtree(src_path)
        self.assertFalse(os.path.exists(src_path))
        # 此时 link_path 成为死链
        self.assertTrue(os.path.lexists(link_path))

        # 触发服务强制扫描自愈
        works = self.scanner.scan(force=True)
        # 扫描应平稳完成，且该死链软链接被自动解绑自愈
        self.assertFalse(os.path.lexists(link_path), "死链必须被扫描器自愈清除")
        # 并且结果中不应包含该作品
        matching = [w for w in works if w["rawTitle"] == work_name]
        self.assertEqual(len(matching), 0)


if __name__ == "__main__":
    unittest.main()
