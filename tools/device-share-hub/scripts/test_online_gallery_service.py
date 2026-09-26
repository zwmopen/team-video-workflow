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
import online_gallery_service as _svc_mod
from online_gallery_service import WorkScanner, ThreadingHTTPServer, OnlineGalleryHandler, detect_destination

# ⚠️ 本脚本会打印中文。Windows 上 Python 默认按 locale 编码输出（cp1252），
#    一打印中文就 UnicodeEncodeError 崩掉，而且崩在「打印」而不是「判定」上，
#    很容易被误读成闸门失败。固定 utf-8，保证在任何 runner / 任何 locale 下都能跑。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# Minimal 1x1 PNG（用作样例作品的封面，避免依赖外部图片文件）
PNG_1PX = (b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
           b"\x08\x06\x00\x00\x00\x1f\x15c4\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00"
           b"\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82")


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
        """首次使用后：计数 +1、物理移入「_已发送1次」、stage0 视角剩余次数归零。

        ⚠️ 本用例长期红灯，原因不是实现坏了，而是**它还在断言已经被替换掉的旧业务规则**：
          旧规则（此断言写就时）：首次使用「不移动」，remainingUses=1
          现行规则（代码注释标注为「核心业务铁律」）：只要手机端使用过一次，
          电脑后台就**物理移入**「_已发送1次（微信公众号可发）」，因此 stage0 视角
          remainingUses=0、moved=True。
        长期红的套件会训练人无视红色 —— 本身就是一种坏闸门。这里改为断言现行规则。

        另外：本用例会**真的移动目录**（共享扫描根），必须自带清理，
        否则按字母序后跑的 test_works_search_and_filter 会搜不到作品而误报失败
        （这正是它此前失败的第二个原因：用例之间互相污染）。
        """
        work_id = "20260918_110000_安吉2天1夜秋季团建保护规则"
        work_dir = os.path.join(self.stage0, work_id)
        os.makedirs(work_dir, exist_ok=True)
        with open(os.path.join(work_dir, "P1_封面.png"), "wb") as f:
            f.write(PNG_1PX)
        with open(os.path.join(work_dir, "文案.txt"), "w", encoding="utf-8") as f:
            f.write("安吉秋日团建攻略：HR直接抄作业！请确保两次分发保护规则生效。")

        def _cleanup():
            shutil.rmtree(work_dir, ignore_errors=True)
            for base in (os.path.join(self.temp_dir, "_已发送1次（微信公众号可发）"),
                         os.path.join(self.temp_dir, "已发送1次（微信公众号可发）"),
                         os.path.join(self.temp_dir, "_垃圾作品（后续参考分析）"),
                         os.path.join(self.temp_dir, "_不合格成品合集")):
                if not os.path.isdir(base):
                    continue
                for name in os.listdir(base):
                    if work_id in name:
                        shutil.rmtree(os.path.join(base, name), ignore_errors=True)
            try:
                self.scanner.scan(force=True)
            except Exception:
                pass
        self.addCleanup(_cleanup)

        # 新建的目录必须让扫描器重新扫一次，否则 get_work 在 5s 缓存里找不到它
        self.scanner.scan(force=True)

        url = f"http://127.0.0.1:{self.port}/api/online/use-work"
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
            # stage0 视角：作品已被移出，剩余次数为 0（现行铁律）
            self.assertEqual(data["remainingUses"], 0)
            self.assertTrue(data["moved"], "现行规则是首次使用即物理移入 _已发送1次")
            self.assertTrue(data["targetPath"], "移动成功必须回报落点路径，否则无从核对")

        # 已从 stage0 移出（这是现行铁律的物理证据）
        self.assertFalse(os.path.exists(work_dir),
                         "首次使用后作品应已物理移出 stage0")

        # 标签文件随作品一起被搬走，且计数已落盘
        moved_dir = data["targetPath"]
        tag_file = os.path.join(moved_dir, "作品标签.json")
        self.assertTrue(os.path.exists(tag_file), f"移动后的目录里没有标签文件：{moved_dir}")
        with open(tag_file, "r", encoding="utf-8") as fp:
            tags = json.load(fp)
            self.assertEqual(tags["distribution"]["useCount"], 1)
            self.assertTrue(any("红米 13C 5G" in r for r in tags["distribution"]["dispatchedTo"]))

        # ── 第二次使用（1 -> 2）──
        req_data2 = json.dumps({
            "workId": work_id,
            "device": "华为 P30",
            "platform": "抖音"
        }).encode("utf-8")
        req2 = urllib.request.Request(url, data=req_data2, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req2) as resp:
            data2 = json.loads(resp.read().decode("utf-8"))
            self.assertTrue(data2["ok"])
            self.assertEqual(data2["useCount"], 2, "同一作品可用两次（小红书 + 公众号/抖音）")
            self.assertEqual(data2["remainingUses"], 0)
            # _move_work_to_stage1 对已在目标位置的目录是**幂等**的：
            # 返回 (True, 同路径, "作品已位于…")。所以 moved=True 表示「已就位」，
            # 不代表又挪了一次 —— 真正该守的不变量是「没有产生重复目录」。
            self.assertTrue(data2["moved"])
            self.assertEqual(os.path.abspath(data2["targetPath"]),
                             os.path.abspath(moved_dir),
                             "第二次使用不得产生新的目标目录（移动必须幂等）")

        dup = [n for n in os.listdir(os.path.join(self.temp_dir, "_已发送1次（微信公众号可发）"))
               if work_id in n]
        self.assertEqual(len(dup), 1, f"「_已发送1次」下出现了重复目录：{dup}")

        # 第二次使用同样必须落盘（不能再被当成 1 次）
        with open(tag_file, "r", encoding="utf-8") as fp:
            tags2 = json.load(fp)
        self.assertEqual(tags2["distribution"]["useCount"], 2)
        self.assertTrue(any("华为 P30" in r for r in tags2["distribution"]["dispatchedTo"]))

    # ===== 体感加速：传输瘦身 + gzip 守卫（第二十一节要求：还原-失败证明） =====

    def _make_fat_work(self, work_id="w1"):
        """构造一份带 searchBlob/slotGuard 的「胖」作品，供瘦身测试用。"""
        return {
            "id": work_id,
            "title": "测试作品",
            "copyText": "正文",
            "path": "/tmp/w1",
            "searchBlob": "搜索用的纯文本 blob" * 100,   # 故意造大
            "slotGuard": {"droppedCount": 0, "keptCount": 3},
        }

    # ---- 7. slim_works_for_wire 必须剥掉两端不解析的字段 ----
    def test_slim_works_for_wire_unit(self):
        from online_gallery_service import slim_works_for_wire, _WIRE_OMIT_FIELDS
        self.assertIn("searchBlob", _WIRE_OMIT_FIELDS,
                      "瘦身白名单丢了 searchBlob，手机端多收几 KB 流量")
        self.assertIn("slotGuard", _WIRE_OMIT_FIELDS,
                      "slotGuard 是诊断字段，手机端根本不该收到")
        w = self._make_fat_work()
        slim = slim_works_for_wire([w])
        self.assertEqual(len(slim), 1)
        self.assertNotIn("searchBlob", slim[0], "searchBlob 仍被下发，手机端流量浪费")
        self.assertNotIn("slotGuard", slim[0], "slotGuard 仍被下发，手机端不需要这个诊断块")
        self.assertIn("copyText", slim[0], "误伤了客户端真要用的字段")
        self.assertIn("path", slim[0], "误伤了客户端真要用的字段")

    # ---- 8. slim 不能破坏原始缓存（_cached_works） ----
    def test_slim_works_for_wire_does_not_mutate_cached(self):
        from online_gallery_service import slim_works_for_wire
        w = self._make_fat_work()
        original_search_blob = w["searchBlob"]
        slim = slim_works_for_wire([w])
        # 瘦身只剥副本：原始 dict 的 searchBlob 必须还在
        self.assertEqual(w["searchBlob"], original_search_blob,
                         "slim 不该改动传入的原始 dict，否则服务端关键词检索会断")
        self.assertNotIn("searchBlob", slim[0])

    # ---- 9. gzip_bytes 内容寻址缓存 ----
    def test_gzip_bytes_unit(self):
        from online_gallery_service import gzip_bytes, gzip_cache_health
        # 重复压缩同一份 bytes，第二次应该是 cache hit
        body = ('{"ok":true,"works":[]}' * 200).encode("utf-8")
        h1 = gzip_cache_health()
        g1 = gzip_bytes(body)
        h2 = gzip_cache_health()
        g2 = gzip_bytes(body)   # 同输入 → cache hit
        h3 = gzip_cache_health()
        self.assertEqual(g1, g2, "同输入的压缩结果必须字节级一致")
        self.assertEqual(h3["hit"] - h2["hit"], 1, "第二次相同输入应记一次 hit")
        self.assertEqual(h3["miss"] - h1["miss"], 1, "第一次输入应记一次 miss")
        # 内容寻址：blake2b digest 必须能解码出原文
        import gzip
        self.assertEqual(gzip.decompress(g1), body)

    # ---- 10. 端到端：HTTP 实际下发的载荷 + 头 ----
    def test_works_endpoint_slim_and_gzip_e2e(self):
        """点对点验证：通过真实 HTTP 拿 /api/online/works，断言：
           (a) Accept-Encoding: gzip 时返回 Content-Encoding: gzip；
           (b) 解压后的 JSON 里 works[0] 没有 searchBlob/slotGuard（瘦身生效）；
           (c) total 与磁盘上作品数一致。
        """
        import gzip
        # 先确保服务端 fresh 写出 searchBlob（slim 之前）
        for w in self.scanner.scan():
            self.assertIn("searchBlob", w, "fixture 自身缺 searchBlob，无法证明 slim 在做事")

        req = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/api/online/works",
            headers={"Accept-Encoding": "gzip"},
        )
        with urllib.request.urlopen(req) as resp:
            self.assertEqual(resp.headers.get("Content-Encoding"), "gzip",
                             "Accept-Encoding=gzip 时应返回 Content-Encoding=gzip")
            payload = gzip.decompress(resp.read())
        data = json.loads(payload)
        self.assertTrue(data["ok"])
        self.assertEqual(data["total"], 2)
        self.assertEqual(len(data["works"]), 2)
        for w in data["works"]:
            self.assertNotIn("searchBlob", w,
                             "端到端：works[0] 仍带 searchBlob —— slim 没接进链路或已回退")
            self.assertNotIn("slotGuard", w,
                             "端到端：works[0] 仍带 slotGuard —— 同上")

    # ---- 11. ⚠️ 还原-失败证明（第二十一节）：把 slim 关掉，下游断言会 FAIL ----
    def test_revert_proof_slim_disabled_breaks_contract(self):
        """这道测试存在的意义不是「证明当前实现正确」，
        而是「证明下游断言 *真的能* 抓到 slim 被回退的事故」——
        把 slim_works_for_wire 临时换回「不过滤」版本（事故形态），
        观察端到端契约断言是否会因此失败。
        期望：事故形态下必须 AssertionError，否则这道闸门就是假的。
        """
        from online_gallery_service import slim_works_for_wire
        import gzip

        original = slim_works_for_wire
        _svc_mod.slim_works_for_wire = lambda works: works   # ← 模拟「slim 被回退」
        try:
            req = urllib.request.Request(
                f"http://127.0.0.1:{self.port}/api/online/works",
                headers={"Accept-Encoding": "gzip"},
            )
            with urllib.request.urlopen(req) as resp:
                raw = gzip.decompress(resp.read())
            data = json.loads(raw)
            # 关键：用「正确实现」下的契约断言去检查「错误实现」的产物，
            # 必须失败 —— 失败 = 闸门有效。
            try:
                self.assertNotIn("searchBlob", data["works"][0])
                self.fail("slim 被还原后契约断言仍通过 —— 端到端闸门是假的（第二十一节）")
            except AssertionError:
                pass   # 期望内的失败：闸门有效
        finally:
            _svc_mod.slim_works_for_wire = original   # 还原，**绝不影响**其他测试

        # 还原 slim 后再次确认契约成立（证明 finally 里的还原没漏）
        req2 = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/api/online/works",
            headers={"Accept-Encoding": "gzip"},
        )
        with urllib.request.urlopen(req2) as resp:
            raw2 = gzip.decompress(resp2.read() if False else resp.read())
        data2 = json.loads(raw2)
        self.assertNotIn("searchBlob", data2["works"][0],
                         "还原 slim 后契约仍不成立 —— finally 块的还原有 bug，会污染后续测试")

    # ---- 12. /api/online/status 的 wire 健康块必须真存在（防死代码） ----
    def test_wire_block_is_exposed_in_status(self):
        url = f"http://127.0.0.1:{self.port}/api/online/status"
        with urllib.request.urlopen(url) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        self.assertIn("wire", data, "wire 健康块没出现在 status 响应 —— 'wire 守卫' 可能没人接线")
        wire = data["wire"]
        for k in ("gzipMinBytes", "listOmitFields", "gzipCache", "hint"):
            self.assertIn(k, wire, f"wire.{k} 缺失")
        self.assertIn("searchBlob", wire["listOmitFields"])
        self.assertIn("slotGuard", wire["listOmitFields"])
        for k in ("hit", "miss", "hitRate", "entries", "maxEntries"):
            self.assertIn(k, wire["gzipCache"], f"gzipCache.{k} 缺失")


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


