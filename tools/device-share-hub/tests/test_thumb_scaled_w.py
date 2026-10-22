#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSH-194：服务端缩略图 w 参数契约测试（静态契约，不起服务）。

验证点：
1. /api/online/image 的 thumb 分支：0 < w < 320 时走 build_scaled_image_bytes；
2. w 越界（<=0 或 >=320）维持 build_thumbnail_bytes 原行为（旧客户端兼容）；
3. X-Thumb 头带 w 标记（运维可核对实际下发的缩放档）；
4. 版本号升到 1.2.2。
"""
import os
import re
import sys
import unittest

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_SVC = os.path.join(_ROOT, "scripts", "online_gallery_service.py")


class TestThumbScaledW(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(_SVC, "r", encoding="utf-8") as fp:
            cls.src = fp.read()

    def test_thumb_branch_honors_w(self):
        # thumb + 有效 w → 走缩放链路
        pat = re.compile(
            r"if thumb:.*?if 0 < scaled_w < 320:\s*data = build_scaled_image_bytes\(img_path, mtime, max\(64, scaled_w\)\)",
            re.S,
        )
        self.assertRegex(self.src, pat)

    def test_thumb_branch_default_unchanged(self):
        # 无 w / w 越界 → 维持 320 缩略图
        pat = re.compile(
            r"if 0 < scaled_w < 320:.*?else:\s*data = build_thumbnail_bytes\(img_path, mtime\)",
            re.S,
        )
        self.assertRegex(self.src, pat)

    def test_x_thumb_header_marks_w(self):
        self.assertIn('thumb_mark += f";w={max(64, scaled_w)}"', self.src)
        self.assertIn('self.send_header("X-Thumb", thumb_mark)', self.src)

    def test_version_bumped(self):
        self.assertIn('"version": "1.2.2"', self.src)
        self.assertNotIn('"version": "1.2.1"', self.src)


if __name__ == "__main__":
    unittest.main()
