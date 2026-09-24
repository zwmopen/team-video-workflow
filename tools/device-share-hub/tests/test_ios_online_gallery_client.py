# -*- coding: utf-8 -*-
"""iOS 在线相册客户端硬判据闸门（DSH-099 iOS 等价引入）。

背景：DSH-099 Android 修复 `downloadWorkImages` catch 块漏双写（用户铁律"debug 回传"）。
iOS 端对应路径是 `OnlineGalleryClient.loadImage`（in-memory UIImage 给 UIActivityViewController
分享）和 `fetchCategories` / `fetchWorks` —— 这三处 catch 块原本都静默失败，违背铁律。

iOS 用 NSLog（与现有 line 237 `[DeviceRegister] OK (%@)` 风格一致）。

判据：
B1 loadImage 失败分支有 NSLog（含 path / isThumbnail）
B2 loadImage 磁盘缓存写失败有 NSLog（替换原 try? 吞错）
B3 fetchCategories catch 有 NSLog
B4 fetchWorks catch 有 NSLog（含 category / query / error）

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


def check(name, ok, detail):
    print("  %s %-52s %s" % ("PASS" if ok else "FAIL", name, detail if not ok else ""))
    return ok


def main():
    print("=== iOS OnlineGalleryClient 硬判据闸门（DSH-099 iOS 等价） ===")
    if not IOS_CLIENT.is_file():
        print("  FAIL 找不到 OnlineGalleryClient.swift: %s" % IOS_CLIENT)
        return 1

    src = IOS_CLIENT.read_text(encoding="utf-8", errors="replace")
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
    bad = results.count(False)
    if bad:
        print("FAIL %d/%d 项未达标" % (bad, len(results)))
        return 1
    print("PASS %d/%d 项全部通过" % (len(results), len(results)))
    return 0


if __name__ == "__main__":
    sys.exit(main())