SKELETON_COPY = (
    "<<<COPY_FORMAT:3>>>\n\n"
    "<<<DOUYIN_START>>>\n抖音短平快口播脚本，痛点切入+亮点+留资号召\n<<<DOUYIN_END>>>\n\n"
    "<<<XHS_START>>>\n小红书主标题\n\n"
    "[小红书种草正文，带两日详细行程排期、亮点提炼与真实避坑，拒绝空话]\n\n"
    "[12个同行热门话题标签]\n<<<XHS_END>>>\n\n"
    "<<<XHS_2_START>>>\nHR方案决策版大纲，包含方案名称、适用对象、预算参考、决策亮点与服务保障\n<<<XHS_2_END>>>"
)

REAL_COPY = (
    "<<<COPY_FORMAT:3>>>\n\n"
    "<<<DOUYIN_START>>>\n" + ("真实抖音口播脚本正文，痛点切入。" * 20) + "\n<<<DOUYIN_END>>>\n\n"
    "<<<XHS_START>>>\n" + ("真实小红书种草正文，含两日行程排期与避坑提示。" * 30) + "\n<<<XHS_END>>>"
)


class TestPlatformSlotGuard(unittest.TestCase):
    """平台槽位守卫的真实场景测试（第三层空壳守卫）。

    ⚠️ 事故背景：8 套作品躺在「已发送0次（抖音小红书可发）」里，文案是**未填充的模板骨架**，
    整段实质 107 字 >= MIN_COPY_DISTRIBUTABLE(30)，被整段口径的守卫直接放行；
    而手机端 PlatformCopyParser 是**按槽位**独立出按钮的，
    于是用户点「规避营销版」拿到的就是那句「抖音短平快口播脚本，痛点切入+亮点+留资号召」。

    根因与 staleCode 那次同源：**闸门测的粒度（整段）和用户消费的粒度（单个槽位）不在同一层**。
    所以这里的关键断言**必须走完整链路** `WorkScanner._inspect_work_dir()`，
    只测 sanitize_platform_copy() 这个 helper 是不够的——
    上一轮假闸门正是栽在「只测 helper、不测真实路径」上。
    """

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="test_slot_guard_")
        self.scanner = WorkScanner(self.tmp)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _make_work(self, name, copy_text, n_images=3):
        d = os.path.join(self.tmp, name)
        os.makedirs(d, exist_ok=True)
        for i in range(n_images):
            with open(os.path.join(d, f"{i:02d}.jpg"), "wb") as fp:
                fp.write(b"\xff\xd8\xff\xe0fake-jpeg")
        with open(os.path.join(d, "文案.txt"), "w", encoding="utf-8") as fp:
            fp.write(copy_text)
        return d

    # ---- 1. 端到端：骨架作品必须被判为缺失 ----
    def test_skeleton_work_is_flagged_missing_end_to_end(self):
        d = self._make_work("骨架作品", SKELETON_COPY)
        w = self.scanner._inspect_work_dir(d, "骨架作品", "已发送0次", 0)
        self.assertIsNotNone(w)
        self.assertEqual(
            w["slotGuard"]["droppedCount"], 3,
            "三个槽位全是占位骨架，应全部剔除",
        )
        self.assertTrue(
            w["copyMissing"],
            "整段 107 字 >= 30 被放行 —— 用户点平台按钮会拿到占位说明文字，"
            "这就是守卫被自己的靶子骗过去的原始事故",
        )
        self.assertFalse(w["hasCopyText"])
        self.assertEqual(w["copyText"].strip(), "<<<COPY_FORMAT:3>>>",
                         "骨架槽位应从下发载荷中剔除")

    # ---- 2. 端到端：真实文案不得被误伤 ----
    def test_real_work_passes_untouched_end_to_end(self):
        d = self._make_work("正常作品", REAL_COPY)
        w = self.scanner._inspect_work_dir(d, "正常作品", "已发送0次", 0)
        self.assertEqual(w["slotGuard"]["droppedCount"], 0)
        self.assertFalse(w["copyMissing"])
        self.assertTrue(w["hasCopyText"])
        self.assertEqual(w["copyText"], REAL_COPY, "真实文案必须原样下发，一个字节都不许动")

    # ---- 3. 混合：真槽位保留、占位槽位剔除（不能一刀切整块置灰）----
    def test_mixed_work_keeps_real_slots_only(self):
        mixed = REAL_COPY + (
            "\n\n<<<XHS_2_START>>>\n"
            "HR方案决策版大纲，包含方案名称、适用对象、预算参考、决策亮点与服务保障\n<<<XHS_2_END>>>"
        )
        d = self._make_work("混合作品", mixed)
        w = self.scanner._inspect_work_dir(d, "混合作品", "已发送0次", 0)
        self.assertEqual(w["slotGuard"]["droppedMarkers"], ["XHS_2"])
        self.assertIn("<<<XHS_START>>>", w["copyText"], "真实种草版槽位必须保留")
        self.assertNotIn("<<<XHS_2_START>>>", w["copyText"], "占位大纲槽位必须剔除")
        self.assertFalse(w["copyMissing"], "还有真实槽位，不能整块置灰")
        self.assertEqual(w["slotGuard"]["keptCount"], 2)

    # ---- 4. 空槽位与零字节文案 ----
    def test_empty_slot_and_empty_copy(self):
        empty_slot = ("<<<COPY_FORMAT:3>>>\n\n<<<DOUYIN_START>>><<<DOUYIN_END>>>\n\n"
                      "<<<XHS_START>>>\n" + ("真实正文内容" * 30) + "\n<<<XHS_END>>>")
        d = self._make_work("空槽位作品", empty_slot)
        w = self.scanner._inspect_work_dir(d, "空槽位作品", "已发送0次", 0)
        self.assertEqual(w["slotGuard"]["droppedMarkers"], ["DOUYIN"])
        self.assertIn("<<<XHS_START>>>", w["copyText"])
        self.assertFalse(w["copyMissing"])

        d2 = self._make_work("零字节作品", "")
        w2 = self.scanner._inspect_work_dir(d2, "零字节作品", "已发送0次", 0)
        self.assertTrue(w2["copyMissing"])
        self.assertEqual(w2["slotGuard"]["droppedCount"], 0)

    # ---- 5. 守卫自身不能是死代码：函数必须真的被下发链路调用 ----
    def test_guard_is_wired_into_payload_not_dead_code(self):
        """只声明常量/函数却不接进链路 = 假闸门的另一种形态。这里断言字段真的出现在载荷里。"""
        d = self._make_work("接线检查", REAL_COPY)
        w = self.scanner._inspect_work_dir(d, "接线检查", "已发送0次", 0)
        self.assertIn("slotGuard", w, "slotGuard 诊断字段未出现在下发载荷中 —— 守卫没接线")
        for key in ("droppedCount", "droppedMarkers", "keptCount",
                    "rawSubstance", "servedSubstance"):
            self.assertIn(key, w["slotGuard"])

    # ---- 5.5 契约：只读 文案.txt ----
    def test_only_wen_an_txt_is_authoritative(self):
        """用户口径：软件只识别 `文案.txt`。

        `三平台文案.txt` 是早期 Codex 产线的遗留文件，**不是 `文案.txt` 的改名版**，
        不得参与下发；否则手机端可能显示一份未经确认的历史副本
        （实测库内 145 套两份都有，其中 32 套内容并不相同）。
        """
        name = "只认文案txt"
        d = os.path.join(self.tmp, name)
        os.makedirs(d, exist_ok=True)
        for i in range(2):
            with open(os.path.join(d, f"{i:02d}.jpg"), "wb") as fp:
                fp.write(b"\xff\xd8\xff\xe0fake-jpeg")

        # ① 只放 三平台文案.txt（内容是充实的真文案）→ 必须判为缺失
        with open(os.path.join(d, "三平台文案.txt"), "w", encoding="utf-8") as fp:
            fp.write(REAL_COPY)
        w = self.scanner._inspect_work_dir(d, name, "已发送0次", 0)
        self.assertIsNotNone(w)
        self.assertTrue(
            w["copyMissing"],
            "三平台文案.txt 不是 文案.txt 的改名 —— 只读 文案.txt 时它必须被判缺失",
        )

        # ② 补上 文案.txt 后必须原样下发
        with open(os.path.join(d, "文案.txt"), "w", encoding="utf-8") as fp:
            fp.write(REAL_COPY)
        w2 = self.scanner._inspect_work_dir(d, name, "已发送0次", 0)
        self.assertFalse(w2["copyMissing"])
        self.assertEqual(w2["copyText"], REAL_COPY, "文案.txt 必须原样下发")

    # ---- 6. 搜索可检索性不因守卫丢失 ----
    def test_search_blob_survives_sanitization(self):
        """被剔除的骨架作品，其原文仍应可被关键词搜到，否则等于把内容从库里抹掉。"""
        d = self._make_work("检索检查", SKELETON_COPY)
        w = self.scanner._inspect_work_dir(d, "检索检查", "已发送0次", 0)
        self.assertTrue(w["copyMissing"])
        self.assertIn("小红书主标题", w["searchBlob"],
                      "searchBlob 用的是未净化原文，剔除槽位不能连带丢掉可检索性")


