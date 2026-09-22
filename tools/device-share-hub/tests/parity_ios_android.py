# -*- coding: utf-8 -*-
"""iOS / Android 三端对等契约闸门（源码级）

2026-09-22 建立。背景：
- 旧记忆里的 `_parity_audit/audit_three_end_parity.py` 在仓库中**并不存在**（记忆漂移），
  等于"没有闸门却以为有"。本文件补上，并且**只放可源码验证的硬判据**。
- 用户 2026-09-22 现场反馈的 iOS 缺口全部固化成本契约：
  C1 长按平台按钮 → 文案预览（Android 有 / iOS 缺）
  C2 顶部作品搜索框（Android 有 / iOS 缺）
  C3 平台按钮换行全展开（Android FlowLayout / iOS 曾为单行横滚）
  C4 元信息附加行（✓ 平台明细 / 🗑️ 垃圾备注）

纪律：改实现**之前**必须先看到对应项 FAIL，改完必须 PASS。
退出码 0 = 全通过；1 = 有未达标项。
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IOS = ROOT / "ios" / "Album"
ANDROID = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "zwm" / "gallery"


def read_all(d, exts):
    out = []
    if not d.is_dir():
        return out
    for p in d.rglob("*"):
        if p.is_file() and p.suffix.lower() in exts:
            try:
                out.append((p, p.read_text(encoding="utf-8", errors="replace")))
            except Exception:
                pass
    return out


IOS_SRC = read_all(IOS, {".swift"})
AND_SRC = read_all(ANDROID, {".java"})


def has(src, needle):
    return any(needle in text for _, text in src)


def check(name, ok, detail):
    print("  %s %-46s %s" % ("PASS" if ok else "FAIL", name, detail if not ok else ""))
    return ok


def main():
    print("=== iOS / Android 对等契约 ===")
    print("  iOS 源文件 %d 个 / Android 源文件 %d 个" % (len(IOS_SRC), len(AND_SRC)))
    print()
    results = []

    # C1 长按平台按钮 → 文案预览
    results.append(check(
        "C1 iOS 平台按钮长按预览文案",
        has(IOS_SRC, "platformLongPress") or has(IOS_SRC, "platformButtonLongPress"),
        "iOS 源码缺少平台按钮长按手势（Android MainActivity 有 btn.setOnLongClickListener）"))

    # C2 顶部搜索框
    results.append(check(
        "C2 iOS 顶部作品搜索框",
        has(IOS_SRC, "UISearchBar") or has(IOS_SRC, "searchBar("),
        "iOS 源码缺少 UISearchBar"))

    # C3 平台按钮换行全展开
    # 三条都要查：只查类名会放过「加了类但没接线」，只查没横滚会放过「没换布局」。
    cv = "\n".join(t for p, t in IOS_SRC if p.name == "ContentView.swift")
    c3_flow = "class PlatformFlowView" in cv
    c3_no_scroll = "platformScroll" not in cv
    c3_dynamic = "platformRowCount(labels:" in cv and "cardBaseHeight" in cv
    results.append(check(
        "C3 iOS 平台按钮换行全展开",
        c3_flow and c3_no_scroll and c3_dynamic,
        "flow=%s 无横滚=%s 行高动态=%s" % (c3_flow, c3_no_scroll, c3_dynamic)))

    # C4 元信息附加行
    # 附加行写进文本还不够：UILabel 默认 numberOfLines = 1，不设 0 就是「加了看不见」。
    c4_detail = "✓ 小红书" in cv
    c4_multiline = "detail.numberOfLines = 0" in cv
    results.append(check(
        "C4 iOS 本地卡 ✓ 平台使用明细",
        c4_detail and c4_multiline,
        "附加行=%s 多行生效=%s" % (c4_detail, c4_multiline)))
    results.append(check(
        "C4 iOS 回收站 🗑️ 垃圾备注",
        has(IOS_SRC, "🗑️ 垃圾样本"),
        "iOS 卡片缺少「🗑️ 垃圾样本 + 垃圾备注」附加行"))

    # C5 文案预览弹窗的三个出口：关闭 / 复制全文 / 前往使用（Android AlertDialog 三按钮）
    c5_copy = "复制全文" in cv
    c5_go = "前往使用" in cv
    c5_count = ("共 \\(charCount) 字" in cv) or ("共 %d 字" in cv)
    results.append(check(
        "C5 iOS 文案预览：复制全文 / 前往使用",
        c5_copy and c5_go and c5_count,
        "复制全文=%s 前往使用=%s 字数=%s" % (c5_copy, c5_go, c5_count)))

    # C6 在线列表分页（Android 首屏 30 条 + 「加载更多」，iOS 曾一次性全渲染）
    c6_limit = "onlinePageLimit" in cv
    c6_btn = "加载更多作品" in cv
    results.append(check(
        "C6 iOS 在线列表分页加载",
        c6_limit and c6_btn,
        "pageLimit=%s 加载更多=%s" % (c6_limit, c6_btn)))

    # C7 在线回收站分页
    orv = "\n".join(t for p, t in IOS_SRC if p.name == "OnlineRecycleView.swift")
    c7_limit = "recyclePageLimit" in orv
    c7_btn = "加载更多" in orv
    results.append(check(
        "C7 iOS 在线回收站分页加载",
        c7_limit and c7_btn,
        "pageLimit=%s 加载更多=%s" % (c7_limit, c7_btn)))

    # C8 重复分享确认（Android：作品已分享过 N 次 → 弹二次确认）
    # 判能力不判逐字文案 —— iOS 用的是「继续发布全部 / 继续发布」，语义同一回事。
    wdv = "\n".join(t for p, t in IOS_SRC if p.name == "WorkDetailView.swift")
    c8_guard = wdv.count("work.shareCount > 0") >= 2
    c8_alert = "继续发布" in wdv
    results.append(check(
        "C8 iOS 重复分享二次确认",
        c8_guard and c8_alert,
        "两处入口都拦=%s 确认按钮=%s" % (c8_guard, c8_alert)))

    print()
    bad = results.count(False)
    if bad:
        print("❌ %d/%d 项未达标" % (bad, len(results)))
        return 1
    print("✅ %d/%d 项全部通过" % (len(results), len(results)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
