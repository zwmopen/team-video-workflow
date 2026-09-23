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
2026-09-23 DSH-094 追加：
  C14 版本名不截断到 4 字（两端；5 字「抖音无营销」曾被砍成「抖音无营」）
  C15 多版本按钮顺序 = 文案块先后顺序（Android 曾摘「抖音」项追加末尾 + `result.sort`）
  C16 版本按钮允许折行且 iOS 行高与卡片高度预估同源
2026-09-23 DSH-095 追加：
  C17 iOS openTrash 不再按 isOnlineMode 分支（统一 push TrashViewController）
  A4  Android rightModeButton 不再按 isOnlineMode 跳 OnlineRecycle（统一调 showTrash）

纪律：改实现**之前**必须先看到对应项 FAIL，改完必须 PASS。
退出码 0 = 全通过；1 = 有未达标项。
"""
import re
import sys
from pathlib import Path

# ⚠️ 本脚本会打印 ✅ / ❌ 与中文。Windows runner（或任何非 UTF-8 locale 的重定向 stdout）
# 下默认按 locale 编码（en-US 是 cp1252）输出 ⇒ 直接 `UnicodeEncodeError` 崩掉。
# 固定 utf-8 + errors=replace，保证在任何 runner 上都不会因为「打印」而失败。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def _extract_open_trash_body(cv_text):
    """从 ContentView.swift 全文抽出 openTrash 函数体（@objc 到下一个 @objc 之间）。"""
    m = re.search(r"@objc private func openTrash\(\) \{(.*?)(?=\n    @objc)", cv_text, re.DOTALL)
    return m.group(0) if m else ""


def _extract_right_mode_button_lambda(and_main_text):
    """从 MainActivity.java 全文抽出 rightModeButton.setOnClickListener 的 lambda 体。"""
    m = re.search(
        r"rightModeButton\.setOnClickListener\(v -> \{(.*?)\}\);",
        and_main_text, re.DOTALL)
    return m.group(0) if m else ""

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

    # ---- DSH-093：称呼 / 口径统一（以 Android 为基准）----
    and_main = "\n".join(t for p, t in AND_SRC if p.name == "MainActivity.java")

    # C9 本地卡「已使用 N 次」的计数口径。
    # Android 用 xhsShareCount + douyinShareCount（因为下面一行明细就是这两个数，
    # 总数必须等于明细之和）；iOS 曾直接用 shareCount ⇒ 同一作品两端数字不一样。
    c9_and = "localUsedCount" in and_main
    c9_ios = "let localUsed = work.xhsShareCount + work.douyinShareCount" in cv
    c9_no_share = "已使用 \\(work.shareCount) 次" not in cv
    results.append(check(
        "C9 本地卡使用次数 = 小红书+抖音（两端同口径）",
        c9_and and c9_ios and c9_no_share,
        "安卓基准=%s iOS改用合计数=%s 不再用shareCount=%s" % (c9_and, c9_ios, c9_no_share)))

    # C10 在线卡「记录：」行（Android 在线卡拼 dispatchedTo）
    c10_and = "记录：" in and_main
    c10_ios = "记录：" in cv
    results.append(check(
        "C10 iOS 在线卡「记录：」分发去向",
        c10_and and c10_ios,
        "安卓有=%s iOS有=%s" % (c10_and, c10_ios)))

    # C11「垃圾备注」称呼统一：安卓叫「垃圾备注：」，iOS 回收站两处曾叫「备注：」
    tv = "\n".join(t for p, t in IOS_SRC if p.name == "TrashView.swift")
    c11_online = "垃圾备注：" in cv
    c11_recycle = "垃圾备注：" in orv and "\\n备注：" not in orv
    c11_trash = "垃圾备注：" in tv and "\\n备注：" not in tv
    results.append(check(
        "C11「垃圾备注：」称呼三处统一",
        c11_online and c11_recycle and c11_trash,
        "在线卡=%s 在线回收站=%s 本地回收站=%s" % (c11_online, c11_recycle, c11_trash)))

    # C12 本地卡自动清理倒计时（Android deleteCountdown：「N 分钟后自动删除」）
    c12_and = "分钟后自动删除" in and_main
    c12_ios = "分钟后自动删除" in cv and "即将自动删除" in cv
    results.append(check(
        "C12 iOS 本地卡自动清理倒计时",
        c12_and and c12_ios,
        "安卓有=%s iOS有=%s" % (c12_and, c12_ios)))

    # C13 分平台计数要覆盖全部小红书槽位。
    # Android 把 XHS / XHS_2 / XHS_3 都算进 xhsShareCount；iOS 枚举里有 .xhs3 也能生成按钮，
    # 但 WorkLibrary 只判了 .xhs / .xhs2 ⇒ 点「短文精选版」分享后 iOS 不计次数，两端逐步分叉。
    wl = "\n".join(t for p, t in IOS_SRC if p.name == "WorkLibrary.swift")
    c13_and = "XHS_3" in and_main and "xhsShareCount" in and_main
    c13_ios = "platform == .xhs || platform == .xhs2 || platform == .xhs3" in wl
    results.append(check(
        "C13 iOS 小红书三槽位都计入次数",
        c13_and and c13_ios,
        "安卓含XHS_3=%s iOS三槽位=%s" % (c13_and, c13_ios)))

    # ---- DSH-093 反向：安卓自己的三处不合理（别把安卓当圣旨，它也会错）----

    # A1 分页「首屏 = 步长」：原本首屏 25、步长 30，两个数不搭
    a1 = "onlinePageLimit = 30" in and_main and "onlinePageLimit += 30" in and_main \
        and "onlinePageLimit = 25" not in and_main
    results.append(check(
        "A1 安卓在线分页首屏与步长一致（30/+30）",
        a1,
        "原本首屏 25 却每次 +30，两个数不搭"))

    # A2 本地卡：状态行用 localUsedCount>0，明细行原本却用 work.used ⇒ 自相矛盾
    a2_state = "localUsedCount > 0 ?" in and_main
    a2_detail = "if (localUsedCount > 0) {" in and_main
    a2_bad = 'if (work.used) {\n            detail += "\\n✓ 小红书' in and_main
    results.append(check(
        "A2 安卓本地卡明细行与状态行同口径",
        a2_state and a2_detail and not a2_bad,
        "状态行=%s 明细行=%s 仍用work.used=%s" % (a2_state, a2_detail, a2_bad)))

    # A3「🗑️ 垃圾样本」前缀：本地回收站有、在线卡原本没有 ⇒ 安卓自己两处不一致。
    # ⚠️ 别只数「🗑️ 垃圾样本」出现次数 —— 它在 badge 里也出现，改前就已经是 2 次，
    #    那样判据改前改后都 PASS，等于没有闸门。必须查在线卡那句 append 的精确形态。
    a3 = 'detail.append(" · 🗑️ 垃圾样本\\n垃圾备注：")' in and_main
    results.append(check(
        "A3 安卓在线卡垃圾备注补 🗑️ 前缀",
        a3,
        "在线卡 append 语句里没找到「 · 🗑️ 垃圾样本\\n垃圾备注：」"))

    # ---- DSH-094：老三家文案前端化（改名 + 重排 + 按钮完整字数）----
    # 用户口径：「11 个版本是一样的、平级的……前面三个版本我规定写在文档里面在前，
    #            手机那边就是跟随 文案.txt 识别」「按钮要完整字数，不要限制 4 字」。
    and_parser = "\n".join(t for p, t in AND_SRC if p.name == "PlatformCopyParser.java")
    ios_parser = "\n".join(t for p, t in IOS_SRC if p.name == "PlatformCopyParser.swift")

    # C14 版本名不截断到 4 字（两端）。
    # 改前实测：Android `return trimmed.length() > 4 ? trimmed.substring(0, 4) : trimmed;`、
    #          iOS `return String(trimmed.prefix(4))` ⇒ 5 字的「抖音无营销」被砍成「抖音无营」。
    c14_and = "substring(0, 4)" not in and_parser and "return trimmed;" in and_parser
    c14_ios = "prefix(4)" not in ios_parser and "return trimmed" in ios_parser
    results.append(check(
        "C14 版本名不截断到 4 字（两端）",
        c14_and and c14_ios,
        "安卓已去掉截断=%s iOS已去掉截断=%s" % (c14_and, c14_ios)))

    # C15 多版本按钮顺序 = 文案块先后顺序（两端都不排序、不摘项）。
    # 改前实测：Android `enrichPlatformSuite` 把「抖音」那一版摘出来 `result.add(douyinItem)`
    # 追加到末尾，末尾还有 `result.sort(getButtonRank)` ⇒ 已排在文案最前的抖音版被甩到整行最后；
    # iOS 直接 `if !multiItems.isEmpty { return multiItems }`，两端顺序因此不一致。
    c15_and = "return new ArrayList<>(rawPlatforms);" in and_main \
        and "result.add(douyinItem)" not in and_main
    c15_ios = "if !multiItems.isEmpty { return multiItems }" in ios_parser
    results.append(check(
        "C15 多版本按钮顺序 = 文案块顺序（两端不排序）",
        c15_and and c15_ios,
        "安卓多版本原样返回=%s iOS原样返回=%s" % (c15_and, c15_ios)))

    # C16 版本按钮允许折行，且 iOS 行高与卡片高度预估同源。
    # Android 原本 `styleNeumorphicButton` 里 `button.setMaxLines(1)` ⇒ 长名字被切；
    # iOS 由 `PlatformFlowView` 承载，`rowHeight` 必须与 `sizeForItemAt` 用的
    # `WorkCell.platformRowHeight` 同源，否则预估行数与实渲染不一致（裁切或大片留白）。
    c16_and = "button.setMaxLines(1)" not in and_main and "button.setMaxLines(2)" in and_main
    c16_ios = "platformRow.rowHeight = WorkCell.platformRowHeight" in cv
    results.append(check(
        "C16 版本按钮允许折行且 iOS 行高同源",
        c16_and and c16_ios,
        "安卓放开单行=%s iOS行高同源=%s" % (c16_and, c16_ios)))

    # ---- DSH-095：回收站统一入口（本地/在线模式都进同一屏）----
    # 用户口径：「回收站是本地相册和在线相册共用的……顶多再回收站里面你显示是在线还是本地，
    #            现在的本地相册界面点击回收站那个界面可以，在线相册点击居然没进去这个界面」。
    # 含义：两端都不再按 isOnlineMode 把「回收站」按钮分流到两个不同的屏幕，
    #        电脑端回收站（_已发送1次 + _垃圾作品）从内部按钮「💻 打开电脑端回收站」再跳。

    # C17 iOS openTrash 不再按 isOnlineMode 分支，统一 push TrashViewController。
    # 改前实测：openTrash 内 `if isOnlineMode { push OnlineRecycleViewController } else { TrashView }`。
    ios_open_trash = _extract_open_trash_body(cv)
    c17_no_branch = "if isOnlineMode" not in ios_open_trash \
        and "OnlineRecycleViewController" not in ios_open_trash
    c17_trash_only = "TrashViewController" in ios_open_trash
    results.append(check(
        "C17 iOS openTrash 不再按 isOnlineMode 分支",
        c17_no_branch and c17_trash_only,
        "openTrash 仍按模式分流=%s 不再含OnlineRecycle=%s 含TrashViewController=%s"
        % (not c17_no_branch, "OnlineRecycleViewController" not in ios_open_trash, c17_trash_only)))

    # A4 Android rightModeButton 不再按 isOnlineMode 跳 OnlineRecycle，统一调 showTrash()。
    # 改前实测：`else if (isOnlineMode) { toast("在线回收站"); showOnlineRecycle("sent"); }`。
    and_right_btn = _extract_right_mode_button_lambda(and_main)
    a4_no_branch = "isOnlineMode" not in and_right_btn \
        and "showOnlineRecycle(" not in and_right_btn
    a4_trash_only = "showTrash()" in and_right_btn
    results.append(check(
        "A4 Android rightModeButton 不再调 OnlineRecycle",
        a4_no_branch and a4_trash_only,
        "rightModeButton 仍按模式分流=%s 不再含showOnlineRecycle=%s 含showTrash=%s"
        % (not a4_no_branch, "showOnlineRecycle(" not in and_right_btn, a4_trash_only)))

    print()
    bad = results.count(False)
    if bad:
        print("❌ %d/%d 项未达标" % (bad, len(results)))
        return 1
    print("✅ %d/%d 项全部通过" % (len(results), len(results)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
