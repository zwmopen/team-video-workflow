# -*- coding: utf-8 -*-
"""手机次数回读同步（phone_sync.py）回归测试。

安全设计：
- **完全不碰真实成品库**：所有夹具都建在 tempfile 临时目录里，测试结束自动删。
- 假手机用 127.0.0.1 上的临时 HTTP 服务扮演，不依赖真机。
- 可直接运行（python test_phone_sync.py），也可被 pytest 收集。

覆盖的核心不变量（都是「出错就会毁数据」的点）：
    1. 只增不减 —— 手机次数更低时绝不回写（否则把已用作品打回待首发）
    2. 只动数字不动文件 —— 回写前后作品目录的文件清单与 mtime 完全不变
    3. 坏 JSON 不覆盖 —— 解析失败必须跳过并把原文件原样留在磁盘上
    4. dryRun 不落盘
    5. 配对歧义放弃 —— 归一化后两端不唯一时不认领（防串号）
    6. 审计日志可回溯
"""
import json
import os
import shutil
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(os.path.dirname(__file__))))
import phone_sync as ps  # noqa: E402

PASS = 0
FAIL = 0


def check(cond, label):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [OK] {label}")
    else:
        FAIL += 1
        print(f"  [FAIL] {label}")


# ────────────────────────────────────────────────────────────────────────
# 夹具
# ────────────────────────────────────────────────────────────────────────
def make_work(root, work_id, use_count=0, tag=True, manifest=True, bad_tag=False):
    """在临时库里造一个作品目录，返回其路径。"""
    d = os.path.join(root, work_id)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "文案.txt"), "w", encoding="utf-8") as fp:
        fp.write("这是一段足够长的正文文案，用来通过空壳守卫的实质字数阈值判定。" * 3)
    with open(os.path.join(d, "01.png"), "wb") as fp:
        fp.write(b"\x89PNG\r\n\x1a\n" + b"x" * 64)
    if bad_tag:
        with open(os.path.join(d, "作品标签.json"), "w", encoding="utf-8") as fp:
            fp.write("{这不是合法 JSON")
    elif tag:
        with open(os.path.join(d, "作品标签.json"), "w", encoding="utf-8") as fp:
            json.dump({"distribution": {"useCount": use_count, "status": f"已使用{use_count}次"}},
                      fp, ensure_ascii=False, indent=2)
    if manifest:
        with open(os.path.join(d, "manifest.json"), "w", encoding="utf-8") as fp:
            json.dump({"useCount": use_count, "used": use_count > 0, "title": work_id},
                      fp, ensure_ascii=False, indent=2)
    return d


def snapshot(path):
    """目录快照：文件名 + 大小 + mtime，用于证明「没搬文件」。"""
    out = {}
    for dirpath, _dirnames, filenames in os.walk(path):
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, path)
            st = os.stat(full)
            out[rel] = (st.st_size, round(st.st_mtime, 3))
    return out


def read_json(path):
    with open(path, "r", encoding="utf-8") as fp:
        return json.load(fp)


def read_text(path):
    with open(path, "r", encoding="utf-8") as fp:
        return fp.read()


# ────────────────────────────────────────────────────────────────────────
# 假手机
# ────────────────────────────────────────────────────────────────────────
class _FakePhoneHandler(BaseHTTPRequestHandler):
    payloads = {}

    def log_message(self, *a):
        pass

    def do_GET(self):
        data = self.payloads.get(self.path)
        if data is None:
            self.send_response(404)
            self.end_headers()
            return
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class FakePhone:
    """本机随机端口上的假手机相册服务。"""

    def __init__(self):
        self.payloads = {}
        _FakePhoneHandler.payloads = self.payloads
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _FakePhoneHandler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def host(self):
        return f"127.0.0.1:{self.port}"

    def set_works(self, works):
        self.payloads["/v2/works"] = works

    def set_info(self, info):
        self.payloads["/v2/info"] = info

    def close(self):
        try:
            self.server.shutdown()
            self.server.server_close()
        except Exception:
            pass


