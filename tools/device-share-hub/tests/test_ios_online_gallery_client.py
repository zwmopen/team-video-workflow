# -*- coding: utf-8 -*-
"""iOS 在线相册客户端硬判据闸门（DSH-099 iOS 等价引入 + DSH-102 iOS URL 拼接加固）。

背景：DSH-099 Android 修复 `downloadWorkImages` catch 块漏双写（用户铁律"debug 回传"）。
iOS 端对应路径是 `OnlineGalleryClient.loadImage`（in-memory UIImage 给 UIActivityViewController
分享）和 `fetchCategories` / `fetchWorks` —— 这三处 catch 块原本都静默失败，违背铁律。

iOS 用 NSLog（与现有 line 237 `[DeviceRegister] OK (%@)` 风格一致）。

DSH-102：服务端 `image_name_index()` 同名文件只保留首个命中（库内 174 条作品共用 P1_封面.png），
iOS 旧契约 `?path=裸文件名` 会让 iPhone 上图永远拿到第一个扫到的作品的图（与当前浏览无关，
用户 iPhone 上图错位显示阳澄湖/浙江省攻略就是这条 BUG）。修法：iOS loadImage 改 `?id+?file`
双键，与 Android 走齐；3 个 caller (downloadAllImages / loadOnline / loadCurrent) 必须传 workId。

判据：
B1 loadImage 失败分支有 NSLog（含 path / isThumbnail）
B2 loadImage 磁盘缓存写失败有 NSLog（替换原 try? 吞错）
B3 fetchCategories catch 有 NSLog
B4 fetchWorks catch 有 NSLog（含 category / query / error）
B5 loadImage 走新契约 ?id+?file（有 workId 时）（DSH-102）
B6 loadImage 函数签名带 workId 参数（DSH-102）
B7 ContentView 三个 caller (downloadAllImages / loadOnline / loadCurrent) 都传 workId（DSH-102）

纪律：改前必须先看到对应项 FAIL（用 `git show HEAD:./<path>` 取改前代码摸底）；
     改完必须 PASS。退出码 0 = 全通过；1 = 有未达标项。
"""
import re
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def extract_func_body(text, signature):
    # 抽函数体（signature 起，到下一行 4 空格缩进 + } + 换行 结束，含 signature 行）
    m = re.search(re.escape(signature) + r"(.*?\n    \}\n)", text, re.DOTALL)
    return m.group(0) if m else ""


ROOT = Path(__file__).resolve().parents[1]
IOS_CLIENT = ROOT / "ios" / "Album" / "OnlineGalleryClient.swift"
CONTENT_VIEW = ROOT / "ios" / "Album" / "ContentView.swift"


def check(name, ok, detail):
    print("  %s %-52s %s" % ("PASS" if ok else "FAIL", name, detail if not ok else ""))
    return ok


