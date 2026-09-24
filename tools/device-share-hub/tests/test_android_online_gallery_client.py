# -*- coding: utf-8 -*-
"""Android 在线相册客户端硬判据闸门（DSH-098 引入）。

背景：2026-09-24 用户反馈 K60 "获取在线相册失败"，日志指向
`OnlineGalleryClient.downloadThumb` 用 `ByteArrayOutputStream` 累积 byte[]，
THUMB_MAX_BYTES = 512KB × N 并发触发 OOM / GC 抖动。同时按用户硬约束
"所有开发都要有 debug 回传"，必须把每个错误路径都同时写 Log.w（堆栈）+ DiagnosticLog.write（持久化）。

判据：
A1 downloadThumb 流式写盘（不再用 BAOS 把整张图累积到内存）
   - 函数体内 ByteArrayOutputStream = 0
   - 函数体内 FileOutputStream(tempFile) 存在（边读边写 8KB 缓冲）
   - 函数体内 byte[8192] 缓冲存在
   - 函数体内 tempFile.renameTo(diskFile) 原子落盘存在
A2 downloadThumb 大小阈值 THUMB_MAX_BYTES（> 512KB 直接拒绝，避免被服务端降级发原图打爆）
A3 downloadThumb 错误路径 Log.w(TAG,...) + DiagnosticLog.write 双写
   - 函数体内 Log.w(TAG,...) >= 6（每个错误分支一个）
   - 函数体内 DiagnosticLog.write >= 6
   - 函数体内 Log.w("OnlineGalleryClient"...) = 0（裸字符串全删）
A4 loadFullImage 同样已迁移 Log.w(TAG,...) + DiagnosticLog.write 双写
   - 函数体内 Log.w("OnlineGalleryClient"...) = 0
   - 函数体内 DiagnosticLog.write >= 1
A5 类级 TAG 常量声明（DSH-098 新增，否则所有 Log.w(TAG,...) 引用会编译失败）
A6 DiagnosticLog 双写覆盖率（全文件，错误路径都不允许"只 Log.w 不 DiagnosticLog"）
   - 全文件 Log.w(TAG,...) 处数 >= 8
   - 全文件 DiagnosticLog.write 处数 >= 8

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
CLIENT = ROOT / "android" / "app" / "src" / "main" / "java" / "com" / "zwm" / "gallery" / "OnlineGalleryClient.java"


def check(name, ok, detail):
    print("  %s %-52s %s" % ("PASS" if ok else "FAIL", name, detail if not ok else ""))
    return ok


def main():
    print("=== Android OnlineGalleryClient 硬判据闸门（DSH-098） ===")
    if not CLIENT.is_file():
        print("  FAIL 找不到 OnlineGalleryClient.java: %s" % CLIENT)
        return 1

    src = CLIENT.read_text(encoding="utf-8", errors="replace")
    download_thumb = extract_func_body(src, "private boolean downloadThumb(")
    load_full_image = extract_func_body(src, "public void loadFullImage(")
    download_work_images = extract_func_body(src, "public void downloadWorkImages(String workId, List<String> fileNames, DownloadProgressCallback callback)")

    print("  downloadThumb 函数体 %d 字符 / loadFullImage 函数体 %d 字符" % (len(download_thumb), len(load_full_image)))
    print()
    results = []

    # ---- A1：downloadThumb 流式写盘 ----
    a1_no_baos = "ByteArrayOutputStream" not in download_thumb
    a1_stream = "FileOutputStream(tempFile)" in download_thumb
    a1_buf8k = "new byte[8192]" in download_thumb
    a1_rename = "tempFile.renameTo(diskFile)" in download_thumb
    results.append(check(
        "A1 downloadThumb 流式写盘（去 BAOS + FileOutputStream + 8KB + renameTo）",
        a1_no_baos and a1_stream and a1_buf8k and a1_rename,
        "无BAOS=%s FileOutputStream(tempFile)=%s 8KB缓冲=%s renameTo=%s"
        % (a1_no_baos, a1_stream, a1_buf8k, a1_rename)))

    # ---- A2：THUMB_MAX_BYTES 大小阈值（> 512KB 直接拒绝）----
    a2_const = "THUMB_MAX_BYTES" in download_thumb
    a2_compare = ("> THUMB_MAX_BYTES" in download_thumb) or (">= THUMB_MAX_BYTES" in download_thumb)
    results.append(check(
        "A2 downloadThumb THUMB_MAX_BYTES 大小阈值",
        a2_const and a2_compare,
        "常量存在=%s 大于阈值检查=%s" % (a2_const, a2_compare)))

    # ---- A3：downloadThumb 错误路径 Log.w(TAG,...) + DiagnosticLog.write 双写 ----
    a3_logw_tag = download_thumb.count("Log.w(TAG,")
    a3_diag = download_thumb.count("DiagnosticLog.write")
    a3_no_bare = "Log.w(\"OnlineGalleryClient\"" not in download_thumb
    results.append(check(
        "A3 downloadThumb 错误路径 Log.w(TAG)+DiagnosticLog 双写",
        a3_logw_tag >= 6 and a3_diag >= 6 and a3_no_bare,
        "Log.w(TAG 处数=%d(>=6) DiagnosticLog 处数=%d(>=6) 裸字符串残留=%s"
        % (a3_logw_tag, a3_diag, not a3_no_bare)))

    # ---- A4：loadFullImage 同样迁移 ----
    a4_no_bare = "Log.w(\"OnlineGalleryClient\"" not in load_full_image
    a4_diag = load_full_image.count("DiagnosticLog.write")
    a4_logw_tag = load_full_image.count("Log.w(TAG,")
    results.append(check(
        "A4 loadFullImage Log.w(TAG)+DiagnosticLog 双写",
        a4_no_bare and a4_diag >= 1 and a4_logw_tag >= 1,
        "裸字符串残留=%s DiagnosticLog 处数=%d Log.w(TAG 处数=%d"
        % (not a4_no_bare, a4_diag, a4_logw_tag)))

    # ---- A5：类级 TAG 常量声明 ----
    a5_tag = "private static final String TAG" in src and re.search(r"private static final String TAG\s*=\s*\"OnlineGalleryClient\"", src) is not None
    results.append(check(
        "A5 类级 TAG 常量声明（Log.w(TAG,...) 引用前提）",
        a5_tag,
        "TAG 声明=%s（必须是 OnlineGalleryClient 字符串以让 logcat 过滤）" % a5_tag))

    # ---- A6：DiagnosticLog 双写覆盖率（全文件）----
    a6_logw_tag_total = src.count("Log.w(TAG,")
    a6_diag_total = src.count("DiagnosticLog.write")
    results.append(check(
        "A6 全文件 Log.w(TAG)+DiagnosticLog 双写覆盖率",
        a6_logw_tag_total >= 8 and a6_diag_total >= 8,
        "Log.w(TAG 总量=%d(>=8) DiagnosticLog 总量=%d(>=8)"
        % (a6_logw_tag_total, a6_diag_total)))

    # ---- A7：downloadWorkImages 错误路径 Log.w(TAG)+DiagnosticLog 双写（DSH-099 引入）----
    # DSH-099 修复：用户点平台按钮时下载原图路径 catch 块原本只 mainHandler.post(onError)，
    # 没 Log.w 没 DiagnosticLog，违背"debug 回传"铁律。改后必须双写。
    # 改前实测（HEAD DSH-098）：downloadWorkImages 内 DiagnosticLog.write = 0, Log.w(TAG = 0, 裸字符串 = 0（全没写过）
    a7_logw_tag = download_work_images.count("Log.w(TAG,")
    a7_diag = download_work_images.count("DiagnosticLog.write")
    a7_no_bare = "Log.w(\"OnlineGalleryClient\"" not in download_work_images
    a7_currentFile = "currentFileName" in download_work_images
    results.append(check(
        "A7 downloadWorkImages 错误路径 Log.w(TAG)+DiagnosticLog 双写",
        a7_no_bare and a7_diag >= 2 and a7_logw_tag >= 2 and a7_currentFile,
        "裸字符串残留=%s Log.w(TAG 处数=%d(>=2) DiagnosticLog 处数=%d(>=2) currentFileName=%s"
        % (not a7_no_bare, a7_logw_tag, a7_diag, a7_currentFile)))

    print()
    bad = results.count(False)
    if bad:
        print("FAIL %d/%d 项未达标" % (bad, len(results)))
        return 1
    print("PASS %d/%d 项全部通过" % (len(results), len(results)))
    return 0


if __name__ == "__main__":
    sys.exit(main())