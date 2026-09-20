#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Unit tests for Online Gallery Service
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

# Add scripts directory to path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from online_gallery_service import WorkScanner, ThreadingHTTPServer, OnlineGalleryHandler, detect_destination


class TestOnlineGalleryService(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp_dir = tempfile.mkdtemp(prefix="test_online_gallery_")
        # Create stage folders
        cls.stage0 = os.path.join(cls.temp_dir, "已发送0次（抖音小红书可发）")
        cls.stage1 = os.path.join(cls.temp_dir, "已发送1次（微信公众号可发）")
        os.makedirs(cls.stage0, exist_ok=True)
        os.makedirs(cls.stage1, exist_ok=True)

        # Create sample work in stage0: 安吉团建方案
        cls.work1_dir = os.path.join(cls.stage0, "20260918_100000_安吉2天1夜秋季团建大爆款")
        os.makedirs(cls.work1_dir, exist_ok=True)
        with open(os.path.join(cls.work1_dir, "P1_封面.png"), "wb") as f:
            # Minimal 1x1 PNG bytes
            f.write(b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15c4\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82")
        with open(os.path.join(cls.work1_dir, "文案.txt"), "w", encoding="utf-8") as f:
            f.write("安吉秋日团建攻略：HR直接抄作业！")

        # Create sample work in stage0: 莫干山
        cls.work2_dir = os.path.join(cls.stage0, "20260917_090000_莫干山赏秋指南")
        os.makedirs(cls.work2_dir, exist_ok=True)
        with open(os.path.join(cls.work2_dir, "1.jpg"), "wb") as f:
            f.write(b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x01\x00H\x00H\x00\x00\xff\xdb\x00C\x00\x08\x06\x06\x07\x06\x05\x08\x07\x07\x07\t\t\x08\n\x0c\x14\r\x0c\x0b\x0b\x0c\x19\x12\x13\x0f\x14\x1d\x1a\x1f\x1e\x1d\x1a\x1c\x1c $.' \",#\x1c\x1c(7),01444\x1f'9=82<.342\xff\xc0\x00\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00\xff\xc4\x00\x1f\x00\x00\x01\x05\x01\x01\x01\x01\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x01\x02\x03\x04\x05\x06\x07\x08\t\n\x0b\xff\xda\x00\x08\x01\x01\x00\x00?\x00\xbf\x00\xff\xd9")

        # Start test HTTP server on random port
        cls.scanner = WorkScanner(cls.temp_dir)
        OnlineGalleryHandler.scanner = cls.scanner
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), OnlineGalleryHandler)
        cls.port = cls.server.server_port
        cls.server_thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.server_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        shutil.rmtree(cls.temp_dir, ignore_errors=True)

    def test_destination_detection(self):
        self.assertEqual(detect_destination("安吉2天1夜团建"), "安吉")
        self.assertEqual(detect_destination("莫干山赏秋攻略"), "莫干山")
        self.assertEqual(detect_destination("千岛湖骑行"), "千岛湖")
        self.assertEqual(detect_destination("没有明确目的地的标题"), "其他")

    def test_status_endpoint(self):
        url = f"http://127.0.0.1:{self.port}/api/online/status"
        with urllib.request.urlopen(url) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data["ok"])
            self.assertEqual(data["totalWorks"], 2)

    def test_categories_endpoint(self):
        url = f"http://127.0.0.1:{self.port}/api/online/categories"
        with urllib.request.urlopen(url) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data["ok"])
            names = [c["name"] for c in data["categories"]]
            self.assertNotIn("全部", names)
            self.assertIn("安吉", names)
            self.assertIn("莫干山", names)

    def test_works_search_and_filter(self):
        # 1. Filter by category 安吉
        url = f"http://127.0.0.1:{self.port}/api/online/works?category={urllib.parse.quote('安吉')}"
        with urllib.request.urlopen(url) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            self.assertEqual(len(data["works"]), 1)
            self.assertEqual(data["works"][0]["destination"], "安吉")

        # 2. Search by query '秋季'
        url = f"http://127.0.0.1:{self.port}/api/online/works?query={urllib.parse.quote('秋季')}"
        with urllib.request.urlopen(url) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            self.assertEqual(len(data["works"]), 1)
            self.assertIn("安吉", data["works"][0]["title"])

    def test_two_uses_protection_rule(self):
        work_id = "20260918_100000_安吉2天1夜秋季团建大爆款"
        url = f"http://127.0.0.1:{self.port}/api/online/use-work"

        # First use (sendCount: 0 -> 1)
        req_data = json.dumps({
            "workId": work_id,
            "device": "红米 13C 5G",
            "platform": "小红书"
        }).encode("utf-8")
        req = urllib.request.Request(url, data=req_data, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data["ok"])
            self.assertEqual(data["useCount"], 1)
            self.assertEqual(data["remainingUses"], 1)
            self.assertFalse(data["moved"])  # MUST NOT MOVE!

        # Verify folder STILL exists in stage 0 (NOT moved!)
        self.assertTrue(os.path.exists(self.work1_dir))

        # Check tag file was updated
        tag_file = os.path.join(self.work1_dir, "作品标签.json")
        self.assertTrue(os.path.exists(tag_file))
        with open(tag_file, "r", encoding="utf-8") as fp:
            tags = json.load(fp)
            self.assertEqual(tags["distribution"]["useCount"], 1)
            self.assertTrue(any("红米 13C 5G" in r for r in tags["distribution"]["dispatchedTo"]))

        # Second use (sendCount: 1 -> 2)
        req_data2 = json.dumps({
            "workId": work_id,
            "device": "华为 P30",
            "platform": "抖音"
        }).encode("utf-8")
        req2 = urllib.request.Request(url, data=req_data2, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req2) as resp:
            data2 = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data2["ok"])
            self.assertEqual(data2["useCount"], 2)
            self.assertEqual(data2["remainingUses"], 0)
            self.assertFalse(data2["moved"])  # Still not abruptly moved!

        # Folder remains intact
        self.assertTrue(os.path.exists(self.work1_dir))


