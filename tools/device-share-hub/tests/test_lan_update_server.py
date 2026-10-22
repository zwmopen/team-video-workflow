# -*- coding: utf-8 -*-
"""DSH-191 回归：局域网极速更新源 (4348) 版本解析契约

背景（真实事故）：
  lan_update_server.py 的 get_latest_apk_info() 原本按 [RELEASES_DIR, ALT_OUT_DIR]
  顺序「取第一个存在元数据文件的目录」。而 RELEASES_DIR 是外部技能包里的发布目录，
  最后一次同步停在 0.8.109 —— 于是刚构建出来的 android/out 新包永远被压住，
  手机点「检查更新」拿到的是三个月前的老包。
  更糟的是：这个服务全仓库没有任何启动入口，文档却从 2026-09-12 起就把它写成
  「常驻服务」，实际上端口从来没监听过，所有请求都静默降级到了云端源。

本测试把「必须取磁盘上版本号最大的那个包」这条不变量钉死。
"""

import json
import os
import pathlib
import sys
import tempfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
SCRIPTS_DIR = os.path.join(REPO_ROOT, "scripts")
LAN_SERVER = os.path.join(SCRIPTS_DIR, "lan_update_server.py")

if SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, SCRIPTS_DIR)

import lan_update_server as L  # noqa: E402


def _reset_cache():
    L._META_CACHE["ts"] = 0.0
    L._META_CACHE["val"] = None


def _write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return path


def _make_apk(directory, version, payload=None):
    os.makedirs(directory, exist_ok=True)
    name = f"album-Android-v{version}.apk"
    path = os.path.join(directory, name)
    with open(path, "wb") as f:
        f.write((payload or f"apk-bytes-{version}").encode("utf-8"))
    return path


def test_static_contract():
    print("=== DSH-191: 静态契约核验 ===")
    with open(LAN_SERVER, "r", encoding="utf-8") as f:
        code = f.read()

    # 旧的「第一个目录命中就 return」写法必须消失
    assert "for candidate_dir in [RELEASES_DIR, ALT_OUT_DIR]" not in code, \
        "不能再按目录顺序取第一个命中项（DSH-191 根因）"
    # 必须是「收集候选 -> 按版本排序 -> 取最大」
    assert "candidates.sort(key=score)" in code, "必须对所有候选按版本号排序"
    assert "candidates[-1]" in code, "必须取排序后的最大版本"
    assert "_candidate_from_scan" in code, "必须有不依赖元数据的文件名扫描兜底"
    # sha256 必须现场实算，不能轻信元数据
    assert "_sha256_of(apk_file)" in code, "sha256 必须按实际文件计算"
    # 日志不能再写进外部技能包目录
    assert 'LOG_FILE = _SCRIPT_DIR / "lan_update_server.log"' in code, \
        "日志必须落在本仓库 scripts/ 内"
    print("  PASS 1. 版本解析改为「全目录扫描 + 取最大 + sha256 实算」，日志落地正确")

    # 启动入口必须存在，且挂进了既有开机自启链路
    launcher = os.path.join(SCRIPTS_DIR, "start_lan_update_server.ps1")
    assert os.path.isfile(launcher), "必须提供 4348 的常驻拉起脚本"
    with open(launcher, "rb") as f:
        assert f.read(3) == b"\xef\xbb\xbf", "ps1 必须带 UTF-8 BOM，否则 PowerShell 5.1 按 GBK 解析中文"
    restart = os.path.join(SCRIPTS_DIR, "restart_online_gallery.ps1")
    with open(restart, "r", encoding="utf-8") as f:
        assert "start_lan_update_server.ps1" in f.read(), \
            "4348 必须挂进 restart_online_gallery.ps1，才能随既有开机自启一起恢复"
    print("  PASS 2. 常驻拉起脚本存在且已挂进开机自启链路")