# ────────────────────────────────────────────────────────────────────────
# 单元用例
# ────────────────────────────────────────────────────────────────────────
def test_only_increase_never_decrease(tmp):
    print("\n[T1] 只增不减：手机 0 / 电脑 1 → 必须不写")
    d = make_work(tmp, "W_onlyincrease", use_count=1)
    before = snapshot(d)
    rep = ps.sync_phone_counts(
        {"W_onlyincrease": {"id": "W_onlyincrease", "path": d, "useCount": 1}},
        [{"id": "W_onlyincrease", "name": "W_onlyincrease", "folderName": "W_onlyincrease", "shareCount": 0}],
        dry_run=False,
    )
    check(rep["appliedCount"] == 0, "appliedCount == 0")
    check(rep["alreadyInSyncCount"] == 1, "计入 alreadyInSync")
    check(read_json(os.path.join(d, "作品标签.json"))["distribution"]["useCount"] == 1,
          "作品标签.json 次数仍是 1（未被清零）")
    check(read_json(os.path.join(d, "manifest.json"))["useCount"] == 1, "manifest.json 次数仍是 1")
    check(snapshot(d) == before, "目录快照未变（没碰任何文件）")


def test_normal_bump(tmp):
    print("\n[T2] 正常补记：手机 2 / 电脑 0 → 两个元数据文件都写 2")
    d = make_work(tmp, "W_bump", use_count=0)
    rep = ps.sync_phone_counts(
        {"W_bump": {"id": "W_bump", "path": d, "useCount": 0}},
        [{"id": "W_bump", "name": "W_bump", "folderName": "W_bump", "shareCount": 2, "used": True}],
        dry_run=False,
    )
    check(rep["appliedCount"] == 1, "appliedCount == 1")
    tag = read_json(os.path.join(d, "作品标签.json"))
    man = read_json(os.path.join(d, "manifest.json"))
    check(tag["distribution"]["useCount"] == 2, "作品标签 useCount == 2")
    check(tag["distribution"]["status"] == "已使用2次", "作品标签 status == 已使用2次")
    check(man["useCount"] == 2 and man["used"] is True, "manifest useCount == 2 / used == true")
    check(man["distribution"]["useCount"] == 2, "manifest.distribution.useCount == 2")


def test_dry_run(tmp):
    print("\n[T3] dryRun：报告要算出来，磁盘不能动")
    d = make_work(tmp, "W_dry", use_count=0)
    before = snapshot(d)
    rep = ps.sync_phone_counts(
        {"W_dry": {"id": "W_dry", "path": d, "useCount": 0}},
        [{"id": "W_dry", "name": "W_dry", "folderName": "W_dry", "shareCount": 3}],
        dry_run=True,
    )
    check(rep["appliedCount"] == 1, "appliedCount == 1")
    check(rep["applied"][0].get("dryRun") is True, "条目标记 dryRun")
    check(read_json(os.path.join(d, "作品标签.json"))["distribution"]["useCount"] == 0, "磁盘未被改动")
    check(snapshot(d) == before, "目录快照未变")


def test_bad_json_not_overwritten(tmp):
    print("\n[T4] 坏 JSON：必须跳过，且原文件原样保留")
    d = make_work(tmp, "W_badjson", use_count=0, bad_tag=True)
    raw_before = read_text(os.path.join(d, "作品标签.json"))
    rep = ps.sync_phone_counts(
        {"W_badjson": {"id": "W_badjson", "path": d, "useCount": 0}},
        [{"id": "W_badjson", "name": "W_badjson", "folderName": "W_badjson", "shareCount": 5}],
        dry_run=False,
    )
    check(rep["appliedCount"] == 0, "appliedCount == 0")
    check(len(rep["skipped"]) == 1, "被记入 skipped")
    check(read_text(os.path.join(d, "作品标签.json")) == raw_before,
          "坏文件内容一字未改（没被覆盖成半截）")


def test_missing_dir(tmp):
    print("\n[T5] 目录不存在：跳过并报告，不抛异常")
    rep = ps.sync_phone_counts(
        {"W_gone": {"id": "W_gone", "path": os.path.join(tmp, "不存在的目录"), "useCount": 0}},
        [{"id": "W_gone", "name": "W_gone", "folderName": "W_gone", "shareCount": 5}],
        dry_run=False,
    )
    check(rep["appliedCount"] == 0 and len(rep["skipped"]) == 1, "跳过并报告")
    check("不存在" in rep["skipped"][0]["reason"], "原因说明目录不存在")


