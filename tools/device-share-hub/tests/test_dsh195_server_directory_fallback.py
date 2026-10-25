# -*- coding: utf-8 -*-
"""
DSH-195: 「在线设备」列表在路由器屏蔽 UDP 广播（客户端隔离）时为空 —— 服务端目录兜底。

验证点：
1. OnlineService 新增 SERVER_PEERS + fetchServerDirectory（GET /api/online/phones）
2. peers() 合并 UDP 直连发现与服务端目录（UDP 优先）
3. 用户主动刷新（ACTION_DISCOVER_PEERS）立即拉目录并对已知设备补单播探测
4. ShareImportActivity 打开即主动刷新；空态文案提示电脑服务兜底
5. 服务端目录解析过滤自己（deviceId/IP）与掉线设备（lastSeen 超时）
"""

import os
import unittest

HUB_DIR = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SERVICE = os.path.join(HUB_DIR, "android", "app", "src", "main", "java", "com", "zwm", "gallery",
                       "OnlineService.java")
SHARE = os.path.join(HUB_DIR, "android", "app", "src", "main", "java", "com", "zwm", "gallery",
                     "ShareImportActivity.java")


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


class TestDSH195ServerDirectoryFallback(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.service = read(SERVICE)
        cls.share = read(SHARE)

    def test_server_peers_map_and_fetch(self):
        self.assertIn("SERVER_PEERS", self.service)
        self.assertIn("fetchServerDirectory", self.service)
        self.assertIn("/api/online/phones", self.service)
        self.assertIn("SERVER_DIRECTORY_INTERVAL_MS", self.service)

    def test_fetch_uses_dedicated_executor(self):
        """目录拉取必须用独立线程池：serviceExecutor 的 2 个线程被两个长跑循环永久占满。"""
        self.assertIn("directoryExecutor", self.service)
        self.assertIn("directoryExecutor.execute", self.service)
        self.assertNotIn("serviceExecutor.execute(() -> {\n            HttpURLConnection", self.service)

    def test_peers_merges_server_directory(self):
        self.assertIn("LinkedHashMap<String, PeerDevice> merged = new LinkedHashMap<>(SERVER_PEERS)",
                      self.service)
        self.assertIn("merged.putAll(PEERS)", self.service)

    def test_merge_filters_self_and_stale(self):
        self.assertIn('id.equals(ownId) || ip.equals(ownIp)', self.service)
        self.assertIn("SERVER_PEER_FRESH_MS", self.service)
        self.assertIn("lastSeenTs", self.service)

    def test_discover_action_triggers_fetch_and_unicast(self):
        # 用户点刷新：立即拉目录 + 补单播探测
        self.assertIn("lastServerDirectoryMs = 0L;", self.service)
        self.assertIn("unicastProbeRequested = true;", self.service)
        self.assertIn("unicastProbeServerPeers(socket)", self.service)

    def test_unicast_probe_uses_discovery_port(self):
        self.assertIn('"ZWMDS2_DISCOVER".getBytes(StandardCharsets.UTF_8)', self.service)
        self.assertIn("DISCOVERY_PORT", self.service)

    def test_share_activity_triggers_immediate_discovery(self):
        self.assertIn("OnlineService.requestImmediateDiscovery(this);", self.share)

    def test_empty_hint_mentions_pc_relay(self):
        self.assertIn("电脑端", self.share)
        self.assertIn("中转", self.share)


class TestBuildScriptWritesLocalLatest(unittest.TestCase):
    """DSH-195 附带修复：local_latest.json 腐烂导致局域网更新中转供旧包。

    事实链（2026-10-25 实测）：online_gallery_service 的 _read_local_update_manifest()
    读 android/out/local_latest.json；该文件 10-23 起停在 0.8.122/233（指向的旧 APK 还在，
    校验能过），而 build-local.ps1 只写 android-release-metadata.json ⇒ 中转一直供 0.8.122。
    修法：构建时一并写 local_latest.json（无 BOM，lan_update_server 用 utf-8 读）。
    """

    @classmethod
    def setUpClass(cls):
        cls.build = read(os.path.join(HUB_DIR, "scripts", "build-local.ps1"))
        cls.service = read(os.path.join(HUB_DIR, "scripts", "online_gallery_service.py"))

    def test_build_script_writes_local_latest(self):
        self.assertIn("local_latest.json", self.build)
        self.assertIn("local_apk_path", self.build)

    def test_build_script_writes_utf8_no_bom(self):
        # lan_update_server.py 以 utf-8 读 local_latest.json，BOM 会炸 json.loads
        self.assertIn("UTF8Encoding($false)", self.build.replace(" ", ""))

    def test_service_reads_local_latest(self):
        self.assertIn("LOCAL_LATEST_JSON", self.service)
        self.assertIn("_read_local_update_manifest", self.service)


if __name__ == "__main__":
    unittest.main()