def test_stale_metadata_loses_to_newer_dir():
    print("=== DSH-191: 陈旧目录不得压过新目录 ===")
    root = tempfile.mkdtemp(prefix="dsh191_")
    stale_dir = os.path.join(root, "releases")
    fresh_dir = os.path.join(root, "out")

    _make_apk(stale_dir, "0.8.109")
    _write(os.path.join(stale_dir, "android-release-metadata.json"), json.dumps({
        "schema": 1, "version_name": "0.8.109", "version_code": 220,
        "file_name": "album-Android-v0.8.109.apk", "sha256": "deadbeef",
    }))

    fresh_apk = _make_apk(fresh_dir, "0.8.120")
    _write(os.path.join(fresh_dir, "android-release-metadata.json"), json.dumps({
        "schema": 1, "version_name": "0.8.120", "version_code": 231,
        "file_name": "album-Android-v0.8.120.apk", "sha256": "feedface",
    }))

    saved = L.CANDIDATE_DIRS
    try:
        # 顺序故意反过来也无所谓，因为最终按版本号取最大
        for order in ([stale_dir, fresh_dir], [fresh_dir, stale_dir]):
            L.CANDIDATE_DIRS = [pathlib.Path(p) for p in order]
            _reset_cache()
            meta, apk = L.get_latest_apk_info()
            assert meta["version_name"] == "0.8.120", f"应取 0.8.120，实得 {meta['version_name']}"
            assert meta["version_code"] == 231, f"应取 231，实得 {meta['version_code']}"
            assert os.path.abspath(str(apk)) == os.path.abspath(fresh_apk), "应指向新目录的 APK"
            assert meta["file_name"] == "album-Android-v0.8.120.apk"
    finally:
        L.CANDIDATE_DIRS = saved
        _reset_cache()
    print("  PASS 3. 无论目录顺序如何，0.8.109 都压不过 0.8.120")


def test_filename_scan_survives_rotten_metadata():
    print("=== DSH-191: 元数据腐烂时文件名扫描兜底 ===")
    root = tempfile.mkdtemp(prefix="dsh191b_")
    d = os.path.join(root, "out")
    _make_apk(d, "0.8.115")
    _make_apk(d, "0.8.120")
    _make_apk(d, "0.8.64")
    # 元数据指向一个根本不存在的包
    _write(os.path.join(d, "latest.json"), json.dumps({
        "version_name": "0.8.64", "version_code": 175,
        "file_name": "album-Android-v0.8.64.apk", "apk_url": "/album-Android-v0.8.64.apk",
        "sha256": "whatever",
    }))

    saved = L.CANDIDATE_DIRS
    try:
        L.CANDIDATE_DIRS = [pathlib.Path(d)]
        _reset_cache()
        meta, apk = L.get_latest_apk_info()
        assert meta["version_name"] == "0.8.120", f"应取 0.8.120，实得 {meta['version_name']}"
        assert os.path.basename(str(apk)) == "album-Android-v0.8.120.apk"
    finally:
        L.CANDIDATE_DIRS = saved
        _reset_cache()
    print("  PASS 4. latest.json 写着 0.8.64 也不影响，扫文件名照样拿到 0.8.120")


def test_sha256_always_matches_served_file():
    print("=== DSH-191: sha256 必须与实发文件一致 ===")
    root = tempfile.mkdtemp(prefix="dsh191c_")
    d = os.path.join(root, "out")
    apk = _make_apk(d, "0.8.121", payload="real-bytes-0.8.121")
    _write(os.path.join(d, "android-release-metadata.json"), json.dumps({
        "version_name": "0.8.121", "version_code": 232,
        "file_name": "album-Android-v0.8.121.apk",
        "sha256": "0" * 64,  # 故意胡写
    }))

    saved = L.CANDIDATE_DIRS
    try:
        L.CANDIDATE_DIRS = [pathlib.Path(d)]
        _reset_cache()
        meta, served = L.get_latest_apk_info()
        import hashlib
        real = hashlib.sha256(open(apk, "rb").read()).hexdigest()
        assert meta["sha256"] == real, "下发的 sha256 必须等于实际文件摘要"
        assert meta["sha256"] != "0" * 64, "不能直接照抄元数据里的假 sha256"
        assert os.path.abspath(str(served)) == os.path.abspath(apk)
    finally:
        L.CANDIDATE_DIRS = saved
        _reset_cache()
    print("  PASS 5. 元数据 sha256 造假时以实际文件为准")