def test_no_file_move(tmp):
    print("\n[T6] 只动数字不动文件：图片与文案文件的 mtime 必须原封不动")
    d = make_work(tmp, "W_nomove", use_count=0)
    before = snapshot(d)
    time.sleep(0.05)
    ps.sync_phone_counts(
        {"W_nomove": {"id": "W_nomove", "path": d, "useCount": 0}},
        [{"id": "W_nomove", "name": "W_nomove", "folderName": "W_nomove", "shareCount": 1}],
        dry_run=False,
    )
    after = snapshot(d)
    check(set(before) == set(after), "文件清单不变（没有新增/删除/重命名）")
    check(before["01.png"] == after["01.png"], "01.png 的 size/mtime 未变")
    check(before["文案.txt"] == after["文案.txt"], "文案.txt 的 size/mtime 未变")


def test_normalized_match(tmp):
    print("\n[T7] 归一化配对：[转] 后缀差异要能认出来")
    d = make_work(tmp, "20260903_083549_桐庐夏日团建方案_7张图[转]", use_count=0)
    rep = ps.sync_phone_counts(
        {"20260903_083549_桐庐夏日团建方案_7张图[转]":
            {"id": "20260903_083549_桐庐夏日团建方案_7张图[转]", "path": d, "useCount": 0}},
        [{"id": "lark-x", "name": "20260903_083549_桐庐夏日团建方案_7张图",
          "folderName": "桐庐", "shareCount": 1}],
        dry_run=False,
    )
    check(rep["matchedCount"] == 1 and rep["appliedCount"] == 1, "跨端叫法差异配对成功并回写")


def test_ambiguous_dropped(tmp):
    print("\n[T8] 配对歧义：归一化后电脑端有两个候选 → 放弃，不串号")
    a = make_work(tmp, "同名作品", use_count=0)
    b = make_work(tmp, "同名作品[转]", use_count=0)
    rep = ps.sync_phone_counts(
        {
            "同名作品": {"id": "同名作品", "path": a, "useCount": 0},
            "同名作品[转]": {"id": "同名作品[转]", "path": b, "useCount": 0},
        },
        [{"id": "lark-y", "name": "同名作品（转）", "folderName": "x", "shareCount": 9}],
        dry_run=False,
    )
    check(rep["appliedCount"] == 0, "一个有歧义的都不写")
    check(rep["unmatchedPhoneCount"] == 1, "歧义作品计入未配对")


def test_unmatched_reported(tmp):
    print("\n[T9] 手机端独有作品（飞书直收）要被完整报告出来")
    d = make_work(tmp, "W_have", use_count=0)
    rep = ps.sync_phone_counts(
        {"W_have": {"id": "W_have", "path": d, "useCount": 0}},
        [
            {"id": "W_have", "name": "W_have", "folderName": "W_have", "shareCount": 1},
            {"id": "lark-1", "name": "苏州方案", "folderName": "苏州", "shareCount": 4},
        ],
        dry_run=True,
    )
    check(rep["unmatchedPhoneCount"] == 1, "未配对数 == 1")
    u = rep["unmatchedPhoneWorks"][0]
    check(u["id"] == "lark-1" and u["shareCount"] == 4, "未配对条目带 id 与次数，可人工核对")


def test_audit_log(tmp):
    print("\n[T10] 审计日志：每次回写都要留下 谁/哪个作品/从几次到几次")
    logdir = os.path.join(tmp, "logs")
    d = make_work(tmp, "W_log", use_count=0)
    ps.sync_phone_counts(
        {"W_log": {"id": "W_log", "path": d, "useCount": 0}},
        [{"id": "W_log", "name": "W_log", "folderName": "W_log", "shareCount": 2}],
        phone_label="192.168.1.200:45833", dry_run=False, log_dir=logdir,
    )
    files = [f for f in os.listdir(logdir) if f.startswith("phone_count_sync_")]
    check(len(files) == 1, "生成了审计日志文件")
    content = read_text(os.path.join(logdir, files[0]))
    check("W_log" in content and "192.168.1.200:45833" in content and ",0,2," in content,
          "日志含 作品ID / 手机 / 原次数 0 → 回读次数 2")


