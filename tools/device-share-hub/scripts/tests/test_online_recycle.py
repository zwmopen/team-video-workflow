# -*- coding: utf-8 -*-
"""在线回收站接口回归测试（GET /api/online/recycle、POST restore、POST remark-garbage）。

安全设计：只在成品库里创建带 `ZZ_` 前缀的夹具作品，测试结束（含异常路径）一律清理，
绝不触碰任何真实作品。可直接运行，也可被 pytest 收集。

用法：
    python test_online_recycle.py                       # 默认 http://127.0.0.1:45835
    python test_online_recycle.py http://192.168.1.27:45835
"""
import json
import os
import shutil
import struct
import sys
import time
import urllib.error
import urllib.request
import zlib
import csv

DEFAULT_BASE = "http://127.0.0.1:45835"
DEFAULT_ROOT = r"D:\AICode\项目推进\projects\江湖有旅人\主项目\成品库（GPT+本地脚本制作）"

STAGE0 = "已发送0次（抖音小红书可发）"
STAGE1 = "_已发送1次（微信公众号可发）"
GARBAGE = "_垃圾作品（后续参考分析）"

FIX_PREFIX = "ZZ_回收站回归测试"
FIX_GARBAGE = FIX_PREFIX + "_垃圾"
FIX_SENT = FIX_PREFIX + "_已使用"
# 合集场景：作品原本住在「已发送0次/作品集_ZZ回归测试」里
ALBUM_PREFIX = "作品集_ZZ回归测试"
FIX_ALBUM_WORK = FIX_PREFIX + "_合集内作品"


def _png_bytes(w=8, h=8):
    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + b"\xff\x00\x00" * w for _ in range(h))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw))
            + chunk(b"IEND", b""))


def make_fixture(root, stage_folder, name, use_count, garbage=False, remark=""):
    """造一个最小可用作品目录（1 张图 + 文案 + 三份元数据）。"""
    path = os.path.join(root, stage_folder, name)
    if os.path.isdir(path):
        shutil.rmtree(path)
    os.makedirs(path)
    with open(os.path.join(path, "01.png"), "wb") as fp:
        fp.write(_png_bytes())
    with open(os.path.join(path, "文案.txt"), "w", encoding="utf-8") as fp:
        fp.write("在线回收站回归测试文案，仅用于接口验证，测试结束自动清理。")

    manifest = {"id": name, "title": name, "copy_content": "在线回收站回归测试"}
    if garbage:
        manifest["garbage"] = {"marked": True, "remark": remark,
                               "markedBy": "regression-test", "markedAt": "2026-09-20 12:00:00"}
    with open(os.path.join(path, "manifest.json"), "w", encoding="utf-8") as fp:
        json.dump(manifest, fp, ensure_ascii=False, indent=2)

    with open(os.path.join(path, "作品标签.json"), "w", encoding="utf-8") as fp:
        json.dump({"distribution": {"useCount": use_count,
                                    "dispatchedTo": ["dev"] * use_count,
                                    "status": "已使用%d次" % use_count}},
                  fp, ensure_ascii=False, indent=2)

    if garbage:
        with open(os.path.join(path, "quality_tag.json"), "w", encoding="utf-8") as fp:
            json.dump({"usable_for_wechat": False, "usable_for_other_platforms": False,
                       "garbage": True, "garbage_remark": remark,
                       "marked_by": "regression-test", "marked_at": "2026-09-20 12:00:00"},
                      fp, ensure_ascii=False, indent=2)
    return path


def make_album_fixture(root, work_name, use_count=0):
    """造一个原本住在「已发送0次/作品集_xxx」合集里的作品，并模拟被移到 _已发送1次。

    同时补一条移动日志 —— 「恢复」靠它反查原位，才能把作品放回自己的合集，
    而不是抖到 已发送0次 根目录。
    """
    album = os.path.join(root, STAGE0, ALBUM_PREFIX)
    work_path = os.path.join(album, work_name)
    if os.path.isdir(work_path):
        shutil.rmtree(work_path)
    os.makedirs(work_path)
    with open(os.path.join(work_path, "01.png"), "wb") as fp:
        fp.write(_png_bytes())
    with open(os.path.join(work_path, "文案.txt"), "w", encoding="utf-8") as fp:
        fp.write("合集内作品回归测试文案，测试结束自动清理。")
    with open(os.path.join(work_path, "manifest.json"), "w", encoding="utf-8") as fp:
        json.dump({"id": work_name, "title": work_name}, fp, ensure_ascii=False, indent=2)
    with open(os.path.join(work_path, "作品标签.json"), "w", encoding="utf-8") as fp:
        json.dump({"distribution": {"useCount": use_count, "dispatchedTo": ["dev"] * use_count,
                                    "status": "已使用%d次" % use_count}},
                  fp, ensure_ascii=False, indent=2)

    sent_path = os.path.join(root, STAGE1, work_name)
    if os.path.isdir(sent_path):
        shutil.rmtree(sent_path)
    shutil.move(work_path, sent_path)

    log_dir = os.path.join(root, "_portfolio_move_logs")
    os.makedirs(log_dir, exist_ok=True)
    log_file = os.path.join(log_dir, "delete_move_log_%s.csv" % time.strftime("%Y%m"))
    header_needed = not os.path.exists(log_file)
    with open(log_file, "a", encoding="utf-8-sig", newline="") as fp:
        writer = csv.writer(fp)
        if header_needed:
            writer.writerow(["时间", "设备", "作品ID", "原路径", "目标路径", "原使用次数", "动作类型"])
        writer.writerow([time.strftime("%Y-%m-%d %H:%M:%S"), "regression-test", work_name,
                         work_path, sent_path, use_count, "use_auto_dispatched"])
    return work_path, sent_path


