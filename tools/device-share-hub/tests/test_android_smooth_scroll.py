#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSH-194：Android 在线列表「丝滑」三件套契约测试（静态源码契约）。

背景（2026-10-23 真机实测）：加载更多 6 页后掉帧 5.26%、Slow bitmap uploads 167、
Slow issue draw commands 77、TOTAL PSS 372MB。根因三件套：
1. 缩略图固定发 320px，实际显示仅 112dp（红米 320dpi = 224px）→ w 参数按需缩放；
2. 「加载更多」removeAllViews 全量重建 O(n²) → 增量追加；
3. 手动按钮截断「随便划」→ 滚动近底自动加载。
"""
import os
import re
import unittest

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_MAIN = os.path.join(_ROOT, "android", "app", "src", "main", "java", "com", "zwm", "gallery", "MainActivity.java")
_CLIENT = os.path.join(_ROOT, "android", "app", "src", "main", "java", "com", "zwm", "gallery", "OnlineGalleryClient.java")
_GRADLE = os.path.join(_ROOT, "android", "app", "build.gradle.kts")


def _read(path):
    with open(path, "r", encoding="utf-8") as fp:
        return fp.read()


class TestThumbWidthParam(unittest.TestCase):
    def test_thumb_url_carries_w(self):
        src = _read(_CLIENT)
        self.assertIn('"&thumb=1&w=" + thumbDisplayPx', src)

    def test_thumb_display_px_from_density(self):
        src = _read(_CLIENT)
        # 112dp 长边按屏幕密度换算，下限 64 与服务端夹紧区间对齐
        self.assertRegex(src, r"thumbDisplayPx = Math\.max\(64, Math\.round\(112f \* context\.getResources\(\)\.getDisplayMetrics\(\)\.density\)\)")


class TestIncrementalLoadMore(unittest.TestCase):
    def test_expand_uses_append_not_rebuild(self):
        src = _read(_MAIN)
        m = re.search(r"private void expandOnlinePageLimit\(String trigger\) \{(.*?)\n    \}\n", src, re.S)
        self.assertIsNotNone(m, "expandOnlinePageLimit 方法不存在")
        body = m.group(1)
        self.assertNotIn("removeAllViews", body, "增量路径严禁全量重建")
        self.assertIn("worksContainer.addView(card, insertAt++", body)

    def test_chunked_append_with_generation_guard(self):
        src = _read(_MAIN)
        # 分帧追加：每帧 6 张，摊薄滑动中的 inflate 卡顿
        self.assertIn("final int CHUNK = 6;", src)
        self.assertIn("uiHandler.post(step[0]);", src)
        # 代际守卫：筛选/刷新触发全量重建后，在途追加必须作废
        self.assertIn("onlineRenderGeneration++;", src)
        self.assertIn("if (generation != onlineRenderGeneration)", src)

    def test_generation_abort_handoff(self):
        src = _read(_MAIN)
        # 代际 abort 是「所有权移交」：新渲染链自己复位锁与 transition，
        # abort 路径若再碰锁会清掉新链的锁、重新打开双链竞态
        m = re.search(r"if \(generation != onlineRenderGeneration\) \{", src)
        self.assertIsNone(m, "abort 路径应为裸 return（所有权移交新渲染链）")
        self.assertIn("if (generation != onlineRenderGeneration) return;", src)
        # 渲染链在跑时必须持锁，完成后释放
        self.assertIn("onlineAutoLoadingMore = true;   // 渲染链在跑期间", src)

    def test_big_render_chunked(self):
        src = _read(_MAIN)
        # 大列表全量渲染也必须分帧（首屏 30 同步 + 每帧 12 流式）
        self.assertIn("ONLINE_FIRST_SCREEN_COUNT = 30", src)
        self.assertIn("ONLINE_RENDER_CHUNK = 12", src)
        m = re.search(r"private void renderOnlineWorksCards.*?\{(.*)", src, re.S)
        self.assertIsNotNone(m)
        body = m.group(1)[:6000]
        self.assertIn("if (showCount <= ONLINE_FIRST_SCREEN_COUNT)", body)
        self.assertIn("gen != onlineRenderGeneration", body)

    def test_full_render_tracks_rendered_count(self):
        src = _read(_MAIN)
        self.assertIn("renderedOnlineCardCount = showCount;", src)

    def test_button_tagged_for_anchor(self):
        src = _read(_MAIN)
        self.assertIn('loadMoreBtn.setTag(TAG_ONLINE_LOAD_MORE);', src)


class TestAutoLoadMore(unittest.TestCase):
    def test_scroll_listener_wired(self):
        src = _read(_MAIN)
        self.assertIn("contentScroll.setOnScrollChangeListener(", src)
        self.assertIn("maybeAutoLoadMoreOnline();", src)

    def test_auto_load_guards(self):
        src = _read(_MAIN)
        m = re.search(r"private void maybeAutoLoadMoreOnline\(\) \{(.*?)\n    \}\n", src, re.S)
        self.assertIsNotNone(m)
        body = m.group(1)
        self.assertIn("onlineAutoLoadingMore", body, "必须有重入保护")
        self.assertIn("isOnlineMode", body)
        self.assertIn("remaining < contentScroll.getHeight() * 1.2f", body)


class TestOffscreenThumbRecycle(unittest.TestCase):
    """离屏缩略图手动回收：无 RecyclerView 架构下的内存封顶。"""

    def test_recycler_wired_to_scroll(self):
        src = _read(_MAIN)
        self.assertIn("maybeRecycleOffscreenOnlineThumbs();", src)

    def test_recycler_throttled_and_bounded(self):
        src = _read(_MAIN)
        self.assertIn("now - lastOnlineThumbRecycleMs < 250L", src, "必须节流")
        # 迟滞窗口：回绑 ±2.5 屏、释放 ±4 屏 + 压力门 700
        self.assertIn("ONLINE_REBIND_SCREENS = 2.5f", src)
        self.assertIn("ONLINE_RELEASE_SCREENS = 4.0f", src)
        self.assertIn("ONLINE_THUMB_RELEASE_GATE = 700", src)
        self.assertIn("if (retained > ONLINE_THUMB_RELEASE_GATE)", src, "低于压力门不许释放")

    def test_recycler_uses_flat_index_no_scroll_recursion(self):
        src = _read(_MAIN)
        # 滚动期走平铺索引，不在热路径递归爬树
        self.assertIn("onlineCardThumbIndex.get(card)", src)
        m = re.search(r"private void maybeRecycleOffscreenOnlineThumbs\(\) \{(.*?)\n    \}\n", src, re.S)
        self.assertIsNotNone(m)
        self.assertNotIn("getChildAt(i))", m.group(1).replace("worksContainer.getChildAt(i)", ""), "回收热路径只许看卡片一层")

    def test_rebind_checks_lru_first(self):
        src = _read(_MAIN)
        m = re.search(r"private void maybeRecycleOffscreenOnlineThumbs\(\) \{(.*?)\n    \}\n", src, re.S)
        self.assertIsNotNone(m)
        body = m.group(1)
        # 回绑必须先查内存缓存（命中即秒回，不进调度队列）
        self.assertIn("THUMBNAIL_CACHE.get(cacheKey)", body)
        self.assertNotIn(".recycle()", body, "位图实例可能在 LruCache 中复用，严禁 recycle")

    def test_index_built_at_card_creation(self):
        src = _read(_MAIN)
        self.assertIn("indexOnlineCardThumbs(card);", src)
        self.assertIn("onlineCardThumbIndex.clear();", src)
        self.assertIn("IdentityHashMap<View, List<ImageView>>", src)

    def test_version_bumped(self):
        src = _read(_GRADLE)
        self.assertIn("versionCode = 237", src)
        self.assertIn('versionName = "0.8.126"', src)


if __name__ == "__main__":
    unittest.main()