class TestCodeFreshness(unittest.TestCase):
    """代码新鲜度自检的真实场景测试。

    ⚠️ 这个类存在的唯一理由，就是防止「假闸门」再次出现。

    初版实现是：
        SCRIPT_MTIME = os.path.getmtime(SCRIPT_PATH)   # 导入时取的一次性快照
        PROCESS_STARTED_AT = time.time()
        stale = SCRIPT_MTIME > PROCESS_STARTED_AT + 1.0
    这个条件在启动瞬间**必然不成立**（脚本肯定早于进程存在），
    所以 staleCode 永远是 False —— 一道安静失效的闸门，比没有更危险。

    因此这里**必须真的去改磁盘上的文件内容**再断言。
    任何「构造场景 / monkeypatch 常量 / 只查字段存在」的测法都会漏掉这个 bug。
    """

    def setUp(self):
        self.scripts_dir = os.path.dirname(os.path.abspath(__file__))
        self.src = os.path.join(self.scripts_dir, "online_gallery_service.py")
        self.tmp = tempfile.mkdtemp(prefix="test_code_freshness_")
        self.dst = os.path.join(self.tmp, "online_gallery_service.py")
        shutil.copy2(self.src, self.dst)
        import importlib.util
        spec = importlib.util.spec_from_file_location("ogs_freshness_probe", self.dst)
        self.mod = importlib.util.module_from_spec(spec)
        sys.modules["ogs_freshness_probe"] = self.mod
        spec.loader.exec_module(self.mod)

    def tearDown(self):
        sys.modules.pop("ogs_freshness_probe", None)
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_not_stale_right_after_import(self):
        """刚导入时磁盘内容 == 启动快照 → 不该报 stale（判据不能反向）。"""
        out = self.mod.code_freshness()
        self.assertFalse(out["staleCode"], "刚启动就报 staleCode=True，说明判据方向反了")
        self.assertEqual(out["hint"], "")
        self.assertEqual(out["scriptShaRunning"], out["scriptShaOnDisk"])

    def test_stale_detected_after_script_content_changed(self):
        """服务运行期间有人改了脚本 → 必须报 stale（这条就是防假闸门的）。"""
        with open(self.dst, "a", encoding="utf-8") as fp:
            fp.write("\n# 模拟：有人在服务运行期间改了脚本，但没重启\n")
        out = self.mod.code_freshness()
        self.assertTrue(
            out["staleCode"],
            "磁盘脚本已改，staleCode 仍为 False —— 闸门失效，这正是本次要防的 bug",
        )
        self.assertNotEqual(out["scriptShaRunning"], out["scriptShaOnDisk"])
        self.assertIn("重启", out["hint"])

    def test_judgement_is_content_based_not_mtime(self):
        """把内容改回去（mtime 已变）→ 指纹重新一致 → 不再报 stale。

        这条证明判据是**内容哈希**而不是 mtime：若退化成 mtime 比较，
        mtime 已被追加写改过，就会误报 stale。
        """
        with open(self.dst, "a", encoding="utf-8") as fp:
            fp.write("\n# 临时改动\n")
        self.assertTrue(self.mod.code_freshness()["staleCode"])

        with open(self.src, "rb") as fp:
            original = fp.read()
        with open(self.dst, "wb") as fp:
            fp.write(original)

        out = self.mod.code_freshness()
        self.assertFalse(
            out["staleCode"], "内容已恢复一致却仍报 stale，说明判据退化成 mtime 比较了"
        )
        self.assertEqual(out["scriptShaRunning"], out["scriptShaOnDisk"])

    def test_judgement_is_immune_to_line_ending_changes(self):
        """行尾符从 LF 变 CRLF（代码内容没变）→ 不得报 stale。

        本仓库 core.autocrlf=true，一次 git checkout / git pull 就会把工作区文件
        从 LF 改写成 CRLF。若不归一化行尾就做内容哈希，staleCode 会因这种
        纯字节差异误报并一直亮着，久而久之就没人看这个信号了。
        实测：checkout 后原样 sha=ec52055b6144，归一化后=212f2a86b697（与 HEAD 一致）。
        """
        with open(self.src, "rb") as fp:
            body = fp.read().replace(b"\r\n", b"\n")
        self.assertIn(b"\n", body)
        crlf = body.replace(b"\n", b"\r\n")
        with open(self.dst, "wb") as fp:
            fp.write(crlf)                       # 只改行尾，不改任何代码内容

        out = self.mod.code_freshness()
        self.assertFalse(
            out["staleCode"],
            "仅行尾符不同就报 staleCode=True —— 会产生长期噪声，等于又造一道假闸门",
        )
        self.assertEqual(out["scriptShaRunning"], out["scriptShaOnDisk"])

    def test_fingerprint_helper_degrades_safely(self):
        """指纹函数对不存在的路径必须安全降级，不能抛异常。"""
        mtime, sha = self.mod._script_fingerprint(
            os.path.join(self.tmp, "不存在的文件.py")
        )
        self.assertEqual(mtime, 0.0)
        self.assertEqual(sha, "")


if __name__ == "__main__":
    unittest.main()