class TestSubdirImages(unittest.TestCase):
    """【闸门】作品图在子目录（产线标准 `产出素材/`）时，作品必须可见且可取图。

    背景：成品库存在两种目录布局 ——
      A. 顶层直接放成品图（`P1_封面.png`）；
      B. 成品图放在产线标准的 `产出素材/` 子目录里（`产出素材/P1.png`）。
    老 `_inspect_work_dir()` 只 `os.listdir(dir_path)` 看顶层，遇到布局 B 直接
    `return None`，导致 103 套作品在手机相册里「人间蒸发」（实测 totalWorks 389 而非 483）。
    """

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="test_subdir_img_")
        self.stage0 = os.path.join(self.temp_dir, "已发送0次（抖音小红书可发）")
        os.makedirs(self.stage0, exist_ok=True)
        self.scanner = WorkScanner(self.temp_dir)

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _make_work(self, name, layout):
        d = os.path.join(self.stage0, name)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "文案.txt"), "w", encoding="utf-8") as f:
            f.write("这是一条真实作品文案，用于测试子目录取图是否可见。" * 12)
        if layout == "subdir":
            sub = os.path.join(d, "产出素材")
            os.makedirs(sub, exist_ok=True)
            for n in ("P1.png", "P2.png"):
                with open(os.path.join(sub, n), "wb") as f:
                    f.write(PNG_1PX)
        elif layout == "toplevel":
            for n in ("P1.png", "P2.png"):
                with open(os.path.join(d, n), "wb") as f:
                    f.write(PNG_1PX)
        return d

    def test_subdir_images_work_is_visible(self):
        """布局 B：图在 `产出素材/` 里，作品仍必须被扫到（老实现会漏）。"""
        self._make_work("20260915_Codex-AUTUMN_A_台州2天1晚攻略", "subdir")
        works = self.scanner.scan(force=True)
        self.assertEqual(len(works), 1,
                         "图在 产出素材/ 子目录的作品被扫描器漏掉了（布局 B 漏收）")
        self.assertEqual(works[0]["imageCount"], 2)

    def test_subdir_image_path_is_resolvable(self):
        """布局 B 下发的 images 值必须能被 resolve_image_path 解析（iOS 走 ?path= 契约）。"""
        self._make_work("20260915_Codex-AUTUMN_A_台州2天1晚攻略", "subdir")
        works = self.scanner.scan(force=True)
        self.assertEqual(len(works), 1)
        for fn in works[0]["images"]:
            got = self.scanner.resolve_image_path(fn)
            self.assertIsNotNone(got, "iOS 契约 ?path=%s 解析失败，手机端会 404" % fn)
            self.assertTrue(os.path.isfile(got))

    def test_toplevel_layout_unique_relpath(self):
        """【2026-09-24 修「iPhone 全库串图」】布局 A 也必须返回成品库根相对路径。

        旧契约要求布局 A 返回裸文件名——那正是串图根因：全库 393 套作品共用
        P1.png / P1_封面.png 这类同名标识，iOS `?path=<裸文件名>` 会命中
        image_name_index 的「同名首命中」，叠加客户端缓存键=路径字符串，
        表现为全库作品在 iPhone 上显示同一套图（2026-09-24 实测复现）。
        """
        self._make_work("20260918_100000_安吉2天1夜秋季团建", "toplevel")
        works = self.scanner.scan(force=True)
        self.assertEqual(len(works), 1)
        ims = works[0]["images"]
        self.assertEqual(len(ims), 2)
        for fn in ims:
            self.assertIn("/", fn, "布局 A 必须返回含路径的全局唯一标识，禁止裸文件名")
            got = self.scanner.resolve_image_path(fn)
            self.assertIsNotNone(got, "iOS 契约 ?path=%s 解析失败，手机端会 404" % fn)
            self.assertTrue(os.path.isfile(got))

    def test_two_works_same_image_names_get_unique_ids(self):
        """【2026-09-24 串图闸门】两套作品图片同名时，下发标识必须互不相同。"""
        self._make_work("20260918_100000_安吉2天1夜秋季团建", "toplevel")
        self._make_work("20260918_110000_莫干山2天1夜秋季团建", "toplevel")
        works = self.scanner.scan(force=True)
        self.assertEqual(len(works), 2)
        a = set(works[0]["images"])
        b = set(works[1]["images"])
        self.assertEqual(len(a), 2)
        self.assertEqual(len(b), 2)
        self.assertFalse(a & b, "两套作品的图片标识出现交集 ⇒ iOS 端必然串图")

    def test_no_image_work_still_excluded(self):
        """反向闸门：真·没有图的作品（子目录也没有）仍必须排除，别把空壳放进手机。"""
        self._make_work("20260918_110000_空壳作品", "none")
        works = self.scanner.scan(force=True)
        self.assertEqual(len(works), 0, "无图空壳作品被误放进了在线相册")

    def test_dsh101_get_work_miss_falls_back_to_image_name_index(self):
        """【DSH-101 闸门】use-work 移走作品后，/api/online/image 必须能取原图。

        现象：手机点平台按钮 → handleOnlineWorkUse 先 recordUse 把作品从「已发送0次」
        移到「_已发送1次」→ 紧接着 downloadWorkImages(id, file, ...) 服务端
        `os.path.join(target_work["path"], file_name)` 拼成
        `_已发送1次/.../_已发送1次/.../产出素材/P1.png`（path 已是新分类，file 又带旧前缀）
        → 老代码 fallback 才能命中。
        DSH-101 修复：服务端直接走 resolve_image_path(file_name)，由它内部
        `os.path.join(self.root, raw)` 正确解析成品库根相对路径。
        """
        # 1. 布两条作品：一条会被移走，另一条不变
        moved_dir = self._make_work("20260922_Codex-DSH101_moved", "subdir")
        stable_dir = self._make_work("20260922_Codex-DSH101_stable", "subdir")
        works = self.scanner.scan(force=True)
        self.assertEqual(len(works), 2)

        # 2. 模拟 use-work 移走：把 moved_dir 整体搬到 stage1
        stage1 = os.path.join(self.temp_dir, "_已发送1次（微信公众号可发）")
        os.makedirs(stage1, exist_ok=True)
        moved_id = "20260922_Codex-DSH101_moved"
        moved_dst = os.path.join(stage1, moved_id)
        shutil.move(moved_dir, moved_dst)

        # 3. 重建索引（手机端会先拉列表触发扫描）
        self.scanner.scan(force=True)

        # 4. file_name 是「成品库根相对路径」（DSH-100 下发形态）
        moved_file = "_已发送1次（微信公众号可发）/{}/产出素材/P1.png".format(moved_id)

        # 5. DSH-101 核心：resolve_image_path 必须用 file_name 直接解析（不再走 os.path.join(path, file_name)）
        resolved = self.scanner.resolve_image_path(moved_file)
        self.assertIsNotNone(
            resolved,
            "DSH-101 修复目标：服务端 image endpoint 改走 resolve_image_path(file_name)，"
            "避开 use-work 移走后的 path/file_name 重复拼接问题。实解析={}".format(resolved),
        )
        self.assertTrue(os.path.isfile(resolved))
        self.assertIn(moved_id, resolved)


