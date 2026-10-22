#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSH-194：iOS 缩略图 w 参数契约测试。

1. isThumbnail 分支带 w（112pt × scale，下限 64）；
2. 非缩略图维持 serverWidth ?? 1600（DSH-186 不回退）；
3. 版本 0.8.121/232。
"""
import os
import re
import unittest

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_CLIENT = os.path.join(_ROOT, "ios", "Album", "OnlineGalleryClient.swift")
_PROJ = os.path.join(_ROOT, "ios", "project.yml")


class TestIosThumbW(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(_CLIENT, "r", encoding="utf-8") as fp:
            cls.src = fp.read()
        with open(_PROJ, "r", encoding="utf-8") as fp:
            cls.proj = fp.read()

    def test_thumbnail_carries_w(self):
        pat = re.compile(
            r"if isThumbnail \{\s*let thumbW = max\(64, Int\(\(112 \* UIScreen\.main\.scale\)\.rounded\(\)\)\)\s*"
            r"queryItems\.append\(URLQueryItem\(name: \"w\", value: String\(thumbW\)\)\)",
        )
        self.assertRegex(self.src, pat)

    def test_full_image_width_preserved(self):
        self.assertIn('URLQueryItem(name: "w", value: String(serverWidth ?? 1600))', self.src)

    def test_version_bumped(self):
        self.assertIn("CURRENT_PROJECT_VERSION: 232", self.proj)
        self.assertIn("MARKETING_VERSION: 0.8.121", self.proj)


if __name__ == "__main__":
    unittest.main()
