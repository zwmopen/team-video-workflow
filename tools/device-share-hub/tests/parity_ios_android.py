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
2026-09-23 DSH-095 v2 追加（iOS HIG native 重做，回应用户「太不符合直觉」）：
  C18 iOS TrashView 用 navigationItem.titleView + rightBarButtonItems（不再用 tableHeaderView 装 UIStackView）
  A5  Android 仍走 showTrash 同一屏（与 v2 行为对齐 —— v1+ v2 两端行为一致）
2026-09-24 DSH-097 追加（iOS / Android 双端主界面 toolbar 38pt 半圆浅绿统一 + folderItem 双模式分发）：
  C22 iOS toolbarButton / toolbarItem 38pt 半圆浅绿（与 Android ImageButton 42dp 视觉对齐）
  C23 iOS PlatformFlowView 居中布局（lineXOffset 算法，每行累计 + 末行结算 = Android rowXCenter）
  C24 iOS folderItem 双模式可见 + openFiles 模式分发（本地→LibraryFilesViewController / 在线→openTrash）

纪律：改实现**之前**必须先看到对应项 FAIL，改完必须 PASS。
退出码 0 = 全通过；1 = 有未达标项。
"""
import re
import sys
import os
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
# DSH-109：客户端 fetchWorks 签名在 OnlineGalleryClient.java，服务端在 online_gallery_service.py
android_client_path = ANDROID / "OnlineGalleryClient.java"
service_path = ROOT / "scripts" / "online_gallery_service.py"


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

    # ---- DSH-095 v2：iOS HIG native 重做（回应用户「太不符合直觉」）----
    # DSH-094 v1 把 segmented + 按钮塞进 tableHeaderView（Android inline header 的硬搬），
    # iOS HIG 完全不这么做。v2 改成 navigationItem.titleView + rightBarButtonItems（iOS 标准）。
    tv = next((t for p, t in IOS_SRC if p.name == "TrashView.swift"), "")

    c18_no_header = "tableHeaderView = header" not in tv and "let header = UIView()" not in tv
    c18_title_view = "navigationItem.titleView = segmented" in tv
    c18_right_btns = "navigationItem.rightBarButtonItems" in tv and "openOnlineRecycle" in tv
    results.append(check(
        "C18 iOS TrashView 用 navigationItem.titleView + rightBarButtonItems",
        c18_no_header and c18_title_view and c18_right_btns,
        "无tableHeaderView=%s titleView=segmented=%s rightBarButtonItems含openOnlineRecycle=%s"
        % (c18_no_header, c18_title_view, c18_right_btns)))

    # A5 Android 仍走 showTrash 同一屏（v1+v2 两端行为一致 = 核心对等；v2 只动 iOS 表现）。
    # 判据：A4 已验 Android 仍调 showTrash（不再调 showOnlineRecycle），v2 没改 Android 路径。
    # 复跑 A4 的判据以"重新证明 v2 没动 Android"。
    and_right_btn_v2 = _extract_right_mode_button_lambda(and_main)
    a5_no_branch = "isOnlineMode" not in and_right_btn_v2 \
        and "showOnlineRecycle(" not in and_right_btn_v2
    a5_trash_only = "showTrash()" in and_right_btn_v2
    results.append(check(
        "A5 Android 仍走 showTrash 同一屏（v2 与 v1 行为一致）",
        a5_no_branch and a5_trash_only,
        "v2 没动 Android 路径；no_branch=%s trash_only=%s"
        % (a5_no_branch, a5_trash_only)))

    # ---- DSH-097：iOS / Android 主界面 toolbar 38pt 半圆浅绿 + folderItem 双模式 ----
    # 顶端按钮颜色 / 形变对等：iOS 38pt + cornerRadius 19 + 浅绿 RGB(226,244,236) + 深绿图标 RGB(15,135,88)；
    # 这套跟 Android ImageButton 42dp 视觉对齐（size 像素差 < 1pt，宽屏自适应后基本无差异）。

    # C22 iOS toolbarButton / toolbarItem 38pt 半圆浅绿（与 Android ImageButton 42dp 视觉对齐）。
    # 改前实测：34pt + cornerRadius 11 + `tintColor.withAlphaComponent(0.11)`（颜色不全、不固定 RGB）。
    c22_size = "widthAnchor.constraint(equalToConstant: 38)" in cv \
        and "heightAnchor.constraint(equalToConstant: 38)" in cv
    c22_corner = "layer.cornerRadius = 19" in cv
    c22_bg = "red: 226/255, green: 244/255, blue: 236/255" in cv
    c22_icon = "red: 15/255, green: 135/255, blue: 88/255" in cv
    c22_btn_count = cv.count("red: 226/255, green: 244/255, blue: 236/255")
    results.append(check(
        "C22 iOS toolbarButton/toolbarItem 38pt 半圆浅绿",
        c22_size and c22_corner and c22_bg and c22_icon and c22_btn_count >= 2,
        "38pt=%s 圆角19pt=%s 浅绿RGB=%s 深绿图标=%s 出现次数=%d"
        % (c22_size, c22_corner, c22_bg, c22_icon, c22_btn_count)))

    # C23 iOS PlatformFlowView 居中布局（lineXOffset 算法）。
    # 改前实测：measure 直接 `view.frame = CGRect(x: x, ...)`，整行从左边起；多行时左对齐看着散。
    # 改后：每行累计 xInLine + 末行结算 `lineXOffset = (width - lineWidth) / 2`。
    c23_var = "lineXOffset" in cv and "lineRow" in cv and "xInLine" in cv
    c23_apply = "lineXOffset + item.xInLine" in cv
    c23_no_naive = "view.frame = CGRect(x: x" not in cv  # 旧式左对齐不应再存在
    results.append(check(
        "C23 iOS PlatformFlowView 居中布局（lineXOffset）",
        c23_var and c23_apply and c23_no_naive,
        "变量齐全=%s 行内应用=%s 旧左对齐已删=%s"
        % (c23_var, c23_apply, c23_no_naive)))

    # C24 iOS folderItem 双模式可见 + openFiles 模式分发。
    # 改前实测：`folderItem?.customView?.isHidden = isOnlineMode` + openFiles 无模式分发。
    # 改后：`isHidden = false` + openFiles 首行 `if isOnlineMode { openTrash(); return }`。
    c24_no_hide = "folderItem?.customView?.isHidden = false" in cv \
        and "isHidden = isOnlineMode" not in cv
    c24_mode_branch = "if isOnlineMode {\n            openTrash()\n            return" in cv
    c24_library = "LibraryFilesViewController(rootURL: root, currentURL: root)" in cv
    results.append(check(
        "C24 iOS folderItem 双模式可见 + openFiles 模式分发",
        c24_no_hide and c24_mode_branch and c24_library,
        "不隐藏=%s openFiles模式分发=%s LibraryFiles入口保留=%s"
        % (c24_no_hide, c24_mode_branch, c24_library)))

    print()
    print("=== DSH-102: 服务端 image endpoint ?id+?file 双键（绕开 image_name_index 同名冲突） ===")
    print()
    server_src = (ROOT / "scripts" / "online_gallery_service.py").read_text(encoding="utf-8", errors="replace")
    # C25 服务端 handle_image ?id+?file 优先于 resolve_image_path。
    # DSH-102 改前实测：HEAD 版本下 `?id+?file` 走 resolve_image_path → 忽略 id
    #              → image_name_index 同名冲突 → iOS 取图永远拿第一个扫到的作品
    #              （库内 174 条 P1_封面.png 同名命中），用户 iPhone 上图错位就是这个 BUG。
    # 改后必须：`target_work = scanner.get_work(work_id)` + `os.path.join(target_work["path"], bn)`
    #              用 basename 拼作品目录，彻底绕开 image_name_index 索引。
    c25_id_param = re.search(r'id\s*=\s*query\.get\(\s*"id"\s*,\s*\[""\]\s*\)\[0\]', server_src) is not None
    c25_get_work = re.search(r'self\.scanner\.get_work\(work_id\)', server_src) is not None
    c25_path_join = re.search(r'os\.path\.join\(target_work\["path"\],\s*bn\)', server_src) is not None
    c25_basename = re.search(r"bn = file_name\.rsplit", server_src) is not None
    results.append(check(
        "C25 服务端 handle_image ?id+?file 双键 + basename 拼作品目录",
        c25_id_param and c25_get_work and c25_path_join and c25_basename,
        "id 参数=%s get_work=%s path+bn join=%s basename=%s"
        % (c25_id_param, c25_get_work, c25_path_join, c25_basename)))

    # C26 DSH-104：在线相册分类下，已用过的作品（useCount > 0）必须置顶。
    c26_sort = re.search(
        r'filtered\.sort\(\s*key\s*=\s*lambda\s+w:\s*-int\(w\.get\("useCount"\s*,\s*0\)\s*or\s*0\)\)',
        server_src) is not None
    results.append(check(
        "C26 服务端 DSH-104 分类下 useCount>0 作品置顶（按 useCount desc 排序）",
        c26_sort,
        "sort by useCount desc=%s" % c26_sort))

    # ---- DSH-107：Android 顶部 statusText 不显示「已连接电脑在线相册 (url)」----
    # 用户口径：「安卓顶部那个已连接电脑XXXXX就别显示了」+「机箱苹果那个」（= iOS 也确认无此条）。
    # 改前实测：MainActivity.java:2906 statusText.setText("💻 已连接电脑在线相册 (" + ... + ") · 共 N 套")
    #          用户觉得电脑上 IP 数字 + 端口 45835 这种技术字段不该堆在主屏顶部。
    # 改后必须：statusText.setText(...) 调用里没有 "已连接电脑在线相册" 字符串，
    #          作品数 (onlineWorks.size()) 仍保留。
    status_text_calls = re.findall(
        r'statusText\.setText\(\s*([^)]*?)\s*\)', and_main, re.DOTALL)
    a6_status_text = " ".join(status_text_calls)
    a6_and_no_status = "已连接电脑在线相册" not in a6_status_text
    a6_and_keep_count = "onlineWorks.size()" in and_main
    results.append(check(
        "A6 Android 顶部 statusText 不显示「已连接电脑在线相册 (url)」（DSH-107）",
        a6_and_no_status and a6_and_keep_count,
        "statusText调用中含字符串=%s 保留作品数=%s 共%d处statusText"
        % (not a6_and_no_status, a6_and_keep_count, len(status_text_calls))))

    # ---- DSH-108：底部三按钮顺序统一 = 重置 → 复制 → 删除（与 iOS 对齐）----
    # 用户口径：苹果端「按钮应该换成 重置、复制、删除」+ 安卓端「（删除、复制）放在最底下那一行」
    # 改前实测：旧代码 iOS 与 Android 顺序都是 reset → delete → copyPath，挤在 platformRow 里没分开
    # 改后必须：
    #   - iOS ContentView.swift 两处 actionRow.addArrangedSubview 顺序 = reset → copyPath → delete
    #   - Android MainActivity.java onlineWorkCard 拆出 bottomActionRow = reset → copyPath → delete

    # iOS: 两处正确顺序
    ios_pattern = re.compile(
        r"actionRow\.addArrangedSubview\(resetButton\)\s*\n\s*"
        r"actionRow\.addArrangedSubview\(copyPathButton\)\s*\n\s*"
        r"actionRow\.addArrangedSubview\(deleteButton\)"
    )
    a7_ios_correct = len(ios_pattern.findall(cv)) >= 2
    a7_ios_old_gone = not re.search(
        r"actionRow\.addArrangedSubview\(resetButton\)\s*\n\s*"
        r"actionRow\.addArrangedSubview\(deleteButton\)\s*\n\s*"
        r"actionRow\.addArrangedSubview\(copyPathButton\)",
        cv,
    )

    # Android: bottomActionRow 拆出 + 顺序 reset → copyPath → delete
    # Android: 缩窗到 onlineWorkCard 函数体（line 3635~3879），排除本地 workCard 的 platformRow
    online_card_window = and_main.split("private View onlineWorkCard", 1)
    online_card_body = online_card_window[1].split("private void confirmResetOnlineWork", 1)[0] \
        if len(online_card_window) > 1 else ""

    a7_and_split = "bottomActionRow" in and_main
    a7_and_order = re.search(
        r"bottomActionRow\.addView\(reset[,\s]"
        r".*?bottomActionRow\.addView\(copyPathButton"
        r".*?bottomActionRow\.addView\(delete[,\s]",
        and_main, re.DOTALL) is not None
    # 强校验：onlineWorkCard 内的 platformRow 不再混 reset/delete/copyPathButton（已拆出）
    # 用 split 缩到 onlineWorkCard 窗内，避免命中本地 workCard 的同名 patternRow
    a7_and_no_mix = re.search(
        r"platformRow\.addView\(reset\b|platformRow\.addView\(delete\b",
        online_card_body) is None
    results.append(check(
        "A7 底部三按钮重置→复制→删除（iOS 顺序 + Android 拆出）（DSH-108）",
        a7_ios_correct and a7_ios_old_gone and a7_and_split and a7_and_order and a7_and_no_mix,
        "iOS正确顺序=%d处 iOS旧序消失=%s Android拆分=%s Android顺序=%s Android不再混=%s"
        % (len(ios_pattern.findall(cv)), a7_ios_old_gone, a7_and_split, a7_and_order, a7_and_no_mix)))

    # ---- DSH-108：Android 搜索框位置 = 分类按钮之下（与 iOS 对齐）----
    # 用户原话：「安卓现在搜索框是在按钮之上的，现在要把它改成和苹果一样，都放在分类按钮之下」
    # iOS 端：C2 已通过（搜索框在分类文件夹之下）。Android 端：当前 frozenLayout.addView 顺序是
    # titleRow → statusText → searchBar → recycleTabs → categorySelector → contentFrame
    # 期望顺序：titleRow → statusText → recycleTabs → categorySelector → searchBar → contentFrame
    # 强校验：缩到 buildUi 内 frozenLayout 装配片段，searchBar.addView 必须出现在 categorySelector.addView 之后
    build_window = and_main.split("LinearLayout frozenLayout = new LinearLayout(this);", 1)
    build_tail = build_window[1].split("frame.addView(frozenLayout", 1)[0] \
        if len(build_window) > 1 else ""

    a8_and_category_pos = re.search(r"frozenLayout\.addView\(categorySelector", build_tail)
    a8_and_search_pos = re.search(r"frozenLayout\.addView\(searchBar", build_tail)
    a8_and_search_after_category = (
        a8_and_category_pos is not None
        and a8_and_search_pos is not None
        and a8_and_search_pos.start() > a8_and_category_pos.start()
    )
    results.append(check(
        "A8 Android 搜索框位置 = 分类按钮之下（DSH-108）",
        a8_and_search_after_category,
        "categoryPos=%s searchPos=%s searchAfterCategory=%s"
        % (
            "line@" + str(build_tail[:a8_and_category_pos.start()].count("\n") + 1) if a8_and_category_pos else "None",
            "line@" + str(build_tail[:a8_and_search_pos.start()].count("\n") + 1) if a8_and_search_pos else "None",
            a8_and_search_after_category,
        )))

    # ---- DSH-109：在线相册排序机制 = 搜索框右侧排序按钮 + 服务端 ?sort= 参数 + sizeBytes 字段 ----
    # 用户口径：「最好能实现：默认排序 = 时间最新在底；筛选 = 名称/大小」
    # Android 端：
    #   - searchBar 内有 sortKeyButton + 5 种排序 PopupMenu（time_asc / name_asc / name_desc / size_desc / size_asc）
    #   - sortKey 持久化到 SharedPreferences（PREF_ONLINE_SORT_KEY）
    #   - onlineClient.fetchWorks 调用带 sortKey 参数
    a9_and_sort_btn = re.search(
        r"sortKeyButton = new Button\(this\)|sortKeyButton = new Button\b",
        and_main) is not None
    a9_and_sort_menu = "SORT_MENU" in and_main and "showSortKeyMenu" in and_main
    a9_and_sort_prefs = "PREF_ONLINE_SORT_KEY" in and_main
    a9_and_sort_call = "fetchWorks(null, null, currentSortKey" in and_main
    # 客户端 fetchWorks 签名：sortKey 参数 + URL 拼装 &sort=
    a9_client_sort_param = re.search(
        r"public void fetchWorks\(String category, String query, String sortKey",
        open(android_client_path, "r", encoding="utf-8", errors="replace").read()) is not None \
        if android_client_path.exists() else False
    a9_client_sort_url = re.search(
        r'\.append\("sort="\)', open(android_client_path, "r", encoding="utf-8", errors="replace").read()) is not None \
        if android_client_path.exists() else False

    # 服务端：SORT_KEYS 常量 + ?sort= 参数 + sizeBytes 字段
    service_src = open(service_path, "r", encoding="utf-8", errors="replace").read()
    a9_service_keys = "SORT_KEYS" in service_src and "time_asc" in service_src and "size_desc" in service_src
    a9_service_sort_param = re.search(
        r'query\.get\("sort"', service_src) is not None
    a9_service_size_bytes = '"sizeBytes"' in service_src and "_dir_size_bytes" in service_src

    # ⚠️ DSH-112 补漏：A9 原版**只查 Android**，于是 iOS 端整个排序功能从没做过，
    # 而闸门一路绿灯 —— 这正是「对等契约」最危险的失效方式（单端通过冒充两端通过）。
    # 现在两端都查。判据用**特征串**而非全文件短子串（见 A13 假闸门教训）。
    ios_client_src = "\n".join(
        t for p, t in IOS_SRC if p.name == "OnlineGalleryClient.swift")
    # 排序按钮本体
    a9_ios_sort_btn = "onlineSortButton" in cv
    # 6 个排序键必须都出现在菜单里（这些串只会出现在 sortMenu 定义中）
    a9_ios_sort_menu = all(
        k in cv for k in ("time_desc", "time_asc", "name_asc",
                          "name_desc", "size_desc", "size_asc"))
    # 持久化到 UserDefaults
    a9_ios_sort_prefs = "online_sort_key" in cv
    # 加载列表时真的把排序键传下去（不是只存着不用）
    a9_ios_sort_call = "fetchWorks(sortKey: currentSortKey)" in cv
    # iOS 客户端：fetchWorks 有 sortKey 形参 + URL 拼 &sort=
    a9_ios_client_sort_param = re.search(
        r"sortKey: String\? = nil", ios_client_src) is not None
    a9_ios_client_sort_url = re.search(
        r'URLQueryItem\(name: "sort"', ios_client_src) is not None

    a9_overall = (
        a9_and_sort_btn
        and a9_and_sort_menu
        and a9_and_sort_prefs
        and a9_and_sort_call
        and a9_client_sort_param
        and a9_client_sort_url
        and a9_service_keys
        and a9_service_sort_param
        and a9_service_size_bytes
        and a9_ios_sort_btn
        and a9_ios_sort_menu
        and a9_ios_sort_prefs
        and a9_ios_sort_call
        and a9_ios_client_sort_param
        and a9_ios_client_sort_url
    )
    results.append(check(
        "A9 排序按钮两端对等 + sortKey 上报 + 服务端 sort 参数（DSH-109 + DSH-112 补 iOS）",
        a9_overall,
        "and[btn=%s menu=%s prefs=%s call=%s url=%s] ios[btn=%s menu=%s prefs=%s call=%s param=%s url=%s] svc[keys=%s param=%s size=%s]"
        % (
            a9_and_sort_btn, a9_and_sort_menu, a9_and_sort_prefs, a9_and_sort_call,
            a9_client_sort_url,
            a9_ios_sort_btn, a9_ios_sort_menu, a9_ios_sort_prefs, a9_ios_sort_call,
            a9_ios_client_sort_param, a9_ios_client_sort_url,
            a9_service_keys, a9_service_sort_param, a9_service_size_bytes,
        )))

    # ---- DSH-110：服务端文件监听 watchdog + 桌面入口 + 开机自启脚本 ----
    # 用户口径：「怎么才能用上在线相册？一键启动？怎么保存在线相册让它一直在线？新增作品手机端自动刷新」
    # 改动：
    #   - WorkScanner 加 _start_watchdog_loop() / _poll_diff() / watchdog_status()
    #   - run_service() 启动 watchdog 线程；/api/online/status 暴露 watchdog 字段
    #   - 桌面 3 个 .lnk：在线相册.lnk / 在线相册-状态.lnk / 在线相册-开机自启.lnk
    #   - 3 个 cmd：online_gallery.cmd / online_gallery-status.cmd / online_gallery-autostart.cmd
    #   - install-autostart.ps1：注册 Windows 启动文件夹开机自启（-Uninstall 卸载）
    #   - check_online_gallery.ps1：健康检查（IP / 端口 / 总作品数 / watchdog 状态）
    a10_watchdog_methods = (
        "_start_watchdog_loop" in service_src
        and "_poll_diff" in service_src
        and "_walk_root_paths" in service_src
        and "watchdog_status" in service_src
        and "_watchdog_paths" in service_src
    )
    a10_watchdog_start = "scanner._start_watchdog_loop()" in service_src
    a10_watchdog_status_field = '"watchdog": self.scanner.watchdog_status()' in service_src
    a10_install_autostart = (service_path.parent / "install-autostart.ps1").exists()
    a10_check_ps1 = (service_path.parent / "check_online_gallery.ps1").exists()
    a10_create_lnks = (service_path.parent / "create-desktop-shortcuts.py").exists()
    # 桌面 .lnk 是用户态外部依赖（CI runner 没有 %USERPROFILE%\Desktop\在线相册.lnk），
    # 改为验证 3 个 .cmd 入口文件存在（与 .lnk 同内容）；真桌面 .lnk 由 create-desktop-shortcuts.py 在本机单独跑验证
    a10_cmd_entry_main = (service_path.parent / "online_gallery.cmd").exists()
    a10_cmd_entry_status = (service_path.parent / "online_gallery-status.cmd").exists()
    a10_cmd_entry_autostart = (service_path.parent / "online_gallery-autostart.cmd").exists()
    a10_cmd_entries = a10_cmd_entry_main and a10_cmd_entry_status and a10_cmd_entry_autostart
    # 本机软检查：仅在 %USERPROFILE%\Desktop 真实存在时校验 .lnk；CI 上自然 False 不计入 FAIL
    a10_lnk_path = os.path.expandvars(r"%USERPROFILE%\Desktop") + r"\在线相册.lnk"
    a10_lnk_exists = os.path.exists(a10_lnk_path)

    a10_overall = (
        a10_watchdog_methods
        and a10_watchdog_start
        and a10_watchdog_status_field
        and a10_install_autostart
        and a10_check_ps1
        and a10_create_lnks
        and a10_cmd_entries
    )
    results.append(check(
        "A10 文件监听 watchdog + 桌面入口 + 开机自启（DSH-110）",
        a10_overall,
        "watchdogMethods=%s watchdogStart=%s statusField=%s install=%s check=%s createLnks=%s cmdEntries=%s"
        % (
            a10_watchdog_methods, a10_watchdog_start, a10_watchdog_status_field,
            a10_install_autostart, a10_check_ps1, a10_create_lnks, a10_cmd_entries,
        )))

    # ---- DSH-109 fix：PopupMenu 当前选中排序打勾（视觉状态显示） ----
    # 用户口径：「选中后有个状态显示就行」—— 当前实现只更新按钮文字，PopupMenu 项没标记
    # 修法：在 showSortKeyMenu 里给 currentSortKey 对应项 setCheckable(true) + setChecked(true)
    a11_and_sort_checkable = re.search(
        r"setCheckable\s*\(\s*true\s*\)", and_main) is not None
    a11_and_sort_checked = re.search(
        r"setChecked\s*\(\s*true\s*\)", and_main) is not None
    a11_and_sort_in_menu = "showSortKeyMenu" in and_main and re.search(
        r"setCheckable\s*\(\s*true\s*\)[\s\S]{0,200}showSortKeyMenu", and_main) is None \
        and re.search(r"showSortKeyMenu[\s\S]{0,500}setCheckable", and_main) is not None
    a11_overall = a11_and_sort_checkable and a11_and_sort_checked and a11_and_sort_in_menu
    results.append(check(
        "A11 排序 PopupMenu 当前选中项打勾（DSH-109 fix）",
        a11_overall,
        "checkable=%s checked=%s inShowSortKeyMenu=%s"
        % (a11_and_sort_checkable, a11_and_sort_checked, a11_and_sort_in_menu)))

    # ---- DSH-109 fix2：排序菜单补齐 time_desc（新→旧） ----
    # 用户口径：「还差一个按时间 新→旧 / 旧→新」—— 名称/大小都成对（A→Z/Z→A、大→小/小→大），
    # 唯独时间只有单向（time_asc），缺 time_desc。服务端 SORT_KEYS 早就支持，客户端菜单漏列。
    a12_menu_has_time_desc = re.search(
        r'\{\s*"time_desc"\s*,\s*"[^"]+"\s*\}', and_main) is not None
    # 6 个排序键全部出现在 SORT_MENU 里
    a12_menu_all_six = all(
        re.search(r'\{\s*"%s"\s*,' % k, and_main) is not None
        for k in ("time_desc", "time_asc", "name_asc", "name_desc", "size_desc", "size_asc")
    )
    # fallback 不能指向菜单第一项（第一项现在是 time_desc，默认却是 time_asc）
    a12_fallback_not_first = re.search(
        r'return\s+SORT_MENU\[0\]\[1\]', and_main) is None
    a12_fallback_uses_default = re.search(
        r'DEFAULT_ONLINE_SORT\s*\)+\s*return\s+pair\[1\]', and_main) is not None
    a12_overall = (
        a12_menu_has_time_desc and a12_menu_all_six
        and a12_fallback_not_first and a12_fallback_uses_default
    )
    results.append(check(
        "A12 排序菜单补齐 time_desc（新→旧）（DSH-109 fix2）",
        a12_overall,
        "timeDesc=%s allSix=%s fallbackNotFirst=%s fallbackDefault=%s"
        % (a12_menu_has_time_desc, a12_menu_all_six,
           a12_fallback_not_first, a12_fallback_uses_default)))

    # ---- DSH-111：在线相册自动刷新（手机不用下拉也能看到电脑新增的作品）----
    # 用户口径：「新增的作品……手机端都能自动刷新，那边就能看到最新的」
    # 服务端 watchdog（DSH-110）已经让数据实时正确，但客户端只会 Pull-to-refresh 和
    # 进页面时才拉 —— 补一排定时探测定时器，两端都要有（吸取 A9 只查一端的教训）。
    # 做法统一：每 30s 问 /api/online/status 拿 totalWorks + watchdog.lastChangeAt 做指纹，
    #           指纹变了才真正刷列表；滚动/搜索时不打扰。
    ios_client_src = "\n".join(
        t for p, t in IOS_SRC if p.name == "OnlineGalleryClient.swift")
    and_client_src = open(android_client_path, "r", encoding="utf-8", errors="replace").read() \
        if android_client_path.exists() else ""

    # —— 指纹探测：两端 client 都要有 ——
    a13_ios_fingerprint = re.search(
        r"func\s+fetchServerFingerprint\s*\(", ios_client_src) is not None
    a13_and_fingerprint = re.search(
        r"public void fetchServerFingerprint\s*\(", and_client_src) is not None

    # ⚠️ 判据必须**隔离到方法体内**查。踩过的坑：直接 `in and_main` 查短子串会形同虚设——
    # `searchInput.isFocused()` 在滚动监听里也有一处、`uiHandler.postDelayed` 在缩略图
    # 排水逻辑里有 5 处，删光真守卫闸门照样 PASS。所以用花括号配平取方法体。
    def _method_body(src, signature):
        i = src.find(signature)
        if i < 0:
            return ""
        j = src.find("{", i)
        if j < 0:
            return ""
        depth = 0
        for k in range(j, len(src)):
            if src[k] == "{":
                depth += 1
            elif src[k] == "}":
                depth -= 1
                if depth == 0:
                    return src[j:k + 1]
        return src[j:]

    a13_and_guard_body = _method_body(and_main, "private boolean canAutoRefreshNow()")
    a13_and_start_body = _method_body(and_main, "private void startOnlineAutoRefresh()")
    a13_and_stop_body = _method_body(and_main, "private void stopOnlineAutoRefresh()")
    a13_ios_guard_body = _method_body(cv, "private func canAutoRefreshNow()")
    a13_ios_start_body = _method_body(cv, "private func startOnlineAutoRefresh()")
    a13_ios_stop_body = _method_body(cv, "private func stopOnlineAutoRefresh()")

    # —— Android：定时器 + 守卫（都在对应方法体内）+ 生命周期挂载 ——
    a13_and_interval = "ONLINE_AUTO_REFRESH_INTERVAL_MS" in and_main
    a13_and_timer = ("startOnlineAutoRefresh" in and_main
                     and "stopOnlineAutoRefresh" in and_main
                     and "uiHandler.postDelayed" in a13_and_start_body
                     and "uiHandler.removeCallbacks" in a13_and_stop_body)
    # 守卫必须真的检查「正在搜索」和「刚动过」，否则会在用户输入时把列表拽回去
    a13_and_guard_search = "searchInput.isFocused()" in a13_and_guard_body
    a13_and_guard_quiet = "AUTO_REFRESH_QUIET_MS" in a13_and_guard_body
    a13_and_lifecycle = re.search(
        r"protected void onStart[\s\S]{0,900}startOnlineAutoRefresh", and_main) is not None \
        and re.search(r"protected void onStop[\s\S]{0,400}stopOnlineAutoRefresh", and_main) is not None
    a13_and_poke = "noteUserTouch()" in and_main

    # —— iOS：定时器 + 守卫（都在对应方法体内）+ 生命周期挂载 ——
    a13_ios_timer = ("startOnlineAutoRefresh" in cv
                     and "stopOnlineAutoRefresh" in cv
                     and "Timer.scheduledTimer" in a13_ios_start_body
                     and "invalidate()" in a13_ios_stop_body)
    a13_ios_guard_drag = "collectionView.isDragging" in a13_ios_guard_body
    a13_ios_guard_search = "workSearchBar.isFirstResponder" in a13_ios_guard_body
    a13_ios_lifecycle = re.search(
        r"func viewWillAppear[\s\S]{0,400}startOnlineAutoRefresh", cv) is not None \
        and re.search(r"func viewWillDisappear[\s\S]{0,300}stopOnlineAutoRefresh", cv) is not None

    a13_overall = (
        a13_ios_fingerprint and a13_and_fingerprint
        and a13_and_interval and a13_and_timer
        and a13_and_guard_search and a13_and_guard_quiet
        and a13_and_lifecycle and a13_and_poke
        and a13_ios_timer and a13_ios_guard_drag and a13_ios_guard_search
        and a13_ios_lifecycle
    )
    results.append(check(
        "A13 在线相册自动刷新定时器 + 打扰守卫（两端）（DSH-111）",
        a13_overall,
        "fp(ios/and)=%s/%s and定时器=%s and搜索守卫=%s and静默期=%s and生命周期=%s ios定时器=%s ios滑动守卫=%s ios搜索守卫=%s ios生命周期=%s"
        % (a13_ios_fingerprint, a13_and_fingerprint, a13_and_timer,
           a13_and_guard_search, a13_and_guard_quiet, a13_and_lifecycle,
           a13_ios_timer, a13_ios_guard_drag, a13_ios_guard_search,
           a13_ios_lifecycle)))

    # ---- DSH-112 fix：DSH-110 交付的 .ps1 必须带 UTF-8 BOM ----
    # 踩过的坑：用「UTF-8 无 BOM」写含中文的 .ps1，PowerShell 5.1 会按 GBK 去读，
    # 中文/emoji 全变乱码 —— 注释里的乱码还能侥幸跑，但 Write-Host "✅ 已注册..."
    # 这种字符串里的会直接**语法错误**，脚本整个跑不起来（实测 install-autostart.ps1
    # 报「表达式或语句中包含意外的标记」，而当时看不出原因，因为子进程 stderr 被吞）。
    # 仓库里老脚本（build-local.ps1 / copy-usb-apk.ps1）本来就都带 BOM，是我新写的三个漏了。
    ps1_dir = service_path.parent
    ps1_names = ["check_online_gallery.ps1", "install-autostart.ps1",
                 "restart_online_gallery.ps1"]
    a14_bom = {}
    for n in ps1_names:
        fp = ps1_dir / n
        if not fp.exists():
            a14_bom[n] = False
            continue
        with open(fp, "rb") as fh:
            a14_bom[n] = fh.read(3) == b"\xef\xbb\xbf"
    a14_overall = all(a14_bom.values())
    results.append(check(
        "A14 开机自启 .ps1 带 UTF-8 BOM（否则 PowerShell 5.1 中文乱码挂掉）（DSH-112 fix）",
        a14_overall,
        " ".join("%s=%s" % (n.split(".")[0], v) for n, v in a14_bom.items())))

    # ---- DSH-112 fix3：开机自启必须「只有一个入口」且「真传 -Restart」 ----
    # 踩过的坑（都是静默的，不报错，只是行为不对）：
    #   ① restart_online_gallery.ps1 的注释写着「传 -Restart 保证幂等」，代码里压根没传。
    #      不带 -Restart 时，若旧进程仍在监听且能应答，脚本直接 return —— 于是开机后
    #      服务继续用旧代码跑（历史上就是这么让 thumb=1 静默回落成发原图的）。
    #   ② 启动文件夹里躺着两份开机自启：2026-09-20 的 DeviceShareHub-OnlineGallery.vbs
    #      和 DSH-110 装的 DSH-OnlineGallery-AutoStart.lnk。开机瞬间并发抢 45835，
    #      且 -Uninstall 只删 .lnk —— 用户以为卸载了，.vbs 照样把服务拉起来。
    # 判据粒度纪律：查「完整调用语句」，不查裸子串 —— 「-Restart」在注释里也出现了 3 次，
    # 只查子串的话把实现删光闸门照样 PASS（假闸门）。
    restart_src = (ps1_dir / "restart_online_gallery.ps1").read_text(encoding="utf-8-sig")
    install_src = (ps1_dir / "install-autostart.ps1").read_text(encoding="utf-8-sig")

    a15_restart_call = re.search(
        r"&\s+\$StartScript\s+-Port\s+\$Port\s+-Restart", restart_src) is not None
    a15_legacy_named = ("DeviceShareHub-OnlineGallery.vbs" in install_src
                        and "$LegacyVbsPath" in install_src)
    # 注册路径 + 卸载路径各清一次 ⇒ Remove-Item 出现 2 次
    a15_legacy_cleanup = len(re.findall(
        r"Remove-Item\s+-LiteralPath\s+\$LegacyVbsPath", install_src)) >= 2

    a15_overall = a15_restart_call and a15_legacy_named and a15_legacy_cleanup
    results.append(check(
        "A15 开机自启单入口 + restart 真传 -Restart（DSH-112 fix3）",
        a15_overall,
        "restartCall=%s legacyNamed=%s legacyCleanup=%s"
        % (a15_restart_call, a15_legacy_named, a15_legacy_cleanup)))

    print()
    bad = results.count(False)
    if bad:
        print("❌ %d/%d 项未达标" % (bad, len(results)))
        return 1
    print("✅ %d/%d 项全部通过" % (len(results), len(results)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