def test_end_to_end_with_fake_phone(tmp):
    print("\n[T11] 端到端：假手机 HTTP → 拉 /v2/works → 回写")
    phone = FakePhone()
    try:
        d0 = make_work(tmp, "W_e2e_0", use_count=0)
        d1 = make_work(tmp, "W_e2e_1", use_count=1)
        phone.set_info({"protocol": 2, "name": "假手机", "packageName": "com.zwm.gallery", "workCount": 2})
        phone.set_works([
            {"id": "W_e2e_0", "name": "W_e2e_0", "folderName": "W_e2e_0", "shareCount": 2, "used": True},
            {"id": "W_e2e_1", "name": "W_e2e_1", "folderName": "W_e2e_1", "shareCount": 1, "used": True},
        ])
        ip, port = ps.normalize_host(phone.host)
        ok, works, err = ps.fetch_phone_works(ip, port)
        check(ok and len(works) == 2, f"拉到手机作品列表（err={err}）")

        index = {
            "W_e2e_0": {"id": "W_e2e_0", "path": d0, "useCount": 0},
            "W_e2e_1": {"id": "W_e2e_1", "path": d1, "useCount": 1},
        }
        rep = ps.sync_phone_counts(index, works, phone_label=phone.host, dry_run=False)
        check(rep["appliedCount"] == 1, "只有 W_e2e_0 被回写（W_e2e_1 两端已一致）")
        check(read_json(os.path.join(d0, "作品标签.json"))["distribution"]["useCount"] == 2, "d0 次数 0 → 2")
        check(read_json(os.path.join(d1, "作品标签.json"))["distribution"]["useCount"] == 1, "d1 次数保持 1")

        # /v2/info 也要能读（在线状态面板的数据源）
        ok2, info, err2 = ps.http_get_json(ip, port, "/v2/info")
        check(ok2 and info.get("packageName") == "com.zwm.gallery", f"能读到 /v2/info（err={err2}）")
    finally:
        phone.close()


def test_probe_and_subnets():
    print("\n[T12] 网段推导与端口探测：本机 IP 要能折成 /24，本机开着的端口要探得到")
    ips = ps.local_ipv4_addresses()
    check(all(not ip.startswith("127.") for ip in ips) or ips == [], "过滤掉回环地址")
    subnets = ps.candidate_subnets(["192.168.1.27", "10.0.0.5", "192.168.1.99"])
    check(subnets == ["192.168.1", "10.0.0"], f"/24 折算去重保序 → {subnets}")

    # 拿本机 45835（在线相册服务）探测自己：服务在跑就应该 True，没跑就跳过
    import socket as _s
    srv = _s.socket()
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    probe_port = srv.getsockname()[1]
    try:
        check(ps.probe_port("127.0.0.1", probe_port, timeout=0.5) is True, "开着端口 → 探到")
        check(ps.probe_port("127.0.0.1", 1, timeout=0.3) is False, "关闭端口 → 探不到")
    finally:
        srv.close()


def main():
    tmp = tempfile.mkdtemp(prefix="phone_sync_test_")
    print(f"临时夹具目录：{tmp}")
    try:
        test_only_increase_never_decrease(tmp)
        test_normal_bump(tmp)
        test_dry_run(tmp)
        test_bad_json_not_overwritten(tmp)
        test_missing_dir(tmp)
        test_no_file_move(tmp)
        test_normalized_match(tmp)
        test_ambiguous_dropped(tmp)
        test_unmatched_reported(tmp)
        test_audit_log(tmp)
        test_end_to_end_with_fake_phone(tmp)
        test_probe_and_subnets()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
        print(f"\n已清理临时夹具目录 {tmp}")

    print(f"\n{'=' * 56}\n通过 {PASS} / 失败 {FAIL}\n{'=' * 56}")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