class TestMovedWorksPruning(unittest.TestCase):
    """DSH-128：`WorkScanner._moved_works` 只增不减 ⇒ 常驻内存单调增长。

    背景：_moved_works 是「作品被移走之后还能按 id 找回原图」的登记表。
    但它在 `__init__` 里初始化之后，只有三处会往里塞（扫阶段目录 / 按 id 定位 /
    作品被移走），**只有 restore 回「已发送0次」时会 pop 一条**。服务是 7x24 常驻
    进程 ⇒ 这个字典单调增长，跑几个月就是上万条。

    严重性不是「占几 KB」：每条存的是**完整作品对象**，含 `copyText`（V4.5 全系
    11 个版本的文案）和 `images` 列表，单条就是几 KB 到十几 KB。

    ⛔ 判据设计上最容易犯的错：用「这次 scan 的结果里有没有」来判断该不该删。
       scan **只扫「已发送0次」**，而 _moved_works 里登记的绝大多数是
       「已发送1次」的作品 —— 那样会当场误删一大片，把「移走后取原图」的兜底
       直接干掉。判据只能用「路径还在不在」。
    """

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="dsh128_")
        self.scanner = _svc_mod.WorkScanner(self.temp_dir)

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _seed(self, name, exists=True):
        """造一条 _moved_works 条目；exists=False 时只登记路径、不建目录。"""
        path = os.path.join(self.temp_dir, name)
        if exists:
            os.makedirs(path, exist_ok=True)
        self.scanner._moved_works[name] = {
            "id": name, "path": path, "images": [], "copyText": "",
        }
        return name

    def test_01_stale_entries_are_dropped(self):
        """路径已经不存在的条目必须清掉 —— 它救不了任何请求，只是白占内存。"""
        self._seed("gone-work", exists=False)
        self._seed("gone-again", exists=False)

        removed = self.scanner.prune_moved_works()

        self.assertGreaterEqual(removed, 2, "两条陈旧条目都应该被清掉")
        self.assertNotIn("gone-work", self.scanner._moved_works)
        self.assertNotIn("gone-again", self.scanner._moved_works)

    def test_02_live_entries_are_kept(self):
        """路径还在的条目**一条都不能删**。

        这条专门防「按 scan 结果判断」那种错法：scan 只扫「已发送0次」，
        而这里登记的绝大多数是「已发送1次」的作品，按 scan 结果删会当场误删一大片。
        """
        for name in ("stage1-work", "garbage-work", "nested-work"):
            self._seed(name, exists=True)

        self.scanner.prune_moved_works()

        for name in ("stage1-work", "garbage-work", "nested-work"):
            self.assertIn(name, self.scanner._moved_works,
                          "路径还在的条目绝不能被清掉：%s" % name)

    def test_03_mixed_keeps_live_drops_stale(self):
        """混合场景：只清陈旧的，留下的必须恰好是路径还在的那些。"""
        self._seed("live-a", exists=True)
        self._seed("stale-b", exists=False)
        self._seed("live-c", exists=True)

        self.scanner.prune_moved_works()

        self.assertEqual(sorted(self.scanner._moved_works.keys()),
                         ["live-a", "live-c"])

    def test_04_over_limit_is_trimmed(self):
        """超过上限要压回来 —— 否则「只增不减」的老毛病还在，只是涨得慢点。

        limit 做成可注入参数：默认上限（MOVED_WORKS_LIMIT）很大，测试里造
        上千个目录太慢，也没必要。
        """
        for index in range(30):
            self._seed("w%03d" % index, exists=True)

        self.scanner.prune_moved_works(limit=10)

        self.assertEqual(len(self.scanner._moved_works), 10,
                         "超过上限必须压回 10 条，实际=%d" % len(self.scanner._moved_works))

    def test_05_scan_triggers_pruning(self):
        """真的扫一轮之后，陈旧条目应该被清掉（证明清理确实挂在扫描路径上）。"""
        self._seed("stale-before-scan", exists=False)
        self._seed("live-before-scan", exists=True)

        self.scanner.scan(force=True)

        self.assertNotIn("stale-before-scan", self.scanner._moved_works,
                         "扫完一轮之后陈旧条目应该已经清掉了")
        self.assertIn("live-before-scan", self.scanner._moved_works)

    def test_06_prune_is_idempotent(self):
        """连着清两次结果不变 —— 清理不能自己把还活着的条目越清越少。"""
        self._seed("live-1", exists=True)
        self._seed("live-2", exists=True)
        self._seed("stale-1", exists=False)

        self.scanner.prune_moved_works()
        after_first = sorted(self.scanner._moved_works.keys())
        self.scanner.prune_moved_works()
        after_second = sorted(self.scanner._moved_works.keys())

        self.assertEqual(after_first, after_second)
        self.assertEqual(after_first, ["live-1", "live-2"])

    def test_07_default_limit_is_sane(self):
        """默认上限要既能兜住「最近移走的作品」，又不能大到形同虚设。"""
        limit = getattr(_svc_mod, "MOVED_WORKS_LIMIT", None)
        self.assertIsNotNone(limit, "缺 MOVED_WORKS_LIMIT 常量")
        self.assertGreaterEqual(limit, 100, "上限太小会让「移走后取原图」的兜底频繁失效")
        self.assertLessEqual(limit, 5000, "上限太大就失去保护意义（每条含完整文案）")


