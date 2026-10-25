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


if __name__ == "__main__":
    unittest.main()