def main():
    print("=== iOS OnlineGalleryClient 硬判据闸门（DSH-099 iOS 等价） ===")
    if not IOS_CLIENT.is_file():
        print("  FAIL 找不到 OnlineGalleryClient.swift: %s" % IOS_CLIENT)
        return 1

    src = IOS_CLIENT.read_text(encoding="utf-8", errors="replace")
    cv_src = CONTENT_VIEW.read_text(encoding="utf-8", errors="replace")
    load_image = extract_func_body(src, "public func loadImage(")
    fetch_categories = extract_func_body(src, "public func fetchCategories(")
    fetch_works = extract_func_body(src, "public func fetchWorks(")

    print("  loadImage 函数体 %d 字符 / fetchCategories %d 字符 / fetchWorks %d 字符"
          % (len(load_image), len(fetch_categories), len(fetch_works)))
    print()
    results = []

    # ---- B1：loadImage 失败分支 NSLog（含 path / isThumbnail）----
    b1_nslog = "[OnlineGalleryClient] loadImage failed" in load_image \
        and "path=" in load_image and "isThumbnail=" in load_image
    b1_no_silent = "DispatchQueue.main.async { completion(nil) }" in load_image
    b1_real_nslog_count = sum(
        1 for ln in load_image.split('\n')
        if ln.strip().startswith("NSLog(") and "loadImage failed" in ln)
    results.append(check(
        "B1 loadImage 失败分支 NSLog（含 path / isThumbnail）",
        b1_nslog and b1_no_silent and b1_real_nslog_count >= 1,
        "NSLog标记=%s silent branch 保留=%s 真实NSLog行数=%d(>=1)"
        % (b1_nslog, b1_no_silent, b1_real_nslog_count)))

    # ---- B2：loadImage 磁盘缓存写失败 NSLog（替换 try?）----
    b2_no_try_question = "try? data.write(to: diskURL)" not in load_image
    b2_disk_nslog = "[OnlineGalleryClient] loadImage disk write failed" in load_image
    b2_do_catch = "do {\n                try data.write(to: diskURL)" in load_image \
        or "try data.write(to: diskURL)" in load_image
    results.append(check(
        "B2 loadImage 磁盘写失败 NSLog（替换原 try? 吞错）",
        b2_no_try_question and b2_disk_nslog and b2_do_catch,
        "无try?=%s disk write NSLog=%s try/catch 块=%s"
        % (b2_no_try_question, b2_disk_nslog, b2_do_catch)))

    # ---- B3：fetchCategories catch NSLog ----
    b3_catch = "} catch {" in fetch_categories
    b3_nslog = "[OnlineGalleryClient] fetchCategories failed" in fetch_categories \
        and fetch_categories.count("NSLog(") >= 1
    results.append(check(
        "B3 fetchCategories catch NSLog",
        b3_catch and b3_nslog,
        "catch存在=%s NSLog标记=%s NSLog数=%d"
        % (b3_catch, b3_nslog, fetch_categories.count("NSLog("))))

    # ---- B4：fetchWorks catch NSLog（含 category / query / error）----
    b4_catch = "} catch {" in fetch_works
    b4_nslog = "[OnlineGalleryClient] fetchWorks failed" in fetch_works \
        and "category=" in fetch_works \
        and "query=" in fetch_works \
        and "error=" in fetch_works
    b4_nslog_count = fetch_works.count("NSLog(")
    results.append(check(
        "B4 fetchWorks catch NSLog（含 category / query / error）",
        b4_catch and b4_nslog and b4_nslog_count >= 1,
        "catch存在=%s 三参数标记=%s NSLog数=%d"
        % (b4_catch, b4_nslog, b4_nslog_count)))

    print()
    print("=== DSH-102: iOS loadImage 改 ?id+?file 双键（绕开 image_name_index 同名冲突） ===")
    print()
    results_102 = []

    # ---- B6: loadImage 函数签名带 workId 参数（DSH-102）----
    # 必须先判，否则 B5 的 URLQueryItem 也可能拼在旧 ?path= 上下文里
    b6_sig = re.search(r"public func loadImage\(\s*path\s*:\s*String\s*,\s*workId\s*:\s*String\?\s*=\s*nil", load_image) is not None
    results_102.append(check(
        "B6 loadImage 签名带 workId: String? = nil",
        b6_sig,
        "签名缺失 workId 参数（必须放到 path 之后、isThumbnail 之前）"))

    # ---- B5: loadImage 走新契约 ?id+?file（有 workId 时）----
    # 用 (?:name|key)\s*:\s*"id" / "file" 容错，DSH-102 期间若做小幅重命名也不会误报
    b5_id = re.search(r'URLQueryItem\(\s*(?:name|key)\s*:\s*"id"\s*,\s*(?:value|value)\s*:\s*workId\s*\)', load_image) is not None
    b5_file = re.search(r'URLQueryItem\(\s*(?:name|key)\s*:\s*"file"\s*,\s*(?:value|value)\s*:\s*bn\s*\)', load_image) is not None
    b5_basename = "lastPathComponent" in load_image  # 必须从 path 抽 basename
    results_102.append(check(
        "B5 loadImage 走新契约 ?id+?file（带 basename 取裸文件名）",
        b5_id and b5_file and b5_basename,
        "id 拼=%s file 拼=%s basename=%s" % (b5_id, b5_file, b5_basename)))

    # ---- B7: ContentView 三个 caller 都传 workId（DSH-102）----
    # 三个调用点：line ~863 downloadAllImages(paths: entry.images, workId: entry.id, ...)
    #          line ~1466 loadOnline 内 loadImage(path: path, workId: workId, ...)
    #          line ~2318 loadCurrent 内 loadImage(path: path, workId: entry.id, ...)
    #          line ~1971 loadOnline 自身被 renderOnlinePreviews 调时传 workId
    b7_dl = "downloadAllImages(paths: entry.images, workId: entry.id" in cv_src
    b7_online_li = "OnlineGalleryClient.shared.loadImage(path: path, workId: workId" in cv_src
    b7_preview_lc = "OnlineGalleryClient.shared.loadImage(path: path, workId: entry.id" in cv_src
    b7_loadonline = re.search(r"func loadOnline\(\s*path\s*:\s*String\s*,\s*workId\s*:\s*String\?\s*=\s*nil", cv_src) is not None
    b7_render = "renderOnlinePreviews(entry.images, workId: entry.id" in cv_src
    results_102.append(check(
        "B7 ContentView 三个 caller 都传 workId（DSH-102）",
        b7_dl and b7_online_li and b7_preview_lc and b7_loadonline and b7_render,
        "downloadAllImages=%s loadOnline内loadImage=%s loadCurrent内loadImage=%s loadOnline签名=%s renderOnlinePreviews=%s"
        % (b7_dl, b7_online_li, b7_preview_lc, b7_loadonline, b7_render)))

    results.extend(results_102)

    print()
    print("=== DSH-105: iOS 重置按钮默认展示（与 Android MainActivity.java:1201 无 if 守卫对齐） ===")
    print()
    results_105 = []

    # ---- B8: 两处守卫必须消失（iOS 重置按钮默认无条件展示）----
    # 旧代码 line 1990（在线）：if entry.useCount > 0 { actionRow.addArrangedSubview(resetButton) }
    # 旧代码 line 2159（本地）：if work.shareCount > 0 { actionRow.addArrangedSubview(resetButton) }
    # DSH-105：去掉守卫，与 Android 对齐——按钮常驻可见
    b8_no_guard_online = "if entry.useCount > 0 { actionRow.addArrangedSubview(resetButton) }" not in cv_src
    b8_no_guard_local = "if work.shareCount > 0 { actionRow.addArrangedSubview(resetButton) }" not in cv_src
    # 强校验：两处都必须有"无条件 addArrangedSubview(resetButton)" —— 允许中间夹注释
    b8_unconditional_add = re.search(
        r"rebuildActionRow\(\)(?:[^\n]*\n){0,5}\s*actionRow\.addArrangedSubview\(resetButton\)", cv_src) is not None
    results_105.append(check(
        "B8 ContentView 重置按钮默认展示（去掉 useCount/shareCount 守卫）",
        b8_no_guard_online and b8_no_guard_local and b8_unconditional_add,
        "在线守卫消失=%s 本地守卫消失=%s rebuildActionRow后无条件add=%s"
        % (b8_no_guard_online, b8_no_guard_local, b8_unconditional_add)))

    results.extend(results_105)

    print()
    print("=== DSH-108: iOS 底部三按钮顺序 = 重置 → 复制 → 删除（与 Android 对齐） ===")
    print()
    results_108 = []

    # ---- B9: ContentView 两处 actionRow 顺序必须 reset → copyPath → delete ----
    # 改前实测：旧代码 reset → delete → copyPath（顺序错）
    # 改后必须：两处 addArrangedSubview 调用顺序都是 resetButton → copyPathButton → deleteButton
    online_action_pattern = re.compile(
        r"actionRow\.addArrangedSubview\(resetButton\)\s*\n\s*"
        r"actionRow\.addArrangedSubview\(copyPathButton\)\s*\n\s*"
        r"actionRow\.addArrangedSubview\(deleteButton\)"
    )
    online_matches = online_action_pattern.findall(cv_src)
    b9_online_order = len(online_matches) >= 2
    # 强校验：旧错误顺序（reset → delete → copyPath）必须消失
    b9_no_old_order = not re.search(
        r"actionRow\.addArrangedSubview\(resetButton\)\s*\n\s*"
        r"actionRow\.addArrangedSubview\(deleteButton\)\s*\n\s*"
        r"actionRow\.addArrangedSubview\(copyPathButton\)",
        cv_src,
    )
    results_108.append(check(
        "B9 ContentView actionRow 顺序 = 重置 → 复制 → 删除（DSH-108）",
        b9_online_order and b9_no_old_order,
        "正确顺序匹配次数=%d(>=2) 旧错序消失=%s"
        % (len(online_action_pattern.findall(cv_src)), b9_no_old_order)))

    results.extend(results_108)

    print()
    bad = results.count(False)
    if bad:
        print("FAIL %d/%d 项未达标" % (bad, len(results)))
        return 1
    print("PASS %d/%d 项全部通过" % (len(results), len(results)))
    return 0


if __name__ == "__main__":
    sys.exit(main())