def cleanup(root):
    """删掉所有回归测试夹具（含恢复后落到「已发送0次」的副本）。"""
    removed = []
    for stage_folder in (STAGE0, STAGE1, GARBAGE):
        base = os.path.join(root, stage_folder)
        if not os.path.isdir(base):
            continue
        for entry in os.listdir(base):
            target = os.path.join(base, entry)
            if entry.startswith(FIX_PREFIX) or entry.startswith(ALBUM_PREFIX):
                shutil.rmtree(target, ignore_errors=True)
                removed.append(target)
                continue
            # 合集本身也要清（里面的夹具已被上一轮删掉，可能剩空壳）
            if entry.startswith(ALBUM_PREFIX) and os.path.isdir(target):
                shutil.rmtree(target, ignore_errors=True)
                removed.append(target)
    return removed


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, path, payload=None, timeout=180):
        url = self.base + path
        if payload is None:
            req = urllib.request.Request(url)
        else:
            req = urllib.request.Request(
                url, data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            return exc.code, json.loads(exc.read().decode("utf-8"))

    def recycle(self, tab, refresh=False):
        suffix = "&refresh=1" if refresh else ""
        return self.call("/api/online/recycle?tab=%s%s" % (tab, suffix))


def find_work(works, work_id):
    for work in works or []:
        if work.get("id") == work_id:
            return work
    return None


def run(base=DEFAULT_BASE, root=DEFAULT_ROOT, verbose=True):
    """跑完整回归流程，返回 (是否全部通过, 失败项列表)。"""
    def log(msg):
        if verbose:
            print(msg)

    api = Api(base)
    failures = []

    def check(condition, label):
        if condition:
            log("   ✓ " + label)
        else:
            log("   ✗ " + label)
            failures.append(label)

    try:
        cleanup(root)
        make_fixture(root, GARBAGE, FIX_GARBAGE, 0, garbage=True, remark="初始夹具备注")
        make_fixture(root, STAGE1, FIX_SENT, 3)
        album_home, album_sent = make_album_fixture(root, FIX_ALBUM_WORK, 1)

        log("[1] 已标记垃圾 Tab 列表 + 备注读取")
        status, data = api.recycle("garbage", refresh=True)
        check(status == 200 and data.get("ok"), "接口返回 200/ok")
        check(data.get("label") == "已标记垃圾", "label 为「已标记垃圾」")
        work = find_work(data.get("works"), FIX_GARBAGE)
        check(work is not None, "垃圾夹具出现在列表中")
        check((work or {}).get("garbage", {}).get("remark") == "初始夹具备注", "读到初始垃圾备注")
        check(data.get("counts", {}).get("garbage") == data.get("total"), "垃圾库角标与 total 一致")
        sent_total = data.get("counts", {}).get("sent")
        log("       counts=%s" % (data.get("counts"),))

        log("[2] 垃圾备注写入并回读")
        status, data = api.call("/api/online/remark-garbage",
                                {"workId": FIX_GARBAGE, "remark": "回归测试备注：生成上限导致拼图重复",
                                 "device": "regression-test"})
        check(status == 200 and data.get("ok"), "备注接口返回 ok")
        status, data = api.recycle("garbage", refresh=True)
        work = find_work(data.get("works"), FIX_GARBAGE)
        check((work or {}).get("garbage", {}).get("remark") == "回归测试备注：生成上限导致拼图重复",
              "备注已落盘并被回读")

        log("[3] 垃圾 Tab「恢复」")
        status, data = api.call("/api/online/restore", {"workId": FIX_GARBAGE, "device": "regression-test"})
        check(status == 200 and data.get("ok"), "恢复接口返回 ok")
        restored = os.path.join(root, STAGE0, FIX_GARBAGE)
        check(os.path.isdir(restored), "作品已物理移入「已发送0次」")
        check(not os.path.isdir(os.path.join(root, GARBAGE, FIX_GARBAGE)), "已从垃圾库移出")
        quality = os.path.join(restored, "quality_tag.json")
        if os.path.exists(quality):
            with open(quality, encoding="utf-8") as fp:
                tag = json.load(fp)
            check(tag.get("garbage") is False, "垃圾标记已撤销")
            check(tag.get("usable_for_wechat") is True, "重新允许微信渠道")
        else:
            check(False, "quality_tag.json 存在")
        with open(os.path.join(restored, "作品标签.json"), encoding="utf-8") as fp:
            dist = json.load(fp).get("distribution", {})
        check(int(dist.get("useCount", -1)) == 0, "使用次数归零")
        with open(os.path.join(restored, "manifest.json"), encoding="utf-8") as fp:
            manifest = json.load(fp)
        check("garbage" not in manifest, "manifest 的 garbage 块已摘除")
        check(bool(manifest.get("restore_history")), "恢复历史已留档")

        log("[4] 已使用 Tab 列表")
        status, data = api.recycle("sent", refresh=True)
        check(status == 200 and data.get("ok"), "接口返回 200/ok")
        check(data.get("label") == "已使用", "label 为「已使用」")
        check(data.get("counts", {}).get("sent") == data.get("total"), "已使用角标与 total 一致")
        check(data.get("counts", {}).get("sent") == sent_total, "两次请求的角标稳定")
        work = find_work(data.get("works"), FIX_SENT)
        check(work is not None, "已使用夹具出现在列表中")
        check(int((work or {}).get("useCount", -1)) == 3, "useCount 读取正确")

        log("[5] 已使用 Tab「恢复」")
        status, data = api.call("/api/online/restore", {"workId": FIX_SENT, "device": "regression-test"})
        check(status == 200 and data.get("ok"), "恢复接口返回 ok")
        moved = os.path.join(root, STAGE0, FIX_SENT)
        check(os.path.isdir(moved), "作品已物理移入「已发送0次」")
        with open(os.path.join(moved, "作品标签.json"), encoding="utf-8") as fp:
            dist = json.load(fp).get("distribution", {})
        check(int(dist.get("useCount", -1)) == 0, "使用次数归零")

        log("[6] 列表缓存")
        cold = time.time()
        api.recycle("sent", refresh=True)
        cold_cost = time.time() - cold
        warm = time.time()
        api.recycle("sent")
        warm_cost = time.time() - warm
        log("       冷启动(refresh=1) %.2fs / 命中缓存 %.2fs" % (cold_cost, warm_cost))
        check(warm_cost < cold_cost, "二次请求走缓存更快")

        log("[7] 不存在的作品应返回 404")
        status, data = api.call("/api/online/restore", {"workId": "ZZ_不存在的作品_xyz"})
        check(status == 404 and not data.get("ok"), "恢复不存在作品返回 404")

        log("[8] 合集内作品的「恢复」应放回原作品集（而非抖到根目录）")
        status, data = api.call("/api/online/restore", {"workId": FIX_ALBUM_WORK, "device": "regression-test"})
        check(status == 200 and data.get("ok"), "恢复接口返回 ok")
        check(os.path.isdir(album_home), "作品已回到原作品集合集 %s" % ALBUM_PREFIX)
        check(not os.path.isdir(album_sent), "已从「已发送1次」移出")
        check("原作品集合集" in (data.get("message") or ""), "返回信息说明了放回合集")
        if os.path.isdir(album_home):
            with open(os.path.join(album_home, "作品标签.json"), encoding="utf-8") as fp:
                dist = json.load(fp).get("distribution", {})
            check(int(dist.get("useCount", -1)) == 0, "合集内作品使用次数同样归零")

    finally:
        removed = cleanup(root)
        log("[cleanup] 已清理夹具 %d 个" % len(removed))

    return not failures, failures


def test_online_recycle():
    """pytest 入口。"""
    ok, failures = run(verbose=False)
    assert ok, "在线回收站回归失败: %s" % failures


if __name__ == "__main__":
    base_url = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_BASE
    library_root = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_ROOT
    print("在线回收站回归测试 -> %s" % base_url)
    print("成品库根目录          -> %s" % library_root)
    print("=" * 64)
    passed, failed = run(base_url, library_root, verbose=True)
    print("=" * 64)
    print("结论: %s" % ("全部通过" if passed else "失败项 %s" % failed))
    sys.exit(0 if passed else 1)
