#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
局域网极速更新源服务 (LAN Ultra-Fast Update Server)
端口：4348 (默认绑定 0.0.0.0:4348)
为同一 Wi-Fi 内的手机客户端（Android 相册 App / iOS）提供内网秒级极速更新与探针响应。
若客户端处于外网环境或本服务未启动，客户端将自动平滑降级至 GitHub Releases 云端源。
"""

import argparse
import hashlib
import http.server
import json
import os
import pathlib
import sys

RELEASES_DIR = pathlib.Path(r"D:\AICode\AI\skills\技能包\技能\device-folder-transfer\releases")
DEFAULT_APK_NAME = "album-Android-v0.8.63.apk"
DEFAULT_SHA256 = "e0805d8c6265b0c5f5cb7b48a38db3dc72e54d974f4d4f8eb02cba74fb99a191"
DEFAULT_VERSION_NAME = "0.8.63"
DEFAULT_VERSION_CODE = 174

ALT_OUT_DIR = pathlib.Path(r"D:\AICode\AI\repos\team-video-workflow\tools\device-share-hub\android\out")


def get_latest_apk_info():
    for candidate_dir in [RELEASES_DIR, ALT_OUT_DIR]:
        meta_file = candidate_dir / "android-release-metadata.json"
        if not meta_file.exists():
            meta_file = candidate_dir / "update.json"
        if meta_file.exists():
            try:
                data = json.loads(meta_file.read_text(encoding="utf-8"))
                apk_name = data.get("file_name", DEFAULT_APK_NAME)
                apk_file = candidate_dir / apk_name
                if not apk_file.exists():
                    apk_file = RELEASES_DIR / apk_name
                if not apk_file.exists():
                    apk_file = ALT_OUT_DIR / apk_name
                if apk_file.exists():
                    v_name = data.get("version_name", data.get("versionName", DEFAULT_VERSION_NAME))
                    v_code = data.get("version_code", data.get("versionCode", DEFAULT_VERSION_CODE))
                    return {
                        "version_name": v_name,
                        "version_code": v_code,
                        "versionName": v_name,
                        "versionCode": v_code,
                        "tag_name": f"v{v_name}",
                        "apk_url": f"/{apk_file.name}",
                        "url": f"/{apk_file.name}",
                        "sha256": data.get("sha256", DEFAULT_SHA256),
                        "source": "lan",
                        "notes": data.get("notes", "相册 Android 0.8.63 (versionCode 174) / iOS 0.8.45 (build 117)：\n1. 支持全货架分类智能过滤\n2. 支持中秋国庆游戏快捷筛选\n3. 两阶段原画加载优化"),
                        "ios": data.get("ios", {
                            "version_name": "0.8.45",
                            "build": 117
                        })
                    }, apk_file
            except Exception:
                pass

    for candidate_dir in [RELEASES_DIR, ALT_OUT_DIR]:
        apk_file = candidate_dir / DEFAULT_APK_NAME
        if apk_file.exists():
            sha = hashlib.sha256(apk_file.read_bytes()).hexdigest()
            return {
                "version_name": DEFAULT_VERSION_NAME,
                "version_code": DEFAULT_VERSION_CODE,
                "versionName": DEFAULT_VERSION_NAME,
                "versionCode": DEFAULT_VERSION_CODE,
                "tag_name": f"v{DEFAULT_VERSION_NAME}",
                "apk_url": f"/{apk_file.name}",
                "url": f"/{apk_file.name}",
                "sha256": sha,
                "source": "lan",
                "notes": "相册 Android 0.8.63 (versionCode 174) / iOS 0.8.45 (build 117)：\n1. 支持全货架分类智能过滤\n2. 支持中秋国庆游戏快捷筛选\n3. 两阶段原画加载优化",
                "ios": {
                    "version_name": "0.8.45",
                    "build": 117
                }
            }, apk_file

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
        "notes": "相册 Android 0.8.63 (versionCode 174) / iOS 0.8.45 (build 117)：\n1. 支持全货架分类智能过滤\n2. 支持中秋国庆游戏快捷筛选\n3. 两阶段原画加载优化",
        "ios": {
            "version_name": "0.8.45",
            "build": 117
        }
    }, RELEASES_DIR / DEFAULT_APK_NAME


LOG_FILE = RELEASES_DIR / "lan_update_server.log"


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

    def _handle_request(self, is_head=False):
        path = self.path.split("?")[0]
        meta, default_apk_file = get_latest_apk_info()

        # 1. 版本探针响应
        if path in ["/latest.json", "/version", "/update.json", "/version.json"]:
            payload = json.dumps(meta, ensure_ascii=False, indent=2).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            if not is_head:
                self.wfile.write(payload)
            return

        # 2. APK 极速流式下载 (支持断点续传 Range)
        if path.endswith(".apk") or path == meta["apk_url"]:
            requested_filename = os.path.basename(path)
            target_file = default_apk_file
            if requested_filename.endswith(".apk"):
                candidate = RELEASES_DIR / requested_filename
                if candidate.is_file():
                    target_file = candidate

            if not target_file.exists():
                self.send_error(404, f"APK not found: {target_file.name}")
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

            self.send_header("Content-Type", "application/vnd.android.package-archive")
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
            return

        # 3. 健康检查
        if path in ["/", "/health", "/ping"]:
            payload = json.dumps({
                "status": "ok",
                "service": "lan-update-server",
                "port": 4348,
                "latest_version": meta["version_name"],
                "file_name": default_apk_file.name
            }, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            if not is_head:
                self.wfile.write(payload)
            return

        self.send_error(404, "Not Found")


def main():
    parser = argparse.ArgumentParser(description="LAN Update Server")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=4348, help="Port to bind (default: 4348)")
    args = parser.parse_args()

    meta, apk_file = get_latest_apk_info()
    server = http.server.ThreadingHTTPServer((args.host, args.port), LanUpdateHandler)
    safe_log("====================================================\n")
    safe_log(f"LAN Update Server is LIVE on http://{args.host}:{args.port}\n")
    safe_log(f"Serving Latest Version: {meta['version_name']} (Code {meta['version_code']})\n")
    safe_log(f"Package: {apk_file.name} ({apk_file.stat().st_size / 1024 / 1024:.2f} MB)\n")
    safe_log(f"SHA256: {meta['sha256']}\n")
    safe_log("====================================================\n")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

