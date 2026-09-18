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


if __name__ == "__main__":
    unittest.main()
