#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
局域网极速更新源服务 (LAN Ultra-Fast Update Server)
端口：4348 (默认绑定 0.0.0.0:4348)
为同一 Wi-Fi 内的手机客户端（Android 相册 App / iOS）提供内网秒级极速更新与探针响应。
若客户端处于外网环境或本服务未启动，客户端将自动平滑降级至 GitHub Releases 云端源。

DSH-191 修复说明（2026-10-22）：
  旧实现按 [RELEASES_DIR, ALT_OUT_DIR] 顺序取「第一个存在元数据文件的目录」，
  于是外部技能包里那份 **0.8.109** 的陈年老包，永远压过刚构建出来的 android/out 新包。
  本版改为：扫描全部候选目录 -> 收集全部候选 -> **按版本号取最大** -> sha256 现场实算。
  修完后即使元数据文件全部腐烂（本仓库 latest.json 至今还写着 0.8.64），
  只要 APK 文件本身在，服务吐出的就是磁盘上真正最新的那个包。
"""

import argparse
import hashlib
import http.server
import json
import os
import pathlib
import re
import sys
import time

# DSH-191：候选目录顺序不再代表优先级，只是遍历顺序（最终按版本号取最大）
RELEASES_DIR = pathlib.Path(r"D:\AICode\AI\skills\技能包\技能\device-folder-transfer\releases")
ALT_OUT_DIR = pathlib.Path(r"D:\AICode\AI\repos\team-video-workflow\tools\device-share-hub\android\out")
CANDIDATE_DIRS = [ALT_OUT_DIR, RELEASES_DIR]

_SCRIPT_DIR = pathlib.Path(__file__).resolve().parent
_HUB_ROOT = _SCRIPT_DIR.parent
IOS_PROJECT_YML = _HUB_ROOT / "ios" / "project.yml"
ANDROID_GRADLE = _HUB_ROOT / "android" / "app" / "build.gradle.kts"
IPA_DIRS = [_HUB_ROOT / "_ipa", ALT_OUT_DIR]

DEFAULT_APK_NAME = "album-Android-v0.8.63.apk"
DEFAULT_SHA256 = "e0805d8c6265b0c5f5cb7b48a38db3dc72e54d974f4d4f8eb02cba74fb99a191"
DEFAULT_VERSION_NAME = "0.8.63"
DEFAULT_VERSION_CODE = 174

_APK_RE = re.compile(r"^album-Android-v([0-9]+(?:\.[0-9]+)*)\.apk$", re.IGNORECASE)
_IPA_RE = re.compile(r"^album-iOS-v([0-9]+(?:\.[0-9]+)*)-altstore\.ipa$", re.IGNORECASE)

# 元数据缓存：目录里躺着 200+ 个历史 APK，每个请求都全扫一遍没必要
_META_CACHE = {"ts": 0.0, "val": None}
_META_TTL_SEC = 5.0


def _vt(text):
    """'0.8.120' -> (0, 8, 120)，用于版本号大小比较。"""
    try:
        return tuple(int(part) for part in str(text).split("."))
    except Exception:
        return (0,)


def _sha256_of(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _read_build_gradle():
    """真源兜底：build.gradle.kts 的 versionName / versionCode。"""
    try:
        text = ANDROID_GRADLE.read_text(encoding="utf-8")
        name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
        code = re.search(r"versionCode\s*=\s*(\d+)", text)
        if name and code:
            return name.group(1), int(code.group(1))
    except Exception:
        pass
    return None, None


def _read_ios_project_yml():
    """真源：ios/project.yml 的 MARKETING_VERSION / CURRENT_PROJECT_VERSION。"""
    try:
        text = IOS_PROJECT_YML.read_text(encoding="utf-8")
        name = re.search(r"^\s*MARKETING_VERSION:\s*([0-9][0-9.]*)\s*$", text, re.MULTILINE)
        build = re.search(r"^\s*CURRENT_PROJECT_VERSION:\s*(\d+)\s*$", text, re.MULTILINE)
        if name and build:
            return {"version_name": name.group(1), "build": int(build.group(1))}
    except Exception:
        pass
    return {"version_name": "0.8.45", "build": 117}


def _resolve_file(name):
    """按文件名在全部候选目录里找实体文件（旧版只找 RELEASES_DIR，新包必然 404）。"""
    for directory in CANDIDATE_DIRS:
        candidate = directory / name
        if candidate.is_file():
            return candidate
    return None


def _candidate_from_meta(directory):
    """从目录里的元数据文件取候选（metadata/update/latest/version.json 任一）。"""
    for meta_name in ("android-release-metadata.json", "update.json", "latest.json", "version.json"):
        meta_file = directory / meta_name
        if not meta_file.is_file():
            continue
        try:
            data = json.loads(meta_file.read_text(encoding="utf-8"))
        except Exception:
            continue
        if not isinstance(data, dict):
            continue
        apk_name = data.get("file_name") or ""
        if not apk_name:
            raw_url = str(data.get("apk_url") or data.get("url") or "")
            apk_name = os.path.basename(raw_url.split("?")[0])
        version_name = data.get("version_name") or data.get("versionName")
        if not apk_name or not version_name:
            continue
        if not _APK_RE.match(apk_name):
            continue
        apk_file = _resolve_file(apk_name)
        if apk_file is None:
            continue
        try:
            version_code = int(data.get("version_code") or data.get("versionCode") or 0)
        except (TypeError, ValueError):
            version_code = 0
        return {
            "version_name": str(version_name),
            "version_code": version_code,
            "apk_file": apk_file,
            "meta": data,
            "origin": f"{meta_file.name}@{directory.name}",
        }
    return None


def _candidate_from_scan(directory):
    """无视元数据，直接扫 APK 文件名取本目录最新版本（元数据腐烂时的兜底）。"""
    if not directory.is_dir():
        return None
    best = None
    try:
        for path in directory.glob("album-Android-v*.apk"):
            matched = _APK_RE.match(path.name)
            if not matched:
                continue
            key = _vt(matched.group(1))
            if best is None or key > best[0]:
                best = (key, path)
    except Exception:
        return None
    if best is None:
        return None
    version_name = ".".join(str(part) for part in best[0])
    gradle_name, gradle_code = _read_build_gradle()
    version_code = 0
    if gradle_name and gradle_code and gradle_name == version_name:
        version_code = gradle_code
    return {
        "version_name": version_name,
        "version_code": version_code,
        "apk_file": best[1],
        "meta": None,
        "origin": f"filename-scan@{directory.name}",
    }


def _newest_ipa():
    best = None
    for directory in IPA_DIRS:
        if not directory.is_dir():
            continue
        try:
            for path in directory.glob("album-iOS-v*-altstore.ipa"):
                matched = _IPA_RE.match(path.name)
                if not matched:
                    continue
                key = _vt(matched.group(1))
                if best is None or key > best[0]:
                    best = (key, path)
        except Exception:
            continue
    return best[1] if best else None


def _fallback_payload(apk_file):
    return {
        "version_name": DEFAULT_VERSION_NAME,
        "version_code": DEFAULT_VERSION_CODE,
        "versionName": DEFAULT_VERSION_NAME,
        "versionCode": DEFAULT_VERSION_CODE,
        "tag_name": f"v{DEFAULT_VERSION_NAME}",
        "apk_url": f"/{DEFAULT_APK_NAME}",
        "url": f"/{DEFAULT_APK_NAME}",
        "sha256": DEFAULT_SHA256,
        "source": "lan",
        "notes": "未找到任何可用安装包，请先在电脑上构建一次 Android 包。",
        "ios": _read_ios_project_yml(),
    }, apk_file


def get_latest_apk_info():
    """返回 (manifest_dict, apk_path)。永远取磁盘上版本号最大的那个包。"""
    now = time.time()
    cached = _META_CACHE["val"]
    if cached is not None and (now - _META_CACHE["ts"]) < _META_TTL_SEC:
        return cached

    candidates = []
    for directory in CANDIDATE_DIRS:
        if not directory.is_dir():
            continue
        item = _candidate_from_meta(directory)
        if item:
            candidates.append(item)
        item = _candidate_from_scan(directory)
        if item:
            candidates.append(item)

    if not candidates:
        value = _fallback_payload(RELEASES_DIR / DEFAULT_APK_NAME)
        _META_CACHE["ts"] = now
        _META_CACHE["val"] = value
        return value

    def score(item):
        try:
            mtime = item["apk_file"].stat().st_mtime
        except Exception:
            mtime = 0.0
        return (_vt(item["version_name"]), item["version_code"], mtime)

    candidates.sort(key=score)
    best = candidates[-1]
    apk_file = best["apk_file"]
    version_name = best["version_name"]
    version_code = best["version_code"]

    if not version_code:
        gradle_name, gradle_code = _read_build_gradle()
        if gradle_name and gradle_code and gradle_name == version_name:
            version_code = gradle_code

    try:
        actual_sha = _sha256_of(apk_file)
    except Exception:
        actual_sha = ""

    declared_sha = ""
    if best["meta"]:
        declared_sha = str(best["meta"].get("sha256") or "")
    if declared_sha and actual_sha and declared_sha.lower() != actual_sha.lower():
        safe_log(f"[WARN] sha256 与文件不符 {apk_file.name}: meta={declared_sha} actual={actual_sha}（以实际为准）\n")

    notes = ""
    if best["meta"]:
        notes = str(best["meta"].get("notes") or "")
    if not notes:
        local_latest = ALT_OUT_DIR / "local_latest.json"
        try:
            notes = str(json.loads(local_latest.read_text(encoding="utf-8")).get("notes") or "")
        except Exception:
            notes = ""
    if not notes:
        notes = f"相册 Android {version_name}（局域网极速源，来源 {best['origin']}）"

    try:
        size = apk_file.stat().st_size
    except Exception:
        size = 0

    manifest = {
        "version_name": version_name,
        "version_code": version_code,
        "versionName": version_name,
        "versionCode": version_code,
        "tag_name": f"v{version_name}",
        "file_name": apk_file.name,
        "apk_url": f"/{apk_file.name}",
        "url": f"/{apk_file.name}",
        "sha256": actual_sha or declared_sha or DEFAULT_SHA256,
        "size": size,
        "source": "lan",
        "notes": notes,
        "ios": _read_ios_project_yml(),
    }

    value = (manifest, apk_file)
    _META_CACHE["ts"] = now
    _META_CACHE["val"] = value
    return value


# DSH-191：日志落到本仓库内，别再往外部技能包目录里写
LOG_FILE = _SCRIPT_DIR / "lan_update_server.log"


def safe_log(msg):
    try:
        if sys.stderr is not None:
            sys.stderr.write(msg)
            sys.stderr.flush()
    except Exception:
        pass
    try:
        with LOG_FILE.open("a", encoding="utf-8") as f:
            f.write(msg)
    except Exception:
        pass


class LanUpdateHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        safe_log(f"[{self.log_date_time_string()}] {self.client_address[0]}:{self.client_address[1]} {args[0]} {args[1]} {args[2]}\n")

    def do_HEAD(self):
        self._handle_request(is_head=True)

    def do_GET(self):
        self._handle_request(is_head=False)

    def _send_json(self, payload, is_head):
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        if not is_head:
            self.wfile.write(payload)

    def _stream_file(self, target_file, content_type, is_head):
        if not target_file.exists():
            self.send_error(404, f"File not found: {target_file.name}")
            return

        total_size = target_file.stat().st_size
        range_header = self.headers.get("Range")
        start = 0
        end = total_size - 1

        if range_header and range_header.startswith("bytes="):
            try:
                ranges = range_header[6:].split("-")
                if ranges[0]:
                    start = int(ranges[0])
                if len(ranges) > 1 and ranges[1]:
                    end = int(ranges[1])
            except ValueError:
                start = 0
                end = total_size - 1

        if start >= total_size or end >= total_size or start > end:
            self.send_response(416)
            self.send_header("Content-Range", f"bytes */{total_size}")
            self.end_headers()
            return

        length = end - start + 1
        if range_header:
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{total_size}")
        else:
            self.send_response(200)

        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Disposition", f'attachment; filename="{target_file.name}"')
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()

        if is_head:
            return

        with target_file.open("rb") as f:
            if start > 0:
                f.seek(start)
            remaining = length
            chunk_size = 128 * 1024
            while remaining > 0:
                read_len = min(chunk_size, remaining)
                chunk = f.read(read_len)
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (ConnectionResetError, BrokenPipeError):
                    break
                remaining -= len(chunk)

    def _handle_request(self, is_head=False):
        raw_path = self.path.split("?")[0]
        meta, default_apk_file = get_latest_apk_info()
        path = raw_path

        # 客户端可能按 45835 那套路径名来要，做个别名映射
        if path in ("/download/apk", "/apk"):
            path = meta["apk_url"]

        # 1. 版本探针响应
        if path in ["/latest.json", "/version", "/update.json", "/version.json"]:
            payload = json.dumps(meta, ensure_ascii=False, indent=2).encode("utf-8")
            self._send_json(payload, is_head)
            return

        # 2. APK 极速流式下载（支持断点续传 Range）
        if path.endswith(".apk"):
            requested_filename = os.path.basename(path)
            # DSH-191：全候选目录找，旧版只查 RELEASES_DIR，新包必然落回旧包
            resolved = _resolve_file(requested_filename)
            target_file = resolved if resolved is not None else default_apk_file
            self._stream_file(target_file, "application/vnd.android.package-archive", is_head)
            return

        # 3. IPA 下载（iOS 侧载用）
        if path.endswith(".ipa") or path in ("/download/ipa", "/ipa"):
            requested_filename = os.path.basename(path)
            target_file = None
            if requested_filename.endswith(".ipa"):
                for directory in IPA_DIRS:
                    candidate = directory / requested_filename
                    if candidate.is_file():
                        target_file = candidate
                        break
            if target_file is None:
                target_file = _newest_ipa()
            if target_file is None:
                self.send_error(404, "IPA not found")
                return
            self._stream_file(target_file, "application/octet-stream", is_head)
            return

        # 4. 健康检查
        if path in ["/", "/health", "/ping"]:
            payload = json.dumps({
                "status": "ok",
                "service": "lan-update-server",
                "port": 4348,
                "latest_version": meta["version_name"],
                "version_code": meta["version_code"],
                "file_name": default_apk_file.name,
                "ios": meta["ios"],
            }, ensure_ascii=False).encode("utf-8")
            self._send_json(payload, is_head)
            return

        self.send_error(404, "Not Found")


def main():
    parser = argparse.ArgumentParser(description="LAN Update Server")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=4348, help="Port to bind (default: 4348)")
    parser.add_argument("--restart", action="store_true", help="Kill the previous listener on this port first")
    args = parser.parse_args()

    if args.restart:
        try:
            import subprocess
            out = subprocess.run(["netstat", "-ano"], capture_output=True, text=True).stdout
            pids = set()
            for line in out.splitlines():
                if f":{args.port}" in line and "LISTENING" in line:
                    parts = line.split()
                    if parts:
                        pids.add(parts[-1])
            for pid in pids:
                if pid.isdigit() and int(pid) > 0:
                    subprocess.run(["taskkill", "/PID", pid, "/F"], capture_output=True)
            if pids:
                time.sleep(0.8)
        except Exception as exc:
            safe_log(f"[WARN] restart cleanup failed: {exc}\n")

    meta, apk_file = get_latest_apk_info()
    server = http.server.ThreadingHTTPServer((args.host, args.port), LanUpdateHandler)
    safe_log("====================================================\n")
    safe_log(f"LAN Update Server is LIVE on http://{args.host}:{args.port}\n")
    safe_log(f"Serving Latest Version: {meta['version_name']} (Code {meta['version_code']})\n")
    safe_log(f"Package: {apk_file.name} ({apk_file.stat().st_size / 1024 / 1024:.2f} MB)\n")
    safe_log(f"SHA256: {meta['sha256']}\n")
    safe_log(f"iOS: {meta['ios']['version_name']} (build {meta['ios']['build']})\n")
    safe_log("====================================================\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
