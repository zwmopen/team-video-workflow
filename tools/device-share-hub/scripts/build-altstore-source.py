#!/usr/bin/env python3
"""Build the AltStore Classic source for the current iPhone IPA."""

from __future__ import annotations

import argparse
import json
import sys
from typing import Any


BUNDLE_ID = "com.zwm.album"
ICON_URL = "https://github.com/zwmopen.png?size=512"
PRIVACY_DESCRIPTION = (
    "用于让同一 Wi-Fi 的素材投送中控自动发现这台 iPhone，并把文件直接传入所选作品文件夹。"
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--build-version", required=True)
    parser.add_argument("--date", required=True)
    parser.add_argument("--download-url", required=True)
    parser.add_argument("--size", required=True, type=int)
    parser.add_argument("--sha256", required=True)
    return parser.parse_args()


def load_source() -> dict[str, Any]:
    raw = sys.stdin.read().strip()
    if not raw:
        return {}
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as error:
        raise SystemExit(f"invalid AltStore source JSON: {error}") from error
    if not isinstance(value, dict):
        raise SystemExit("AltStore source JSON must be an object")
    return value


def make_version(args: argparse.Namespace) -> dict[str, Any]:
    return {
        "version": args.version,
        "buildVersion": args.build_version,
        "date": args.date,
        "localizedDescription": f"云端更新：{args.version}（build {args.build_version}）。",
        "downloadURL": args.download_url,
        "size": args.size,
        "sha256": args.sha256,
    }


def update_source(source: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    source.update(
        {
            "name": "相册更新源",
            "subtitle": "相册 iPhone 自用侧载更新",
            "description": "相册 iPhone 客户端的 AltStore 更新源。首次添加后，后续云端发布的新版本会自动被 AltStore 发现。",
            "iconURL": ICON_URL,
            "apps": source.get("apps") if isinstance(source.get("apps"), list) else [],
            "news": source.get("news") if isinstance(source.get("news"), list) else [],
        }
    )

    version = make_version(args)
    apps: list[dict[str, Any]] = [app for app in source["apps"] if isinstance(app, dict)]
    app = next((item for item in apps if item.get("bundleIdentifier") == BUNDLE_ID), None)
    if app is None:
        app = {
            "name": "相册",
            "bundleIdentifier": BUNDLE_ID,
            "developerName": "zwmopen",
            "subtitle": "局域网作品与文件传送",
            "localizedDescription": "用于接收作品、查看内容和向电脑传送文件的自用 iPhone 客户端。",
            "iconURL": ICON_URL,
            "tintColor": "#4F9D69",
            "category": "photo-video",
            "versions": [],
            "appPermissions": {
                "entitlements": [],
                "privacy": {"NSLocalNetworkUsageDescription": PRIVACY_DESCRIPTION},
            },
        }
        apps.append(app)
    else:
        app.update(
            {
                "name": "相册",
                "developerName": "zwmopen",
                "subtitle": "局域网作品与文件传送",
                "localizedDescription": "用于接收作品、查看内容和向电脑传送文件的自用 iPhone 客户端。",
                "iconURL": ICON_URL,
                "tintColor": "#4F9D69",
                "category": "photo-video",
            }
        )

    old_versions = app.get("versions") if isinstance(app.get("versions"), list) else []
    app["versions"] = [
        version,
        *[
            item
            for item in old_versions
            if not (
                isinstance(item, dict)
                and item.get("version") == args.version
                and str(item.get("buildVersion")) == args.build_version
            )
        ],
    ]
    app["appPermissions"] = {
        "entitlements": [],
        "privacy": {"NSLocalNetworkUsageDescription": PRIVACY_DESCRIPTION},
    }
    source["apps"] = apps
    return source


def main() -> None:
    args = parse_args()
    result = update_source(load_source(), args)
    json.dump(result, sys.stdout, ensure_ascii=False, indent=2)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
