# -*- coding: utf-8 -*-
"""
DSH-195 iOS 侧：「在线设备」列表服务端目录兜底（路由器屏蔽 UDP 广播时）。
"""

import os
import unittest

HUB_DIR = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
PEER = os.path.join(HUB_DIR, "ios", "Album", "PeerDirectory.swift")
SERVICE = os.path.join(HUB_DIR, "ios", "Album", "IncomingTransferService.swift")
VIEW = os.path.join(HUB_DIR, "ios", "Album", "TransferViewController.swift")


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


class TestIOSDSH195ServerDirectory(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.peer = read(PEER)
        cls.service = read(SERVICE)
        cls.view = read(VIEW)

    def test_peer_directory_merges_server_phones(self):
        self.assertIn("serverValues", self.peer)
        self.assertIn("mergeServerPhones", self.peer)
        self.assertIn("var merged = serverValues", self.peer)
        self.assertIn("serverPeerFresh", self.peer)

    def test_peer_directory_filters_self_and_stale(self):
        self.assertIn("DeviceIdentity.id", self.peer)
        self.assertIn("localIPv4Addresses", self.peer)
        self.assertIn("lastSeenTs", self.peer)

    def test_service_fetches_directory_periodically(self):
        self.assertIn("maybeFetchServerDirectory", self.service)
        self.assertIn("/api/online/phones", self.service)
        self.assertIn("serverDirectoryInterval", self.service)

    def test_force_notification_wired(self):
        self.assertIn("forceServerDirectory", self.peer)
        self.assertIn("forceServerDirectory", self.service)
        self.assertIn("forceServerDirectory", self.view)

    def test_view_hint_mentions_pc_relay(self):
        self.assertIn("电脑端", self.view)
        self.assertIn("中转", self.view)


if __name__ == "__main__":
    unittest.main()