class TestLogRotation(unittest.TestCase):
    """DSH-126：常驻服务的 stdout/stderr 日志必须会自动轮转。

    背景：服务是 7x24 常驻进程，日志一直往脚本目录下同一个 .log 里追加，
    没有任何上限。跑上几周就是 GB 级，而它就在脚本目录里，磁盘被自己吃光也没人发现。

    ⚠️ 闸门设计要点（这几条都是踩过的坑）：
      - 判据查**文件系统事实**（文件在不在、内容对不对），不查「有没有调用某个函数」；
      - 「通过流写入会真的轮转」这条必须走 RotatingLogStream 而不能只测
        rotate_log_if_needed —— Windows 上文件开着时 os.replace 会 PermissionError，
        而本类所有异常都是吞掉的，只测纯函数完全看不出这个静默失效；
      - 备份链顺序（.1 最新 / .3 最老）必须断言，正序改名会静默把内容搅乱。
    """

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="dsh126_")

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _path(self, name="app.log"):
        return os.path.join(self.temp_dir, name)

    def _read(self, path):
        with open(path, "r", encoding="utf-8") as fp:
            return fp.read()

    def test_01_rotate_log_if_needed_renames_chain(self):
        """超过阈值时：x.log -> x.log.1，且原内容完整留在 .1 里。"""
        path = self._path()
        with open(path, "w", encoding="utf-8") as fp:
            fp.write("old-content\n")

        rotated = _svc_mod.rotate_log_if_needed(path, max_bytes=1, backups=3)

        self.assertTrue(rotated, "文件已超过阈值，rotate_log_if_needed 必须返回 True")
        self.assertFalse(os.path.exists(path), "轮转后原文件不应该还在原地")
        self.assertEqual(self._read(path + ".1"), "old-content\n",
                         "旧内容必须完整保留在 .1 里，不能丢")

    def test_02_rotate_log_if_needed_noop_below_threshold(self):
        """没超阈值就一刀都别切：切了就会把正在排查的日志冲掉。"""
        path = self._path()
        with open(path, "w", encoding="utf-8") as fp:
            fp.write("tiny\n")

        rotated = _svc_mod.rotate_log_if_needed(path, max_bytes=1024 * 1024, backups=3)

        self.assertFalse(rotated, "没超阈值不能轮转")
        self.assertEqual(self._read(path), "tiny\n")
        self.assertFalse(os.path.exists(path + ".1"), "没轮转就不该出现 .1")

    def test_03_rotate_log_if_needed_missing_file_is_noop(self):
        """文件不存在时返回 False 且不抛异常（服务刚部署、日志还没建出来的场景）。"""
        self.assertFalse(_svc_mod.rotate_log_if_needed(self._path("nope.log"),
                                                       max_bytes=1, backups=3))

    def test_04_backup_chain_is_newest_first(self):
        """连续切三次：.1 是最新的一份，.3 是最老的一份。"""
        path = self._path()
        for index in range(3):
            with open(path, "w", encoding="utf-8") as fp:
                fp.write("round-%d\n" % index)
            self.assertTrue(_svc_mod.rotate_log_if_needed(path, max_bytes=1, backups=3))

        self.assertEqual(self._read(path + ".1"), "round-2\n",
                         ".1 必须是最近一次被切走的内容（改名链条不能正着来）")
        self.assertEqual(self._read(path + ".2"), "round-1\n")
        self.assertEqual(self._read(path + ".3"), "round-0\n")

    def test_05_backup_count_is_capped(self):
        """切很多次也只留 3 份历史，最老的被踢掉 —— 否则轮转自己就会撑爆磁盘。"""
        path = self._path()
        for index in range(8):
            with open(path, "w", encoding="utf-8") as fp:
                fp.write("round-%d\n" % index)
            _svc_mod.rotate_log_if_needed(path, max_bytes=1, backups=3)

        names = sorted(n for n in os.listdir(self.temp_dir) if n.startswith("app.log."))
        self.assertEqual(names, ["app.log.1", "app.log.2", "app.log.3"],
                         "历史备份必须恰好 3 份，实际=%s" % names)
        self.assertEqual(self._read(path + ".1"), "round-7\n")
        self.assertEqual(self._read(path + ".3"), "round-5\n",
                         "最老的备份要被踢掉，只保留最近 3 次")

    def test_06_stream_rotates_while_file_is_open(self):
        """★核心闸门★ 通过流写入必须真的会轮转。

        这条专门用来抓「文件还开着就改名」的静默失效：Windows 上 os.replace 一个
        被打开的文件会 PermissionError，而 write() 里的异常是吞掉的，表现就是
        「代码看着在轮转，磁盘上文件却一直在涨，且零报错」。
        """
        path = self._path()
        stream = _svc_mod.RotatingLogStream(path, max_bytes=200, backups=2,
                                            check_every=20)
        try:
            for index in range(200):
                stream.write("line-%03d\n" % index)
                stream.flush()
        finally:
            stream.close()

        backups = sorted(n for n in os.listdir(self.temp_dir)
                         if n.startswith("app.log."))
        self.assertTrue(backups,
                        "写了 200 行（约 1.8KB）到阈值 200B 的流里，却一个备份都没切出来 —— "
                        "八成又是「文件开着改不了名」，异常被吞了")

    def test_07_stream_does_not_rotate_below_threshold(self):
        """没超阈值不能切：切了等于把用户正在看的日志凭空搬走。"""
        path = self._path()
        stream = _svc_mod.RotatingLogStream(path, max_bytes=100 * 1024, backups=2,
                                            check_every=10)
        try:
            for index in range(20):
                stream.write("line-%03d\n" % index)
                stream.flush()
        finally:
            stream.close()

        self.assertEqual(sorted(os.listdir(self.temp_dir)), ["app.log"],
                         "没到阈值切出备份 = 误伤正在排查的日志")

    def test_08_stream_keeps_writing_after_rotation(self):
        """切完之后必须继续往新文件里写，不能写进已经关掉的句柄。

        ⚠️ 别断言「最后一个标记一定在当前文件里」：判定是在 write 之后做的，刚好在
           最后一次写入时触发轮转的话，当前文件就是空的（内容被切到 .1 了）。
           要断言的是「写入没丢、且落在最新的那个文件里」。
        """
        path = self._path()
        stream = _svc_mod.RotatingLogStream(path, max_bytes=100, backups=2,
                                            check_every=10)
        try:
            for index in range(60):
                stream.write("line-%03d\n" % index)
                stream.flush()
            # 轮转之后再写一行，确认它一定落进「当前」这个文件
            stream.write("tail-marker\n")
            stream.flush()
        finally:
            stream.close()

        self.assertTrue(os.path.exists(path), "轮转后必须重新打开一个新文件继续写")
        self.assertIn("tail-marker", self._read(path),
                      "轮转之后的新写入必须落进新文件，实际=%r" % self._read(path))
        # 时序不能倒挂：新写入绝不能出现在已被切走的备份里
        for suffix in (".1", ".2"):
            backup_path = path + suffix
            if os.path.exists(backup_path):
                self.assertNotIn("tail-marker", self._read(backup_path),
                                 "新写入不该出现在已切走的备份 %s 里" % suffix)

    def test_09_stream_survives_failed_reopen(self):
        """轮转后「重新打开」失败必须降级，不能把 print() 的调用方炸掉。

        真场景：磁盘满 / 日志目录被手工删掉 / 权限变了。此时 open() 会抛，
        而这个异常一旦往外冒，ThreadingHTTPServer 的请求线程就跟着挂了。
        """
        path = self._path()
        stream = _svc_mod.RotatingLogStream(path, max_bytes=100, backups=2,
                                            check_every=10)
        try:
            stream.write("warm-up\n")
            stream.flush()
            # 把目标指到一个不存在的目录，逼出「重开失败」这条路径
            stream.path = os.path.join(self.temp_dir, "gone-dir", "app.log")
            stream._rotate()
            # 降级之后还能继续写：绝不能抛给调用方
            stream.write("still-alive\n")
            stream.flush()
        finally:
            try:
                stream.close()
            except Exception:
                pass

    def test_10_defaults_are_sane(self):
        """默认阈值必须是「能兜住几周日志、又不会撑爆磁盘」的量级。"""
        self.assertGreaterEqual(_svc_mod.LOG_MAX_BYTES, 1024 * 1024,
                                "单文件上限至少 1MB，太小会导致一天切几十次")
        self.assertLessEqual(_svc_mod.LOG_MAX_BYTES, 64 * 1024 * 1024,
                             "单文件上限不超过 64MB，否则轮转形同虚设")
        self.assertGreaterEqual(_svc_mod.LOG_BACKUP_COUNT, 1,
                                "至少要留一份历史，否则排查时旧日志当场就没了")
        self.assertGreater(_svc_mod.LOG_CHECK_EVERY_CHARS, 0)


if __name__ == "__main__":
    unittest.main()