def test_version_tuple_ordering():
    print("=== DSH-191: 版本号比较不得用字符串字典序 ===")
    assert L._vt("0.8.120") > L._vt("0.8.9"), "0.8.120 必须大于 0.8.9"
    assert L._vt("0.10.0") > L._vt("0.9.9"), "0.10.0 必须大于 0.9.9"
    assert L._vt("1.0.0") > L._vt("0.99.99")
    print("  PASS 6. _vt 数值分段比较正确")


def test_resolve_file_across_dirs():
    print("=== DSH-191: 请求的文件必须在全部候选目录里找 ===")
    root = tempfile.mkdtemp(prefix="dsh191d_")
    a = os.path.join(root, "releases")
    b = os.path.join(root, "out")
    _make_apk(b, "0.8.120")
    saved = L.CANDIDATE_DIRS
    try:
        L.CANDIDATE_DIRS = [pathlib.Path(a), pathlib.Path(b)]
        found = L._resolve_file("album-Android-v0.8.120.apk")
        assert found is not None, "第二个目录里的新包必须能被解析到（旧版只在 RELEASES_DIR 找）"
        assert os.path.basename(str(found)) == "album-Android-v0.8.120.apk"
        assert L._resolve_file("album-Android-v9.9.9.apk") is None
    finally:
        L.CANDIDATE_DIRS = saved
    print("  PASS 7. 跨目录解析正常，找不到的返回 None")


def test_ios_version_from_project_yml():
    print("=== DSH-191: iOS 版本号取自 project.yml 真源 ===")
    ios = L._read_ios_project_yml()
    assert ios["version_name"].count(".") == 2, f"iOS 版本号格式异常: {ios}"
    assert ios["build"] > 100, f"iOS build 明显不合理: {ios}"
    assert ios != {"version_name": "0.8.45", "build": 117}, \
        "不能再回落到硬编码的 0.8.45/117（说明 project.yml 读不到）"
    print(f"  PASS 8. iOS 真源读取正常：{ios['version_name']} (build {ios['build']})")


def test_real_repo_state():
    print("=== DSH-191: 真实仓库现状对账 ===")
    _reset_cache()
    meta, apk = L.get_latest_apk_info()
    with open(os.path.join(REPO_ROOT, "android", "app", "build.gradle.kts"), "r", encoding="utf-8") as f:
        gradle = f.read()
    import re
    g_name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
    g_code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1))

    assert meta["version_name"] == g_name, \
        f"更新源吐出的版本 {meta['version_name']} 必须等于 build.gradle.kts 的 {g_name}"
    assert meta["version_code"] == g_code, \
        f"更新源吐出的 code {meta['version_code']} 必须等于 build.gradle.kts 的 {g_code}"
    assert os.path.isfile(str(apk)), f"APK 实体必须存在: {apk}"
    print(f"  PASS 9. 更新源与版本真源一致：{meta['version_name']} ({meta['version_code']}) -> {os.path.basename(str(apk))}")


if __name__ == "__main__":
    test_static_contract()
    test_stale_metadata_loses_to_newer_dir()
    test_filename_scan_survives_rotten_metadata()
    test_sha256_always_matches_served_file()
    test_version_tuple_ordering()
    test_resolve_file_across_dirs()
    test_ios_version_from_project_yml()
    test_real_repo_state()
    print("\nALL DSH-191 TESTS PASSED")
