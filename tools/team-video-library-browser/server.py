from __future__ import annotations

from dataclasses import dataclass, asdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlparse
import hashlib
import json
import mimetypes
import os
import re
import subprocess
import sys
import html
import tempfile
import ctypes
import time
from ctypes import wintypes


LIBRARY_ROOT = Path(r"D:\Download\素材下载\团建视频")
CACHE_ROOT = LIBRARY_ROOT / "00-模板库" / "素材库浏览器缓存"
TRANSCRIPT_CACHE_ROOT = CACHE_ROOT / "transcripts"
PREVIEW_CACHE_ROOT = CACHE_ROOT / "previews"
TAG_STORE_PATH = CACHE_ROOT / "tags.json"
CROP_LAYOUT_STORE_PATH = CACHE_ROOT / "crop_layouts.json"
TRASH_ROOT = LIBRARY_ROOT / "._采集记录" / "浏览器删除素材"
INBOX_ROOT = LIBRARY_ROOT / "00-待分类整理库"
TEMPLATE_ROOT = LIBRARY_ROOT / "00-模板库"
RECORD_ROOT = LIBRARY_ROOT / "._采集记录"
NATIVE_BROWSER_LAUNCHER = Path(r"D:\AICode\AI\tools\team-video-library-browser\启动剪辑素材浏览器.bat")
JH_TOOLS_ROOT = Path(r"D:\Program Files\江湖工具箱\MinApp")
JH_TOOLBOX_MAIN = Path(r"D:\Program Files\江湖工具箱\江湖工具箱.exe")
AUDIO_LIBRARY_ROOT = LIBRARY_ROOT / "已整理原片音频"
SMART_MATCH_PACK_ROOT = LIBRARY_ROOT / "智能剪辑初剪库"

VSR_CLEAN_SCRIPT = Path(r"C:\Users\z\.codex\skills\teambuilding-video-scene-library\scripts\vsr_clean.ps1")

OPEN_TARGETS = {
    "library": LIBRARY_ROOT,
    "inbox": INBOX_ROOT,
    "templates": TEMPLATE_ROOT,
    "records": RECORD_ROOT,
    "native_browser": NATIVE_BROWSER_LAUNCHER,
    "jianghu_xhs": JH_TOOLS_ROOT / "XHS提取作品.exe",
    "jianghu_dy": JH_TOOLS_ROOT / "DY提取作品.exe",
    "jianghu_ks": JH_TOOLS_ROOT / "KS提取作品.exe",
    "jianghu_bili": JH_TOOLS_ROOT / "BiLi作品分析数据.exe",
    "jianghu_dy_live": JH_TOOLS_ROOT / "DY直播回放下载.exe",
    "jianghu_transcribe": JH_TOOLS_ROOT / "批量视音频语音转写文案.exe",
    "jianghu_segment": JH_TOOLS_ROOT / "批量视频分割.exe",
    "jianghu_fish": JH_TOOLS_ROOT / "飞书妙记提取字幕文案.exe",
}
VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm"}
AUDIO_EXTENSIONS = {".m4a", ".mp3", ".wav", ".aac", ".flac", ".ogg", ".opus"}
SYSTEM_NAMES = {
    "._采集记录",
    "._系统记录",
    "._clean_report",
    "00-模板库",
    "00-待分类整理库",
}
DELIVERY_FOLDER_PREFIXES = ("_自动粗剪",)
DELIVERY_FOLDER_SUFFIXES = ("成品区", "智能匹配工作流")


@dataclass(slots=True)
class LibraryItem:
    id: str
    kind: str
    location: str
    category: str
    keyword: str
    name: str
    path: str
    size_mb: float


ITEMS: list[LibraryItem] = []
ITEM_BY_ID: dict[str, LibraryItem] = {}
WHISPER_MODEL = None
USER_TAGS: dict[str, list[str]] = {}

BATCH_PROGRESS: dict[str, object] = {
    "running": False,
    "total": 0,
    "processed": 0,
    "success": 0,
    "skipped": 0,
    "failed": 0,
    "current_item": "",
    "message": "",
    "dry_run": False,
    "results": [],
}

DEFAULT_HEAD_TRIM_SECONDS = 0.08
BOTTOM_TEXT_MIN_SCORE = 0.03
TOP_TEXT_MIN_SCORE = 0.025
CROP_ALGORITHM_VERSION = "crop-v4-plausible-subtitle"


def transcript_supported(item: LibraryItem) -> bool:
    return item.kind in {"已整理原片", "未分类/未整理素材", "分镜素材", "原片音频素材"}


def is_audio_path(path: Path | str) -> bool:
    return Path(path).suffix.lower() in AUDIO_EXTENSIONS


def is_video_path(path: Path | str) -> bool:
    return Path(path).suffix.lower() in VIDEO_EXTENSIONS


def cleanup_old_cache() -> None:
    import shutil
    import time
    seven_days_ago = time.time() - 7 * 24 * 60 * 60
    detection_root = CACHE_ROOT / "crop_detection"
    if detection_root.exists():
        for item_dir in detection_root.iterdir():
            if item_dir.is_dir():
                try:
                    mtime = item_dir.stat().st_mtime
                    if mtime < seven_days_ago:
                        shutil.rmtree(str(item_dir), ignore_errors=True)
                except Exception:
                    pass

def main() -> int:
    port = int(os.environ.get("TB_LIBRARY_PORT", "8765"))
    load_user_tags()
    scan_library()
    CACHE_ROOT.mkdir(parents=True, exist_ok=True)
    TRANSCRIPT_CACHE_ROOT.mkdir(parents=True, exist_ok=True)
    PREVIEW_CACHE_ROOT.mkdir(parents=True, exist_ok=True)
    cleanup_old_cache()
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"素材库浏览器已启动：http://127.0.0.1:{port}")
    print(f"已索引素材：{len(ITEMS)} 条")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


def scan_library() -> None:
    ITEMS.clear()
    ITEM_BY_ID.clear()
    if not LIBRARY_ROOT.exists():
        return
    if INBOX_ROOT.exists():
        add_videos(INBOX_ROOT, "未分类/未整理素材", "待整理")
    if AUDIO_LIBRARY_ROOT.exists():
        add_audio_files(AUDIO_LIBRARY_ROOT, "原片音频素材", "")
    for folder in sorted(LIBRARY_ROOT.iterdir()):
        if not folder.is_dir() or folder.name in SYSTEM_NAMES:
            continue
        if folder.name.endswith("-原视频素材"):
            location = folder.name.removesuffix("-原视频素材")
            add_videos(folder, "已整理原片", location)
        elif folder.name.endswith("智能镜头分类"):
            location = folder.name.removesuffix("智能镜头分类")
            add_videos(folder, "分镜素材", location)
        elif folder.name.endswith("音频素材库"):
            # Legacy audio libraries are kept on disk but hidden from the main workflow
            # to avoid double-counting against the canonical 已整理原片音频 library.
            continue
        elif folder.name == SMART_MATCH_PACK_ROOT.name:
            add_delivery_videos(folder)
        elif folder.name.startswith(DELIVERY_FOLDER_PREFIXES) or folder.name.endswith(DELIVERY_FOLDER_SUFFIXES):
            add_delivery_videos(folder)


def add_videos(root: Path, kind: str, location: str) -> None:
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in VIDEO_EXTENSIONS:
            continue
        if any((part in SYSTEM_NAMES and part != INBOX_ROOT.name) or part.startswith("._") for part in path.parts):
            continue
        category, keyword = infer_labels(root, path, kind)
        item_id = stable_id(path)
        item = LibraryItem(
            id=item_id,
            kind=kind,
            location=location,
            category=category,
            keyword=keyword,
            name=path.name,
            path=str(path),
            size_mb=round(path.stat().st_size / 1024 / 1024, 2),
        )
        ITEMS.append(item)
        ITEM_BY_ID[item.id] = item


def add_audio_files(root: Path, kind: str, location: str) -> None:
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in AUDIO_EXTENSIONS:
            continue
        if any((part in SYSTEM_NAMES and part != INBOX_ROOT.name) or part.startswith("._") for part in path.parts):
            continue
        try:
            parts = path.relative_to(root).parts
        except ValueError:
            parts = ()
        inferred_location = location
        if not inferred_location and len(parts) >= 2:
            inferred_location = parts[0]
        elif not inferred_location:
            inferred_location = infer_location_from_name(path.stem) or "音频素材"
        keyword = parts[-2] if len(parts) >= 2 else "原片音频"
        item_id = stable_id(path)
        item = LibraryItem(
            id=item_id,
            kind=kind,
            location=inferred_location,
            category="原片音频",
            keyword=keyword,
            name=path.name,
            path=str(path),
            size_mb=round(path.stat().st_size / 1024 / 1024, 2),
        )
        ITEMS.append(item)
        ITEM_BY_ID[item.id] = item


def infer_location_from_name(name: str) -> str:
    for location in ("千岛湖", "安吉", "莫干山", "舟山", "阳澄湖", "天目湖", "溧阳", "杭州", "浙江"):
        if location in name:
            return location
    return ""


def add_delivery_videos(root: Path) -> None:
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in VIDEO_EXTENSIONS:
            continue
        if any(part.startswith("._") for part in path.parts):
            continue
        try:
            parts = path.relative_to(root).parts
        except ValueError:
            parts = ()
        item_id = stable_id(path)
        stem = path.stem.lower()
        if stem == "rough_cut":
            category = "粗剪成片"
        elif stem == "visual_track":
            category = "无声画面轨"
        elif "jianying_pack" in {part.lower() for part in parts}:
            category = "剪映素材包"
        else:
            category = "成品辅助片段"
        keyword = delivery_keyword(root, parts)
        item = LibraryItem(
            id=item_id,
            kind="成品粗剪",
            location="成品区",
            category=category,
            keyword=keyword,
            name=path.name,
            path=str(path),
            size_mb=round(path.stat().st_size / 1024 / 1024, 2),
        )
        ITEMS.append(item)
        ITEM_BY_ID[item.id] = item


def delivery_keyword(root: Path, parts: tuple[str, ...]) -> str:
    if root.name.endswith("智能匹配工作流"):
        if "02_粗剪成品" in parts:
            return f"{root.name} / 02_粗剪成品"
        if "01_审片板" in parts:
            return f"{root.name} / 01_审片板"
        return root.name
    return parts[-2] if len(parts) >= 2 else root.name


def infer_labels(root: Path, path: Path, kind: str) -> tuple[str, str]:
    try:
        parts = path.relative_to(root).parts
    except ValueError:
        return "", ""
    if kind == "分镜素材":
        category = parts[0] if len(parts) >= 2 else ""
        keyword = parts[1] if len(parts) >= 3 else ""
        return category, keyword
    if kind == "未分类/未整理素材":
        if len(parts) >= 2:
            return "未分类/未整理素材", parts[-2]
        return "未分类/未整理素材", "待整理"
    if len(parts) >= 3 and re.match(r"小红书|XHS|抖音|采集", parts[0], re.I):
        return "原片补素材", parts[1]
    if len(parts) >= 2:
        return "已整理原片", parts[-2]
    return "已整理原片", ""


def stable_id(path: Path) -> str:
    return hashlib.md5(str(path).encode("utf-8", errors="ignore")).hexdigest()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = unquote(parsed.path)
        if path == "/":
            self.send_html(INDEX_HTML)
            return
        if path == "/api/summary":
            self.send_json(summary())
            return
        if path == "/api/options":
            self.send_json(options(parse_qs(parsed.query)))
            return
        if path == "/api/items":
            query = parse_qs(parsed.query)
            self.send_json(query_items(query))
            return
        if path.startswith("/api/match-audio/"):
            self.match_audio(path.removeprefix("/api/match-audio/"))
            return
        if path.startswith("/api/match-output-folder/"):
            self.open_match_output_folder(path.removeprefix("/api/match-output-folder/"))
            return
        if path == "/api/crop-layouts":
            self.send_json({"ok": True, "layouts": load_crop_layouts()})
            return
        if path == "/api/batch-progress":
            self.send_json({"ok": True, **BATCH_PROGRESS})
            return
        if path.startswith("/thumb/"):
            self.send_thumbnail(path.removeprefix("/thumb/").removesuffix(".jpg"))
            return
        if path.startswith("/media/"):
            self.send_media(path.removeprefix("/media/"))
            return
        if path.startswith("/preview/"):
            self.send_preview(path.removeprefix("/preview/"))
            return
        if path.startswith("/reveal/"):
            self.reveal_file(path.removeprefix("/reveal/"))
            return
        if path.startswith("/api/transcript/"):
            self.send_transcript(path.removeprefix("/api/transcript/"))
            return
        if path == "/open-target":
            self.open_target(parse_qs(parsed.query).get("key", [""])[0])
            return
        self.send_error(404)

    def do_POST(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = unquote(parsed.path)
        if path == "/api/rename":
            self.rename_item()
            return
        if path == "/api/tag":
            self.tag_item()
            return
        if path == "/api/delete":
            self.delete_item()
            return
        if path == "/api/crop-subtitle-top":
            self.crop_subtitle_top()
            return
        if path == "/api/crop-rect":
            self.crop_rect()
            return
        if path == "/api/manual-process":
            self.manual_process()
            return
        if path == "/api/deep-repair":
            self.deep_repair()
            return
        if path == "/api/trim-time":
            self.trim_time()
            return
        if path == "/api/detect-crop":
            self.detect_crop()
            return
        if path == "/api/crop-layouts":
            self.save_crop_layout()
            return
        if path == "/api/delete-crop-layout":
            self.delete_crop_layout()
            return
        if path == "/api/batch-crop-subtitles":
            self.batch_crop_subtitles()
            return
        if path == "/api/batch-transcribe":
            self.batch_transcribe()
            return
        if path == "/api/batch-extract-audio":
            self.batch_extract_audio()
            return
        if path == "/api/batch-stop":
            self.batch_stop()
            return
        if path == "/api/rescan":
            scan_library()
            self.send_json({"ok": True, **summary()})
            return
        self.send_error(404)

    def log_message(self, fmt: str, *args: object) -> None:
        return

    def send_json(self, payload: object) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_html(self, html: str) -> None:
        body = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_thumbnail(self, item_id: str) -> None:
        item = ITEM_BY_ID.get(item_id)
        if not item:
            self.send_error(404)
            return
        if is_audio_path(item.path):
            self.send_audio_thumbnail(item)
            return
        source = Path(item.path)
        thumb = CACHE_ROOT / f"{item_id}.jpg"
        try:
            source_mtime = source.stat().st_mtime
            thumb_stale = thumb.exists() and thumb.stat().st_mtime < source_mtime
        except OSError:
            thumb_stale = False
        if thumb_stale:
            thumb.unlink(missing_ok=True)
        if not thumb.exists():
            make_thumbnail(source, thumb)
        if not thumb.exists():
            self.send_error(404)
            return
        self.send_file(thumb, "image/jpeg")

    def send_media(self, item_id: str) -> None:
        item = ITEM_BY_ID.get(item_id)
        if not item:
            self.send_error(404)
            return
        self.send_file(Path(item.path), mimetypes.guess_type(item.path)[0] or "video/mp4", range_enabled=True)

    def send_preview(self, item_id: str) -> None:
        item = ITEM_BY_ID.get(item_id)
        if not item:
            self.send_error(404)
            return
        if is_audio_path(item.path):
            self.send_file(Path(item.path), mimetypes.guess_type(item.path)[0] or "audio/mp4", range_enabled=True)
            return
        preview = ensure_preview_video(item)
        if preview and preview.exists():
            self.send_file(preview, "video/mp4", range_enabled=True)
            return
        self.send_file(Path(item.path), mimetypes.guess_type(item.path)[0] or "video/mp4", range_enabled=True)

    def send_audio_thumbnail(self, item: LibraryItem) -> None:
        label = html.escape(item.location or "音频")
        name = html.escape(item.name[:36])
        body = f"""<svg xmlns="http://www.w3.org/2000/svg" width="360" height="520" viewBox="0 0 360 520">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#eef6fb"/><stop offset="1" stop-color="#d7e4ee"/></linearGradient></defs>
<rect width="360" height="520" rx="28" fill="url(#g)"/>
<circle cx="180" cy="190" r="76" fill="#307eff" opacity=".16"/>
<path d="M150 146v108c0 20 16 36 36 36s36-16 36-36V146c0-20-16-36-36-36s-36 16-36 36z" fill="#307eff"/>
<path d="M118 236c0 38 31 68 68 68s68-30 68-68" fill="none" stroke="#1c2938" stroke-width="18" stroke-linecap="round" opacity=".62"/>
<path d="M186 306v48M150 354h72" fill="none" stroke="#1c2938" stroke-width="18" stroke-linecap="round" opacity=".62"/>
<text x="180" y="420" text-anchor="middle" font-size="28" font-family="Microsoft YaHei,Arial" fill="#1c2938" font-weight="700">{label}</text>
<text x="180" y="458" text-anchor="middle" font-size="18" font-family="Microsoft YaHei,Arial" fill="#606f80">{name}</text>
</svg>""".encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "image/svg+xml; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def reveal_file(self, item_id: str) -> None:
        item = ITEM_BY_ID.get(item_id)
        if not item:
            self.send_error(404)
            return
        path = Path(item.path)
        if not path.exists():
            self.send_error(404)
            return
        subprocess.Popen(["explorer", "/select,", str(path)], close_fds=True)
        self.send_json({"ok": True, "path": str(path)})

    def send_transcript(self, item_id: str) -> None:
        item = ITEM_BY_ID.get(item_id)
        if not item:
            self.send_error(404)
            return
        if not transcript_supported(item):
            self.send_json({"ok": False, "error": "只有原片和未整理素材支持复制视频文案", "text": ""})
            return
        try:
            self.send_json({"ok": True, **transcript_for_item(item)})
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc), "text": ""})

    def open_target(self, key: str) -> None:
        target = OPEN_TARGETS.get(key)
        if not target:
            self.send_json({"ok": False, "error": "未知入口"})
            return
        try:
            if target.suffix.lower() in (".exe", ".bat"):
                if not target.exists():
                    self.send_json({"ok": False, "error": f"工具文件不存在：{target}"})
                    return
                if key.startswith("jianghu_"):
                    launch_jianghu_tool(target)
                else:
                    subprocess.Popen([str(target)], cwd=str(target.parent), close_fds=True)
            else:
                target.mkdir(parents=True, exist_ok=True)
                subprocess.Popen(["explorer", str(target)], close_fds=True)
            self.send_json({"ok": True, "path": str(target)})
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc)})

    def match_audio(self, item_id: str) -> None:
        item = ITEM_BY_ID.get(item_id)
        if not item:
            self.send_json({"ok": False, "error": "音频素材不存在，可能需要刷新素材索引"})
            return
        if item.kind != "原片音频素材" and not is_audio_path(item.path):
            self.send_json({"ok": False, "error": "请选择原片音频素材"})
            return
        try:
            self.send_json({"ok": True, **build_audio_match_plan(item)})
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc)})

    def open_match_output_folder(self, item_id: str) -> None:
        item = ITEM_BY_ID.get(item_id)
        if not item:
            self.send_json({"ok": False, "error": "音频素材不存在，可能需要刷新素材索引"})
            return
        folder = find_match_output_folder(item)
        is_specific = folder is not None
        target = folder or SMART_MATCH_PACK_ROOT
        try:
            target.mkdir(parents=True, exist_ok=True)
            subprocess.Popen(["explorer", str(target)], close_fds=True)
            self.send_json({
                "ok": True,
                "path": str(target),
                "specific": is_specific,
                "message": "已打开本条音频对应的初剪素材包" if is_specific else "还没有找到本条音频的初剪素材包，已打开智能剪辑初剪库根目录",
            })
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc)})

    def read_json_body(self) -> dict[str, object]:
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length).decode("utf-8", errors="ignore") if length else "{}"
        payload = json.loads(raw or "{}")
        if not isinstance(payload, dict):
            raise ValueError("请求格式不对")
        return payload

    def rename_item(self) -> None:
        try:
            payload = self.read_json_body()
            item_id = str(payload.get("id") or "")
            new_name = str(payload.get("name") or "").strip()
            item = ITEM_BY_ID.get(item_id)
            if not item:
                self.send_json({"ok": False, "error": "素材不存在，可能页面需要刷新"})
                return
            new_path = rename_library_item(item, new_name)
            item.name = new_path.name
            item.path = str(new_path)
            item.size_mb = round(new_path.stat().st_size / 1024 / 1024, 2)
            self.send_json({"ok": True, "item": public_item(item)})
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc)})

    def tag_item(self) -> None:
        try:
            payload = self.read_json_body()
            item_id = str(payload.get("id") or "")
            tag = sanitize_tag(str(payload.get("tag") or ""))
            item = ITEM_BY_ID.get(item_id)
            if not item:
                self.send_json({"ok": False, "error": "素材不存在，可能页面需要刷新"})
                return
            if not tag:
                self.send_json({"ok": False, "error": "标签不能为空"})
                return
            tags = USER_TAGS.setdefault(item.path, [])
            if tag not in tags:
                tags.append(tag)
                tags.sort()
                save_user_tags()
            self.send_json({"ok": True, "item": public_item(item)})
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc)})

    def delete_item(self) -> None:
        try:
            payload = self.read_json_body()
            item_id = str(payload.get("id") or "")
            item = ITEM_BY_ID.get(item_id)
            if not item:
                self.send_json({"ok": False, "error": "素材不存在，可能页面需要刷新"})
                return
            moved_to = move_item_to_trash(item)
            ITEMS[:] = [entry for entry in ITEMS if entry.id != item.id]
            ITEM_BY_ID.pop(item.id, None)
            self.send_json({"ok": True, "moved_to": str(moved_to)})
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc)})

    def crop_subtitle_top(self) -> None:
        try:
            payload = self.read_json_body()
            item_id = str(payload.get("id") or "")
            keep_pct = float(payload.get("keep_pct") or 78)
            item = ITEM_BY_ID.get(item_id)
            if not item:
                self.send_json({"ok": False, "error": "素材不存在，可能页面需要刷新"})
                return
            output = crop_subtitle_top_video(item, keep_pct)
            scan_library()
            new_id = stable_id(output)
            new_item = ITEM_BY_ID.get(new_id)
            self.send_json({"ok": True, "output": str(output), "item": public_item(new_item) if new_item else None})
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc)})

    def crop_rect(self) -> None:
        try:
            payload = self.read_json_body()
            item_id = str(payload.get("id") or "")
            item = ITEM_BY_ID.get(item_id)
            if not item:
                self.send_json({"ok": False, "error": "素材不存在，可能页面需要刷新"})
                return
            rect = {
                "x": float(payload.get("x") or 0),
                "y": float(payload.get("y") or 0),
                "w": float(payload.get("w") or 100),
                "h": float(payload.get("h") or 100),
            }
            output = crop_rect_video(item, rect)
            scan_library()
            new_id = stable_id(output)
            new_item = ITEM_BY_ID.get(new_id)
            self.send_json({"ok": True, "output": str(output), "item": public_item(new_item) if new_item else None})
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc)})

    def manual_process(self) -> None:
        try:
            payload = self.read_json_body()
            item_id = str(payload.get("id") or "")
            item = ITEM_BY_ID.get(item_id)
            if not item:
                self.send_json({"ok": False, "error": "素材不存在，可能页面需要刷新"})
                return
            rect = {
                "x": float(payload.get("x") or 0),
                "y": float(payload.get("y") or 0),
                "w": float(payload.get("w") or 100),
                "h": float(payload.get("h") or 100),
            }
            start = float(payload.get("start") or 0)
            end = float(payload.get("end") or 0)
            output = manual_process_video(item, rect, start, end)
            scan_library()
            new_id = stable_id(output)
            new_item = ITEM_BY_ID.get(new_id)
            self.send_json({"ok": True, "output": str(output), "item": public_item(new_item) if new_item else None})
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc)})

    def trim_time(self) -> None:
        try:
            payload = self.read_json_body()
            item_id = str(payload.get("id") or "")
            item = ITEM_BY_ID.get(item_id)
            if not item:
                self.send_json({"ok": False, "error": "素材不存在，可能页面需要刷新"})
                return
            start = float(payload.get("start") or 0)
            end = float(payload.get("end") or 0)
            output = trim_time_video(item, start, end)
            scan_library()
            new_id = stable_id(output)
            new_item = ITEM_BY_ID.get(new_id)
            self.send_json({"ok": True, "output": str(output), "item": public_item(new_item) if new_item else None})
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc)})

    def detect_crop(self) -> None:
        try:
            payload = self.read_json_body()
            item_id = str(payload.get("id") or "")
            item = ITEM_BY_ID.get(item_id)
            if not item:
                self.send_json({"ok": False, "error": "素材不存在，可能页面需要刷新"})
                return
            result = detect_subtitle_crop_rect(item)
            self.send_json({"ok": True, **result})
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc)})

    def save_crop_layout(self) -> None:
        try:
            payload = self.read_json_body()
            name = sanitize_tag(str(payload.get("name") or ""))
            rect = {
                "x": float(payload.get("x") or 0),
                "y": float(payload.get("y") or 0),
                "w": float(payload.get("w") or 100),
                "h": float(payload.get("h") or 78),
            }
            if not name:
                self.send_json({"ok": False, "error": "布局名称不能为空"})
                return
            layouts = load_crop_layouts()
            layouts = [layout for layout in layouts if layout.get("name") != name]
            layouts.append({"name": name, "rect": normalize_rect_percent(rect)})
            save_crop_layouts(layouts)
            self.send_json({"ok": True, "layouts": layouts})
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc)})

    def delete_crop_layout(self) -> None:
        try:
            payload = self.read_json_body()
            name = sanitize_tag(str(payload.get("name") or ""))
            if not name:
                self.send_json({"ok": False, "error": "请选择要删除的布局"})
                return
            layouts = [layout for layout in load_crop_layouts() if layout.get("name") != name]
            save_crop_layouts(layouts)
            self.send_json({"ok": True, "layouts": layouts})
        except Exception as exc:
            self.send_json({"ok": False, "error": str(exc)})

    def batch_crop_subtitles(self) -> None:
        global BATCH_PROGRESS
        try:
            payload = self.read_json_body()
            dry_run = bool(payload.get("dry_run", False))
            confidence_threshold = float(payload.get("confidence_threshold", 0.35))
            item_ids = [str(item_id) for item_id in payload.get("item_ids", []) if str(item_id)]
            if BATCH_PROGRESS["running"]:
                self.send_json({"ok": False, "error": "已有批量任务在运行中，请等待完成"})
                return
            BATCH_PROGRESS = {
                "running": True,
                "total": 0,
                "processed": 0,
                "success": 0,
                "skipped": 0,
                "failed": 0,
                "current_item": "",
                "message": "",
                "dry_run": dry_run,
                "scope": "visible" if item_ids else "all",
                "results": [],
            }
            import threading
            threading.Thread(target=run_batch_crop_subtitles, args=(dry_run, confidence_threshold, item_ids), daemon=True).start()
            self.send_json({"ok": True, "message": "批量任务已启动，请轮询 /api/batch-progress 获取进度"})
        except Exception as exc:
            BATCH_PROGRESS["running"] = False
            self.send_json({"ok": False, "error": str(exc)})

    def batch_transcribe(self) -> None:
        global BATCH_PROGRESS
        try:
            payload = self.read_json_body()
            skip_existing = bool(payload.get("skip_existing", True))
            kind_filter = str(payload.get("kind", ""))
            if BATCH_PROGRESS["running"]:
                self.send_json({"ok": False, "error": "已有批量任务在运行中，请等待完成"})
                return
            BATCH_PROGRESS = {
                "running": True,
                "total": 0,
                "processed": 0,
                "success": 0,
                "skipped": 0,
                "failed": 0,
                "current_item": "",
                "message": "",
                "dry_run": False,
                "results": [],
            }
            import threading
            threading.Thread(target=run_batch_transcribe, args=(skip_existing, kind_filter), daemon=True).start()
            self.send_json({"ok": True, "message": "批量语音转文字任务已启动，请轮询 /api/batch-progress 获取进度"})
        except Exception as exc:
            BATCH_PROGRESS["running"] = False
            self.send_json({"ok": False, "error": str(exc)})

    def batch_extract_audio(self) -> None:
        global BATCH_PROGRESS
        try:
            payload = self.read_json_body()
            location_filter = str(payload.get("location", "") or "")
            transcribe = bool(payload.get("transcribe", False))
            if BATCH_PROGRESS["running"]:
                self.send_json({"ok": False, "error": "已有批量任务在运行中，请等待完成"})
                return
            BATCH_PROGRESS = {
                "running": True,
                "total": 0,
                "processed": 0,
                "success": 0,
                "skipped": 0,
                "failed": 0,
                "current_item": "",
                "message": "",
                "dry_run": False,
                "results": [],
            }
            import threading
            threading.Thread(target=run_batch_extract_audio, args=(location_filter, transcribe), daemon=True).start()
            self.send_json({"ok": True, "message": "批量提取音频/文案任务已启动，请轮询 /api/batch-progress 获取进度"})
        except Exception as exc:
            BATCH_PROGRESS["running"] = False
            self.send_json({"ok": False, "error": str(exc)})

    def deep_repair(self) -> None:
        global BATCH_PROGRESS
        try:
            payload = self.read_json_body()
            item_id = str(payload.get("id") or "")
            area = str(payload.get("area") or "auto").strip().lower()
            mode = str(payload.get("mode") or "sttn_auto").strip().lower()
            item = ITEM_BY_ID.get(item_id)
            if not item:
                self.send_json({"ok": False, "error": "素材不存在，可能需要刷新素材索引"})
                return
            if BATCH_PROGRESS["running"]:
                self.send_json({"ok": False, "error": "已有批量/修复任务在运行中，请等它结束"})
                return
            BATCH_PROGRESS = {
                "running": True,
                "total": 1,
                "processed": 0,
                "success": 0,
                "skipped": 0,
                "failed": 0,
                "current_item": item.name,
                "message": "深度修复任务已启动，正在准备 VSR/AI 去字...",
                "dry_run": False,
                "scope": "single",
                "results": [],
            }
            import threading
            threading.Thread(target=run_deep_repair_item, args=(item.id, area, mode), daemon=True).start()
            self.send_json({"ok": True, "message": "深度修复任务已启动，请看总控任务队列"})
        except Exception as exc:
            BATCH_PROGRESS["running"] = False
            self.send_json({"ok": False, "error": str(exc)})

    def batch_stop(self) -> None:
        global BATCH_PROGRESS
        BATCH_PROGRESS["running"] = False
        self.send_json({"ok": True, **BATCH_PROGRESS})

    def send_file(self, path: Path, content_type: str, range_enabled: bool = False) -> None:
        if not path.exists():
            self.send_error(404)
            return
        size = path.stat().st_size
        start = 0
        end = size - 1
        status = 200
        if range_enabled:
            range_header = self.headers.get("Range", "")
            match = re.match(r"bytes=(\d*)-(\d*)", range_header)
            if match:
                raw_start, raw_end = match.group(1), match.group(2)
                if raw_start:
                    start = int(raw_start)
                    if raw_end:
                        end = min(size - 1, int(raw_end))
                elif raw_end:
                    suffix = min(size, int(raw_end))
                    start = max(0, size - suffix)
                if start >= size or start > end:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Accept-Ranges", "bytes")
                    self.end_headers()
                    return
                status = 206
        length = end - start + 1
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()
        with path.open("rb") as file:
            file.seek(start)
            remaining = length
            while remaining > 0:
                chunk = file.read(min(1024 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)


def summary() -> dict[str, object]:
    by_kind: dict[str, int] = {}
    by_location: dict[str, int] = {}
    transcript_ready = 0
    transcript_missing = 0
    manual_processed = 0
    for item in ITEMS:
        by_kind[item.kind] = by_kind.get(item.kind, 0) + 1
        by_location[item.location] = by_location.get(item.location, 0) + 1
        if transcript_supported(item):
            if has_cached_or_sidecar_transcript(item):
                transcript_ready += 1
            else:
                transcript_missing += 1
        if "手动处理" in item.path or "字幕之上" in item.path or "裁去字幕" in item.path or "裁切废料" in item.path:
            manual_processed += 1
    return {
        "total": len(ITEMS),
        "by_kind": by_kind,
        "by_location": by_location,
        "quality": {
            "transcript_ready": transcript_ready,
            "transcript_missing": transcript_missing,
            "manual_processed": manual_processed,
        },
        "library_root": str(LIBRARY_ROOT),
    }


FILTER_FIELDS = {
    "kind": "kind",
    "location": "location",
    "category": "category",
    "keyword": "keyword",
}


def options(query: dict[str, list[str]]) -> dict[str, list[dict[str, object]]]:
    return {
        "kinds": option_counts(query, "kind"),
        "locations": option_counts(query, "location"),
        "categories": option_counts(query, "category"),
        "keywords": option_counts(query, "keyword"),
    }


def option_counts(query: dict[str, list[str]], field: str) -> list[dict[str, object]]:
    counts: dict[str, int] = {}
    for item in ITEMS:
        if not item_matches_for_options(item, query, field):
            continue
        value = str(getattr(item, FILTER_FIELDS[field]) or "")
        if not value:
            continue
        counts[value] = counts.get(value, 0) + 1
    return [{"value": value, "count": count} for value, count in sorted(counts.items(), key=lambda pair: (-pair[1], pair[0]))]


def item_matches_for_options(item: LibraryItem, query: dict[str, list[str]], field: str) -> bool:
    q = (query.get("q", [""])[0] or "").strip().lower()
    if q:
        tags = " ".join(USER_TAGS.get(item.path, []))
        hay = " ".join([item.name, item.location, item.category, item.keyword, item.path, tags]).lower()
        if q not in hay:
            return False
    order = ["kind", "location", "category", "keyword"]
    field_index = order.index(field)
    for parent_field in order[:field_index]:
        value = query.get(parent_field, [""])[0]
        if value and str(getattr(item, FILTER_FIELDS[parent_field])) != value:
            return False
    return True


def item_matches(item: LibraryItem, query: dict[str, list[str]], ignore_field: str | None = None) -> bool:
    q = (query.get("q", [""])[0] or "").strip().lower()
    for field, attr in FILTER_FIELDS.items():
        if field == ignore_field:
            continue
        value = query.get(field, [""])[0]
        if value and str(getattr(item, attr)) != value:
            return False
    tags = " ".join(USER_TAGS.get(item.path, []))
    hay = " ".join([item.name, item.location, item.category, item.keyword, item.path, tags]).lower()
    return not q or q in hay


def query_items(query: dict[str, list[str]]) -> dict[str, object]:
    page = max(1, int(query.get("page", ["1"])[0] or "1"))
    page_size = min(80, max(12, int(query.get("page_size", ["36"])[0] or "36")))
    sort_by = (query.get("sort", ["scene"])[0] or "scene").strip()

    result = []
    for item in ITEMS:
        if not item_matches(item, query):
            continue
        result.append(item)
    result.sort(key=lambda item: material_sort_key(item, sort_by))
    start = (page - 1) * page_size
    page_items = result[start : start + page_size]
    return {
        "total": len(result),
        "page": page,
        "page_size": page_size,
        "items": [public_item(item) for item in page_items],
    }


def public_item(item: LibraryItem) -> dict[str, object]:
    data = asdict(item)
    try:
        thumb_version = int(Path(item.path).stat().st_mtime)
    except OSError:
        thumb_version = 0
    data["thumb"] = f"/thumb/{quote(item.id)}.jpg?v={thumb_version}"
    data["media"] = f"/media/{quote(item.id)}"
    data["preview_media"] = f"/preview/{quote(item.id)}"
    data["reveal"] = f"/reveal/{quote(item.id)}"
    data["transcript"] = f"/api/transcript/{quote(item.id)}"
    data["has_transcript"] = has_cached_or_sidecar_transcript(item)
    data["user_tags"] = USER_TAGS.get(item.path, [])
    data["file_uri"] = Path(item.path).as_uri()
    data["mime"] = mimetypes.guess_type(item.path)[0] or "video/mp4"
    data["process_tag"] = process_tag(item)
    return data


def process_tag(item: LibraryItem) -> str:
    text = f"{item.name} {item.path}"
    if "深度修复" in text:
        return "深度修复"
    if any(token in text for token in ("裁剪分割", "裁切废料", "字幕之上", "裁去字幕", "手动处理")):
        return "已裁切"
    if item.kind == "分镜素材":
        return "原分镜"
    if item.kind == "已整理原片":
        return "原片"
    return ""


def material_sort_key(item: LibraryItem, sort_by: str = "scene") -> tuple:
    path = Path(item.path)
    try:
        stat = path.stat()
        mtime = stat.st_mtime
        size = stat.st_size
    except OSError:
        mtime = 0
        size = 0
    if sort_by == "name":
        return (item.name.lower(), item.location, item.category, item.keyword)
    if sort_by == "newest":
        return (-mtime, item.name.lower())
    if sort_by == "oldest":
        return (mtime, item.name.lower())
    if sort_by == "size_desc":
        return (-size, item.name.lower())
    if sort_by == "size_asc":
        return (size, item.name.lower())
    stem = path.stem
    scene_match = re.search(r"([A-Za-z]*V\d+_S\d+)", stem, re.IGNORECASE)
    scene_key = scene_match.group(1).upper() if scene_match else stem
    normalized_stem = re.sub(r"^\d+[_-]?", "", stem)
    normalized_stem = re.sub(r"(_?裁剪分割|_?裁切废料|_?裁去字幕|_?字幕之上|_?深度修复|_?手动处理)", "", normalized_stem)
    version_order = {"原分镜": 0, "原片": 0, "已裁切": 1, "深度修复": 2}.get(process_tag(item), 3)
    return (item.kind, item.location, item.category, item.keyword, str(path.parent), scene_key, normalized_stem, version_order, item.name)


def load_user_tags() -> None:
    USER_TAGS.clear()
    if not TAG_STORE_PATH.exists():
        return
    try:
        payload = json.loads(TAG_STORE_PATH.read_text(encoding="utf-8"))
    except Exception:
        return
    if isinstance(payload, dict):
        for key, value in payload.items():
            if isinstance(value, list):
                USER_TAGS[str(key)] = [sanitize_tag(str(tag)) for tag in value if sanitize_tag(str(tag))]


def save_user_tags() -> None:
    TAG_STORE_PATH.parent.mkdir(parents=True, exist_ok=True)
    TAG_STORE_PATH.write_text(json.dumps(USER_TAGS, ensure_ascii=False, indent=2), encoding="utf-8")


def rename_library_item(item: LibraryItem, requested_name: str) -> Path:
    source = Path(item.path)
    if not source.exists():
        raise FileNotFoundError("原文件不存在")
    clean_name = sanitize_filename(requested_name)
    if not clean_name:
        raise ValueError("文件名不能为空")
    requested_path = Path(clean_name)
    if requested_path.name != clean_name:
        raise ValueError("文件名不能包含路径")
    if not requested_path.suffix:
        clean_name = f"{clean_name}{source.suffix}"
    elif requested_path.suffix.lower() != source.suffix.lower():
        clean_name = f"{requested_path.stem}{source.suffix}"
    target = source.with_name(clean_name)
    if target == source:
        return source
    if target.exists():
        raise FileExistsError("同文件夹已经有这个名字了")
    sidecars = [source.with_suffix(".txt"), source.with_suffix(".json")]
    renamed_sidecars: list[tuple[Path, Path]] = []
    source.rename(target)
    try:
        for sidecar in sidecars:
            if not sidecar.exists():
                continue
            sidecar_target = target.with_suffix(sidecar.suffix)
            if sidecar_target.exists():
                continue
            sidecar.rename(sidecar_target)
            renamed_sidecars.append((sidecar_target, sidecar))
    except Exception:
        for current, previous in reversed(renamed_sidecars):
            if current.exists() and not previous.exists():
                current.rename(previous)
        if target.exists() and not source.exists():
            target.rename(source)
        raise
    if item.path in USER_TAGS:
        USER_TAGS[str(target)] = USER_TAGS.pop(item.path)
        save_user_tags()
    return target


def sanitize_filename(name: str) -> str:
    name = name.strip().strip(".")
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name)
    name = re.sub(r"\s+", " ", name)
    reserved = {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}
    if name.upper() in reserved:
        name = f"{name}_"
    return name[:180].strip()


def sanitize_tag(tag: str) -> str:
    tag = re.sub(r"\s+", " ", tag.strip())
    tag = re.sub(r"[<>:\"/\\|?*\x00-\x1f]", "_", tag)
    return tag[:30].strip()


def normalize_rect_percent(rect: dict[str, float]) -> dict[str, float]:
    x = min(95.0, max(0.0, float(rect.get("x", 0))))
    y = min(95.0, max(0.0, float(rect.get("y", 0))))
    w = min(100.0 - x, max(5.0, float(rect.get("w", 100))))
    h = min(100.0 - y, max(5.0, float(rect.get("h", 78))))
    return {"x": round(x, 3), "y": round(y, 3), "w": round(w, 3), "h": round(h, 3)}


def load_crop_layouts() -> list[dict[str, object]]:
    default_layouts: list[dict[str, object]] = [
        {"name": "裁切废料_74", "rect": {"x": 0, "y": 0, "w": 100, "h": 74}},
        {"name": "裁切废料_72", "rect": {"x": 0, "y": 0, "w": 100, "h": 72}},
        {"name": "全画面", "rect": {"x": 0, "y": 0, "w": 100, "h": 100}},
    ]
    if not CROP_LAYOUT_STORE_PATH.exists():
        return default_layouts
    try:
        payload = json.loads(CROP_LAYOUT_STORE_PATH.read_text(encoding="utf-8"))
    except Exception:
        return default_layouts
    if not isinstance(payload, list):
        return default_layouts
    seen = {str(layout["name"]) for layout in default_layouts}
    merged = default_layouts[:]
    for entry in payload:
        if not isinstance(entry, dict):
            continue
        name = sanitize_tag(str(entry.get("name") or ""))
        rect = entry.get("rect")
        if not name or not isinstance(rect, dict) or name in seen:
            continue
        merged.append({"name": name, "rect": normalize_rect_percent(rect)})
        seen.add(name)
    return merged


def save_crop_layouts(layouts: list[dict[str, object]]) -> None:
    CROP_LAYOUT_STORE_PATH.parent.mkdir(parents=True, exist_ok=True)
    cleaned = []
    for entry in layouts:
        if not isinstance(entry, dict):
            continue
        name = sanitize_tag(str(entry.get("name") or ""))
        rect = entry.get("rect")
        if name and isinstance(rect, dict):
            cleaned.append({"name": name, "rect": normalize_rect_percent(rect)})
    CROP_LAYOUT_STORE_PATH.write_text(json.dumps(cleaned, ensure_ascii=False, indent=2), encoding="utf-8")


def move_item_to_trash(item: LibraryItem) -> Path:
    source = Path(item.path)
    if not source.exists():
        raise FileNotFoundError("原文件不存在")
    sidecars = [source.with_suffix(".txt"), source.with_suffix(".json")]
    send_path_to_recycle_bin(source)
    for sidecar in sidecars:
        if not sidecar.exists():
            continue
        send_path_to_recycle_bin(sidecar)
    if item.path in USER_TAGS:
        USER_TAGS.pop(item.path, None)
        save_user_tags()
    return source


class SHFILEOPSTRUCTW(ctypes.Structure):
    _fields_ = [
        ("hwnd", wintypes.HWND),
        ("wFunc", wintypes.UINT),
        ("pFrom", wintypes.LPCWSTR),
        ("pTo", wintypes.LPCWSTR),
        ("fFlags", wintypes.WORD),
        ("fAnyOperationsAborted", wintypes.BOOL),
        ("hNameMappings", wintypes.LPVOID),
        ("lpszProgressTitle", wintypes.LPCWSTR),
    ]


def send_path_to_recycle_bin(path: Path) -> None:
    absolute = str(path.resolve())
    last_result = 0
    last_aborted = False
    for _attempt in range(4):
        buffer = ctypes.create_unicode_buffer(absolute + "\0\0")
        operation = SHFILEOPSTRUCTW()
        operation.hwnd = None
        operation.wFunc = 3  # FO_DELETE
        operation.pFrom = ctypes.cast(buffer, wintypes.LPCWSTR)
        operation.pTo = None
        operation.fFlags = 0x0040 | 0x0010 | 0x0400  # FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_NOERRORUI
        operation.fAnyOperationsAborted = False
        result = ctypes.windll.shell32.SHFileOperationW(ctypes.byref(operation))
        last_result = result
        last_aborted = bool(operation.fAnyOperationsAborted)
        if result == 0 and not operation.fAnyOperationsAborted and not Path(absolute).exists():
            return
        time.sleep(0.25)
    raise RuntimeError(f"移动到回收站失败：{absolute}（错误码 {last_result}，可能是视频仍在预览占用：{last_aborted}）")


def crop_subtitle_top_video(item: LibraryItem, keep_pct: float) -> Path:
    source = Path(item.path)
    if not source.exists():
        raise FileNotFoundError("原文件不存在")
    keep_pct = min(95.0, max(45.0, keep_pct))
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        raise RuntimeError("找不到 FFmpeg，无法裁剪视频")
    output_dir = source.parent / "裁切废料"
    output_dir.mkdir(parents=True, exist_ok=True)
    output = unique_path(output_dir / f"{source.stem}__裁切废料{source.suffix}")
    crop_expr = f"crop=iw:trunc(ih*{keep_pct / 100:.4f}/2)*2:0:0"
    temp = output.with_suffix(".tmp.mp4")
    temp.unlink(missing_ok=True)
    result = subprocess.run(
        [
            str(ffmpeg),
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            f"{DEFAULT_HEAD_TRIM_SECONDS:.3f}",
            "-i",
            str(source),
            "-map",
            "0:v:0",
            "-map",
            "0:a?",
            "-vf",
            crop_expr,
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "18",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-movflags",
            "+faststart",
            str(temp),
        ],
        capture_output=True,
        timeout=240,
        check=False,
    )
    if result.returncode != 0 or not temp.exists() or temp.stat().st_size <= 0:
        temp.unlink(missing_ok=True)
        message = result.stderr.decode("utf-8", errors="ignore").strip() if result.stderr else "裁剪失败"
        raise RuntimeError(message or "裁剪失败")
    temp.replace(output)
    return output


def detect_subtitle_crop_rect(item: LibraryItem) -> dict[str, object]:
    source = Path(item.path)
    if not source.exists():
        raise FileNotFoundError("原文件不存在")
    frames = extract_detection_frames(source, item.id)
    if not frames:
        return {"confidence": 0.0, "rect": {"x": 0, "y": 0, "w": 100, "h": 74}, "reason": "未能抽帧，使用默认裁切废料布局"}
    intro_trim = detect_intro_waste_trim(source, item.id)

    text_candidates = []
    top_text_candidates = []
    watermark_candidates = []
    black_bar_results = []
    text_details = []
    watermark_details = []
    bar_details = []

    for frame in frames:
        top_text_detected = detect_top_text_bottom_in_frame(frame)
        if top_text_detected and float(top_text_detected.get("score", 0)) >= TOP_TEXT_MIN_SCORE:
            top_text_candidates.append(top_text_detected["bottom_pct"])
            watermark_details.append(top_text_detected)

        text_detected = detect_text_top_in_frame(frame)
        if text_detected and float(text_detected.get("score", 0)) >= BOTTOM_TEXT_MIN_SCORE:
            text_candidates.append(text_detected["top_pct"])
            text_details.append(text_detected)

        watermark_detected = detect_top_watermark_in_frame(frame)
        if watermark_detected:
            watermark_candidates.append(watermark_detected["bottom_pct"])
            watermark_details.append(watermark_detected)

        bar_detected = detect_black_bars_in_frame(frame)
        if bar_detected:
            black_bar_results.append(bar_detected)
            bar_details.append(bar_detected)

    plausible_text_details = []
    for detail in text_details:
        top = float(detail.get("top_pct", 0))
        bottom = float(detail.get("bottom_pct", 0))
        span = max(0.0, bottom - top)
        score = float(detail.get("score", 0))
        if top >= 84.0 and 0.4 <= span <= 8.0 and score >= BOTTOM_TEXT_MIN_SCORE:
            plausible_text_details.append(detail)
    ignored_text_details = [detail for detail in text_details if detail not in plausible_text_details]
    text_details = plausible_text_details
    text_candidates = [detail["top_pct"] for detail in text_details]

    y_offset = 0.0
    y_reason = ""
    top_text_candidates = [value for value in top_text_candidates if value <= 17.0]
    if top_text_candidates:
        top_text_bottom = max(top_text_candidates)
        y_offset = max(y_offset, min(18.0, top_text_bottom + 1.2))
        y_reason = f"，顶部字幕底部 {round(top_text_bottom, 1)}%"
    if y_offset == 0.0 and watermark_candidates:
        watermark_candidates.sort()
        top_watermarks = [value for value in watermark_candidates if 0.5 <= value <= 18.0]
        if top_watermarks:
            watermark_bottom = max(top_watermarks)
            y_offset = watermark_bottom
            y_reason = f"，顶部水印底部 {round(watermark_bottom, 1)}%"
    if y_offset == 0.0 and black_bar_results:
        top_bars = [b["top_bar_pct"] for b in black_bar_results]
        avg_top_bar = sum(top_bars) / len(top_bars)
        if 1.0 < avg_top_bar <= 18.0:
            y_offset = avg_top_bar
            y_reason = f"，顶部黑边 {round(avg_top_bar, 1)}%"

    # Conservative batch mode: do not remove useful top picture for tiny top
    # watermarks. Top watermark cleanup belongs to deep repair/manual review.
    if y_offset > 0:
        y_offset = 0.0

    bottom_limit = 100.0
    if black_bar_results:
        bottom_bars = [b["bottom_bar_pct"] for b in black_bar_results]
        avg_bottom_bar = sum(bottom_bars) / len(bottom_bars)
        if avg_bottom_bar < 99.0:
            bottom_limit = avg_bottom_bar

    if not text_candidates:
        if black_bar_results and bottom_limit < 92.0:
            keep_h = max(45.0, min(bottom_limit - 2.0, 100.0))
            reason = f"未检测到可靠底部字幕，仅检测到明显底部黑边，保留到 {round(keep_h, 1)}%{y_reason}"
            return {
                "confidence": 0.5,
                "rect": {"x": 0, "y": round(y_offset, 3), "w": 100, "h": round(keep_h - y_offset, 3)},
                "reason": reason,
                "suggested_start": intro_trim.get("suggested_start", 0),
                "suggested_start_reason": intro_trim.get("reason", ""),
                "details": {"text": text_details, "ignored_text": ignored_text_details, "watermark": watermark_details, "bars": bar_details},
            }
        reason = f"未检测到可靠底部字幕，不裁切；低分疑似文字已忽略{y_reason}"
        return {
            "confidence": 0.05,
            "rect": {"x": 0, "y": 0, "w": 100, "h": 100},
            "reason": reason,
            "no_crop": True,
            "suggested_start": intro_trim.get("suggested_start", 0),
            "suggested_start_reason": intro_trim.get("reason", ""),
            "details": {"text": text_details, "ignored_text": ignored_text_details, "watermark": watermark_details, "bars": bar_details},
        }

    text_candidates.sort()
    top_pct = text_candidates[-1]
    subtitle_spans = [
        max(0.0, float(detail.get("bottom_pct", 0)) - float(detail.get("top_pct", 0)))
        for detail in text_details
    ]
    avg_subtitle_span = sum(subtitle_spans) / len(subtitle_spans) if subtitle_spans else 2.0
    # Bottom captions are often only a thin strip. Keep the margin dynamic so
    # clean picture area is not destroyed by an overly large fixed crop.
    subtitle_safety_margin = round(min(4.0, max(1.4, avg_subtitle_span * 0.65 + 0.8)), 3)
    keep_h_raw = top_pct - subtitle_safety_margin
    max_bottom_crop_pct = 14.0
    min_keep_pct = max(80.0, 100.0 - max_bottom_crop_pct)
    keep_h = max(min_keep_pct, min(bottom_limit - 1.0, keep_h_raw))
    confidence = min(0.92, 0.42 + 0.16 * len(text_candidates))
    if black_bar_results:
        confidence = min(0.95, confidence + 0.12)
    if y_offset > 0 and watermark_candidates:
        confidence = min(0.95, confidence + 0.12)

    final_h = round(max(80.0, keep_h - y_offset), 3)
    reason = f"抽取 {len(frames)} 帧，{len(text_candidates)} 帧检测到底部字幕；保留到字幕顶部上方约 {round(keep_h, 1)}%（含 {subtitle_safety_margin}% 安全边距）{y_reason}"
    if y_offset > 0 and watermark_candidates:
        reason += f"（顶部水印已移除）"
    if black_bar_results and bottom_limit < 99.0:
        reason += f"，底部黑边截止 {round(bottom_limit, 1)}%"

    return {
        "confidence": round(confidence, 3),
        "rect": {"x": 0, "y": round(y_offset, 3), "w": 100, "h": final_h},
        "reason": reason,
        "suggested_start": intro_trim.get("suggested_start", 0),
        "suggested_start_reason": intro_trim.get("reason", ""),
        "details": {"text": text_details, "ignored_text": ignored_text_details, "watermark": watermark_details, "bars": bar_details},
    }


def extract_detection_frames(video: Path, item_id: str) -> list[Path]:
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        return []
    out_dir = CACHE_ROOT / "crop_detection" / item_id
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
    except PermissionError:
        try:
            out_dir = Path(os.environ.get("TEMP", "/tmp")) / "video_library_crop" / item_id
            out_dir.mkdir(parents=True, exist_ok=True)
        except Exception:
            return []
    for old in out_dir.glob("frame_*.jpg"):
        old.unlink(missing_ok=True)
    duration = get_video_duration_seconds(video, ffmpeg)
    if duration and duration > 3:
        positions = [max(0.25, duration * pct) for pct in (0.25, 0.5, 0.75)]
    else:
        positions = [0.5, 1.5, 2.5]
    frames: list[Path] = []
    for idx, position in enumerate(positions, start=1):
        output = out_dir / f"frame_{idx}.jpg"
        subprocess.run(
            [
                str(ffmpeg),
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-ss",
                f"{position:.3f}",
                "-i",
                str(video),
                "-frames:v",
                "1",
                "-update",
                "1",
                "-vf",
                "scale=540:-1",
                "-q:v",
                "3",
                str(output),
            ],
            capture_output=True,
            timeout=45,
            check=False,
        )
        if output.exists() and output.stat().st_size > 0:
            frames.append(output)
    if frames:
        return frames
    output = out_dir / "frame_1.jpg"
    subprocess.run(
        [str(ffmpeg), "-hide_banner", "-loglevel", "error", "-y", "-i", str(video), "-frames:v", "1", "-update", "1", "-vf", "scale=540:-1", "-q:v", "3", str(output)],
        capture_output=True,
        timeout=45,
        check=False,
    )
    return [output] if output.exists() and output.stat().st_size > 0 else []


def detect_intro_waste_trim(video: Path, item_id: str) -> dict[str, object]:
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        return {"suggested_start": 0.0, "reason": ""}
    duration = get_video_duration_seconds(video, ffmpeg) or 0
    if duration < 2:
        return {"suggested_start": 0.0, "reason": ""}
    out_dir = CACHE_ROOT / "crop_detection" / item_id / "intro"
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
    except Exception:
        return {"suggested_start": 0.0, "reason": ""}
    positions = [0.25, 1.0, 1.75, 2.5, 3.25, 4.0, 4.75, 5.5, 6.25, 7.0]
    positions = [pos for pos in positions if pos < max(0.5, duration - 0.2)]
    detected: list[float] = []
    for idx, position in enumerate(positions, start=1):
        frame = out_dir / f"intro_{idx}.jpg"
        subprocess.run(
            [
                str(ffmpeg),
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-ss",
                f"{position:.3f}",
                "-i",
                str(video),
                "-frames:v",
                "1",
                "-vf",
                "scale=540:-1",
                "-q:v",
                "3",
                str(frame),
            ],
            capture_output=True,
            timeout=30,
            check=False,
        )
        if frame.exists() and frame.stat().st_size > 0 and detect_large_title_or_cover_frame(frame):
            detected.append(position)
    if not detected:
        return {"suggested_start": 0.0, "reason": ""}
    groups: list[list[float]] = []
    current: list[float] = []
    for position in detected:
        if not current or position - current[-1] <= 1.05:
            current.append(position)
        else:
            groups.append(current)
            current = [position]
    if current:
        groups.append(current)
    intro_groups = [group for group in groups if group[0] <= 1.25 and len(group) >= 3]
    if not intro_groups:
        return {"suggested_start": 0.0, "reason": ""}
    suggested = min(duration - 0.2, max(intro_groups[0]) + 0.75)
    if suggested < 0.8:
        return {"suggested_start": 0.0, "reason": ""}
    return {
        "suggested_start": round(suggested, 3),
        "reason": f"开头检测到大封面/大标题废料，建议从 {round(suggested, 1)} 秒开始保留",
    }


def detect_large_title_or_cover_frame(frame: Path) -> bool:
    import cv2
    import numpy as np

    image = cv2.imdecode(np.fromfile(str(frame), dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return False
    height, width = image.shape[:2]
    y1 = int(height * 0.12)
    y2 = int(height * 0.80)
    crop = image[y1:y2, :]
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 70, 170)
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    bright = gray > 178
    yellow = ((hsv[:, :, 0] >= 10) & (hsv[:, :, 0] <= 45) & (hsv[:, :, 1] > 65) & (hsv[:, :, 2] > 115))
    mask = ((edges > 0) & (bright | yellow)).astype("uint8") * 255
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (27, 9))
    mask = cv2.dilate(mask, kernel, iterations=2)
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    frame_area = float(height * width)
    for contour in contours:
        x, y, w, h = cv2.boundingRect(contour)
        full_y = y + y1
        contour_area = float(w * h)
        if w >= width * 0.24 and h >= height * 0.045 and contour_area >= frame_area * 0.012:
            if height * 0.16 <= full_y <= height * 0.78:
                return True
    return False


def detect_top_text_bottom_in_frame(frame: Path) -> dict[str, float] | None:
    import cv2
    import numpy as np

    image = cv2.imdecode(np.fromfile(str(frame), dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return None
    height, width = image.shape[:2]
    search_bottom = int(height * 0.22)
    crop = image[0:search_bottom, :]
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 70, 170)
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    bright = gray > 175
    yellow = ((hsv[:, :, 0] >= 10) & (hsv[:, :, 0] <= 45) & (hsv[:, :, 1] > 65) & (hsv[:, :, 2] > 115))
    mask = ((edges > 0) & (bright | yellow)).astype("uint8")
    row_score = mask.mean(axis=1)
    if row_score.max() < 0.009:
        return None
    threshold = max(0.009, float(row_score.max()) * 0.30)
    active = np.where(row_score >= threshold)[0]
    if active.size == 0:
        return None
    groups = []
    start = int(active[0])
    previous = int(active[0])
    for value in active[1:]:
        value = int(value)
        if value - previous > 7:
            groups.append((start, previous))
            start = value
        previous = value
    groups.append((start, previous))
    groups = [(a, b) for a, b in groups if b - a >= max(3, height * 0.004)]
    if not groups:
        return None
    top_group = max(groups, key=lambda pair: pair[1])
    bottom = top_group[1]
    return {
        "top_pct": round(top_group[0] / height * 100, 3),
        "bottom_pct": round(bottom / height * 100, 3),
        "score": round(float(row_score[top_group[0] : top_group[1] + 1].mean()), 5),
    }


def get_video_duration_seconds(video: Path, ffmpeg: Path) -> float | None:
    result = subprocess.run(
        [str(ffmpeg), "-hide_banner", "-i", str(video)],
        capture_output=True,
        timeout=30,
        check=False,
    )
    text = (result.stderr or b"").decode("utf-8", errors="ignore")
    match = re.search(r"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", text)
    if not match:
        return None
    hours, minutes, seconds = match.groups()
    return int(hours) * 3600 + int(minutes) * 60 + float(seconds)


def detect_text_top_in_frame(frame: Path) -> dict[str, float] | None:
    import cv2
    import numpy as np

    image = cv2.imdecode(np.fromfile(str(frame), dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return None
    height, width = image.shape[:2]
    search_top = int(height * 0.80)
    crop = image[search_top:height, :]
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 80, 180)
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    bright = gray > 185
    yellow = ((hsv[:, :, 0] >= 12) & (hsv[:, :, 0] <= 42) & (hsv[:, :, 1] > 70) & (hsv[:, :, 2] > 120))
    mask = ((edges > 0) & (bright | yellow)).astype("uint8")
    row_score = mask.mean(axis=1)
    if row_score.max() < 0.012:
        return None
    threshold = max(0.012, float(row_score.max()) * 0.34)
    active = np.where(row_score >= threshold)[0]
    if active.size == 0:
        return None
    groups = []
    start = int(active[0])
    previous = int(active[0])
    for value in active[1:]:
        value = int(value)
        if value - previous > 8:
            groups.append((start, previous))
            start = value
        previous = value
    groups.append((start, previous))
    groups = [(a, b) for a, b in groups if b - a >= max(4, height * 0.006)]
    if not groups:
        return None
    lowest_group = max(groups, key=lambda pair: pair[1])
    top = search_top + lowest_group[0]
    bottom = search_top + lowest_group[1]
    return {
        "top_pct": round(top / height * 100, 3),
        "bottom_pct": round(bottom / height * 100, 3),
        "score": round(float(row_score[lowest_group[0] : lowest_group[1] + 1].mean()), 5),
    }


def detect_top_watermark_in_frame(frame: Path) -> dict[str, float] | None:
    import cv2
    import numpy as np

    image = cv2.imdecode(np.fromfile(str(frame), dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return None
    height, width = image.shape[:2]
    search_bottom = int(height * 0.18)
    crop = image[0:search_bottom, :]
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    
    b, g, r = cv2.split(crop)
    
    r_float = r.astype(np.float32)
    g_float = g.astype(np.float32)
    b_float = b.astype(np.float32)
    
    total = r_float + g_float + b_float + 1
    blue_ratio = b_float / total
    red_ratio = r_float / total
    
    color_balance = np.abs(blue_ratio - red_ratio)
    
    smooth_balance = np.convolve(color_balance.mean(axis=1), np.ones(7)/7, mode='same')
    
    avg_balance = smooth_balance.mean()
    std_balance = smooth_balance.std()
    
    if std_balance < 0.005:
        return None
    
    watermark_rows = np.where(smooth_balance < (avg_balance - std_balance * 0.8))[0]
    
    if len(watermark_rows) == 0:
        return None
    
    groups = []
    start = int(watermark_rows[0])
    previous = int(watermark_rows[0])
    for value in watermark_rows[1:]:
        value = int(value)
        if value - previous > 8:
            groups.append((start, previous))
            start = value
        previous = value
    groups.append((start, previous))
    
    groups = [(a, b) for a, b in groups if b - a >= max(5, height * 0.005)]
    
    if not groups:
        return None
    
    lowest_group = max(groups, key=lambda pair: pair[1])
    top = lowest_group[0]
    bottom = lowest_group[1]
    
    padding = int(height * 0.015)
    text_top = max(0, top - padding)
    text_bottom = min(search_bottom, bottom + padding)
    
    balance_diff = avg_balance - smooth_balance[text_top:text_bottom].mean()
    
    if balance_diff < 0.01:
        return None
    
    return {
        "top_pct": round(text_top / height * 100, 3),
        "bottom_pct": round(text_bottom / height * 100, 3),
        "score": round(min(balance_diff * 10, 1.0), 5),
    }


def detect_faint_watermark_in_frame(frame: Path) -> dict[str, float] | None:
    import cv2
    import numpy as np

    image = cv2.imdecode(np.fromfile(str(frame), dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return None
    height, width = image.shape[:2]
    y0 = int(height * 0.05)
    y1 = int(height * 0.55)
    crop = image[y0:y1, :]
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    bg = cv2.GaussianBlur(gray, (0, 0), 9)
    diff = cv2.absdiff(gray, bg)
    mask = (diff > 3).astype("uint8") * 255
    dilate_kernel = cv2.getStructuringElement(
        cv2.MORPH_RECT,
        (max(32, width // 13), max(5, height // 110)),
    )
    mask = cv2.dilate(mask, dilate_kernel, iterations=1)
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    candidates: list[tuple[float, int, int, int, int]] = []
    for contour in contours:
        x, y, box_w, box_h = cv2.boundingRect(contour)
        abs_y = y + y0
        aspect = box_w / max(1, box_h)
        if box_w < width * 0.12 or box_w > width * 0.72:
            continue
        if box_h < height * 0.015 or box_h > height * 0.18:
            continue
        if aspect < 2.0 or aspect > 20.0:
            continue
        if abs_y < height * 0.10 or abs_y > height * 0.48:
            continue
        area_ratio = (box_w * box_h) / max(1, width * height)
        score = min(aspect / 8, 1.0) + area_ratio * 18
        candidates.append((score, x, abs_y, box_w, box_h))
    if not candidates:
        return None
    _, x, y, box_w, box_h = max(candidates, key=lambda entry: entry[0])
    pad_x = int(width * 0.025)
    pad_y = int(height * 0.018)
    left = max(0, x - pad_x)
    top = max(0, y - pad_y)
    right = min(width, x + box_w + pad_x)
    bottom = min(height, y + box_h + pad_y)
    return {
        "left_pct": round(left / width * 100, 3),
        "right_pct": round(right / width * 100, 3),
        "top_pct": round(top / height * 100, 3),
        "bottom_pct": round(bottom / height * 100, 3),
        "score": 0.62,
    }


def detect_black_bars_in_frame(frame: Path) -> dict[str, float] | None:
    import cv2
    import numpy as np

    image = cv2.imdecode(np.fromfile(str(frame), dtype=np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return None
    height, width = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    black_threshold = 22
    margin = 5
    row_avg = gray.mean(axis=1)
    top_bar_end = 0
    for i in range(height):
        if row_avg[i] > black_threshold:
            top_bar_end = i
            break
    bottom_bar_start = height - 1
    for i in range(height - 1, -1, -1):
        if row_avg[i] > black_threshold:
            bottom_bar_start = i
            break
    top_bar_pct = round(top_bar_end / height * 100, 3)
    bottom_bar_pct = round(bottom_bar_start / height * 100, 3)
    if top_bar_pct < 0.5 and bottom_bar_pct > 99.5:
        return None
    return {
        "top_bar_pct": top_bar_pct,
        "bottom_bar_pct": bottom_bar_pct,
        "top_height_pct": round(top_bar_end / height * 100, 3),
        "bottom_height_pct": round((height - bottom_bar_start) / height * 100, 3),
    }


def crop_rect_video(item: LibraryItem, rect: dict[str, float]) -> Path:
    source = Path(item.path)
    if not source.exists():
        raise FileNotFoundError("原文件不存在")
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        raise RuntimeError("找不到 FFmpeg，无法裁剪视频")
    x = min(95.0, max(0.0, rect["x"]))
    y = min(95.0, max(0.0, rect["y"]))
    w = min(100.0 - x, max(5.0, rect["w"]))
    h = min(100.0 - y, max(5.0, rect["h"]))
    output_dir = source.parent / "手动处理"
    output_dir.mkdir(parents=True, exist_ok=True)
    output = unique_path(output_dir / f"{source.stem}__画面裁剪{source.suffix}")
    crop_expr = (
        f"crop="
        f"trunc(iw*{w / 100:.6f}/2)*2:"
        f"trunc(ih*{h / 100:.6f}/2)*2:"
        f"trunc(iw*{x / 100:.6f}/2)*2:"
        f"trunc(ih*{y / 100:.6f}/2)*2"
    )
    temp = output.with_suffix(".tmp.mp4")
    temp.unlink(missing_ok=True)
    result = subprocess.run(
        [
            str(ffmpeg),
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source),
            "-map",
            "0:v:0",
            "-map",
            "0:a?",
            "-vf",
            crop_expr,
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "18",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-movflags",
            "+faststart",
            str(temp),
        ],
        capture_output=True,
        timeout=240,
        check=False,
    )
    if result.returncode != 0 or not temp.exists() or temp.stat().st_size <= 0:
        temp.unlink(missing_ok=True)
        message = result.stderr.decode("utf-8", errors="ignore").strip() if result.stderr else "裁剪失败"
        raise RuntimeError(message or "裁剪失败")
    temp.replace(output)
    return output


def manual_process_video(item: LibraryItem, rect: dict[str, float], start: float, end: float) -> Path:
    source = Path(item.path)
    if not source.exists():
        raise FileNotFoundError("原文件不存在")
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        raise RuntimeError("找不到 FFmpeg，无法输出新素材")
    x = min(95.0, max(0.0, rect["x"]))
    y = min(95.0, max(0.0, rect["y"]))
    w = min(100.0 - x, max(5.0, rect["w"]))
    h = min(100.0 - y, max(5.0, rect["h"]))
    start = max(DEFAULT_HEAD_TRIM_SECONDS, float(start))
    end = max(0.0, float(end))
    duration = get_video_duration_seconds(source, ffmpeg)
    if duration and duration > 0:
        end = min(end or duration, duration)
    if end <= start:
        raise ValueError("结束时间必须大于开始时间")
    keep_duration = end - start
    if keep_duration < 0.2:
        raise ValueError("保留片段太短，至少保留 0.2 秒")
    output_dir = source.parent / "手动处理"
    output_dir.mkdir(parents=True, exist_ok=True)
    time_label = f"{format_time_label(start)}-{format_time_label(end)}"
    output = unique_path(output_dir / f"{source.stem}__裁剪切割_{time_label}{source.suffix}")
    crop_expr = (
        f"crop="
        f"trunc(iw*{w / 100:.6f}/2)*2:"
        f"trunc(ih*{h / 100:.6f}/2)*2:"
        f"trunc(iw*{x / 100:.6f}/2)*2:"
        f"trunc(ih*{y / 100:.6f}/2)*2"
    )
    temp = output.with_suffix(".tmp.mp4")
    temp.unlink(missing_ok=True)
    result = subprocess.run(
        [
            str(ffmpeg),
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            f"{start:.3f}",
            "-i",
            str(source),
            "-t",
            f"{keep_duration:.3f}",
            "-map",
            "0:v:0",
            "-map",
            "0:a?",
            "-vf",
            crop_expr,
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "18",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "160k",
            "-movflags",
            "+faststart",
            str(temp),
        ],
        capture_output=True,
        timeout=240,
        check=False,
    )
    if result.returncode != 0 or not temp.exists() or temp.stat().st_size <= 0:
        temp.unlink(missing_ok=True)
        message = result.stderr.decode("utf-8", errors="ignore").strip() if result.stderr else "输出新素材失败"
        raise RuntimeError(message or "输出新素材失败")
    temp.replace(output)
    return output


def trim_time_video(item: LibraryItem, start: float, end: float) -> Path:
    source = Path(item.path)
    if not source.exists():
        raise FileNotFoundError("原文件不存在")
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        raise RuntimeError("找不到 FFmpeg，无法截取视频")
    start = max(DEFAULT_HEAD_TRIM_SECONDS, float(start))
    end = max(0.0, float(end))
    duration = get_video_duration_seconds(source, ffmpeg)
    if duration and duration > 0:
        end = min(end, duration)
    if end <= start:
        raise ValueError("结束时间必须大于开始时间")
    keep_duration = end - start
    if keep_duration < 0.2:
        raise ValueError("截取片段太短，至少保留 0.2 秒")
    output_dir = source.parent / "手动处理"
    output_dir.mkdir(parents=True, exist_ok=True)
    label = f"{format_time_label(start)}-{format_time_label(end)}"
    output = unique_path(output_dir / f"{source.stem}__时间截取_{label}{source.suffix}")
    temp = output.with_suffix(".tmp.mp4")
    temp.unlink(missing_ok=True)
    result = subprocess.run(
        [
            str(ffmpeg),
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            f"{start:.3f}",
            "-i",
            str(source),
            "-t",
            f"{keep_duration:.3f}",
            "-map",
            "0:v:0",
            "-map",
            "0:a?",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "18",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-movflags",
            "+faststart",
            str(temp),
        ],
        capture_output=True,
        timeout=240,
        check=False,
    )
    if result.returncode != 0 or not temp.exists() or temp.stat().st_size <= 0:
        temp.unlink(missing_ok=True)
        message = result.stderr.decode("utf-8", errors="ignore").strip() if result.stderr else "截取失败"
        raise RuntimeError(message or "截取失败")
    temp.replace(output)
    return output


def format_time_label(seconds: float) -> str:
    total = max(0, int(round(seconds * 100)))
    centiseconds = total % 100
    total_seconds = total // 100
    minutes = total_seconds // 60
    whole_seconds = total_seconds % 60
    return f"{minutes:02d}m{whole_seconds:02d}s{centiseconds:02d}"


def unique_path(path: Path) -> Path:
    if not path.exists():
        return path
    for index in range(1, 1000):
        candidate = path.with_name(f"{path.stem}_{index:02d}{path.suffix}")
        if not candidate.exists():
            return candidate
    raise FileExistsError("同名文件太多，无法生成新文件名")


def get_video_dimensions(video: Path) -> tuple[int, int]:
    try:
        import cv2
        cap = cv2.VideoCapture(str(video))
        try:
            width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
            height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
            if width > 0 and height > 0:
                return width, height
        finally:
            cap.release()
    except Exception:
        pass
    frames = extract_detection_frames(video, stable_id(video))
    if frames:
        try:
            import cv2
            import numpy as np
            image = cv2.imdecode(np.fromfile(str(frames[0]), dtype=np.uint8), cv2.IMREAD_COLOR)
            if image is not None:
                height, width = image.shape[:2]
                return int(width), int(height)
        except Exception:
            pass
    return 1080, 1920


def deep_repair_coords(item: LibraryItem, area: str, width: int, height: int) -> tuple[int, int, int, int, str]:
    area = (area or "auto").lower()
    if area in {"top", "watermark", "顶部", "top_watermark"}:
        return 0, max(1, int(height * 0.32)), 0, width, "top"
    if area in {"full", "all", "整屏", "full_frame"}:
        return 0, height, 0, width, "full"
    if area in {"bottom", "subtitle", "字幕", "底部"}:
        return max(0, int(height * 0.64)), height, 0, width, "bottom"
    try:
        frames = extract_detection_frames(Path(item.path), item.id)
        faint_watermarks = []
        for frame in frames:
            detected_watermark = detect_faint_watermark_in_frame(frame)
            if detected_watermark:
                faint_watermarks.append(detected_watermark)
        if faint_watermarks:
            top_pct = max(0.0, min(float(entry.get("top_pct", 0) or 0) for entry in faint_watermarks) - 1.5)
            bottom_pct = min(100.0, max(float(entry.get("bottom_pct", 0) or 0) for entry in faint_watermarks) + 1.5)
            left_pct = max(0.0, min(float(entry.get("left_pct", 0) or 0) for entry in faint_watermarks) - 1.5)
            right_pct = min(100.0, max(float(entry.get("right_pct", 100) or 100) for entry in faint_watermarks) + 1.5)
            return (
                int(height * top_pct / 100),
                max(1, int(height * bottom_pct / 100)),
                int(width * left_pct / 100),
                max(1, int(width * right_pct / 100)),
                "auto-faint-watermark",
            )
        detected = detect_subtitle_crop_rect(item)
        rect = detected.get("rect", {}) if isinstance(detected, dict) else {}
        confidence = float(detected.get("confidence", 0) or 0) if isinstance(detected, dict) else 0
        keep_bottom_pct = float(rect.get("y", 0) or 0) + float(rect.get("h", 100) or 100)
        details = detected.get("details", {}) if isinstance(detected, dict) else {}
        watermark = details.get("watermark") if isinstance(details, dict) else []
        if confidence >= 0.35 and keep_bottom_pct < 98.5:
            y_min = max(0, int(height * max(0, keep_bottom_pct - 4) / 100))
            return y_min, height, 0, width, "auto-bottom"
        if isinstance(watermark, list) and watermark:
            top_pct = max(0.0, min(float(entry.get("top_pct", 0) or 0) for entry in watermark) - 3)
            bottom_pct = min(100.0, max(float(entry.get("bottom_pct", 18) or 18) for entry in watermark) + 4)
            return int(height * top_pct / 100), max(1, int(height * bottom_pct / 100)), 0, width, "auto-top"
    except Exception:
        pass
    return max(0, int(height * 0.64)), height, 0, width, "auto-bottom-fallback"


def run_deep_repair_item(item_id: str, area: str = "auto", mode: str = "sttn_auto") -> None:
    global BATCH_PROGRESS
    try:
        item = ITEM_BY_ID.get(item_id)
        if not item:
            BATCH_PROGRESS["failed"] += 1
            BATCH_PROGRESS["message"] = "深度修复失败：素材不存在，请刷新索引"
            return
        source = Path(item.path)
        if not source.exists():
            BATCH_PROGRESS["failed"] += 1
            BATCH_PROGRESS["message"] = "深度修复失败：源文件不存在"
            return
        if not VSR_CLEAN_SCRIPT.exists():
            BATCH_PROGRESS["failed"] += 1
            BATCH_PROGRESS["message"] = f"深度修复失败：找不到 VSR 脚本 {VSR_CLEAN_SCRIPT}"
            return
        output_dir = source.parent / "深度修复"
        output_dir.mkdir(parents=True, exist_ok=True)
        output = unique_path(output_dir / f"{source.stem}__深度修复{source.suffix}")
        width, height = get_video_dimensions(source)
        y_min, y_max, x_min, x_max, area_used = deep_repair_coords(item, area, width, height)
        vsr_mode = "sttn-auto" if mode in {"sttn", "sttn_auto", "sttn-auto", "auto"} else "opencv"
        BATCH_PROGRESS["message"] = f"深度修复运行中：{item.name}；区域 {area_used}；这一步可能很慢"
        result = subprocess.run(
            [
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(VSR_CLEAN_SCRIPT),
                "-InputVideo",
                str(source),
                "-OutputVideo",
                str(output),
                "-Mode",
                vsr_mode,
                "-YMin",
                str(y_min),
                "-YMax",
                str(y_max),
                "-XMin",
                str(x_min),
                "-XMax",
                str(x_max),
            ],
            capture_output=True,
            timeout=3600,
            check=False,
        )
        if result.returncode != 0 or not output.exists() or output.stat().st_size <= 0:
            output.unlink(missing_ok=True)
            stderr = result.stderr.decode("utf-8", errors="ignore").strip()
            stdout = result.stdout.decode("utf-8", errors="ignore").strip()
            raise RuntimeError(stderr or stdout or "VSR 深度修复失败")
        BATCH_PROGRESS["success"] += 1
        BATCH_PROGRESS["results"].append({
            "name": item.name,
            "status": "success",
            "reason": f"已输出深度修复副本：{output.name}；区域 {area_used}",
            "output": str(output),
        })
        BATCH_PROGRESS["message"] = f"深度修复完成：{output.name}"
    except Exception as exc:
        BATCH_PROGRESS["failed"] += 1
        BATCH_PROGRESS["results"].append({"name": item_id, "status": "failed", "reason": str(exc)})
        BATCH_PROGRESS["message"] = f"深度修复失败：{exc}"
    finally:
        BATCH_PROGRESS["processed"] = 1
        BATCH_PROGRESS["running"] = False
        scan_library()


def has_cached_or_sidecar_transcript(item: LibraryItem) -> bool:
    if not transcript_supported(item):
        return False
    if transcript_cache_path(item).exists():
        return True
    return any(path.exists() for path in transcript_sidecar_candidates(Path(item.path)))


def transcript_cache_path(item: LibraryItem) -> Path:
    return TRANSCRIPT_CACHE_ROOT / f"{item.id}.json"


def transcript_sidecar_candidates(video: Path) -> list[Path]:
    candidates = [video.with_suffix(".txt")]
    if " - 副本" in video.stem:
        candidates.append(video.with_name(video.stem.replace(" - 副本", "") + ".txt"))
    if "- 副本" in video.stem:
        candidates.append(video.with_name(video.stem.replace("- 副本", "") + ".txt"))
    return list(dict.fromkeys(candidates))


PHRASE_SIMPLIFY_MAP = {
    "團建": "团建",
    "視頻": "视频",
    "語音": "语音",
    "複製": "复制",
    "識別": "识别",
    "標點": "标点",
    "轉文字": "转文字",
    "轉場": "转场",
    "畫面": "画面",
    "鏡頭": "镜头",
    "素材庫": "素材库",
    "遊艇": "游艇",
    "皮劃艇": "皮划艇",
    "燒烤": "烧烤",
    "煙花": "烟花",
    "農家菜": "农家菜",
    "飯桌": "饭桌",
    "騎行": "骑行",
    "環湖": "环湖",
    "風景": "风景",
    "車上": "车上",
    "大巴車": "大巴车",
}

CHAR_SIMPLIFY_MAP = str.maketrans({
    "這": "这", "條": "条", "個": "个", "裡": "里", "裏": "里", "還": "还", "對": "对",
    "開": "开", "關": "关", "後": "后", "會": "会", "來": "来", "說": "说",
    "讓": "让", "給": "给", "聽": "听", "讀": "读", "寫": "写", "網": "网",
    "與": "与", "時": "时", "間": "间", "點": "点", "標": "标", "識": "识",
    "別": "别", "複": "复", "製": "制", "轉": "转", "視": "视", "頻": "频",
    "語": "语", "聲": "声", "檔": "档", "圖": "图", "畫": "画", "鏡": "镜",
    "頭": "头", "庫": "库", "場": "场", "團": "团", "隊": "队", "氣": "气",
    "體": "体", "驗": "验", "項": "项", "劃": "划", "槳": "桨", "遊": "游",
    "環": "环", "騎": "骑", "車": "车", "風": "风", "農": "农", "飯": "饭",
    "魚": "鱼", "燒": "烧", "煙": "烟", "營": "营", "灣": "湾", "島": "岛",
    "鄉": "乡", "樂": "乐", "館": "馆", "門": "门", "線": "线", "兩": "两",
    "壓": "压", "緊": "紧", "從": "从", "過": "过", "覺": "觉", "單": "单",
    "無": "无", "專": "专", "業": "业", "設": "设", "長": "长", "龍": "龙",
    "內": "内", "區": "区",
    "愛": "爱", "辦": "办", "報": "报", "寶": "宝", "備": "备", "筆": "笔",
    "畢": "毕", "邊": "边", "編": "编", "變": "变", "標": "标", "表": "表",
    "別": "别", "病": "病", "並": "并", "部": "部", "參": "参", "產": "产",
    "長": "长", "廠": "厂", "場": "场", "車": "车", "稱": "称", "處": "处",
    "創": "创", "從": "从", "達": "达", "帶": "带", "當": "当", "黨": "党",
    "導": "导", "點": "点", "電": "电", "動": "动", "斷": "断", "對": "对",
    "兒": "儿", "發": "发", "法": "法", "範": "范", "飛": "飞", "費": "费",
    "復": "复", "蓋": "盖", "幹": "干", "個": "个", "給": "给", "構": "构",
    "購": "购", "顧": "顾", "觀": "观", "廣": "广", "規": "规", "歸": "归",
    "過": "过", "國": "国", "海": "海", "號": "号", "合": "合", "後": "后",
    "護": "护", "華": "华", "畫": "画", "話": "话", "歡": "欢", "還": "还",
    "黃": "黄", "會": "会", "機": "机", "積": "积", "極": "极", "計": "计",
    "記": "记", "際": "际", "繼": "继", "堅": "坚", "見": "见", "將": "将",
    "講": "讲", "較": "较", "階": "阶", "節": "节", "結": "结", "緊": "紧",
    "經": "经", "靜": "静", "覺": "觉", "開": "开", "課": "课", "擴": "扩",
    "來": "来", "勞": "劳", "樂": "乐", "離": "离", "歷": "历", "麗": "丽",
    "兩": "两", "聯": "联", "練": "练", "買": "买", "賣": "卖", "滿": "满",
    "門": "门", "夢": "梦", "秘": "秘", "密": "密", "名": "名", "明": "明",
    "內": "内", "腦": "脑", "農": "农", "歐": "欧", "盤": "盘", "盤": "盘",
    "評": "评", "齊": "齐", "氣": "气", "千": "千", "錢": "钱", "強": "强",
    "喬": "乔", "親": "亲", "輕": "轻", "區": "区", "權": "权", "認": "认",
    "讓": "让", "熱": "热", "實": "实", "視": "视", "適": "适", "數": "数",
    "雙": "双", "說": "说", "雖": "虽", "隨": "随", "孫": "孙", "態": "态",
    "體": "体", "聽": "听", "頭": "头", "圖": "图", "團": "团", "脫": "脱",
    "外": "外", "為": "为", "偉": "伟", "衛": "卫", "溫": "温", "問": "问",
    "無": "无", "務": "务", "習": "习", "細": "细", "係": "系", "戲": "戏",
    "顯": "显", "現": "现", "項": "项", "響": "响", "向": "向", "寫": "写",
    "協": "协", "協": "协", "謝": "谢", "興": "兴", "許": "许", "亞": "亚",
    "嚴": "严", "業": "业", "醫": "医", "藝": "艺", "陰": "阴", "陰": "阴",
    "應": "应", "營": "营", "擁": "拥", "永": "永", "用": "用", "優": "优",
    "與": "与", "語": "语", "緣": "缘", "員": "员", "遠": "远", "約": "约",
    "運": "运", "雜": "杂", "責": "责", "則": "则", "擇": "择", "張": "张",
    "這": "这", "針": "针", "爭": "争", "知": "知", "質": "质", "執": "执",
    "職": "职", "紙": "纸", "至": "至", "製": "制", "鐘": "钟", "種": "种",
    "眾": "众", "狀": "状", "準": "准", "資": "资", "自": "自", "總": "总",
    "縱": "纵", "走": "走", "組": "组", "作": "作", "坐": "坐", "做": "做",
})

PUNCTUATION_BREAKERS = (
    "第一天", "第二天", "第三天", "第一天上午", "第一天下午",
    "上午", "中午", "下午", "傍晚", "晚上", "凌晨",
    "最后", "然后", "接着", "再来", "直接", "先", "再", "这条", "这次",
    "适合", "想要", "如果", "比如", "一路", "前半段", "后半段", "到了",
    "可以", "咱们", "我们", "大家", "你们", "他们", "然后就", "然后再",
    "其实", "所以", "但是", "不过", "可是", "然而", "虽然", "因为",
    "现在", "接下来", "最后", "最终", "终于", "结果", "后来", "之后",
    "首先", "其次", "再次", "最后", "第一", "第二", "第三", "第四",
    "刚才", "刚才说", "刚才讲", "刚才看到", "刚才听到",
    "注意", "重点", "关键", "核心", "重要的是", "主要是",
    "然后呢", "然后呢", "然后呢", "然后呢",
    "最后呢", "最后呢", "最后呢", "最后呢",
    "那么", "那么", "那么", "那么",
    "所以呢", "所以呢", "所以呢", "所以呢",
    "你看", "你看", "你看", "你看",
    "你听", "你听", "你听", "你听",
    "大家看", "大家看", "大家看", "大家看",
    "大家听", "大家听", "大家听", "大家听",
)


def simplify_chinese(text: str) -> str:
    try:
        from opencc import OpenCC  # type: ignore
        return OpenCC("t2s").convert(text)
    except Exception:
        simplified = text
        for trad, simp in PHRASE_SIMPLIFY_MAP.items():
            simplified = simplified.replace(trad, simp)
        return simplified.translate(CHAR_SIMPLIFY_MAP)


def normalize_transcript_text(text: str) -> str:
    text = html.unescape(text or "")
    text = simplify_chinese(text)
    text = text.replace("\ufeff", " ")
    text = re.sub(r"\[[^\]]{1,40}\]", " ", text)
    text = re.sub(r"\([^)]{1,40}\)", " ", text)
    text = re.sub(r"https?://\S+", " ", text)
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    text = re.sub(r"\s*([，。！？；：、])\s*", r"\1", text)
    text = text.replace(",", "，").replace("?", "？").replace("!", "！").replace(";", "；").replace(":", "：")
    return text.strip()


def punctuate_transcript_text(text: str) -> str:
    text = normalize_transcript_text(text)
    if not text:
        return ""

    text = re.sub(r"(了|啦|吧|吗|呢|呀|哦|哈|嘛)(?=[一-龥A-Za-z0-9])", r"\1。", text)
    text = re.sub(r"(重点看|状态打开|气氛就来了|氛围感直接到位|参与感太多|可以先放进备选|直接照抄)(?=[一-龥A-Za-z0-9])", r"\1。", text)

    scene_breakers = ("第一天", "第二天", "第三天", "第一天上午", "第一天下午", "上午", "中午", "下午", "傍晚", "晚上", "凌晨", "前半段", "后半段")
    for token in scene_breakers:
        text = re.sub(rf"(?<!^)(?<!\n)(?<!。)(?<!！)(?<!？)(?<!；)({re.escape(token)})", r"。\n\1", text)

    connector_breakers = ("然后", "接着", "再来", "之后", "后来", "最终", "终于", "结果")
    for token in connector_breakers:
        text = re.sub(rf"(?<!^)(?<!\n)(?<!。)(?<!！)(?<!？)(?<!；)({re.escape(token)})", r"。\n\1", text)

    text = re.sub(r"([。！？；])", r"\1\n", text)

    rough_lines = [line.strip(" ，。") for line in re.split(r"\n+", text) if line.strip(" ，。")]
    lines: list[str] = []
    for line in rough_lines:
        while len(line) > 40:
            split_at = max(line.rfind("，", 0, 40), line.rfind("、", 0, 40), line.rfind(" ", 0, 40))
            if split_at < 15:
                split_at = 30
            chunk = line[:split_at].strip(" ，。")
            if chunk:
                lines.append(chunk)
            line = line[split_at:].strip(" ，。")
        if line:
            lines.append(line)

    cleaned: list[str] = []
    for line in lines:
        line = line.strip()
        if not line:
            continue
        if len(line) <= 1:
            continue
        if not re.search(r"[。！？；]$", line):
            line += "。"
        cleaned.append(line)
    return "\n".join(cleaned)


def transcript_for_item(item: LibraryItem) -> dict[str, object]:
    video = Path(item.path)
    for sidecar in transcript_sidecar_candidates(video):
        if sidecar.exists():
            text = punctuate_transcript_text(sidecar.read_text(encoding="utf-8", errors="ignore"))
            if text:
                write_transcript_cache(item, "same_stem_txt", text, sidecar)
                return {"source": "same_stem_txt", "cached": True, "path": str(sidecar), "text": text}

    cache = transcript_cache_path(item)
    if cache.exists():
        payload = json.loads(cache.read_text(encoding="utf-8"))
        text = punctuate_transcript_text(str(payload.get("text") or ""))
        if text and text != str(payload.get("text") or "").strip():
            write_transcript_cache(item, str(payload.get("source", "cache")), text, Path(str(payload.get("path", cache))))
        return {
            "source": payload.get("source", "cache"),
            "cached": True,
            "path": payload.get("path", str(cache)),
            "text": text,
        }

    text = punctuate_transcript_text(transcribe_video_audio(video))
    write_transcript_cache(item, "audio_whisper_tiny", text, cache)
    return {"source": "audio_whisper_tiny", "cached": False, "path": str(cache), "text": text}


def write_transcript_cache(item: LibraryItem, source: str, text: str, source_path: Path) -> None:
    TRANSCRIPT_CACHE_ROOT.mkdir(parents=True, exist_ok=True)
    payload = {
        "id": item.id,
        "name": item.name,
        "video_path": item.path,
        "source": source,
        "path": str(source_path),
        "text": text,
    }
    transcript_cache_path(item).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def transcribe_video_audio(video: Path) -> str:
    global WHISPER_MODEL
    try:
        import whisper
    except Exception as exc:
        raise RuntimeError("本机还没有可用的 Whisper 转文字环境，先放一个同名 txt 或安装 openai-whisper。") from exc
    if WHISPER_MODEL is None:
        WHISPER_MODEL = whisper.load_model("tiny")
    result = WHISPER_MODEL.transcribe(str(video), language="zh", fp16=False, verbose=False)
    return str(result.get("text") or "")


def transcript_sidecar_candidates(video: Path) -> list[Path]:
    candidates = [
        video.with_name(video.stem + ".transcript.txt"),
        video.with_name(video.stem + ".plain.txt"),
        video.with_name(video.stem + "_transcript.txt"),
        video.with_name(video.stem + "_转写.txt"),
        video.with_suffix(".txt"),
    ]
    if " - 副本" in video.stem:
        candidates.append(video.with_name(video.stem.replace(" - 副本", "") + ".txt"))
    if "- 副本" in video.stem:
        candidates.append(video.with_name(video.stem.replace("- 副本", "") + ".txt"))
    return list(dict.fromkeys(candidates))


def looks_like_real_transcript(path: Path, text: str) -> bool:
    if path.name.endswith(".transcript.txt") or path.name.endswith(".plain.txt") or path.name.endswith("_transcript.txt") or path.name.endswith("_转写.txt"):
        return bool((text or "").strip())
    if re.search(r"\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d+)?\s*(?:-->|-)\s*\d{1,2}:\d{2}", text or ""):
        return True
    plain = strip_transcript_timestamps(text)
    stripped_lines = [line.strip().lstrip("\ufeff") for line in plain.splitlines() if line.strip().lstrip("\ufeff")]
    if not stripped_lines:
        return False
    non_hash_lines = [line for line in stripped_lines if not line.startswith("#")]
    if len(non_hash_lines) == 0 and sum(line.count("#") for line in stripped_lines) >= 2:
        return False
    compact = re.sub(r"\s+", "", plain)
    if len(compact) < 60:
        return False
    hash_count = compact.count("#")
    if hash_count >= 3 and len("".join(non_hash_lines)) < 30:
        return False
    return True


def strip_transcript_timestamps(text: str) -> str:
    lines = []
    for raw_line in (text or "").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        line = re.sub(r"^\[?\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d+)?\]?\s*(?:-->|-)\s*\[?\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d+)?\]?\s*", "", line)
        line = re.sub(r"^\[?\d{1,2}:\d{2}(?::\d{2})?(?:[.,]\d+)?\]?\s*", "", line)
        if line:
            lines.append(line)
    return "\n".join(lines)


def write_plain_transcript_sidecar(video: Path, text: str) -> Path | None:
    plain = punctuate_transcript_text(strip_transcript_timestamps(text))
    if not plain:
        return None
    output = video.with_name(video.stem + ".plain.txt")
    output.write_text(plain.strip() + "\n", encoding="utf-8")
    return output


def format_asr_timestamp(seconds: float) -> str:
    total_ms = max(0, int(round(float(seconds) * 1000)))
    ms = total_ms % 1000
    total_seconds = total_ms // 1000
    s = total_seconds % 60
    total_minutes = total_seconds // 60
    m = total_minutes % 60
    h = total_minutes // 60
    return f"{h:02d}:{m:02d}:{s:02d}.{ms:03d}"


def write_timestamped_transcript_sidecar(video: Path, segments: list[dict[str, object]], text: str) -> Path:
    output = video.with_name(video.stem + ".transcript.txt")
    lines = []
    if segments:
        for segment in segments:
            start = format_asr_timestamp(float(segment.get("start", 0) or 0))
            end = format_asr_timestamp(float(segment.get("end", 0) or 0))
            segment_text = punctuate_transcript_text(str(segment.get("text") or "")).replace("\n", " ")
            if segment_text:
                lines.append(f"[{start} --> {end}] {segment_text}")
    else:
        lines.append(punctuate_transcript_text(text))
    output.write_text("\n".join(lines).strip() + "\n", encoding="utf-8")
    write_plain_transcript_sidecar(video, "\n".join(lines) if lines else text)
    return output


def transcribe_video_audio_payload(video: Path) -> dict[str, object]:
    global WHISPER_MODEL
    model_name = os.environ.get("TB_WHISPER_MODEL", "small").strip() or "small"
    engine = os.environ.get("TB_ASR_ENGINE", "faster-whisper").strip().lower() or "faster-whisper"
    if engine in {"faster", "faster-whisper", "faster_whisper"}:
        try:
            from faster_whisper import WhisperModel  # type: ignore
            current_key = ("faster-whisper", model_name)
            if not isinstance(WHISPER_MODEL, tuple) or WHISPER_MODEL[:2] != current_key:
                device = os.environ.get("TB_WHISPER_DEVICE", "cpu")
                compute_type = os.environ.get("TB_WHISPER_COMPUTE_TYPE", "int8")
                WHISPER_MODEL = (*current_key, WhisperModel(model_name, device=device, compute_type=compute_type))
            model = WHISPER_MODEL[2]
            segments_iter, info = model.transcribe(
                str(video),
                language="zh",
                vad_filter=True,
                beam_size=5,
                without_timestamps=False,
            )
            segments = []
            texts = []
            for segment in segments_iter:
                segment_text = str(segment.text or "").strip()
                if not segment_text:
                    continue
                cleaned = punctuate_transcript_text(segment_text)
                segments.append({"start": float(segment.start), "end": float(segment.end), "text": cleaned})
                texts.append(cleaned)
            text = punctuate_transcript_text("\n".join(texts))
            return {
                "engine": "faster-whisper",
                "model": model_name,
                "language": getattr(info, "language", "zh"),
                "duration": getattr(info, "duration", None),
                "segments": segments,
                "text": text,
            }
        except Exception:
            if os.environ.get("TB_ASR_STRICT", "").lower() in {"1", "true", "yes"}:
                raise

    import whisper
    current_key = ("openai-whisper", model_name)
    if not isinstance(WHISPER_MODEL, tuple) or WHISPER_MODEL[:2] != current_key:
        WHISPER_MODEL = (*current_key, whisper.load_model(model_name))
    model = WHISPER_MODEL[2]
    result = model.transcribe(str(video), language="zh", fp16=False, verbose=False)
    segments = [
        {
            "start": float(segment.get("start", 0) or 0),
            "end": float(segment.get("end", 0) or 0),
            "text": punctuate_transcript_text(str(segment.get("text") or "")),
        }
        for segment in result.get("segments", [])
        if str(segment.get("text") or "").strip()
    ]
    text = punctuate_transcript_text(str(result.get("text") or ""))
    return {
        "engine": "openai-whisper",
        "model": model_name,
        "language": result.get("language", "zh"),
        "segments": segments,
        "text": text,
    }


def write_transcript_cache_payload(item: LibraryItem, payload: dict[str, object], source_path: Path) -> None:
    TRANSCRIPT_CACHE_ROOT.mkdir(parents=True, exist_ok=True)
    cache_payload = {
        "id": item.id,
        "name": item.name,
        "video_path": item.path,
        "source": payload.get("source", payload.get("engine", "audio_asr")),
        "path": str(source_path),
        "engine": payload.get("engine"),
        "model": payload.get("model"),
        "language": payload.get("language"),
        "duration": payload.get("duration"),
        "segments": payload.get("segments", []),
        "text": payload.get("text", ""),
    }
    transcript_cache_path(item).write_text(json.dumps(cache_payload, ensure_ascii=False, indent=2), encoding="utf-8")


def transcribe_video_audio(video: Path) -> str:
    return str(transcribe_video_audio_payload(video).get("text") or "")


def transcript_for_item(item: LibraryItem) -> dict[str, object]:
    video = Path(item.path)
    for sidecar in transcript_sidecar_candidates(video):
        if sidecar.exists():
            raw_text = sidecar.read_text(encoding="utf-8", errors="ignore")
            if not looks_like_real_transcript(sidecar, raw_text):
                continue
            text = punctuate_transcript_text(strip_transcript_timestamps(raw_text))
            if text:
                write_transcript_cache(item, "same_stem_txt", text, sidecar)
                return {"source": "same_stem_txt", "cached": True, "path": str(sidecar), "text": text}

    cache = transcript_cache_path(item)
    if cache.exists():
        try:
            payload = json.loads(cache.read_text(encoding="utf-8"))
        except Exception:
            try:
                cache.unlink(missing_ok=True)
            except Exception:
                pass
            payload = None
        if payload:
            text = punctuate_transcript_text(str(payload.get("text") or ""))
            if str(payload.get("source", "")) == "same_stem_txt" and not looks_like_real_transcript(Path(str(payload.get("path", cache))), text):
                text = ""
            if not text:
                try:
                    cache.unlink(missing_ok=True)
                except Exception:
                    pass
            else:
                if text != str(payload.get("text") or "").strip():
                    payload["text"] = text
                    cache.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
                return {
                    "source": payload.get("source", payload.get("engine", "cache")),
                    "cached": True,
                    "path": payload.get("path", str(cache)),
                    "engine": payload.get("engine"),
                    "model": payload.get("model"),
                    "segments": payload.get("segments", []),
                    "text": text,
                }

    payload = transcribe_video_audio_payload(video)
    text = punctuate_transcript_text(str(payload.get("text") or ""))
    payload["text"] = text
    sidecar = write_timestamped_transcript_sidecar(video, list(payload.get("segments", []) or []), text)
    payload["source"] = str(payload.get("engine", "audio_asr"))
    write_transcript_cache_payload(item, payload, sidecar)
    return {
        "source": payload["source"],
        "cached": False,
        "path": str(sidecar),
        "engine": payload.get("engine"),
        "model": payload.get("model"),
        "segments": payload.get("segments", []),
        "text": text,
    }


def has_cached_or_sidecar_transcript(item: LibraryItem) -> bool:
    if not transcript_supported(item):
        return False
    cache = transcript_cache_path(item)
    if cache.exists():
        try:
            payload = json.loads(cache.read_text(encoding="utf-8"))
            source = str(payload.get("source", ""))
            text = str(payload.get("text") or "")
            if source != "same_stem_txt" or looks_like_real_transcript(Path(str(payload.get("path", cache))), text):
                return bool(text.strip())
        except Exception:
            try:
                cache.unlink(missing_ok=True)
            except Exception:
                pass
            return False
    for path in transcript_sidecar_candidates(Path(item.path)):
        if not path.exists():
            continue
        try:
            if looks_like_real_transcript(path, path.read_text(encoding="utf-8", errors="ignore")):
                return True
        except Exception:
            continue
    return False


def make_thumbnail(video: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    if not video.exists():
        return
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        return
    attempts = [
        ["-ss", "00:00:01.000", "-i", str(video)],
        ["-ss", "00:00:00.500", "-i", str(video)],
        ["-ss", "00:00:00.200", "-i", str(video)],
        ["-i", str(video), "-ss", "00:00:00.100"],
        ["-i", str(video)],
    ]
    for input_args in attempts:
        if output.exists() and output.stat().st_size > 0:
            return
        subprocess.run(
            [
                str(ffmpeg),
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                *input_args,
                "-frames:v",
                "1",
                "-vf",
                "scale=360:-1",
                "-q:v",
                "3",
                "-f",
                "image2",
                str(output),
            ],
            capture_output=True,
            timeout=45,
            check=False,
        )
        if output.exists() and output.stat().st_size > 0:
            return
        if output.exists():
            output.unlink(missing_ok=True)


def ensure_preview_video(item: LibraryItem) -> Path | None:
    source = Path(item.path)
    if not source.exists():
        return None
    if not is_video_path(source):
        return None
    PREVIEW_CACHE_ROOT.mkdir(parents=True, exist_ok=True)
    output = PREVIEW_CACHE_ROOT / f"{item.id}.mp4"
    if output.exists() and output.stat().st_size > 0 and output.stat().st_mtime >= source.stat().st_mtime:
        return output
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        return None
    temp = output.with_suffix(".tmp.mp4")
    temp.unlink(missing_ok=True)
    result = subprocess.run(
        [
            str(ffmpeg),
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source),
            "-map",
            "0:v:0",
            "-map",
            "0:a?",
            "-vf",
            "scale='min(720,iw)':-2",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            "25",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            "-b:a",
            "96k",
            "-movflags",
            "+faststart",
            str(temp),
        ],
        capture_output=True,
        timeout=180,
        check=False,
    )
    if result.returncode == 0 and temp.exists() and temp.stat().st_size > 0:
        temp.replace(output)
        return output
    temp.unlink(missing_ok=True)
    return None


def extracted_audio_path(item: LibraryItem) -> Path:
    location = sanitize_filename(item.location or infer_location_from_name(item.name) or "未分地点")
    output_dir = AUDIO_LIBRARY_ROOT / location
    output_dir.mkdir(parents=True, exist_ok=True)
    stem = sanitize_filename(Path(item.path).stem)
    return output_dir / f"{stem}.m4a"


def extract_audio_from_video(item: LibraryItem) -> tuple[str, Path | None]:
    source = Path(item.path)
    if not source.exists():
        return "文件不存在", None
    if not is_video_path(source):
        return "不是视频文件", None
    existing = find_existing_audio_for_item(item)
    if existing:
        return "已有音频素材，跳过", existing
    output = extracted_audio_path(item)
    if output.exists() and output.stat().st_size > 0:
        return "已存在，跳过", output
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        raise FileNotFoundError("找不到 FFmpeg，无法提取音频")
    temp = output.with_suffix(".tmp.m4a")
    temp.unlink(missing_ok=True)
    result = subprocess.run(
        [
            str(ffmpeg),
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source),
            "-vn",
            "-map",
            "0:a:0",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-movflags",
            "+faststart",
            str(temp),
        ],
        capture_output=True,
        text=True,
        timeout=180,
        check=False,
    )
    if result.returncode != 0 or not temp.exists() or temp.stat().st_size <= 0:
        temp.unlink(missing_ok=True)
        reason = (result.stderr or result.stdout or "提取失败，可能没有音轨").strip()
        return reason[:240], None
    temp.replace(output)
    copy_best_sidecar_transcript(source, output)
    return "提取完成", output


def find_existing_audio_for_item(item: LibraryItem) -> Path | None:
    source_stem = Path(item.path).stem
    candidate_roots = [
        AUDIO_LIBRARY_ROOT / sanitize_filename(item.location or infer_location_from_name(item.name) or "未分地点"),
    ]
    if item.location:
        candidate_roots.append(LIBRARY_ROOT / f"{item.location}音频素材库")
    for root in candidate_roots:
        if not root.exists():
            continue
        for audio in root.rglob("*"):
            if not audio.is_file() or audio.suffix.lower() not in AUDIO_EXTENSIONS:
                continue
            stem = audio.stem
            if stem == source_stem or stem.endswith(source_stem) or stem.endswith("_" + source_stem):
                return audio
    return None


def copy_best_sidecar_transcript(source_video: Path, audio_file: Path) -> None:
    for sidecar in transcript_sidecar_candidates(source_video):
        if not sidecar.exists():
            continue
        try:
            raw = sidecar.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue
        if not looks_like_real_transcript(sidecar, raw):
            continue
        text = raw.strip()
        if text:
            audio_file.with_name(audio_file.stem + ".txt").write_text(text, encoding="utf-8")
            write_plain_transcript_sidecar(audio_file, text)
            return


def audio_library_item(audio_file: Path, source_item: LibraryItem) -> LibraryItem:
    location = source_item.location or infer_location_from_name(audio_file.name) or "音频素材"
    keyword = audio_file.parent.name if audio_file.parent.name else "原片音频"
    return LibraryItem(
        id=stable_id(audio_file),
        kind="原片音频素材",
        location=location,
        category="原片音频",
        keyword=keyword,
        name=audio_file.name,
        path=str(audio_file),
        size_mb=round(audio_file.stat().st_size / 1024 / 1024, 2) if audio_file.exists() else 0,
    )


def ensure_audio_transcript(audio_file: Path, source_item: LibraryItem) -> tuple[str, bool]:
    if not audio_file.exists():
        return "音频文件不存在，无法生成文案", False
    audio_item = audio_library_item(audio_file, source_item)
    if has_cached_or_sidecar_transcript(audio_item):
        return "已有有效文案/时间戳，跳过转写", False

    copy_best_sidecar_transcript(Path(source_item.path), audio_file)
    if has_cached_or_sidecar_transcript(audio_item):
        return "已复用原片同名文案", True

    result = transcript_for_item(audio_item)
    text = str(result.get("text") or "").strip()
    if text:
        transcript_path = Path(str(result.get("path") or ""))
        name = transcript_path.name if transcript_path.name else "转写缓存"
        return f"已生成时间戳文案：{name}", True
    return "未识别到有效文案", False


def find_ffmpeg() -> Path | None:
    candidates = [
        Path(r"C:\ffmpeg\bin\ffmpeg.exe"),
        Path(r"D:\Program Files\江湖工具箱\JHlib\ffmpeg\ffmpeg.exe"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return None


def launch_jianghu_tool(target: Path) -> None:
    if JH_TOOLBOX_MAIN.exists():
        try:
            subprocess.Popen([str(JH_TOOLBOX_MAIN)], cwd=str(JH_TOOLBOX_MAIN.parent), close_fds=True)
        except Exception:
            pass
    subprocess.Popen([str(target)], cwd=str(target.parent), close_fds=True)


PROCESSED_RECORDS_PATH = CACHE_ROOT / "cropped_records.json"

def cropped_record_key(item: LibraryItem) -> str:
    return f"{CROP_ALGORITHM_VERSION}:{item.id}"

def load_cropped_records() -> set[str]:
    if not PROCESSED_RECORDS_PATH.exists():
        return set()
    try:
        data = json.loads(PROCESSED_RECORDS_PATH.read_text(encoding="utf-8"))
        if isinstance(data, list):
            return set(data)
    except Exception:
        pass
    return set()

def save_cropped_records(records: set[str]) -> None:
    try:
        PROCESSED_RECORDS_PATH.parent.mkdir(parents=True, exist_ok=True)
        temp_path = PROCESSED_RECORDS_PATH.with_suffix(".tmp")
        temp_path.write_text(json.dumps(sorted(records), ensure_ascii=False, indent=2), encoding="utf-8")
        import os
        os.replace(str(temp_path), str(PROCESSED_RECORDS_PATH))
    except Exception:
        pass

def delete_thumbnail_cache(item_id: str) -> None:
    thumb = CACHE_ROOT / f"{item_id}.jpg"
    thumb.unlink(missing_ok=True)
    preview = PREVIEW_CACHE_ROOT / f"{item_id}.mp4"
    preview.unlink(missing_ok=True)
    detection_dir = CACHE_ROOT / "crop_detection" / item_id
    if detection_dir.exists():
        import shutil
        try:
            shutil.rmtree(str(detection_dir), ignore_errors=True)
        except Exception:
            pass


GENERATED_CROP_FOLDER_NAMES = {"裁切废料", "裁去字幕", "字幕之上", "手动处理", "瀛楀箷涔嬩笂", "鎵嬪姩澶勭悊"}
GENERATED_CROP_FOLDER_NAMES.update({"\u88c1\u5207\u5e9f\u6599", "\u88c1\u53bb\u5b57\u5e55", "\u5b57\u5e55\u4e4b\u4e0a", "\u624b\u52a8\u5904\u7406"})


def is_generated_crop_output(path: Path) -> bool:
    return any(part in GENERATED_CROP_FOLDER_NAMES for part in path.parts)


def run_batch_crop_subtitles(dry_run: bool, confidence_threshold: float, item_ids: list[str] | None = None) -> None:
    import os
    global BATCH_PROGRESS
    try:
        items_to_process = [item for item in ITEMS if item.kind == "分镜素材"]
        if item_ids:
            allowed_ids = set(item_ids)
            items_to_process = [item for item in items_to_process if item.id in allowed_ids]
        items_to_process = [item for item in items_to_process if not is_generated_crop_output(Path(item.path))]
        processed_ids = load_cropped_records()
        items_to_process = [item for item in items_to_process if cropped_record_key(item) not in processed_ids]
        BATCH_PROGRESS["total"] = len(items_to_process)
        BATCH_PROGRESS["message"] = f"找到 {len(items_to_process)} 个未处理的分镜素材，开始处理..."

        for item in items_to_process:
            if not BATCH_PROGRESS["running"]:
                break
            BATCH_PROGRESS["current_item"] = item.name
            BATCH_PROGRESS["message"] = f"正在处理：{item.name}"

            try:
                source = Path(item.path)
                if not source.exists():
                    BATCH_PROGRESS["skipped"] += 1
                    BATCH_PROGRESS["results"].append({"name": item.name, "status": "skipped", "reason": "文件不存在"})
                    BATCH_PROGRESS["processed"] += 1
                    continue

                detection = detect_subtitle_crop_rect(item)
                confidence = float(detection.get("confidence", 0))
                rect = detection.get("rect", {"x": 0, "y": 0, "w": 100, "h": 74})

                if confidence < confidence_threshold:
                    BATCH_PROGRESS["skipped"] += 1
                    BATCH_PROGRESS["results"].append({
                        "name": item.name,
                        "status": "skipped",
                        "reason": f"字幕检测置信度 {round(confidence*100, 1)}% 低于阈值 {round(confidence_threshold*100, 1)}%",
                        "confidence": confidence,
                    })
                    BATCH_PROGRESS["processed"] += 1
                    continue

                if dry_run:
                    BATCH_PROGRESS["success"] += 1
                    BATCH_PROGRESS["results"].append({
                        "name": item.name,
                        "status": "dry_run",
                        "reason": f"置信度 {round(confidence*100, 1)}%，裁剪区域 y={round(rect['y'],1)}% h={round(rect['h'],1)}%",
                        "confidence": confidence,
                        "rect": rect,
                    })
                    BATCH_PROGRESS["processed"] += 1
                    continue

                ffmpeg = find_ffmpeg()
                if not ffmpeg:
                    BATCH_PROGRESS["failed"] += 1
                    BATCH_PROGRESS["results"].append({"name": item.name, "status": "failed", "reason": "找不到 FFmpeg"})
                    BATCH_PROGRESS["processed"] += 1
                    continue

                y_pct = rect.get("y", 0.0)
                h_pct = rect["h"]
                crop_expr = f"crop=iw:trunc(ih*{h_pct / 100:.4f}/2)*2:0:trunc(ih*{y_pct / 100:.4f}/2)*2"
                output_dir = source.parent / "\u88c1\u5207\u5e9f\u6599"
                output_dir.mkdir(parents=True, exist_ok=True)
                output = output_dir / f"{source.stem}__\u88c1\u5207\u5e9f\u6599{source.suffix}"
                temp = output_dir / f".{item.id}.tmp_crop.mp4"
                temp.unlink(missing_ok=True)

                result = subprocess.run(
                    [
                        str(ffmpeg), "-hide_banner", "-loglevel", "error", "-y",
                        "-ss", f"{DEFAULT_HEAD_TRIM_SECONDS:.3f}",
                        "-i", str(source),
                        "-map", "0:v:0", "-map", "0:a?",
                        "-vf", crop_expr,
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart",
                        str(temp),
                    ],
                    capture_output=True, timeout=180, check=False,
                )

                if result.returncode != 0 or not temp.exists() or temp.stat().st_size <= 0:
                    temp.unlink(missing_ok=True)
                    BATCH_PROGRESS["failed"] += 1
                    BATCH_PROGRESS["results"].append({
                        "name": item.name, "status": "failed",
                        "reason": result.stderr.decode("utf-8", errors="ignore").strip()[:200] or "裁剪失败",
                    })
                    BATCH_PROGRESS["processed"] += 1
                    continue

                success = False
                try:
                    temp.replace(output)
                    success = output.exists() and output.stat().st_size > 0
                except Exception:
                    success = False
                if success:
                    BATCH_PROGRESS["success"] += 1
                    processed_ids.add(cropped_record_key(item))
                    save_cropped_records(processed_ids)
                else:
                    temp.unlink(missing_ok=True)
                    BATCH_PROGRESS["failed"] += 1
                    BATCH_PROGRESS["results"].append({
                        "name": item.name, "status": "failed",
                        "reason": "输出新素材失败，原文件未改动",
                    })
                    BATCH_PROGRESS["processed"] += 1
                    continue
                reason = f"裁剪完成，已输出新素材；原分镜未覆盖；保留区域 y={round(y_pct, 1)}% h={round(h_pct, 1)}%"
                if "bars" in detection.get("details", {}):
                    reason += f"（含黑边检测）"
                BATCH_PROGRESS["results"].append({
                    "name": item.name,
                    "status": "success",
                    "reason": reason,
                    "confidence": confidence,
                    "rect": rect,
                    "output": str(output),
                })

            except Exception as exc:
                BATCH_PROGRESS["failed"] += 1
                BATCH_PROGRESS["results"].append({"name": item.name, "status": "failed", "reason": str(exc)})

            BATCH_PROGRESS["processed"] += 1

        BATCH_PROGRESS["message"] = f"批量处理完成：成功 {BATCH_PROGRESS['success']} 个，跳过 {BATCH_PROGRESS['skipped']} 个，失败 {BATCH_PROGRESS['failed']} 个"
    except Exception as exc:
        BATCH_PROGRESS["message"] = f"批量任务异常终止：{str(exc)}"
        BATCH_PROGRESS["failed"] += BATCH_PROGRESS["total"] - BATCH_PROGRESS["processed"]
    finally:
        BATCH_PROGRESS["running"] = False
        scan_library()


def run_batch_transcribe(skip_existing: bool, kind_filter: str) -> None:
    global BATCH_PROGRESS
    try:
        items_to_process = [item for item in ITEMS if transcript_supported(item)]
        if kind_filter:
            items_to_process = [item for item in items_to_process if item.kind == kind_filter]
        if skip_existing:
            items_to_process = [item for item in items_to_process if not has_cached_or_sidecar_transcript(item)]
        BATCH_PROGRESS["total"] = len(items_to_process)
        BATCH_PROGRESS["message"] = f"找到 {len(items_to_process)} 个待识别素材，开始批量语音转文字..."

        for item in items_to_process:
            if not BATCH_PROGRESS["running"]:
                break
            BATCH_PROGRESS["current_item"] = item.name
            BATCH_PROGRESS["message"] = f"正在识别：{item.name}"

            try:
                source = Path(item.path)
                if not source.exists():
                    BATCH_PROGRESS["skipped"] += 1
                    BATCH_PROGRESS["results"].append({"name": item.name, "status": "skipped", "reason": "文件不存在"})
                    BATCH_PROGRESS["processed"] += 1
                    continue

                result = transcript_for_item(item)
                text = result.get("text", "")
                if text and len(text.strip()) > 0:
                    BATCH_PROGRESS["success"] += 1
                    BATCH_PROGRESS["results"].append({
                        "name": item.name,
                        "status": "success",
                        "reason": f"识别完成，来源: {result.get('source', 'unknown')}，字数: {len(text.strip())}",
                        "text_length": len(text.strip()),
                    })
                else:
                    BATCH_PROGRESS["skipped"] += 1
                    BATCH_PROGRESS["results"].append({
                        "name": item.name,
                        "status": "skipped",
                        "reason": "未识别到有效文案",
                    })

            except Exception as exc:
                BATCH_PROGRESS["failed"] += 1
                BATCH_PROGRESS["results"].append({"name": item.name, "status": "failed", "reason": str(exc)})

            BATCH_PROGRESS["processed"] += 1

        BATCH_PROGRESS["message"] = f"批量语音转文字完成：成功 {BATCH_PROGRESS['success']} 个，跳过 {BATCH_PROGRESS['skipped']} 个，失败 {BATCH_PROGRESS['failed']} 个"
    except Exception as exc:
        BATCH_PROGRESS["message"] = f"批量语音转文字任务异常终止：{str(exc)}"
        BATCH_PROGRESS["failed"] += BATCH_PROGRESS["total"] - BATCH_PROGRESS["processed"]
    finally:
        BATCH_PROGRESS["running"] = False
        scan_library()


def run_batch_extract_audio(location_filter: str = "", transcribe: bool = False) -> None:
    global BATCH_PROGRESS
    try:
        items_to_process = [item for item in ITEMS if item.kind == "已整理原片"]
        if location_filter:
            items_to_process = [item for item in items_to_process if item.location == location_filter]
        BATCH_PROGRESS["total"] = len(items_to_process)
        task_name = "提取原片音频和时间戳文案" if transcribe else "提取原片音频"
        BATCH_PROGRESS["message"] = f"找到 {len(items_to_process)} 条已整理原片，开始{task_name}..."

        for item in items_to_process:
            if not BATCH_PROGRESS["running"]:
                break
            BATCH_PROGRESS["current_item"] = item.name
            BATCH_PROGRESS["message"] = f"正在{task_name}：{item.name}"
            try:
                status, output = extract_audio_from_video(item)
                transcript_note = ""
                if output and transcribe:
                    transcript_note, transcript_created = ensure_audio_transcript(output, item)
                    if transcript_created and "提取完成" not in status:
                        status = "文案已生成"
                if output and "提取完成" in status:
                    BATCH_PROGRESS["success"] += 1
                    BATCH_PROGRESS["results"].append({
                        "name": item.name,
                        "status": "success",
                        "reason": f"已输出：{output.name}" + (f"；{transcript_note}" if transcript_note else ""),
                        "output": str(output),
                    })
                elif output and "文案已生成" in status:
                    BATCH_PROGRESS["success"] += 1
                    BATCH_PROGRESS["results"].append({
                        "name": item.name,
                        "status": "success",
                        "reason": transcript_note or f"已补文案：{output.name}",
                        "output": str(output),
                    })
                elif output:
                    BATCH_PROGRESS["skipped"] += 1
                    BATCH_PROGRESS["results"].append({
                        "name": item.name,
                        "status": "skipped",
                        "reason": status + (f"；{transcript_note}" if transcript_note else ""),
                        "output": str(output),
                    })
                else:
                    BATCH_PROGRESS["failed"] += 1
                    BATCH_PROGRESS["results"].append({"name": item.name, "status": "failed", "reason": status})
            except Exception as exc:
                BATCH_PROGRESS["failed"] += 1
                BATCH_PROGRESS["results"].append({"name": item.name, "status": "failed", "reason": str(exc)})
            BATCH_PROGRESS["processed"] += 1

        BATCH_PROGRESS["message"] = f"批量{task_name}完成：成功 {BATCH_PROGRESS['success']} 个，跳过 {BATCH_PROGRESS['skipped']} 个，失败 {BATCH_PROGRESS['failed']} 个"
    except Exception as exc:
        BATCH_PROGRESS["message"] = f"批量提取音频/文案任务异常终止：{str(exc)}"
        BATCH_PROGRESS["failed"] += BATCH_PROGRESS["total"] - BATCH_PROGRESS["processed"]
    finally:
        BATCH_PROGRESS["running"] = False
        scan_library()


MATCH_KEYWORDS: list[tuple[str, tuple[str, ...]]] = [
    ("大巴集合出发", ("大巴", "上车", "出发", "集合", "车上", "抵达")),
    ("千岛湖风景俯拍", ("千岛湖", "风景", "湖面", "航拍", "俯拍", "岛屿", "湖景")),
    ("安吉风景空镜", ("安吉", "山野", "竹海", "风景", "空镜", "山里")),
    ("莫干山风景空镜", ("莫干山", "竹林", "风景", "空镜", "山里")),
    ("民宿酒店", ("民宿", "酒店", "住宿", "房间", "营地", "森林民宿")),
    ("农家菜聚餐", ("农家菜", "吃饭", "聚餐", "鱼宴", "船头鱼", "土鸡汤", "餐桌", "美食", "烤全羊")),
    ("烧烤露营", ("烧烤", "露营", "营地", "天幕", "烤串", "炭火", "户外烧烤")),
    ("篝火烟花", ("篝火", "烟花", "夜场", "火光", "晚上")),
    ("皮划艇", ("皮划艇", "划艇", "桨板", "船桨")),
    ("游艇游湖", ("游艇", "游湖", "船上", "快艇", "湖上")),
    ("摩托艇", ("摩托艇", "水上摩托")),
    ("漂流", ("漂流", "水花", "尖叫", "龙王山")),
    ("溯溪玩水", ("溯溪", "玩水", "打水仗", "溪谷", "峡谷")),
    ("骑行", ("骑行", "环湖骑行", "自行车")),
    ("真人CS", ("真人CS", "镭战", "水弹", "CS")),
    ("团队游戏挑战", ("团队游戏", "挑战", "破冰", "拓展", "分组", "协作", "拔河", "飞盘")),
    ("团队合照", ("合照", "大合影", "拍照", "团建合影")),
    ("人物反应", ("开心", "大笑", "尖叫", "欢呼", "比耶", "氛围")),
    ("收尾返程", ("返程", "结束", "告别", "早餐", "回程")),
]


def match_folder_token(value: str) -> str:
    text = re.sub(r"^\d+[_\-\s]+", "", value or "")
    text = re.sub(r"\.[^.]+$", "", text)
    return re.sub(r"[\s_\\/\-（）()【】\[\]#＃，。,.!！?？|｜·]+", "", text).lower()


def find_match_output_folder(audio_item: LibraryItem) -> Path | None:
    if not SMART_MATCH_PACK_ROOT.exists():
        return None
    audio_stem = Path(audio_item.path).stem
    token = match_folder_token(audio_stem)
    location = audio_item.location or infer_location_from_name(audio_stem)
    scored: list[tuple[int, float, Path]] = []
    for folder in SMART_MATCH_PACK_ROOT.iterdir():
        if not folder.is_dir():
            continue
        name_token = match_folder_token(folder.name)
        score = 0
        if token and (token in name_token or name_token in token):
            score += 100
        elif token and len(token) >= 12 and token[:12] in name_token:
            score += 60
        if location and location in folder.name:
            score += 20
        if (folder / "jianying_pack").exists() or (folder / "02_粗剪成品" / "jianying_pack").exists():
            score += 10
        if (folder / "rough_cut.mp4").exists() or (folder / "02_粗剪成品" / "rough_cut.mp4").exists():
            score += 8
        if score >= 60:
            try:
                mtime = folder.stat().st_mtime
            except OSError:
                mtime = 0
            scored.append((score, mtime, folder))
    if not scored:
        return None
    scored.sort(key=lambda entry: (entry[0], entry[1]), reverse=True)
    return scored[0][2]


def build_audio_match_plan(audio_item: LibraryItem) -> dict[str, object]:
    transcript = transcript_for_item(audio_item)
    beats = transcript_to_beats(transcript)
    if not beats:
        beats = [{"start": 0.0, "end": 4.0, "text": str(transcript.get("text") or audio_item.name)}]
    clip_pool = [
        item for item in ITEMS
        if item.kind == "分镜素材" and (not audio_item.location or item.location == audio_item.location)
    ]
    if not clip_pool:
        clip_pool = [item for item in ITEMS if item.kind == "分镜素材"]
    used: set[str] = set()
    plan = []
    for index, beat in enumerate(beats[:60], 1):
        text = str(beat.get("text") or "").strip()
        need = visual_need_from_text(text)
        candidates = rank_clip_candidates(clip_pool, text, need, used)
        if candidates:
            used.add(candidates[0]["id"])
        plan.append({
            "index": index,
            "start": beat.get("start", 0.0),
            "end": beat.get("end", 0.0),
            "text": text,
            "visual_need": need,
            "candidates": candidates[:6],
        })
    return {
        "audio": public_item(audio_item),
        "transcript_source": transcript.get("source", ""),
        "text": transcript.get("text", ""),
        "beats": plan,
    }


def transcript_to_beats(transcript: dict[str, object]) -> list[dict[str, object]]:
    segments = transcript.get("segments")
    beats: list[dict[str, object]] = []
    if isinstance(segments, list) and segments:
        for segment in segments:
            if not isinstance(segment, dict):
                continue
            text = punctuate_transcript_text(str(segment.get("text") or ""))
            if text:
                beats.append({
                    "start": float(segment.get("start", 0) or 0),
                    "end": float(segment.get("end", 0) or 0),
                    "text": text,
                })
        if beats:
            return beats
    text = punctuate_transcript_text(str(transcript.get("text") or ""))
    parts = [part.strip() for part in re.split(r"[\n。！？!?；;]+", text) if part.strip()]
    cursor = 0.0
    for part in parts:
        duration = max(2.0, min(6.0, len(part) / 5.2))
        beats.append({"start": round(cursor, 2), "end": round(cursor + duration, 2), "text": part})
        cursor += duration
    return beats


def visual_need_from_text(text: str) -> str:
    hay = text.lower()
    for label, tokens in MATCH_KEYWORDS:
        if any(token.lower() in hay for token in tokens):
            return label
    return "氛围补充/转场空镜"


def rank_clip_candidates(pool: list[LibraryItem], text: str, need: str, used: set[str]) -> list[dict[str, object]]:
    scored = []
    hay_text = f"{text} {need}".lower()
    for item in pool:
        hay_item = " ".join([item.name, item.location, item.category, item.keyword, item.path]).lower()
        score = 0
        if need.lower() in hay_item:
            score += 10
        for label, tokens in MATCH_KEYWORDS:
            if label == need:
                for token in tokens:
                    if token.lower() in hay_text and token.lower() in hay_item:
                        score += 6
                    elif token.lower() in hay_item:
                        score += 2
        for token in re.findall(r"[\u4e00-\u9fffA-Za-z0-9]{2,}", text):
            if token.lower() in hay_item:
                score += 3
        if item.id in used:
            score -= 5
        if score <= 0 and need == "氛围补充/转场空镜":
            if any(token in hay_item for token in ("风景", "空镜", "人物反应", "团队互动", "细节")):
                score = 2
        if score > 0:
            scored.append((score, item))
    scored.sort(key=lambda pair: (-pair[0], pair[1].name))
    return [
        {
            **public_item(item),
            "score": score,
            "reason": f"匹配“{need}”，得分 {score}",
        }
        for score, item in scored[:10]
    ]


INDEX_HTML = r"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>团建视频剪辑工作流</title>
  <style>
    :root {
      color-scheme: light;
      --window-top:#e5edf3; --window-bottom:#d1dce5;
      --panel:#e2ebf1; --panel-light:#eef4f8; --line:#c3d0dc;
      --ink:#1c2938; --muted:#606f80; --accent:#307eff; --accent-dark:#195dde;
      --cyan:#41d5cf; --amber:#ecaa1c; --selection:#d7e2ed;
      --shadow: 9px 12px 24px rgba(112,130,150,.24), -7px -7px 18px rgba(255,255,255,.78);
      --soft-shadow: 5px 7px 15px rgba(112,130,150,.18), -5px -5px 13px rgba(255,255,255,.72);
    }
    * { box-sizing: border-box; }
    body { margin:0; font-family:"Microsoft YaHei UI","Microsoft YaHei",system-ui,sans-serif; color:var(--ink); background:linear-gradient(155deg,var(--window-top),var(--window-bottom)); overflow:hidden; }
    header { min-height:70px; display:grid; grid-template-columns:300px 1fr; gap:16px; align-items:center; padding:12px 20px; background:linear-gradient(180deg,rgba(238,244,248,.86),rgba(226,235,241,.72)); position:sticky; top:0; z-index:2; box-shadow:0 1px 0 rgba(255,255,255,.82),0 10px 24px rgba(104,122,142,.10); }
    h1 { font-size:18px; margin:0; font-weight:750; letter-spacing:0; }
    .subtitle { font-size:12px; color:var(--muted); margin-top:4px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    .top-tabs { display:flex; justify-content:center; gap:10px; }
    .tab { min-width:104px; height:38px; border-radius:18px; border:1px solid rgba(255,255,255,.8); background:var(--panel-light); color:var(--ink); box-shadow:var(--soft-shadow); }
    .tab.active { color:#fff; background:linear-gradient(180deg,#428eff,var(--accent)); border-color:rgba(255,255,255,.45); box-shadow:5px 8px 16px rgba(48,126,255,.24),-4px -4px 12px rgba(255,255,255,.55); }
    .root-path { font-size:12px; color:var(--muted); text-align:right; word-break:break-all; }
    .workflow-health { display:none; }
    body[data-view="organize"] header { grid-template-columns:300px 1fr; }
    body[data-view="organize"] .workflow-health { display:none; }
    .health-line { display:flex; flex-wrap:wrap; justify-content:flex-end; gap:5px; font-size:11px; }
    .health-pill { padding:3px 7px; border-radius:999px; background:rgba(238,244,248,.86); border:1px solid rgba(255,255,255,.78); color:#344054; box-shadow:var(--soft-shadow); }
    .health-next { font-size:12px; color:var(--accent-dark); font-weight:650; }
    .layout { display:grid; grid-template-columns: var(--sidebar-width, 260px) 2px minmax(280px, 1.2fr) 2px var(--preview-width, 360px); height: calc(100vh - 70px); min-height:0; gap:6px; padding:16px; }
    aside, .preview, .panel-card { background:var(--panel); border:1px solid rgba(255,255,255,.74); border-radius:22px; padding:16px; overflow:auto; box-shadow:var(--shadow); scrollbar-width:thin; scrollbar-color:rgba(96,111,128,.18) transparent; }
    main, aside, .preview, .panel-card { scrollbar-width:thin; scrollbar-color:rgba(96,111,128,.18) transparent; }
    .preview { scrollbar-width:auto; scrollbar-color:rgba(72,92,116,.46) rgba(220,231,239,.58); padding-right:12px; }
    main::-webkit-scrollbar, aside::-webkit-scrollbar, .preview::-webkit-scrollbar, .panel-card::-webkit-scrollbar { width:9px; height:9px; }
    main::-webkit-scrollbar-track, aside::-webkit-scrollbar-track, .preview::-webkit-scrollbar-track, .panel-card::-webkit-scrollbar-track { background:transparent; }
    main::-webkit-scrollbar-thumb, aside::-webkit-scrollbar-thumb, .preview::-webkit-scrollbar-thumb, .panel-card::-webkit-scrollbar-thumb { border-radius:999px; background:rgba(96,111,128,.18); border:3px solid transparent; background-clip:content-box; }
    main:hover, aside:hover, .preview:hover, .panel-card:hover { scrollbar-color:rgba(96,111,128,.34) transparent; }
    main:hover::-webkit-scrollbar-thumb, aside:hover::-webkit-scrollbar-thumb, .preview:hover::-webkit-scrollbar-thumb, .panel-card:hover::-webkit-scrollbar-thumb { background:rgba(96,111,128,.34); border:2px solid transparent; background-clip:content-box; }
    .preview::-webkit-scrollbar { width:15px; height:15px; }
    .preview::-webkit-scrollbar-track { border-radius:999px; background:rgba(220,231,239,.58); box-shadow:inset 2px 2px 5px rgba(112,130,150,.12), inset -2px -2px 5px rgba(255,255,255,.55); }
    .preview::-webkit-scrollbar-thumb { min-height:52px; border-radius:999px; border:3px solid rgba(220,231,239,.82); background:rgba(72,92,116,.42); background-clip:padding-box; }
    .preview::-webkit-scrollbar-thumb:hover { background:rgba(48,126,255,.58); border-color:rgba(220,231,239,.86); }
    .preview::-webkit-scrollbar-thumb:active { background:rgba(48,126,255,.74); }
    aside, .preview { max-height:calc(100vh - 102px); }
    main { padding:12px; overflow:auto; min-width:0; min-height:0; }
    .pane-resizer { cursor:col-resize; border-radius:999px; background:transparent; position:relative; z-index:4; margin:-8px -4px; touch-action:none; }
    .pane-resizer::before { content:""; position:absolute; inset:0 -12px; border-radius:999px; }
    .pane-resizer::after { content:""; position:absolute; left:50%; top:50%; width:1px; height:42px; transform:translate(-50%,-50%); border-radius:999px; opacity:0; background:linear-gradient(180deg,rgba(48,126,255,0),rgba(48,126,255,.52),rgba(48,126,255,0)); box-shadow:0 0 0 4px rgba(48,126,255,.06); transition:opacity .16s ease, height .16s ease, box-shadow .16s ease; pointer-events:none; }
    .pane-resizer:hover::after, .pane-resizer.is-dragging::after { opacity:.82; height:68px; box-shadow:0 0 0 6px rgba(48,126,255,.10), 0 10px 24px rgba(48,126,255,.16); }
    body.resizer-hint .pane-resizer::after { animation:resizerPulse .95s ease-in-out 1; }
    @keyframes resizerPulse {
      0%,100% { opacity:0; height:38px; }
      50% { opacity:.44; height:62px; box-shadow:0 0 0 5px rgba(48,126,255,.08); }
    }
    body.resizing-pane { cursor:col-resize; user-select:none; }
    label { display:block; font-size:12px; color:var(--muted); margin:14px 0 6px; }
    input, select { width:100%; height:36px; border:1px solid rgba(255,255,255,.82); border-radius:16px; padding:0 12px; background:rgba(238,244,248,.92); color:var(--ink); outline:none; box-shadow:inset 2px 2px 5px rgba(112,130,150,.16), inset -2px -2px 5px rgba(255,255,255,.72); }
    select.native-select { display:none; }
    input:focus { border-color:rgba(48,126,255,.52); border-radius:18px; box-shadow:0 0 0 3px rgba(48,126,255,.10), inset 2px 2px 5px rgba(112,130,150,.14), inset -2px -2px 5px rgba(255,255,255,.72); }
    .select-wrap { position:relative; width:100%; }
    .select-button { width:100%; height:38px; display:flex; align-items:center; justify-content:space-between; gap:10px; border-radius:18px; padding:0 13px 0 16px; border:1px solid rgba(255,255,255,.82); background:rgba(238,244,248,.92); color:var(--ink); box-shadow:inset 2px 2px 5px rgba(112,130,150,.14), inset -2px -2px 5px rgba(255,255,255,.72); }
    .select-button.open { border-color:rgba(48,126,255,.55); box-shadow:0 0 0 4px rgba(48,126,255,.12), inset 2px 2px 5px rgba(112,130,150,.13), inset -2px -2px 5px rgba(255,255,255,.72); }
    .select-value { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .select-arrow { width:0; height:0; border-left:6px solid transparent; border-right:6px solid transparent; border-top:7px solid var(--muted); flex:0 0 auto; }
    .select-menu { position:absolute; left:0; right:0; top:44px; z-index:20; max-height:260px; overflow:auto; padding:6px; border-radius:20px; border:1px solid rgba(255,255,255,.78); background:rgba(238,244,248,.98); box-shadow:12px 16px 30px rgba(112,130,150,.28), -8px -8px 20px rgba(255,255,255,.82); display:none; }
    .select-wrap.open .select-menu { display:block; }
    .select-option { min-height:34px; display:flex; align-items:center; padding:7px 11px; border-radius:14px; color:var(--ink); cursor:pointer; line-height:1.35; }
    .select-option:hover { background:#dce8f5; }
    .select-option.selected { background:linear-gradient(180deg,#428eff,var(--accent)); color:#fff; }
    .filter-note { margin-top:12px; padding:10px 12px; border-radius:16px; background:var(--panel-light); border:1px solid rgba(255,255,255,.72); box-shadow:var(--soft-shadow); color:var(--muted); font-size:12px; line-height:1.55; }
    .filter-note b { color:var(--ink); font-weight:650; }
    .workflow-prompt { margin-top:14px; padding:12px; border-radius:18px; background:linear-gradient(180deg,rgba(238,244,248,.96),rgba(224,235,244,.92)); border:1px solid rgba(255,255,255,.76); box-shadow:var(--soft-shadow); }
    .workflow-prompt strong { display:block; margin-bottom:6px; font-size:13px; }
    .workflow-prompt p { margin:0 0 10px; color:var(--muted); font-size:12px; line-height:1.55; }
    .workflow-prompt button { width:100%; }
    button { height:36px; border:1px solid rgba(255,255,255,.78); border-radius:16px; padding:0 14px; background:var(--panel-light); color:var(--ink); cursor:pointer; box-shadow:var(--soft-shadow); }
    button:hover { background:#f5f9fc; }
    button:disabled { opacity:.55; cursor:not-allowed; box-shadow:none; }
    button.primary { background:linear-gradient(180deg,#428eff,var(--accent)); color:#fff; border-color:rgba(255,255,255,.55); }
    .stats { display:grid; grid-template-columns:1fr 1fr; gap:8px; margin-bottom:10px; }
    .stat { border:1px solid rgba(255,255,255,.72); border-radius:18px; padding:11px; background:var(--panel-light); box-shadow:var(--soft-shadow); }
    .stat strong { display:block; font-size:18px; }
    .sticky-results-head { position:sticky; top:-12px; z-index:12; margin:-12px -12px 10px; padding:12px 12px 10px; background:linear-gradient(180deg,rgba(226,235,241,.96),rgba(226,235,241,.86)); backdrop-filter:blur(10px); border-bottom:1px solid rgba(255,255,255,.62); box-shadow:0 12px 22px rgba(112,130,150,.10); }
    .toolbar { display:flex; align-items:center; justify-content:space-between; margin-bottom:0; gap:10px; }
    .toolbar-actions { display:flex; align-items:center; gap:8px; flex-wrap:wrap; justify-content:flex-end; }
    #prev, #next { display:none; }
    .sort-select { height:36px; min-width:132px; border:1px solid rgba(255,255,255,.78); border-radius:16px; padding:0 34px 0 12px; color:var(--ink); background:var(--panel-light); box-shadow:var(--soft-shadow); outline:none; }
    .grid { display:grid; grid-template-columns: repeat(auto-fill, minmax(112px, 1fr)); gap:8px; align-items:start; }
    .card { position:relative; background:var(--panel-light); border:1px solid rgba(255,255,255,.76); border-radius:14px; overflow:hidden; cursor:grab; box-shadow:3px 4px 10px rgba(112,130,150,.16), -3px -3px 9px rgba(255,255,255,.70); }
    .card:active { cursor:grabbing; }
    .card.selected { outline:2px solid var(--accent); background:#f6faff; }
    .thumb { width:100%; aspect-ratio:9/13; background:#e5e7eb; object-fit:cover; display:block; }
    .card-more { position:absolute; right:6px; bottom:6px; width:26px; height:26px; min-width:26px; padding:0; border-radius:13px; font-size:18px; line-height:20px; background:rgba(238,244,248,.92); color:#344054; box-shadow:2px 3px 8px rgba(68,82,98,.20), -2px -2px 7px rgba(255,255,255,.72); opacity:.82; }
    .card-more:hover { opacity:1; background:#f8fbfd; }
    .meta { padding:5px 6px 6px; }
    .name { font-size:11px; min-height:28px; height:auto; overflow:visible; line-height:14px; word-break:break-word; }
    .tags { display:flex; flex-wrap:wrap; gap:4px; margin-top:5px; overflow:visible; }
    .tag { font-size:10px; line-height:1.25; color:#344054; background:#dbe8f4; border:1px solid rgba(255,255,255,.7); border-radius:999px; padding:2px 5px; max-width:100%; overflow:visible; text-overflow:clip; white-space:normal; word-break:keep-all; }
    video { width:100%; max-height:36vh; background:#000; border-radius:18px; box-shadow:var(--soft-shadow); display:block; }
    .path { font-size:12px; color:var(--muted); word-break:break-all; line-height:1.55; margin-top:10px; }
    .preview-actions { display:flex; flex-wrap:wrap; gap:8px; margin-top:8px; }
    .context-menu { position:fixed; z-index:50; min-width:168px; display:none; padding:6px; border-radius:16px; background:rgba(238,244,248,.98); border:1px solid rgba(255,255,255,.78); box-shadow:12px 16px 30px rgba(112,130,150,.28), -8px -8px 20px rgba(255,255,255,.82); }
    .context-menu.open { display:block; }
    .context-menu button { width:100%; height:32px; display:block; text-align:left; box-shadow:none; border:0; border-radius:12px; background:transparent; padding:0 10px; }
    .context-menu button:hover { background:#dce8f5; }
    .context-menu button.danger { color:#b42318; }
    .modal-backdrop { position:fixed; inset:0; z-index:70; display:none; align-items:center; justify-content:center; padding:24px; background:rgba(40,52,66,.24); backdrop-filter:blur(5px); }
    .modal-backdrop.open { display:flex; }
    .modal-card { width:min(460px, 92vw); border-radius:24px; padding:18px; background:var(--panel); border:1px solid rgba(255,255,255,.78); box-shadow:16px 22px 48px rgba(68,82,98,.32), -10px -10px 24px rgba(255,255,255,.75); }
    .modal-title { margin:0; font-size:17px; font-weight:750; }
    .modal-body { margin:10px 0 12px; color:var(--muted); font-size:13px; line-height:1.55; word-break:break-word; }
    .modal-input { width:100%; height:40px; margin:4px 0 10px; }
    .modal-actions { display:flex; justify-content:flex-end; gap:8px; margin-top:10px; }
    .crop-backdrop { position:fixed; inset:0; z-index:68; display:none; align-items:center; justify-content:center; padding:18px; background:rgba(40,52,66,.28); backdrop-filter:blur(6px); }
    .crop-backdrop.open { display:flex; }
    .crop-card { width:min(980px, 96vw); max-height:96vh; overflow:auto; display:grid; grid-template-rows:auto minmax(220px, auto) auto auto; gap:12px; border-radius:24px; padding:16px; background:var(--panel); border:1px solid rgba(255,255,255,.78); box-shadow:16px 22px 48px rgba(68,82,98,.32), -10px -10px 24px rgba(255,255,255,.75); }
    .crop-head { display:flex; align-items:flex-start; justify-content:space-between; gap:14px; }
    .crop-head h3 { margin:0; font-size:17px; }
    .crop-tip { margin:5px 0 0; color:var(--muted); font-size:12px; line-height:1.45; }
    .crop-stage { position:relative; min-height:220px; height:min(54vh, 560px); border-radius:18px; overflow:hidden; background:#111827; display:flex; align-items:center; justify-content:center; box-shadow:inset 4px 4px 10px rgba(0,0,0,.22), inset -3px -3px 9px rgba(255,255,255,.08); }
    .crop-stage video { width:100%; height:100%; max-width:100%; max-height:100%; object-fit:contain; border-radius:0; box-shadow:none; display:block; }
    .crop-layer { position:absolute; pointer-events:none; border:2px solid rgba(48,126,255,.95); background:rgba(48,126,255,.08); box-shadow:0 0 0 9999px rgba(0,0,0,.42), 0 0 0 1px rgba(255,255,255,.45) inset; cursor:move; }
    .crop-layer.ready { pointer-events:auto; }
    .crop-handle { position:absolute; width:14px; height:14px; border-radius:50%; background:#fff; border:2px solid var(--accent); box-shadow:0 3px 9px rgba(0,0,0,.22); }
    .crop-handle.nw { left:-8px; top:-8px; cursor:nwse-resize; }
    .crop-handle.ne { right:-8px; top:-8px; cursor:nesw-resize; }
    .crop-handle.sw { left:-8px; bottom:-8px; cursor:nesw-resize; }
    .crop-handle.se { right:-8px; bottom:-8px; cursor:nwse-resize; }
    .crop-edge { position:absolute; border-radius:999px; background:rgba(255,255,255,.92); border:1px solid rgba(48,126,255,.82); box-shadow:0 3px 9px rgba(0,0,0,.16); opacity:.88; }
    .crop-edge.n { left:24px; right:24px; top:-6px; height:12px; cursor:ns-resize; }
    .crop-edge.s { left:24px; right:24px; bottom:-6px; height:12px; cursor:ns-resize; }
    .crop-edge.w { top:24px; bottom:24px; left:-6px; width:12px; cursor:ew-resize; }
    .crop-edge.e { top:24px; bottom:24px; right:-6px; width:12px; cursor:ew-resize; }
    .crop-toolbar { display:grid; grid-template-columns:minmax(250px, 1fr) auto; align-items:end; gap:10px; }
    .crop-presets, .crop-actions, .crop-layout-row { display:flex; flex-wrap:wrap; gap:8px; align-items:center; }
    .crop-layout-row { margin-top:8px; }
    .crop-layout-row select { width:min(280px, 48vw); height:34px; border-radius:15px; }
    .crop-detect-status { min-height:18px; margin-top:7px; color:var(--muted); font-size:12px; line-height:1.45; }
    .crop-readout { color:var(--muted); font-size:12px; }
    .trim-stage { display:grid; grid-template-columns:minmax(220px, 360px) minmax(260px, 1fr); gap:14px; align-items:center; min-height:280px; }
    .trim-stage video { width:100%; max-height:58vh; border-radius:18px; background:#101827; box-shadow:inset 4px 4px 10px rgba(0,0,0,.22); }
    .trim-panel { display:flex; flex-direction:column; gap:12px; margin-top:4px; padding-top:4px; position:relative; z-index:2; }
    .trim-timeline { position:relative; height:48px; border-radius:18px; background:linear-gradient(180deg, rgba(255,255,255,.56), rgba(232,240,248,.76)); box-shadow:inset 5px 5px 12px rgba(119,137,156,.18), inset -5px -5px 12px rgba(255,255,255,.8); touch-action:none; }
    .trim-selection { position:absolute; top:10px; bottom:10px; border-radius:14px; background:linear-gradient(135deg, rgba(48,126,255,.34), rgba(30,190,145,.28)); border:1px solid rgba(48,126,255,.54); }
    .trim-handle { position:absolute; top:4px; width:18px; height:40px; margin-left:-9px; border-radius:12px; background:#fff; border:1px solid rgba(48,126,255,.72); box-shadow:0 7px 16px rgba(54,78,107,.22); cursor:ew-resize; }
    .trim-handle::after { content:""; position:absolute; left:7px; top:10px; width:3px; height:18px; border-radius:99px; background:rgba(48,126,255,.72); }
    .trim-row { display:flex; flex-wrap:wrap; gap:8px; align-items:center; }
    .trim-time-box { min-width:86px; padding:8px 10px; border-radius:15px; background:rgba(255,255,255,.48); color:var(--text); font-size:13px; box-shadow:inset 3px 3px 8px rgba(119,137,156,.12), inset -3px -3px 8px rgba(255,255,255,.7); }
    .trim-tip { color:var(--muted); font-size:12px; line-height:1.55; }
    .pager { display:flex; gap:8px; align-items:center; justify-content:center; margin:16px 0 8px; min-height:38px; color:var(--muted); font-size:12px; }
    .empty { padding:40px; text-align:center; color:var(--muted); }
    .view-panel { display:none; }
    .view-panel.active { display:block; }
    body[data-view="collect"] .layout, body[data-view="match"] .layout { grid-template-columns:minmax(320px, 1fr); }
    body[data-view="collect"] aside, body[data-view="match"] aside,
    body[data-view="collect"] .sidebar-resizer, body[data-view="match"] .sidebar-resizer,
    body[data-view="collect"] .preview-resizer, body[data-view="match"] .preview-resizer,
    body[data-view="collect"] .preview, body[data-view="match"] .preview { display:none; }
    body[data-view="delivery"] .layout { grid-template-columns:minmax(320px, 1fr) 2px var(--preview-width, 430px); }
    body[data-view="delivery"] aside, body[data-view="delivery"] .sidebar-resizer { display:none; }
    .workflow-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(260px,1fr)); gap:14px; }
    .workflow-step { position:relative; min-height:148px; }
    .step-no { width:32px; height:32px; border-radius:16px; background:linear-gradient(180deg,#428eff,var(--accent)); color:#fff; display:flex; align-items:center; justify-content:center; font-weight:750; box-shadow:var(--soft-shadow); }
    .workflow-step h3 { margin:12px 0 8px; font-size:16px; }
    .workflow-step p { margin:0; color:var(--muted); font-size:13px; line-height:1.55; }
    .workflow-code { margin-top:12px; padding:10px; border-radius:14px; background:rgba(210,223,235,.72); color:#344054; font-size:11px; line-height:1.5; word-break:break-all; box-shadow:inset 2px 2px 5px rgba(112,130,150,.12), inset -2px -2px 5px rgba(255,255,255,.68); }
    .action-row { display:flex; flex-wrap:wrap; gap:8px; margin-top:14px; }
    .process-dashboard { display:grid; grid-template-columns:minmax(150px,.78fr) minmax(0,1.55fr); gap:8px; align-items:center; margin-bottom:8px; padding:9px 10px; background:var(--panel); border:1px solid rgba(255,255,255,.74); border-radius:20px; box-shadow:var(--shadow); }
    .process-panel { min-width:0; }
    .process-panel h3 { margin:0 0 5px; font-size:13px; line-height:1.2; }
    .process-panel p { margin:0; color:var(--muted); font-size:11px; line-height:1.35; }
    .queue-status { display:flex; align-items:center; gap:7px; margin-bottom:4px; font-size:12px; color:var(--muted); }
    .queue-dot { width:10px; height:10px; border-radius:50%; background:#22c55e; box-shadow:0 0 0 5px rgba(34,197,94,.12); }
    .queue-dot.busy { background:#307eff; box-shadow:0 0 0 5px rgba(48,126,255,.13); animation:queuePulse 1s ease-in-out infinite; }
    @keyframes queuePulse {
      0%,100% { transform:scale(.86); box-shadow:0 0 0 4px rgba(48,126,255,.10),0 0 0 0 rgba(48,126,255,.32); }
      50% { transform:scale(1.18); box-shadow:0 0 0 6px rgba(48,126,255,.16),0 0 0 11px rgba(48,126,255,.06); }
    }
    .queue-current { display:-webkit-box; -webkit-line-clamp:1; -webkit-box-orient:vertical; overflow:hidden; min-height:16px; }
    .queue-progress-wrap { display:none; }
    .queue-progress { position:relative; flex:1; height:7px; border-radius:999px; overflow:hidden; background:rgba(255,255,255,.42); box-shadow:inset 2px 2px 5px rgba(112,130,150,.16), inset -2px -2px 5px rgba(255,255,255,.7); }
    .queue-progress-fill { position:absolute; inset:0 auto 0 0; width:0%; border-radius:999px; background:linear-gradient(90deg,#307eff,#41d5cf); box-shadow:0 0 12px rgba(48,126,255,.24); transition:width .25s ease; }
    .queue-percent { min-width:38px; text-align:left; color:var(--accent-dark); font-weight:800; font-size:12px; }
    .process-panel .action-row { margin-top:6px; }
    .process-panel button { height:28px; padding:0 10px; border-radius:14px; font-size:12px; }
    .stage-strip { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:4px; min-width:0; }
    .stage-pill { min-height:34px; min-width:0; border-radius:14px; padding:5px 4px; text-align:center; background:var(--panel-light); border:1px solid rgba(255,255,255,.72); box-shadow:var(--soft-shadow); }
    .stage-pill b { display:block; font-size:15px; line-height:1; }
    .stage-pill span { display:block; margin-top:3px; font-size:10px; color:var(--muted); line-height:1.1; white-space:nowrap; }
    .quality-grid { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:5px; margin-top:4px; }
    .quality-cell { border-radius:14px; padding:6px 5px; text-align:center; background:var(--panel-light); border:1px solid rgba(255,255,255,.72); box-shadow:var(--soft-shadow); }
    .quality-cell b { display:block; font-size:15px; line-height:1; }
    .quality-cell span { font-size:10px; color:var(--muted); }
    .batch-action-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(190px,1fr)); gap:12px; padding:4px; }
    .batch-action-card { text-align:left; height:auto; min-height:156px; padding:14px; border-radius:20px; background:var(--panel-light); border:1px solid rgba(255,255,255,.78); box-shadow:var(--soft-shadow); display:flex; flex-direction:column; gap:8px; }
    .batch-action-card h4 { margin:0; font-size:15px; }
    .batch-action-card p { margin:0; color:var(--muted); font-size:12px; line-height:1.5; }
    .batch-action-card .action-row { margin-top:auto; }
    .batch-action-card button { height:34px; }
    .batch-badge { width:max-content; padding:3px 8px; border-radius:999px; font-size:11px; color:#1d4ed8; background:rgba(48,126,255,.12); border:1px solid rgba(48,126,255,.18); }
    body[data-view="match"] header { min-height:64px; grid-template-columns:300px 1fr; }
    body[data-view="match"] .workflow-health { display:none; }
    body[data-view="match"] main { padding:10px 12px; overflow:hidden; }
    .edit-workbench { height:calc(100vh - 92px); min-height:0; display:grid; grid-template-columns:minmax(420px,1fr) minmax(330px,430px); grid-template-rows:minmax(0,1fr); grid-template-areas:"shelf preview"; gap:12px; overflow:hidden; }
    .edit-panel { background:var(--panel); border:1px solid rgba(255,255,255,.74); border-radius:22px; padding:12px; box-shadow:var(--shadow); overflow:auto; min-width:0; min-height:0; }
    .edit-panel-head { display:flex; align-items:flex-start; justify-content:space-between; gap:10px; margin-bottom:8px; }
    .edit-panel-head h3 { margin:0 0 4px; font-size:16px; }
    .edit-panel-head p { margin:0; color:var(--muted); font-size:12px; line-height:1.4; }
    .audio-bin { grid-area:audio; display:flex; flex-direction:column; gap:10px; min-height:0; }
    .clip-bin { grid-area:shelf; display:flex; flex-direction:column; overflow:hidden; }
    .clip-shelf { display:grid; grid-template-columns:repeat(auto-fill,minmax(112px,1fr)); gap:9px; overflow:auto; min-height:0; padding:2px 2px 8px; align-content:start; }
    .clip-mini { min-height:0; border:1px solid rgba(255,255,255,.74); border-radius:14px; overflow:hidden; background:var(--panel-light); cursor:pointer; box-shadow:var(--soft-shadow); }
    .clip-mini.selected { outline:2px solid var(--accent); background:#f6faff; }
    .clip-mini img { width:100%; aspect-ratio:9/13; object-fit:cover; display:block; background:#dfe8f0; }
    .clip-mini div { padding:6px 7px; font-size:11px; line-height:1.25; word-break:break-word; }
    .audio-list { display:grid; gap:8px; overflow:auto; min-height:0; }
    .audio-item { text-align:left; height:auto; min-height:42px; border-radius:15px; padding:8px 10px; line-height:1.35; overflow:hidden; }
    .audio-item.active { background:linear-gradient(180deg,#428eff,var(--accent)); color:#fff; }
    .audio-item .audio-title { display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; font-size:12px; }
    .audio-item small { display:block; opacity:.78; margin-top:3px; }
    .editor-preview { grid-area:preview; display:flex; flex-direction:column; gap:12px; min-height:0; overflow:auto; }
    .canvas-frame { width:100%; aspect-ratio:3/4; margin:0 auto; border-radius:20px; background:#0f172a; display:flex; align-items:center; justify-content:center; overflow:hidden; box-shadow:inset 5px 7px 18px rgba(0,0,0,.38), var(--soft-shadow); }
    .canvas-frame video, .canvas-frame audio { width:100%; max-height:100%; border-radius:0; box-shadow:none; }
    .match-info { display:flex; flex-direction:column; gap:10px; min-width:0; overflow:auto; }
    .match-copy { color:var(--muted); font-size:13px; line-height:1.55; white-space:pre-wrap; }
    .timeline-panel { display:none; }
    .timeline-head { display:flex; justify-content:space-between; align-items:center; gap:10px; margin-bottom:8px; }
    .timeline-head strong { font-size:15px; }
    .timeline-actions { display:flex; flex-wrap:wrap; gap:6px; justify-content:flex-end; }
    .timeline-actions button { height:30px; padding:0 10px; border-radius:14px; font-size:12px; }
    .track-stack { display:grid; gap:8px; min-height:158px; }
    .track-row { display:grid; grid-template-columns:68px minmax(0,1fr); gap:8px; align-items:stretch; }
    .track-label { display:flex; align-items:center; justify-content:center; border-radius:14px; color:var(--muted); font-size:12px; background:rgba(255,255,255,.42); box-shadow:inset 2px 2px 5px rgba(112,130,150,.12), inset -2px -2px 5px rgba(255,255,255,.68); }
    .timeline { display:flex; gap:8px; min-height:84px; overflow:auto; padding:8px; border-radius:16px; background:rgba(210,223,235,.46); box-shadow:inset 3px 4px 8px rgba(112,130,150,.16), inset -3px -3px 8px rgba(255,255,255,.62); }
    .timeline.is-drop { outline:2px solid rgba(48,126,255,.45); outline-offset:2px; background:rgba(48,126,255,.09); }
    .audio-track { min-height:50px; align-items:center; }
    .audio-track-chip { max-width:100%; padding:9px 12px; border-radius:15px; background:linear-gradient(135deg,rgba(48,126,255,.18),rgba(30,190,145,.14)); border:1px solid rgba(48,126,255,.18); color:var(--text); font-size:12px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    .track-clip { flex:0 0 164px; min-height:68px; border-radius:16px; padding:8px; background:var(--panel-light); border:1px solid rgba(255,255,255,.72); box-shadow:var(--soft-shadow); cursor:pointer; position:relative; display:flex; flex-direction:column; gap:4px; }
    .track-clip.selected { border-color:rgba(48,126,255,.78); box-shadow:0 0 0 2px rgba(48,126,255,.18), var(--soft-shadow); }
    .track-clip strong { font-size:12px; color:var(--accent-dark); white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
    .track-clip span { font-size:11px; color:var(--muted); line-height:1.28; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }
    .track-clip small { margin-top:auto; font-size:10px; color:var(--muted); }
    .beat-card { flex:0 0 220px; border-radius:17px; padding:10px; background:var(--panel-light); border:1px solid rgba(255,255,255,.72); box-shadow:var(--soft-shadow); font-size:12px; }
    .beat-card strong { display:block; font-size:12px; margin-bottom:5px; color:var(--accent-dark); }
    .beat-card p { margin:4px 0; color:var(--muted); line-height:1.35; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }
    .beat-thumb { width:100%; aspect-ratio:16/9; object-fit:cover; border-radius:12px; background:#dfe8f0; margin-top:6px; }
    .mini-list { display:grid; gap:9px; margin-top:12px; }
    .mini-item { padding:10px 12px; border-radius:16px; background:var(--panel-light); border:1px solid rgba(255,255,255,.72); box-shadow:var(--soft-shadow); }
    .mini-item strong { display:block; font-size:13px; margin-bottom:3px; }
    .mini-item span { color:var(--muted); font-size:12px; }
    .right-title { margin:0 0 10px; font-size:16px; }
    .right-section { margin-bottom:16px; }
    @media (max-width: 760px) {
      .layout { grid-template-columns: 1fr; }
      .pane-resizer { display:none; }
      .process-dashboard { grid-template-columns:1fr; }
      .stage-strip { grid-template-columns:repeat(2,1fr); }
      aside { border-right:0; border-bottom:1px solid var(--line); }
      .preview { border-left:0; border-top:1px solid var(--line); min-height:280px; }
      video { max-height:45vh; }
    }
  </style>
</head>
<body data-view="organize">
<header>
  <div>
    <h1>团建视频剪辑工作流</h1>
    <div class="subtitle">采集素材、分镜分类、配镜粗剪、自检交付</div>
  </div>
  <nav class="top-tabs">
    <button class="tab" data-view="collect">采集入库</button>
    <button class="tab active" data-view="organize">素材整理</button>
    <button class="tab" data-view="match">智能剪辑</button>
    <button class="tab" data-view="delivery">成品检查</button>
  </nav>
  <div class="workflow-health">
    <div class="health-line" id="workflowHealth"></div>
    <div class="health-next" id="workflowNext">读取素材状态...</div>
    <div class="root-path" id="rootPath"></div>
  </div>
</header>
<div class="layout">
  <aside>
    <div class="stats">
      <div class="stat"><span>总素材</span><strong id="total">0</strong></div>
      <div class="stat"><span>当前结果</span><strong id="resultCount">0</strong></div>
    </div>
    <label>搜索</label>
    <input id="q" placeholder="鱼头 / 烧烤 / 莫干山 / XHS..." />
    <label>素材类型</label><select id="kind" class="native-select"><option value="">全部</option></select>
    <label>地点</label><select id="location" class="native-select"><option value="">全部</option></select>
    <label>一级分类</label><select id="category" class="native-select"><option value="">全部</option></select>
    <label id="keywordLabel">具体场景/素材组</label><select id="keyword" class="native-select"><option value="">全部</option></select>
    <div class="filter-note" id="filterNote"><b>筛选层级：</b>素材类型 → 地点 → 一级分类 → 具体场景/素材组。</div>
    <div style="display:flex; gap:8px; margin-top:16px;">
      <button id="reset">重置筛选</button>
    </div>
    <div class="workflow-prompt">
      <strong>当前工作流提示词</strong>
      <p>素材初加工：抽取 3 帧判断底部字幕顶部，裁掉字幕以下生成新素材，不覆盖原片；必要时再分镜、视觉优先识别关键词、写入记录并归入智能镜头分类。</p>
      <button id="copyWorkflowPrompt">复制给其他工具</button>
    </div>
    <div class="workflow-prompt">
      <strong>江湖工具箱联动</strong>
      <p>在「采集入库」页面点击工具按钮，可一键启动抖音/小红书/快手/B站采集工具，搜索"团建"等关键词下载作品，配置下载路径到待分类库即可自动入库。</p>
    </div>
  </aside>
  <div class="pane-resizer sidebar-resizer" id="sidebarResizer" title="拖动调整筛选栏宽度"></div>
  <main>
      <div id="collectView" class="view-panel">
        <div class="workflow-grid">
          <div class="panel-card workflow-step"><div class="step-no">1</div><h3>采集入库</h3><p>把新下载的原视频和同名文案先放进待分类整理库。预览、筛选、判断统一去“素材整理”里看。</p><div class="action-row"><button class="primary" data-open="inbox">打开待分类库</button><button data-open="library">打开团建视频库</button></div></div>
          <div class="panel-card workflow-step"><div class="step-no">2</div><h3>按地点归档</h3><p>千岛湖、安吉、莫干山等地点分别进入对应原视频素材库。原下载目录不再留重复副本。</p><div class="action-row"><button data-open="records">打开采集记录</button></div></div>
          <div class="panel-card workflow-step"><div class="step-no">3</div><h3>刷新素材索引</h3><p>采集或移动文件后重新扫描本地素材库，不需要重启工具。</p><div class="action-row"><button id="rescanLibraryBtn">重新扫描素材库</button></div></div>
          <div class="panel-card workflow-step"><div class="step-no">4</div><h3>江湖工具箱联动</h3><p>一键启动采集工具，搜索关键词下载作品，自动进入待分类库。</p><div class="action-row"><button data-open="jianghu_xhs">提取小红书作品</button><button data-open="jianghu_dy">提取抖音作品</button></div><div class="action-row" style="margin-top:8px;"><button data-open="jianghu_ks">提取快手作品</button><button data-open="jianghu_bili">提取B站作品</button></div><div class="action-row" style="margin-top:8px;"><button data-open="jianghu_dy_live">抖音直播回放</button><button data-open="jianghu_transcribe">批量语音转写</button></div><div class="action-row" style="margin-top:8px;"><button data-open="jianghu_segment">批量视频分割</button><button data-open="jianghu_fish">飞书妙记提取</button></div></div>
        </div>
      </div>
    <div id="organizeView" class="view-panel active">
      <div class="sticky-results-head">
      <div class="process-dashboard">
        <section class="process-panel">
          <h3>总控任务队列</h3>
          <div class="queue-status"><span class="queue-dot" id="queueDot"></span><span class="queue-percent" id="queuePercent">0%</span><span id="queueStatusText">空闲，可开始批量处理</span></div>
          <p class="queue-current" id="queueCurrentText">这里显示当前批量任务、进度和最近结果。</p>
          <div class="action-row">
            <button class="primary" id="batchProcessQuickBtn">批量处理</button>
          </div>
        </section>
        <section class="process-panel">
          <h3>素材初加工闭环</h3>
          <div class="stage-strip" id="processStageStrip"></div>
        </section>
        <section class="process-panel task-result-panel" hidden>
          <h3>任务结果面板</h3>
          <p id="qualityPanelHint">进度、成功、跳过、失败</p>
          <div class="quality-grid" id="qualityGrid"></div>
        </section>
      </div>
      <div class="toolbar">
        <div id="hint">加载中...</div>
        <div class="toolbar-actions">
          <select id="sort" class="sort-select" title="排序">
            <option value="scene">同镜头对比</option>
            <option value="name">按名称</option>
            <option value="newest">按时间：最新</option>
            <option value="oldest">按时间：最早</option>
            <option value="size_desc">按大小：大到小</option>
            <option value="size_asc">按大小：小到大</option>
          </select>
          <button id="prev">上一页</button>
          <button id="next">下一页</button>
        </div>
      </div>
      </div>
      <div class="grid" id="grid"></div>
      <div class="pager"><span id="pageText"></span></div>
    </div>
    <div id="matchView" class="view-panel">
      <div class="edit-workbench">
        <section class="edit-panel clip-bin">
          <div class="edit-panel-head">
            <div>
              <h3>初筛素材区</h3>
              <p>这里只预览本条音频筛出来的匹配画面；真正剪辑在剪映里完成。</p>
            </div>
            <div class="action-row" style="margin-top:0;">
              <select id="matchAudioSelect" class="sort-select" title="选择音频素材"></select>
              <button class="primary" id="matchStartBtn">复制配镜提示词</button>
              <button id="matchReloadBtn">刷新素材</button>
            </div>
          </div>
          <div class="match-copy" id="matchCopy">选择一条音频后，这里会显示本条音频对应的初筛素材封面。</div>
          <div class="clip-shelf" id="clipShelf"><div class="empty">正在读取素材...</div></div>
        </section>
        <section class="edit-panel editor-preview">
          <div class="edit-panel-head">
            <div>
              <h3>预览区</h3>
              <p>单点左侧素材看单段；合并播放按左侧顺序连续预览。</p>
            </div>
          </div>
          <div class="canvas-frame" id="editFrame">
            <video id="editPreviewVideo" controls playsinline muted preload="metadata"></video>
          </div>
          <div class="preview-actions">
            <button class="primary" id="timelinePlayBtn">合并播放</button>
            <button id="timelineCropBtn">裁剪切割</button>
            <button id="matchOpenPackBtn">打开素材包</button>
          </div>
          <div id="matchStatus" class="path">等待选择素材。</div>
        </section>
      </div>
    </div>
    <div id="deliveryView" class="view-panel">
      <div class="workflow-grid">
        <div class="panel-card workflow-step"><div class="step-no">1</div><h3>剪映素材包</h3><p>输出编号素材，按文案顺序排列。导入剪映后只需要细裁、字幕、BGM 和导出。</p><div class="action-row"><button data-preset-kind="成品粗剪">查看成品区</button><button data-open="library">打开素材库</button></div></div>
        <div class="panel-card workflow-step"><div class="step-no">2</div><h3>粗剪检查</h3><p>检查是否竖屏、有音频、镜头不重复、没有明显废料、画面对台词。</p></div>
        <div class="panel-card workflow-step"><div class="step-no">3</div><h3>规则回写</h3><p>成品里发现的问题，回到素材整理和文案配镜规则里修，下一条视频少踩坑。</p><div class="action-row"><button data-open="records">打开记录</button></div></div>
      </div>
    </div>
  </main>
  <div class="pane-resizer preview-resizer" id="previewResizer" title="拖动调整预览宽度"></div>
  <section class="preview">
    <div class="right-section">
      <h2 class="right-title">预览</h2>
      <video id="video" controls playsinline autoplay muted preload="metadata"></video>
      <div class="preview-actions">
        <button id="muteToggle">打开声音</button>
        <button id="revealBtn">打开文件夹</button>
        <button id="copyPathBtn">复制路径</button>
        <button id="copyTranscriptBtn">复制视频文案</button>
        <button id="cropBtn">裁切废料</button>
        <button id="deepRepairBtn">复制提示词</button>
      </div>
      <div id="detail" class="path">点一个素材查看。</div>
    </div>
    <div class="right-section">
      <h2 class="right-title">当前步骤</h2>
      <div class="mini-list" id="sideGuide">
        <div class="mini-item"><strong>素材预览</strong><span>中间点击素材，右侧播放确认画面。</span></div>
        <div class="mini-item"><strong>下一步</strong><span>素材清洗、分镜、配镜、粗剪入口会逐步接到工作流里。</span></div>
        <div class="mini-item"><strong>原则</strong><span>画面优先，文案辅助；可复用素材沉淀进库。</span></div>
      </div>
    </div>
  </section>
</div>
<div class="crop-backdrop" id="cropBackdrop">
  <div class="crop-card">
    <div class="crop-head">
      <div>
        <h3>裁切废料</h3>
        <p class="crop-tip" id="cropTitle">上方框选保留画面，下方拖动时间线保留片段。输出新素材，不覆盖原片。</p>
      </div>
      <button id="cropClose">关闭</button>
    </div>
    <div class="crop-stage" id="cropStage">
      <video id="cropVideo" muted playsinline></video>
      <div class="crop-layer" id="cropLayer">
        <span class="crop-edge n" data-handle="n"></span>
        <span class="crop-edge e" data-handle="e"></span>
        <span class="crop-edge s" data-handle="s"></span>
        <span class="crop-edge w" data-handle="w"></span>
        <span class="crop-handle nw" data-handle="nw"></span>
        <span class="crop-handle ne" data-handle="ne"></span>
        <span class="crop-handle sw" data-handle="sw"></span>
        <span class="crop-handle se" data-handle="se"></span>
      </div>
    </div>
    <div class="trim-panel">
      <div class="trim-timeline" id="trimTimeline">
        <div class="trim-selection" id="trimSelection"></div>
        <div class="trim-handle" id="trimStartHandle" data-handle="start"></div>
        <div class="trim-handle" id="trimEndHandle" data-handle="end"></div>
      </div>
      <div class="trim-row">
        <span class="trim-time-box">开始 <b id="trimStartText">00:00.0</b></span>
        <span class="trim-time-box">结束 <b id="trimEndText">00:00.0</b></span>
        <span class="trim-time-box">保留 <b id="trimDurationText">00:00.0</b></span>
        <button id="setTrimStartBtn">当前点设为开始</button>
        <button id="setTrimEndBtn">当前点设为结束</button>
        <button id="resetTrimBtn">重置全片</button>
      </div>
      <div class="trim-tip" id="trimReadout">保留 00:00.0，从 00:00.0 到 00:00.0。输出到：手动处理</div>
    </div>
    <div class="crop-toolbar">
      <div>
        <div class="crop-presets">
          <button class="primary" id="detectCropBtn">自动检测字幕线</button>
          <button data-crop-preset="subtitle">裁切废料</button>
          <button data-crop-preset="vertical">9:16竖屏</button>
          <button data-crop-preset="portrait34">3:4</button>
          <button data-crop-preset="full">全画面</button>
        </div>
        <div class="crop-layout-row">
          <select id="cropLayoutSelect"><option value="">选择已保存布局</option></select>
          <button id="saveCropLayoutBtn">保存当前布局</button>
          <button id="deleteCropLayoutBtn">删除布局</button>
        </div>
        <div class="crop-detect-status" id="cropDetectStatus">先自动检测，再手动微调；同渠道素材可保存布局复用。</div>
      </div>
      <div class="crop-readout" id="cropReadout">x 0% / y 0% / w 100% / h 74%</div>
      <div class="crop-actions">
        <button id="cropCancel">取消</button>
        <button class="primary" id="cropApply">输出新素材</button>
        <button id="cropApplyDelete">输出新素材并删除原素材</button>
      </div>
    </div>
  </div>
</div>
<div class="context-menu" id="cardMenu">
  <button data-action="rename">重命名素材</button>
  <button data-action="tag">添加标签</button>
  <button data-action="crop">裁切废料</button>
  <button data-action="reveal">打开文件夹</button>
  <button data-action="copy">复制路径</button>
  <button data-action="delete" class="danger">移到回收站</button>
</div>
<div class="modal-backdrop" id="modalBackdrop">
  <div class="modal-card">
    <h3 class="modal-title" id="modalTitle">操作</h3>
    <div class="modal-body" id="modalBody"></div>
    <input class="modal-input" id="modalInput" />
    <div class="modal-actions">
      <button id="modalCancel">取消</button>
      <button class="primary" id="modalOk">确定</button>
    </div>
  </div>
</div>
<div class="crop-backdrop" id="batchCropBackdrop">
  <div class="crop-card" style="width:min(720px,96vw); max-height:90vh;">
    <div class="crop-head">
      <div>
        <h3>批量裁剪字幕</h3>
        <p class="crop-tip" id="batchCropTitle">只处理分镜素材，未分镜原片不动。自动检测字幕线并裁剪底部。</p>
      </div>
      <button id="batchCropClose">关闭</button>
    </div>
    <div id="batchCropContent" style="min-height:200px; max-height:60vh; overflow:auto;">
      <div style="padding:20px; text-align:center;">
        <p style="color:var(--muted);">点击下方按钮开始干跑检测，确认后再执行裁剪。</p>
        <p style="color:#41d5cf; margin-top:10px;">正式执行会在原位置旁边生成“裁切废料”新素材，不覆盖原分镜。</p>
        <div style="margin-top:20px; display:flex; gap:10px; justify-content:center;">
          <button class="primary" id="batchDryRunBtn">先干跑检测（不裁剪）</button>
          <button class="primary" id="batchExecuteBtn">输出裁切废料新素材</button>
        </div>
      </div>
    </div>
    <div class="crop-toolbar" id="batchCropToolbar" style="display:none;">
      <div class="crop-readout" id="batchProgressText">等待开始...</div>
      <div class="crop-actions">
        <button id="batchStopBtn" style="color:#b42318;">停止任务</button>
        <button id="batchRefreshBtn">刷新列表</button>
      </div>
    </div>
  </div>
</div>
<div class="crop-backdrop" id="batchTranscribeBackdrop">
  <div class="crop-card" style="width:min(720px,96vw); max-height:90vh;">
    <div class="crop-head">
      <div>
        <h3 id="batchProcessTitle">批量处理</h3>
        <p class="crop-tip" id="batchTranscribeTitle">对所有支持转写的素材进行语音识别；有效 TXT/缓存会跳过，标题话题 TXT 不算。默认使用 faster-whisper small。</p>
      </div>
      <button id="batchTranscribeClose">关闭</button>
    </div>
    <div id="batchTranscribeContent" style="min-height:200px; max-height:60vh; overflow:auto;">
      <div style="padding:20px; text-align:center;">
        <p style="color:var(--muted);">点击下方按钮开始批量识别。首次加载模型或长视频会比较慢。</p>
        <p style="color:#41d5cf; margin-top:10px;">识别结果会保存到缓存，并生成同名 .transcript.txt 时间戳文案。</p>
        <div style="margin-top:20px; display:flex; gap:10px; justify-content:center;">
          <button class="primary" id="batchTranscribeExecuteBtn">开始识别</button>
        </div>
      </div>
    </div>
    <div class="crop-toolbar" id="batchTranscribeToolbar" style="display:none;">
      <div class="crop-readout" id="batchTranscribeProgressText">等待开始...</div>
      <div class="crop-actions">
        <button id="batchTranscribeStopBtn" style="color:#b42318;">停止任务</button>
        <button id="batchTranscribeRefreshBtn">刷新列表</button>
      </div>
    </div>
  </div>
</div>
<script>
let page = 1, pageSize = 72, lastTotal = 0, selectedId = "", selectedItem = null;
let isLoadingItems = false, hasMoreItems = true;
let currentVisibleItemIds = [];
let contextMenuItem = null;
let audioWanted = localStorage.getItem("teamVideoBrowserAudioWanted") === "1";
let refreshingOptions = false;
let modalResolve = null;
let cropState = {rect:{x:0,y:0,w:100,h:74}, drag:null};
let trimState = {start:0, end:0, duration:0, drag:null};
let cropLayouts = [];
let matchAudioItems = [];
let selectedMatchAudio = null;
let selectedMatchClip = null;
let currentMatchPlan = null;
let editTimeline = [];
let selectedTimelineIndex = -1;
let timelineDragClip = null;
let timelinePlayToken = 0;
const $ = id => document.getElementById(id);
async function getJson(url){ const r = await fetch(url); return await r.json(); }
async function postJson(url, payload){
  const r = await fetch(url, {method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify(payload)});
  return await r.json();
}
function opt(sel, values){
  const current = sel.value;
  sel.innerHTML = "";
  const all = document.createElement("option");
  all.value = "";
  all.textContent = "全部";
  sel.appendChild(all);
  values.forEach(entry => {
    const value = typeof entry === "string" ? entry : entry.value;
    const count = typeof entry === "string" ? null : entry.count;
    const o = document.createElement("option");
    o.value = value;
    o.textContent = count === null ? value : `${value}（${count}）`;
    sel.appendChild(o);
  });
  sel.value = Array.from(sel.options).some(o => o.value === current) ? current : "";
  renderCustomSelect(sel);
}
function ensureCustomSelect(sel){
  if(sel.nextElementSibling && sel.nextElementSibling.classList.contains("select-wrap")) return sel.nextElementSibling;
  const wrap = document.createElement("div");
  wrap.className = "select-wrap";
  wrap.innerHTML = '<button type="button" class="select-button"><span class="select-value"></span><span class="select-arrow"></span></button><div class="select-menu"></div>';
  sel.insertAdjacentElement("afterend", wrap);
  wrap.querySelector(".select-button").addEventListener("click", e => {
    e.stopPropagation();
    document.querySelectorAll(".select-wrap.open").forEach(other => { if(other !== wrap) other.classList.remove("open"); });
    wrap.classList.toggle("open");
    wrap.querySelector(".select-button").classList.toggle("open", wrap.classList.contains("open"));
  });
  return wrap;
}
function renderCustomSelect(sel){
  const wrap = ensureCustomSelect(sel);
  const valueBox = wrap.querySelector(".select-value");
  const menu = wrap.querySelector(".select-menu");
  const selected = sel.options[sel.selectedIndex] || sel.options[0];
  valueBox.textContent = selected ? selected.textContent : "全部";
  menu.innerHTML = "";
  Array.from(sel.options).forEach(option => {
    const item = document.createElement("div");
    item.className = "select-option" + (option.value === sel.value ? " selected" : "");
    item.textContent = option.textContent;
    item.addEventListener("click", e => {
      e.stopPropagation();
      sel.value = option.value;
      wrap.classList.remove("open");
      wrap.querySelector(".select-button").classList.remove("open");
      renderCustomSelect(sel);
      sel.dispatchEvent(new Event("change", {bubbles:true}));
    });
    menu.appendChild(item);
  });
}
async function init(){
  initPaneResize();
  syncAudioButton();
  const s = await getJson("/api/summary");
  $("total").textContent = s.total;
  $("rootPath").textContent = s.library_root;
  updateWorkflowHealth(s);
  await refreshBatchQueueStatus();
  startDashboardAutoRefresh();
  await refreshOptions();
  restoreFilterState();
  await load();
  bindInfiniteScroll();
  bindTabs();
  document.addEventListener("click", () => {
    hideCardMenu();
    document.querySelectorAll(".select-wrap.open").forEach(wrap => {
      wrap.classList.remove("open");
      wrap.querySelector(".select-button").classList.remove("open");
    });
  });
  window.addEventListener("blur", hideCardMenu);
  $("cardMenu").addEventListener("click", handleCardMenuAction);
  $("modalCancel").addEventListener("click", () => closeModal(null));
  $("modalOk").addEventListener("click", () => closeModal($("modalInput").style.display === "none" ? true : $("modalInput").value));
  $("modalInput").addEventListener("keydown", e => {
    if(e.key === "Enter") closeModal($("modalInput").value);
    if(e.key === "Escape") closeModal(null);
  });
  $("modalBackdrop").addEventListener("click", e => {
    if(e.target.id === "modalBackdrop") closeModal(null);
  });
  bindCropUi();
  bindTrimUi();
  bindBatchCropUi();
  bindMatchUi();
  bindDeleteKey();
}
function updateWorkflowHealth(summary){
  const byKind = summary.by_kind || {};
  const get = name => Number(byKind[name] || 0);
  const pending = get("未分类/未整理素材");
  const originals = get("已整理原片");
  const scenes = get("分镜素材");
  const audios = get("原片音频素材");
  const deliveries = get("成品粗剪");
  const health = $("workflowHealth");
  if(health){
    health.innerHTML = [
      ["待整理", pending],
      ["原片", originals],
      ["分镜", scenes],
      ["音频", audios],
      ["成品", deliveries],
    ].map(([label, value]) => `<span class="health-pill">${label} ${value}</span>`).join("");
  }
  let next = "下一步：进入素材整理，先确认素材是否干净可用";
  if(pending > 0){
    next = `下一步：还有 ${pending} 条未整理素材，先入库/清洗/分镜`;
  }else if(scenes === 0 && originals > 0){
    next = "下一步：已有原片但缺分镜，先做分镜分类";
  }else if(audios === 0 && originals > 0){
    next = "下一步：批量处理 → 提取音频/文案，给文案配镜做主线";
  }else if(audios > 0 && scenes > 0 && deliveries === 0){
    next = "下一步：进入智能剪辑，选音频开始匹配素材";
  }else if(deliveries > 0){
    next = `下一步：已有 ${deliveries} 条成品/粗剪，进入成品检查`;
  }
  const nextBox = $("workflowNext");
  if(nextBox) nextBox.textContent = next;
  updateProcessDashboard(summary);
}
function updateProcessDashboard(summary){
  const byKind = summary.by_kind || {};
  const quality = summary.quality || {};
  const get = name => Number(byKind[name] || 0);
  const stages = [
    ["待整理", get("未分类/未整理素材"), "采集后先清洗归档"],
    ["原片", get("已整理原片"), "保留音频/文案源"],
    ["分镜", get("分镜素材"), "可直接配镜"],
    ["音频", get("原片音频素材"), "智能剪辑主线"],
    ["成品", get("成品粗剪"), "等待自检交付"],
  ];
  const stageStrip = $("processStageStrip");
  if(stageStrip){
    stageStrip.innerHTML = stages.map(([label, value, tip]) => `
      <div class="stage-pill" title="${esc(label)}：${esc(tip)}"><b>${value}</b><span>${esc(label)}</span></div>
    `).join("");
  }
  const qualityGrid = $("qualityGrid");
  if(qualityGrid){
    const hint = $("qualityPanelHint");
    if(hint) hint.textContent = "基础状态：文案、清洗、产出";
    qualityGrid.innerHTML = [
      ["有文案", Number(quality.transcript_ready || 0)],
      ["缺文案", Number(quality.transcript_missing || 0)],
      ["已处理", Number(quality.manual_processed || 0)],
      ["成品", get("成品粗剪")],
    ].map(([label, value]) => `<div class="quality-cell"><b>${value}</b><span>${label}</span></div>`).join("");
  }
}
function updateTaskResultPanel(p){
  const grid = $("qualityGrid");
  if(!grid) return;
  const total = Number(p.total || 0);
  if(!total) return;
  const processed = Number(p.processed || 0);
  const percent = total ? Math.min(100, Math.round(processed / total * 100)) : 0;
  const hint = $("qualityPanelHint");
  if(hint){
    hint.textContent = p.running
      ? `任务运行中：${processed}/${total}，实时更新`
      : `最近任务：${processed}/${total}，${p.message || "已结束"}`;
  }
  grid.innerHTML = [
    ["进度", `${percent}%`],
    ["成功", Number(p.success || 0)],
    ["跳过", Number(p.skipped || 0)],
    ["失败", Number(p.failed || 0)],
  ].map(([label, value]) => `<div class="quality-cell"><b>${value}</b><span>${label}</span></div>`).join("");
}
function updateBatchQueueFromProgress(p){
  const dot = $("queueDot");
  const status = $("queueStatusText");
  const current = $("queueCurrentText");
  const percentBox = $("queuePercent");
  if(!dot || !status || !current) return;
  const total = Number(p.total || 0);
  const processed = Number(p.processed || 0);
  const percent = total > 0 ? Math.min(100, Math.max(0, Math.round(processed / total * 100))) : 0;
  if(percentBox) percentBox.textContent = `${percent}%`;
  dot.classList.toggle("busy", !!p.running);
  if(p.running){
    status.textContent = `运行中 ${p.processed || 0}/${p.total || 0}`;
    current.textContent = p.current_item ? `当前：${p.current_item}` : (p.message || "批量任务正在执行...");
  }else{
    status.textContent = "空闲，可开始批量处理";
    current.textContent = p.message || "这里显示当前批量任务、进度和最近结果。";
  }
}
let batchRefreshTimer = null;
function updateBatchQueueFromProgress(p){
  const dot = $("queueDot");
  const status = $("queueStatusText");
  const current = $("queueCurrentText");
  const fill = $("queueProgressFill");
  const percentBox = $("queuePercent");
  if(!dot || !status || !current) return;
  const total = Number(p.total || 0);
  const processed = Number(p.processed || 0);
  const percent = total > 0 ? Math.min(100, Math.max(0, Math.round(processed / total * 100))) : 0;
  if(fill) fill.style.width = `${percent}%`;
  if(percentBox) percentBox.textContent = `${percent}%`;
  dot.classList.toggle("busy", !!p.running);
  updateTaskResultPanel(p);
  if(p.running){
    status.textContent = `运行中 ${processed}/${total} · ${percent}%`;
    current.textContent = p.current_item ? `当前：${p.current_item}` : (p.message || "批量任务正在执行...");
  }else{
    status.textContent = "空闲，可开始批量处理";
    current.textContent = p.message || "这里显示当前批量任务、进度和最近结果。";
  }
}
let dashboardRefreshTimer = null;
function updateBatchQueueFromProgress(p){
  const dot = $("queueDot");
  const status = $("queueStatusText");
  const current = $("queueCurrentText");
  const percentBox = $("queuePercent");
  if(!dot || !status || !current) return;
  const total = Number(p.total || 0);
  const processed = Number(p.processed || 0);
  const percent = total > 0 ? Math.min(100, Math.max(0, Math.round(processed / total * 100))) : 0;
  if(percentBox) percentBox.textContent = `${percent}%`;
  dot.classList.toggle("busy", !!p.running);
  if(p.running){
    status.textContent = `运行中 ${processed}/${total}`;
    current.textContent = p.current_item ? `当前：${p.current_item}` : (p.message || "任务正在执行...");
  }else{
    status.textContent = "空闲，可开始批量处理";
    current.textContent = p.message || "这里显示当前任务、进度和最近结果。";
  }
}
let dashboardWasRunning = false;
let dashboardLastProgressKey = "";
function startDashboardAutoRefresh(){
  if(dashboardRefreshTimer) clearInterval(dashboardRefreshTimer);
  dashboardRefreshTimer = setInterval(refreshDashboardState, 1500);
}
async function refreshDashboardState(){
  try{
    const p = await getJson("/api/batch-progress");
    if(p.ok){
      updateBatchQueueFromProgress(p);
      const key = `${p.running}:${p.processed}:${p.total}:${p.success}:${p.skipped}:${p.failed}`;
      if(key !== dashboardLastProgressKey){
        dashboardLastProgressKey = key;
      }
      if(dashboardWasRunning && !p.running){
        const s = await getJson("/api/summary");
        $("total").textContent = s.total;
        $("rootPath").textContent = s.library_root;
        updateWorkflowHealth(s);
        await refreshOptions();
        await load({preserveScroll:true});
      }
      dashboardWasRunning = !!p.running;
    }
  }catch(err){}
}
async function refreshBatchQueueStatus(){
  try{
    const p = await getJson("/api/batch-progress");
    if(p.ok){
      updateBatchQueueFromProgress(p);
    }
  }catch(err){}
}
function initPaneResize(){
  setSidebarWidth(Number(localStorage.getItem("teamVideoSidebarWidth") || "286"));
  setPreviewWidth(Number(localStorage.getItem("teamVideoPreviewWidth") || "430"));
  bindPaneResizer("sidebarResizer", currentSidebarWidth, setSidebarWidth, (dx, startWidth) => startWidth + dx, "teamVideoSidebarWidth");
  bindPaneResizer("previewResizer", currentPreviewWidth, setPreviewWidth, (dx, startWidth) => startWidth - dx, "teamVideoPreviewWidth");
  document.body.classList.add("resizer-hint");
  setTimeout(() => document.body.classList.remove("resizer-hint"), 2400);
}
function bindPaneResizer(id, currentFn, setFn, nextFn, storageKey){
  const handle = $(id);
  if(!handle) return;
  let startX = 0, startWidth = 0;
  handle.addEventListener("pointerdown", e => {
    startX = e.clientX;
    startWidth = currentFn();
    document.body.classList.add("resizing-pane");
    handle.classList.add("is-dragging");
    handle.setPointerCapture(e.pointerId);
  });
  handle.addEventListener("pointermove", e => {
    if(!document.body.classList.contains("resizing-pane")) return;
    setFn(nextFn(e.clientX - startX, startWidth));
  });
  handle.addEventListener("pointerup", e => {
    if(!document.body.classList.contains("resizing-pane")) return;
    document.body.classList.remove("resizing-pane");
    handle.classList.remove("is-dragging");
    localStorage.setItem(storageKey, String(currentFn()));
    try{ handle.releasePointerCapture(e.pointerId); }catch(_){}
  });
  handle.addEventListener("pointercancel", () => {
    document.body.classList.remove("resizing-pane");
    handle.classList.remove("is-dragging");
  });
}
function currentSidebarWidth(){
  const value = getComputedStyle(document.documentElement).getPropertyValue("--sidebar-width").trim();
  return Number(value.replace("px","")) || 286;
}
function setSidebarWidth(width){
  const next = Math.round(Math.min(430, Math.max(220, Number(width) || 286)));
  document.documentElement.style.setProperty("--sidebar-width", `${next}px`);
}
function currentPreviewWidth(){
  const value = getComputedStyle(document.documentElement).getPropertyValue("--preview-width").trim();
  return Number(value.replace("px","")) || 430;
}
function setPreviewWidth(width){
  const vw = Math.max(document.documentElement.clientWidth || 1200, 900);
  const maxWidth = Math.min(520, Math.max(280, vw - currentSidebarWidth() - 420));
  const next = Math.round(Math.min(maxWidth, Math.max(280, Number(width) || 340)));
  document.documentElement.style.setProperty("--preview-width", `${next}px`);
}
function params(){
  const p = new URLSearchParams(); p.set("page", page); p.set("page_size", pageSize);
  ["q","kind","location","category","keyword"].forEach(id => { if($(id).value) p.set(id,$(id).value); });
  if($("sort") && $("sort").value) p.set("sort", $("sort").value);
  return p;
}
function bindInfiniteScroll(){
  const pane = document.querySelector("main");
  if(!pane) return;
  pane.addEventListener("scroll", () => {
    if(document.body.dataset.view !== "organize") return;
    if(!hasMoreItems || isLoadingItems) return;
    const remaining = pane.scrollHeight - pane.scrollTop - pane.clientHeight;
    if(remaining < 760) loadNextPage();
  }, {passive:true});
}
async function loadNextPage(){
  if(isLoadingItems || !hasMoreItems) return;
  page += 1;
  await load({append:true});
  saveFilterState();
}
async function refreshOptions(){
  const o = await getJson("/api/options?" + params().toString());
  refreshingOptions = true;
  opt($("kind"), o.kinds);
  opt($("location"), o.locations);
  opt($("category"), o.categories);
  opt($("keyword"), o.keywords);
  refreshingOptions = false;
  updateFilterCopy();
}
async function load(){
  const data = await getJson("/api/items?" + params().toString());
  currentVisibleItemIds = (data.items || []).map(item => item.id);
  lastTotal = data.total; $("resultCount").textContent = data.total; $("hint").textContent = `找到 ${data.total} 条素材`;
  $("pageText").textContent = `第 ${page} 页 / 共 ${Math.max(1, Math.ceil(data.total/pageSize))} 页`;
  const grid = $("grid"); grid.innerHTML = "";
  if(!data.items.length){ grid.innerHTML = '<div class="empty">没有匹配素材</div>'; return; }
  const renderedCards = [];
  data.items.forEach(item => {
    const card = document.createElement("div"); card.className = "card" + (item.id===selectedId ? " selected" : "");
    card.draggable = true;
    card.innerHTML = `<img class="thumb" draggable="false" loading="lazy" src="${item.thumb}" onerror="thumbFail(this)"><button class="card-more" title="素材操作">...</button><div class="meta"><div class="name">${esc(item.name)}</div><div class="tags">${renderTags(item)}</div></div>`;
    card.onclick = () => selectItem(item, card);
    card.querySelector(".card-more").addEventListener("click", e => showCardMenu(e, item, card));
    card.addEventListener("dragstart", e => { selectItem(item, card); prepareDrag(e, item); });
    card.addEventListener("contextmenu", e => showCardMenu(e, item, card));
    grid.appendChild(card);
    renderedCards.push({item, card});
  });
  const visibleSelection = data.items.some(item => item.id === selectedId);
  if(!visibleSelection && renderedCards.length){
    selectItem(renderedCards[0].item, renderedCards[0].card);
  }
}
async function load(options = {}){
  const append = !!options.append;
  const preserveScroll = !!options.preserveScroll;
  if(isLoadingItems) return;
  if(!append) page = 1;
  isLoadingItems = true;
  const pane = document.querySelector("main");
  const previousScrollTop = pane ? pane.scrollTop : 0;
  try{
    const data = await getJson("/api/items?" + params().toString());
    const items = data.items || [];
    lastTotal = data.total;
    const grid = $("grid");
    if(!append){
      currentVisibleItemIds = [];
      grid.innerHTML = "";
      if(pane && !preserveScroll) pane.scrollTop = 0;
    }
    const incomingIds = items.map(item => item.id);
    currentVisibleItemIds = Array.from(new Set([...currentVisibleItemIds, ...incomingIds]));
    hasMoreItems = currentVisibleItemIds.length < data.total && items.length > 0;
    $("resultCount").textContent = data.total;
    $("hint").textContent = `找到 ${data.total} 条素材，已加载 ${currentVisibleItemIds.length} 条`;
    $("pageText").textContent = hasMoreItems
      ? `已加载 ${currentVisibleItemIds.length} / 共 ${data.total} 条，继续下滑自动加载`
      : `已全部加载 ${currentVisibleItemIds.length} 条`;
    if(!items.length && !append){
      grid.innerHTML = '<div class="empty">没有匹配素材</div>';
      return;
    }
    const renderedCards = [];
    items.forEach(item => {
      if(grid.querySelector(`[data-item-id="${CSS.escape(item.id)}"]`)) return;
      const card = createItemCard(item);
      grid.appendChild(card);
      renderedCards.push({item, card});
    });
    const visibleSelection = currentVisibleItemIds.includes(selectedId);
    if(!visibleSelection && renderedCards.length){
      selectItem(renderedCards[0].item, renderedCards[0].card);
    }
    if(hasMoreItems && pane && pane.scrollHeight <= pane.clientHeight + 240){
      setTimeout(loadNextPage, 80);
    }
    if(pane && preserveScroll){
      requestAnimationFrame(() => { pane.scrollTop = previousScrollTop; });
    }
  }finally{
    isLoadingItems = false;
  }
}
function createItemCard(item){
  const card = document.createElement("div");
  card.className = "card" + (item.id===selectedId ? " selected" : "");
  card.draggable = true;
  card.dataset.itemId = item.id;
  card.innerHTML = `<img class="thumb" draggable="false" loading="lazy" src="${item.thumb}" onerror="thumbFail(this)"><button class="card-more" title="素材操作">...</button><div class="meta"><div class="name">${esc(item.name)}</div><div class="tags">${renderTags(item)}</div></div>`;
  card.onclick = () => selectItem(item, card);
  card.querySelector(".card-more").addEventListener("click", e => showCardMenu(e, item, card));
  card.addEventListener("dragstart", e => { selectItem(item, card); prepareDrag(e, item); });
  card.addEventListener("contextmenu", e => showCardMenu(e, item, card));
  return card;
}
function updateLoadedCountText(){
  $("resultCount").textContent = lastTotal;
  $("hint").textContent = `找到 ${lastTotal} 条素材，已加载 ${currentVisibleItemIds.length} 条`;
  $("pageText").textContent = hasMoreItems
    ? `已加载 ${currentVisibleItemIds.length} / 共 ${lastTotal} 条，继续下滑自动加载`
    : `已全部加载 ${currentVisibleItemIds.length} 条`;
}
function replaceItemInView(oldItem, newItem){
  if(!newItem) return false;
  const grid = $("grid");
  const oldId = oldItem && oldItem.id ? oldItem.id : newItem.id;
  const oldCard = grid ? grid.querySelector(`[data-item-id="${CSS.escape(oldId)}"]`) : null;
  const pane = document.querySelector("main");
  const scrollTop = pane ? pane.scrollTop : 0;
  if(!oldCard) return false;
  const newCard = createItemCard(newItem);
  oldCard.replaceWith(newCard);
  currentVisibleItemIds = currentVisibleItemIds.map(id => id === oldId ? newItem.id : id);
  selectItem(newItem, newCard, {scrollPreview:false});
  if(pane) requestAnimationFrame(() => { pane.scrollTop = scrollTop; });
  return true;
}
function insertGeneratedItemNearSource(sourceItem, newItem){
  if(!newItem) return false;
  const grid = $("grid");
  const pane = document.querySelector("main");
  const scrollTop = pane ? pane.scrollTop : 0;
  if(!grid) return false;
  const existing = grid.querySelector(`[data-item-id="${CSS.escape(newItem.id)}"]`);
  if(existing){
    selectItem(newItem, existing, {scrollPreview:false});
    if(pane) requestAnimationFrame(() => { pane.scrollTop = scrollTop; });
    return true;
  }
  const card = createItemCard(newItem);
  const sourceId = sourceItem && sourceItem.id ? sourceItem.id : selectedId;
  const sourceCard = sourceId ? grid.querySelector(`[data-item-id="${CSS.escape(sourceId)}"]`) : null;
  if(sourceCard){
    sourceCard.insertAdjacentElement("afterend", card);
  }else{
    grid.prepend(card);
  }
  const sourceIndex = currentVisibleItemIds.indexOf(sourceId);
  if(sourceIndex >= 0){
    currentVisibleItemIds.splice(sourceIndex + 1, 0, newItem.id);
  }else{
    currentVisibleItemIds.unshift(newItem.id);
  }
  currentVisibleItemIds = Array.from(new Set(currentVisibleItemIds));
  lastTotal = Number(lastTotal || 0) + 1;
  hasMoreItems = currentVisibleItemIds.length < lastTotal;
  updateLoadedCountText();
  selectItem(newItem, card, {scrollPreview:false});
  if(pane) requestAnimationFrame(() => { pane.scrollTop = scrollTop; });
  return true;
}
function renderTags(item){
  const tags = [item.process_tag, item.kind, item.location, item.keyword || item.category, ...(item.user_tags || [])].filter(Boolean);
  return tags.slice(0, 5).map(tag => `<span class="tag">${esc(tag)}</span>`).join("");
}
function updateFilterCopy(){
  const kind = $("kind").value;
  const category = $("category").value;
  if(kind === "已整理原片"){
    $("keywordLabel").textContent = "素材组/来源";
    $("filterNote").innerHTML = "<b>当前关系：</b>已整理原片按地点和素材组管理，适合复制文案、打开原素材、后续重新清洗分镜。";
  }else if(kind === "未分类/未整理素材"){
    $("keywordLabel").textContent = "待整理来源";
    $("filterNote").innerHTML = "<b>当前关系：</b>未分类素材还没入库或分镜，先人工/AI判断，再移动到对应地点素材库。";
  }else if(kind === "分镜素材"){
    $("keywordLabel").textContent = "具体场景/项目";
    $("filterNote").innerHTML = "<b>当前关系：</b>分镜先看一级分类，再看具体场景/项目，例如项目活动 → 皮划艇。";
  }else if(kind === "成品粗剪"){
    $("keywordLabel").textContent = "成品来源/片段组";
    $("filterNote").innerHTML = "<b>当前关系：</b>这里是自动粗剪、无声画面轨、剪映素材包等交付产物。先预览有没有重复镜头、黑屏、废料和音画不匹配，再回到审片板或素材整理修。";
  }else if(kind === "原片音频素材"){
    $("keywordLabel").textContent = "音频来源";
    $("filterNote").innerHTML = "<b>当前关系：</b>这里是从已整理原片提取出来的口播音频，后续在“智能剪辑”里作为主线，按台词时间戳匹配分镜素材。";
  }else{
    $("keywordLabel").textContent = "具体场景/素材组";
    $("filterNote").innerHTML = "<b>筛选层级：</b>素材类型 → 地点 → 一级分类 → 具体场景/素材组。素材类型包含已整理原片、分镜素材、原片音频素材、未分类/未整理素材。";
  }
  if(category && kind !== "已整理原片"){
    $("filterNote").innerHTML += ` 当前一级分类：<b>${esc(category)}</b>。`;
  }
}
function saveFilterState(){
  const state = {
    kind: $("kind").value,
    location: $("location").value,
    category: $("category").value,
    keyword: $("keyword").value,
    q: $("q").value,
    sort: $("sort") ? $("sort").value : "scene",
    page: page
  };
  localStorage.setItem("videoLibraryFilter", JSON.stringify(state));
}
function restoreFilterState(){
  const saved = localStorage.getItem("videoLibraryFilter");
  if(!saved) return;
  try{
    const state = JSON.parse(saved);
    ["kind","location","category","keyword","q"].forEach(id => {
      if(state[id] !== undefined){
        const el = $(id);
        if(el) el.value = state[id];
      }
    });
    if(state.sort && $("sort")) $("sort").value = state.sort;
    if(state.page) page = state.page;
    ["kind","location","category","keyword"].forEach(id => {
      const sel = $(id);
      if(sel) renderCustomSelect(sel);
    });
  }catch(e){}
}
function bindTabs(){
  document.querySelectorAll(".tab").forEach(btn => {
    btn.addEventListener("click", () => setActiveView(btn.dataset.view));
  });
  const rescanBtn = $("rescanLibraryBtn");
  if(rescanBtn){
    rescanBtn.addEventListener("click", async () => {
      const old = rescanBtn.textContent;
      rescanBtn.textContent = "扫描中...";
      const result = await postJson("/api/rescan", {});
      if(result.ok){
        $("total").textContent = result.total;
        updateWorkflowHealth(result);
        await refreshOptions();
        await load();
        await loadMatchAudioItems();
        rescanBtn.textContent = "已刷新";
      }else{
        rescanBtn.textContent = "刷新失败";
      }
      setTimeout(() => rescanBtn.textContent = old, 1200);
    });
  }
  document.querySelectorAll("[data-open]").forEach(btn => {
    btn.addEventListener("click", async () => {
      const old = btn.textContent;
      btn.textContent = "打开中...";
      await fetch("/open-target?key=" + encodeURIComponent(btn.dataset.open)).catch(()=>{});
      setTimeout(() => btn.textContent = old, 800);
    });
  });
  document.querySelectorAll("[data-preset-kind]").forEach(btn => {
    btn.addEventListener("click", async () => {
      setActiveView("organize");
      $("kind").value = btn.dataset.presetKind || "";
      page = 1;
      await refreshOptions();
      $("kind").value = btn.dataset.presetKind || "";
      renderCustomSelect($("kind"));
      await refreshOptions();
      await load();
    });
  });
}
function setActiveView(view){
  document.body.dataset.view = view;
  document.querySelectorAll(".tab").forEach(btn => btn.classList.toggle("active", btn.dataset.view === view));
  ["collect","organize","match","delivery"].forEach(name => {
    const panel = $(name + "View");
    if(panel) panel.classList.toggle("active", name === view);
  });
  updateSideGuide(view);
  if(view === "match") loadMatchAudioItems();
}
function updateSideGuide(view){
  const guide = $("sideGuide");
  const data = {
    collect: [
      ["采集入库", "新素材先进入待分类整理库，再按地点归档。"],
      ["不要重复", "移动优先，避免下载目录和素材库各留一份。"],
      ["下一步", "采集后进入素材整理，做清洗、分镜、分类。"]
    ],
    organize: [
      ["素材浏览", "中间点击素材，右侧播放确认画面。"],
      ["素材整理", "这里负责筛选、判断、打开文件夹、复制原片文案。"],
      ["原则", "画面优先，文案辅助；可复用素材沉淀进库。"]
    ],
    match: [
      ["智能剪辑", "左边选音频和初筛素材，右边预览确认；真正剪辑在剪映完成。"],
      ["复制任务", "点击复制配镜提示词，把音频和配镜规则交给 Codex 做语义匹配。"],
      ["剪映交付", "Codex 生成编号初剪素材包后，拖进剪映细剪。"]
    ],
    delivery: [
      ["成品检查", "检查竖屏、音频、重复镜头、废料、水印字幕残留。"],
      ["剪映交付", "导入编号素材包后，你只做细裁、字幕、BGM。"],
      ["规则回写", "发现错配和废料，回写到素材整理和配镜规则。"]
    ]
  }[view] || [];
  guide.innerHTML = data.map(row => `<div class="mini-item"><strong>${esc(row[0])}</strong><span>${esc(row[1])}</span></div>`).join("");
}
function bindMatchUi(){
  const reloadBtn = $("matchReloadBtn");
  const startBtn = $("matchStartBtn");
  const audioSelect = $("matchAudioSelect");
  if(reloadBtn) reloadBtn.addEventListener("click", loadMatchAudioItems);
  if(startBtn) startBtn.addEventListener("click", copySmartMatchPrompt);
  if(audioSelect){
    audioSelect.addEventListener("change", () => {
      const item = matchAudioItems.find(entry => entry.id === audioSelect.value);
      if(item) selectMatchAudio(item);
    });
  }
  if($("timelinePlayBtn")) $("timelinePlayBtn").addEventListener("click", playTimelineSequence);
  if($("timelineCropBtn")) $("timelineCropBtn").addEventListener("click", cropSelectedTimelineClip);
  if($("matchOpenPackBtn")) $("matchOpenPackBtn").addEventListener("click", openMatchOutputFolder);
}
async function loadMatchAudioItems(){
  const shelf = $("clipShelf");
  if(shelf) shelf.innerHTML = '<div class="empty">正在读取音频素材...</div>';
  try{
    const data = await getJson("/api/items?kind=" + encodeURIComponent("原片音频素材") + "&page_size=80");
    matchAudioItems = data.items || [];
    if(!matchAudioItems.length){
      if(shelf) shelf.innerHTML = '<div class="empty">还没有原片音频素材。先在“素材整理”里提取音频/文案素材。</div>';
      return;
    }
    if(!selectedMatchAudio || !matchAudioItems.some(item => item.id === selectedMatchAudio.id)){
      selectedMatchAudio = matchAudioItems[0];
    }
    renderAudioList();
    selectMatchAudio(selectedMatchAudio);
  }catch(err){
    if(shelf) shelf.innerHTML = '<div class="empty">音频素材读取失败。</div>';
  }
}
function renderAudioList(){
  const select = $("matchAudioSelect");
  if(!select) return;
  select.innerHTML = "";
  matchAudioItems.forEach(item => {
    const option = document.createElement("option");
    option.value = item.id;
    option.textContent = `${item.name} / ${item.location}`;
    select.appendChild(option);
  });
  if(selectedMatchAudio) select.value = selectedMatchAudio.id;
}
function selectMatchAudio(item){
  selectedMatchAudio = item;
  renderAudioList();
  $("matchCopy").textContent = `已选择音频：${item.name}\n地点：${item.location}\n左侧显示本条音频对应的初筛画面素材；真正精配和剪辑交给 Codex + 剪映。`;
  $("matchStatus").textContent = "正在读取初筛素材...";
  startMatchAudio();
}
async function copySmartMatchPrompt(){
  if(!selectedMatchAudio){
    await showMessage("还没选音频", "先在左侧选择一条原片音频。");
    return;
  }
  const audioPath = selectedMatchAudio.path || selectedMatchAudio.name;
  const location = selectedMatchAudio.location || "自动判断";
  const safeTitle = selectedMatchAudio.name.replace(/\.[^.]+$/, "");
  const prompt = [
    "请使用「团建视频素材智能分镜分类 Skill」执行【文案配镜初检】工作流。",
    "",
    `原片音频素材：${audioPath}`,
    `地点：${location}`,
    `任务标题：${safeTitle}`,
    "",
    "素材库根目录：D:\\Download\\素材下载\\团建视频",
    "",
    "目标：",
    "1. 读取这条音频对应的时间戳文案；如果已有同名 TXT/缓存，优先复用，不重复识别。",
    "2. 按一句台词一个画面为基础做配镜；一句话较长或关键词较多时，可以匹配 2-3 个画面。",
    "3. 先做语义匹配，再做关键词匹配和场景匹配；具体活动必须匹配具体素材，例如皮划艇不能用骑行或吃饭代替。",
    "4. 没有明确关键词的台词，用同地点环境空镜、人物反应、团队互动、细节特写做补充，不要重复循环同一个 1-2 秒素材。",
    "5. 优先使用已经清洗好的分镜素材；明显带字幕、水印、废料、横屏小画面的素材不要选。",
    "6. 复制素材到一个新的初剪素材包，不移动源素材。",
    "7. 初剪素材包文件夹命名：日期时间 + 音频标题；放在 D:\\Download\\素材下载\\团建视频\\智能剪辑初剪库 下。",
    "8. 输出素材按剪辑顺序编号：001_、002_、003_，保留原关键词和来源信息。",
    "9. 同时输出：文案.txt、配镜表.csv、配镜说明.md、质检报告.md；如果可行，再输出一个 rough_cut_preview.mp4 作为快速预览。",
    "10. 完成后自检：画面是否对应台词、是否有重复循环、是否有脏字幕/水印、时长是否覆盖音频、是否适合拖进剪映继续细剪。",
    "",
    "验收标准：",
    "- 我能直接打开初剪素材包，把编号素材按顺序拖进剪映。",
    "- 每句台词旁边都有匹配理由和本地素材路径。",
    "- 不确定的台词要写明为什么弱匹配，并给替代建议。"
  ].join("\\n");
  await navigator.clipboard.writeText(prompt).catch(()=>{});
  $("matchStatus").textContent = "已复制 Codex 配镜提示词";
  $("matchCopy").textContent = `已复制配镜任务提示词。\n\n音频：${selectedMatchAudio.name}\n地点：${location}\n\n下一步：直接把提示词发到对话窗口，我会按 Skill 生成编号初剪素材包并自检。`;
  $("matchStartBtn").textContent = "已复制";
  setTimeout(() => $("matchStartBtn").textContent = "复制配镜提示词", 1100);
}
async function startMatchAudio(){
  if(!selectedMatchAudio){
    await showMessage("还没选音频", "先在左侧选择一条原片音频。");
    return;
  }
  $("matchStatus").textContent = "匹配中，首次识别可能需要几分钟...";
  $("clipShelf").innerHTML = '<div class="empty">正在生成候选素材...</div>';
  try{
    const data = await getJson("/api/match-audio/" + encodeURIComponent(selectedMatchAudio.id));
    if(!data.ok){
      $("matchStatus").textContent = "匹配失败";
      await showMessage("匹配失败", data.error || "匹配失败");
      return;
    }
    currentMatchPlan = data;
    renderMatchPlan(data);
  }catch(err){
    $("matchStatus").textContent = "匹配失败";
    await showMessage("匹配失败", String(err));
  }
}
function renderMatchPlan(plan){
  const beats = plan.beats || [];
  selectedMatchClip = null;
  editTimeline = [];
  selectedTimelineIndex = -1;
  $("matchCopy").textContent = `音频：${plan.audio.name}\n文案来源：${plan.transcript_source || "audio_asr"}\n画幅：小红书 3:4\n规则：优先同地点、直接关键词、分镜文件夹与文件名。`;
  const shelf = $("clipShelf");
  shelf.innerHTML = "";
  const seen = new Set();
  beats.forEach(beat => {
    const first = (beat.candidates || [])[0];
    if(first) editTimeline.push(makeTimelineEntry(first, beat));
    (beat.candidates || []).forEach(clip => {
      if(seen.has(clip.id)) return;
      seen.add(clip.id);
      shelf.appendChild(renderClipMini(clip));
    });
  });
  if(!seen.size) shelf.innerHTML = '<div class="empty">没有匹配到候选分镜。</div>';
  $("matchStatus").textContent = `已生成 ${seen.size} 条初筛素材 / ${beats.length} 段台词`;
  const firstClip = beats.map(b => (b.candidates || [])[0]).find(Boolean);
  if(firstClip) previewEditClip(firstClip);
}
function renderClipMini(clip){
  const node = document.createElement("div");
  node.className = "clip-mini";
  node.dataset.clipId = clip.id;
  node.draggable = true;
  node.innerHTML = `<img src="${clip.thumb}" loading="lazy" onerror="thumbFail(this)"><div>${esc(clip.keyword || clip.name)}</div>`;
  node.addEventListener("dragstart", e => {
    prepareDrag(e, clip);
  });
  node.addEventListener("click", () => previewEditClip(clip, node));
  return node;
}
function previewEditClip(clip, node=null){
  const video = $("editPreviewVideo");
  if(!video) return;
  selectedMatchClip = clip;
  document.querySelectorAll(".clip-mini.selected").forEach(el => el.classList.remove("selected"));
  const card = node || document.querySelector(`.clip-mini[data-clip-id="${clip.id}"]`);
  if(card) card.classList.add("selected");
  setFastVideoSource(video, clip, {muted:true, autoplay:true});
  $("matchStatus").textContent = `正在预览：${clip.keyword || clip.name}`;
  $("matchCopy").textContent = `当前素材：${clip.name}\n分类：${clip.location || ""} / ${clip.category || ""} / ${clip.keyword || ""}\n路径：${clip.path || ""}`;
}
function setFastVideoSource(video, item, options={}){
  const primary = item.media || item.preview_media;
  const fallback = item.preview_media && item.preview_media !== primary ? item.preview_media : "";
  if(!primary) return;
  video.pause();
  video.preload = "metadata";
  video.muted = options.muted ?? video.muted;
  video.dataset.fallbackSrc = fallback;
  video.dataset.usingFallback = "0";
  video.onerror = () => {
    const next = video.dataset.fallbackSrc;
    if(next && video.dataset.usingFallback !== "1"){
      video.dataset.usingFallback = "1";
      video.src = next;
      video.load();
      if(options.autoplay){
        const retry = video.play();
        if(retry && typeof retry.catch === "function") retry.catch(() => {});
      }
    }
  };
  video.src = primary;
  video.load();
  if(options.autoplay){
    const playPromise = video.play();
    if(playPromise && typeof playPromise.catch === "function"){
      playPromise.catch(() => {});
    }
  }
}
function makeTimelineEntry(clip, beat=null){
  const start = beat ? Number(beat.start || 0) : 0;
  const end = beat ? Number(beat.end || 0) : 0;
  const duration = Math.max(0.8, end > start ? end - start : 2.5);
  return {
    uid: `${Date.now()}_${Math.random().toString(16).slice(2)}`,
    clip,
    beatIndex: beat ? beat.index : null,
    text: beat ? String(beat.text || "") : "",
    visualNeed: beat ? String(beat.visual_need || clip.keyword || "") : String(clip.keyword || ""),
    duration,
  };
}
function addClipToTimeline(clip, beat=null){
  editTimeline.push(makeTimelineEntry(clip, beat));
  selectedTimelineIndex = editTimeline.length - 1;
  renderEditTimeline();
  previewEditClip(clip);
}
function renderEditTimeline(){
  const timeline = $("matchTimeline");
  if(!timeline) return;
  timeline.innerHTML = "";
  if(!editTimeline.length){
    timeline.innerHTML = '<div class="empty">把上方素材拖进来，或点击“开始匹配素材”自动生成粗剪主轨。</div>';
    $("matchStatus").textContent = "主轨为空";
    return;
  }
  editTimeline.forEach((entry, index) => {
    const node = document.createElement("div");
    node.className = "track-clip" + (index === selectedTimelineIndex ? " selected" : "");
    node.draggable = true;
    node.innerHTML = `<strong>${String(index + 1).padStart(2,"0")} ${esc(entry.clip.keyword || entry.clip.name)}</strong><span>${esc(entry.text || entry.visualNeed || entry.clip.category || "")}</span><small>${formatSeconds(entry.duration || 0)} / ${esc(entry.clip.name)}</small>`;
    node.addEventListener("click", () => selectTimelineClip(index));
    node.addEventListener("dragstart", e => {
      timelineDragClip = {clip: entry.clip};
      prepareDrag(e, entry.clip);
    });
    node.addEventListener("dragend", () => { timelineDragClip = null; });
    timeline.appendChild(node);
  });
  const total = editTimeline.reduce((sum, entry) => sum + Number(entry.duration || 0), 0);
  $("matchStatus").textContent = `主轨 ${editTimeline.length} 段 / 约 ${formatSeconds(total)}`;
}
function renderAudioTrack(){
  const track = $("matchAudioTrack");
  if(!track) return;
  if(!selectedMatchAudio){
    track.innerHTML = '<div class="empty">左侧选择音频后，这里显示主声音轨。</div>';
    return;
  }
  track.innerHTML = `<div class="audio-track-chip">🎙 ${esc(selectedMatchAudio.name)} · ${esc(selectedMatchAudio.location)} · ${selectedMatchAudio.size_mb} MB</div>`;
}
async function playTimelineSequence(){
  if(!editTimeline.length){
    await showMessage("还没有初筛素材", "先选择一条音频，让系统读取它对应的初筛素材。");
    return;
  }
  const token = ++timelinePlayToken;
  $("matchStatus").textContent = "合并播放中";
  for(let index = 0; index < editTimeline.length; index++){
    if(token !== timelinePlayToken) return;
    const entry = editTimeline[index];
    previewEditClip(entry.clip);
    const waitMs = Math.max(600, Number(entry.duration || 2.5) * 1000);
    await new Promise(resolve => setTimeout(resolve, waitMs));
  }
  if(token === timelinePlayToken){
    $("matchStatus").textContent = "合并播放完成";
  }
}
function selectTimelineClip(index){
  if(index < 0 || index >= editTimeline.length) return;
  selectedTimelineIndex = index;
  renderEditTimeline();
  const entry = editTimeline[index];
  previewEditClip(entry.clip);
  $("matchCopy").textContent = `已选主轨片段：${entry.clip.name}\n画面关键词：${entry.clip.keyword || entry.visualNeed || "未标注"}\n台词：${entry.text || "手动拖入素材"}\n\n可用操作：切割片段、裁剪/切割、删除片段。`;
}
function selectedTimelineEntry(){
  if(selectedTimelineIndex < 0 || selectedTimelineIndex >= editTimeline.length) return null;
  return editTimeline[selectedTimelineIndex];
}
async function splitSelectedTimelineClip(){
  const entry = selectedTimelineEntry();
  if(!entry){
    await showMessage("还没选片段", "先在主轨道点一个片段，再切割。");
    return;
  }
  const firstDuration = Math.max(0.4, Number(entry.duration || 1) / 2);
  const secondDuration = Math.max(0.4, Number(entry.duration || 1) - firstDuration);
  const left = {...entry, uid:`${Date.now()}_a`, duration:firstDuration, text:entry.text ? `${entry.text}（上半段）` : "上半段"};
  const right = {...entry, uid:`${Date.now()}_b`, duration:secondDuration, text:entry.text ? `${entry.text}（下半段）` : "下半段"};
  editTimeline.splice(selectedTimelineIndex, 1, left, right);
  renderEditTimeline();
}
async function cropSelectedTimelineClip(){
  const clip = selectedMatchClip || (editTimeline[0] ? editTimeline[0].clip : null);
  if(!clip){
    await showMessage("还没选素材", "先在左侧点一个素材封面，再打开裁剪/切割。");
    return;
  }
  openCropEditor(clip, "subtitle");
}
async function openMatchOutputFolder(){
  if(!selectedMatchAudio){
    await showMessage("还没选音频", "先在左侧选择一条原片音频。");
    return;
  }
  const btn = $("matchOpenPackBtn");
  const oldText = btn ? btn.textContent : "";
  if(btn){
    btn.disabled = true;
    btn.textContent = "打开中...";
  }
  try{
    const data = await getJson("/api/match-output-folder/" + encodeURIComponent(selectedMatchAudio.id));
    if(!data.ok){
      await showMessage("打开失败", data.error || "打开素材包失败");
      return;
    }
    $("matchStatus").textContent = data.message || "已打开素材包";
    if(!data.specific){
      $("matchCopy").textContent = `还没有找到本条音频对应的初剪素材包。\n\n已打开：${data.path}\n\n下一步：点击“复制配镜提示词”，把任务发给 Codex。生成后这里会打开对应素材包；你也可以把编号素材直接拖进剪映。`;
    }else{
      $("matchCopy").textContent = `已打开本条音频对应的初剪素材包：\n${data.path}\n\n用法：打开剪映后，把素材包里的编号视频按顺序拖进媒体区或时间线。`;
    }
  }catch(err){
    await showMessage("打开失败", String(err));
  }finally{
    if(btn){
      btn.disabled = false;
      btn.textContent = oldText || "打开素材包";
    }
  }
}
async function removeSelectedTimelineClip(){
  if(!selectedTimelineEntry()){
    await showMessage("还没选片段", "先在主轨道点一个片段，再删除。");
    return;
  }
  editTimeline.splice(selectedTimelineIndex, 1);
  selectedTimelineIndex = Math.min(selectedTimelineIndex, editTimeline.length - 1);
  renderEditTimeline();
}
async function copyTimelinePaths(){
  const paths = editTimeline.map(entry => entry.clip.path).filter(Boolean).join("\n");
  if(!paths){
    await showMessage("主轨为空", "还没有可以复制的素材路径。");
    return;
  }
  await navigator.clipboard.writeText(paths).catch(()=>{});
  $("matchStatus").textContent = `已复制 ${editTimeline.length} 段主轨路径`;
}
function clearTimeline(){
  editTimeline = [];
  selectedTimelineIndex = -1;
  renderEditTimeline();
}
function timeRange(start, end){
  const s = Number(start || 0);
  const e = Number(end || 0);
  return `${formatSeconds(s)}-${formatSeconds(e)}`;
}
function formatSeconds(value){
  const v = Math.max(0, Number(value || 0));
  const m = Math.floor(v / 60);
  const s = Math.floor(v % 60);
  return `${m}:${String(s).padStart(2,"0")}`;
}
function selectItem(item, card, options = {}){
  selectedId = item.id; selectedItem = item; document.querySelectorAll(".card").forEach(c=>c.classList.remove("selected"));
  if(card) card.classList.add("selected");
  const video = $("video");
  video.autoplay = true;
  video.playsInline = true;
  setFastVideoSource(video, item, {muted:!audioWanted, autoplay:true});
  $("detail").innerHTML = `<b>${esc(item.name)}</b><br>${esc(item.process_tag || "")} / ${esc(item.kind)} / ${esc(item.location)} / ${esc(item.category)} / ${esc(item.keyword)}<br>${item.size_mb} MB<br>${esc(item.path)}`;
  updateTranscriptButton(item);
  if(options.scrollPreview !== false){
    document.querySelector(".preview").scrollIntoView({block:"nearest", behavior:"smooth"});
  }
}
function updateTranscriptButton(item){
  const btn = $("copyTranscriptBtn");
  if(!item || !["已整理原片","未分类/未整理素材","分镜素材","原片音频素材"].includes(item.kind)){
    btn.disabled = true;
    btn.textContent = "复制视频文案";
    btn.title = "原片、未整理素材、分镜素材和原片音频都可以复制文案";
    return;
  }
  btn.disabled = false;
  btn.textContent = item.has_transcript ? "复制视频文案" : "识别并复制文案";
  btn.title = item.has_transcript ? "已有有效文案缓存或带时间戳 TXT，会直接复制" : "第一次会用 faster-whisper small 语音转文字，可能需要几分钟，并会缓存";
}
function syncAudioButton(){
  const video = $("video");
  if(video) video.muted = !audioWanted;
  $("muteToggle").textContent = audioWanted ? "静音播放" : "打开声音";
}
async function startDeepRepairForSelected(){
  if(!selectedItem){
    await showMessage("还没选素材", "先在中间点一个素材，再复制提示词。");
    return;
  }
  const prompt = [
    "请使用「团建视频素材智能分镜分类 Skill」里的深度修复模式处理这个素材。",
    "",
    `素材路径：${selectedItem.path}`,
    "",
    "工作流要求：",
    "1. 不要直接依赖本地按钮的自动检测；先从视频抽取 3-5 张代表帧。",
    "2. 用视觉 AI 判断画面里是否有水印、硬字幕、贴字或封面废料。",
    "3. 先给出精确修复框：x_min, y_min, x_max, y_max，并说明原因和置信度；不要把整块天空/水面都粗暴涂掉。",
    "4. 按画面选择修复策略：普通硬字幕用 VSR/STTN；半透明水印优先尝试窄蒙版 inpainting；纯天空/水面上的淡水印可尝试邻近区域复制/羽化补丁覆盖。",
    "5. 如果同源分镜有同位置水印，先找同源片段并复用同一个修复框，但每条仍要抽帧复核。",
    "6. 输出到原素材旁边的「深度修复」文件夹，不覆盖原文件，并生成前后对比帧。",
    "7. 修复后再次抽帧复核，明确说明水印是否还可见、是否有涂抹痕迹、是否适合进入干净素材库。",
    "8. 如果修复不合格，不要替换素材库；优先找干净替代素材，只保留测试结果和问题说明。"
  ].join("\\n");
  await navigator.clipboard.writeText(prompt).catch(()=>{});
  $("deepRepairBtn").textContent = "已复制";
  $("hint").textContent = "深度修复提示词已复制，贴到对话框后由 Codex 视觉定位并执行修复";
  setTimeout(() => $("deepRepairBtn").textContent = "复制提示词", 1000);
}
function bindCropUi(){
  $("cropBtn").addEventListener("click", () => {
    if(!selectedItem){
      showMessage("还没选素材", "先在中间点一个素材，再打开裁剪。");
      return;
    }
    openCropEditor(selectedItem, "subtitle");
  });
  $("deepRepairBtn").addEventListener("click", startDeepRepairForSelected);
  $("cropClose").addEventListener("click", closeCropEditor);
  $("cropCancel").addEventListener("click", closeCropEditor);
  $("cropBackdrop").addEventListener("click", e => {
    if(e.target.id === "cropBackdrop") closeCropEditor();
  });
  document.querySelectorAll("[data-crop-preset]").forEach(btn => {
    btn.addEventListener("click", () => setCropPreset(btn.dataset.cropPreset));
  });
  $("detectCropBtn").addEventListener("click", detectCropLine);
  $("saveCropLayoutBtn").addEventListener("click", saveCurrentCropLayout);
  $("deleteCropLayoutBtn").addEventListener("click", deleteCurrentCropLayout);
  $("cropLayoutSelect").addEventListener("change", applySavedCropLayout);
  $("cropApply").addEventListener("click", () => applyManualCrop(false));
  $("cropApplyDelete").addEventListener("click", () => applyManualCrop(true));
  $("cropVideo").addEventListener("loadedmetadata", () => {
    initTrimRangeFromCropVideo();
    setCropPreset(cropState.preset || "subtitle");
    setTimeout(renderCropBox, 60);
  });
  $("cropVideo").addEventListener("loadeddata", () => setTimeout(renderCropBox, 30));
  window.addEventListener("resize", renderCropBox);
  $("cropLayer").addEventListener("pointerdown", startCropDrag);
  document.addEventListener("pointermove", moveCropDrag);
  document.addEventListener("pointerup", endCropDrag);
}
function bindTrimUi(){
  $("cropVideo").addEventListener("timeupdate", () => {
    const video = $("cropVideo");
    if(trimState.end > trimState.start && video.currentTime > trimState.end){
      video.pause();
      video.currentTime = trimState.start;
    }
  });
  $("trimTimeline").addEventListener("pointerdown", startTrimDrag);
  $("trimStartHandle").addEventListener("pointerdown", startTrimDrag);
  $("trimEndHandle").addEventListener("pointerdown", startTrimDrag);
  document.addEventListener("pointermove", moveTrimDrag);
  document.addEventListener("pointerup", endTrimDrag);
  $("setTrimStartBtn").addEventListener("click", () => setTrimBoundary("start"));
  $("setTrimEndBtn").addEventListener("click", () => setTrimBoundary("end"));
  $("resetTrimBtn").addEventListener("click", resetTrimRange);
}
function openCropEditor(item, preset="subtitle"){
  selectedItem = item;
  cropState.preset = preset;
  cropState.autoDetect = true;
  trimState = {start:0, end:0, duration:0, drag:null};
  $("cropTitle").textContent = `当前素材：${item.name}`;
  $("cropDetectStatus").textContent = "打开后会自动检测字幕线；如果不准，拖动蓝色框微调。";
  loadCropLayouts();
  $("cropBackdrop").classList.add("open");
  const cropVideo = $("cropVideo");
  const previewVideo = $("video");
  cropVideo.pause();
  cropVideo.muted = true;
  const primary = item.media || item.preview_media;
  const fallback = item.preview_media && item.preview_media !== primary ? item.preview_media : "";
  cropVideo.dataset.fallbackSrc = fallback;
  cropVideo.dataset.usingFallback = "0";
  cropVideo.onerror = () => {
    const next = cropVideo.dataset.fallbackSrc;
    if(next && cropVideo.dataset.usingFallback !== "1"){
      cropVideo.dataset.usingFallback = "1";
      cropVideo.src = next;
      cropVideo.load();
    }
  };
  cropVideo.src = primary;
  cropVideo.load();
  cropVideo.oncanplay = () => {
    if(Number.isFinite(previewVideo.currentTime) && previewVideo.currentTime > 0){
      cropVideo.currentTime = Math.min(previewVideo.currentTime, cropVideo.duration || previewVideo.currentTime);
    }
    cropVideo.oncanplay = null;
    setTimeout(renderCropBox, 60);
    setTimeout(() => {
      if(cropState.autoDetect){
        cropState.autoDetect = false;
        detectCropLine();
      }
    }, 180);
  };
}
async function loadCropLayouts(){
  try{
    const result = await getJson("/api/crop-layouts");
    cropLayouts = result.ok ? (result.layouts || []) : [];
  }catch(err){
    cropLayouts = [];
  }
  renderCropLayoutSelect();
}
function renderCropLayoutSelect(){
  const sel = $("cropLayoutSelect");
  if(!sel) return;
  const current = sel.value;
  sel.innerHTML = '<option value="">选择已保存布局</option>';
  cropLayouts.forEach(layout => {
    const option = document.createElement("option");
    option.value = layout.name;
    const rect = layout.rect || {};
    option.textContent = `${layout.name}  h ${fmtPct(rect.h || 0)}%`;
    sel.appendChild(option);
  });
  sel.value = Array.from(sel.options).some(option => option.value === current) ? current : "";
}
function applySavedCropLayout(){
  const name = $("cropLayoutSelect").value;
  if(!name) return;
  const layout = cropLayouts.find(entry => entry.name === name);
  if(!layout || !layout.rect) return;
  cropState.preset = "layout";
  cropState.rect = normalizeCropRect(layout.rect);
  $("cropDetectStatus").textContent = `已套用布局：${name}`;
  renderCropBox();
}
async function detectCropLine(){
  if(!selectedItem) return;
  const btn = $("detectCropBtn");
  btn.disabled = true;
  btn.textContent = "检测中...";
  $("cropDetectStatus").textContent = "正在抽取 3 帧并判断底部字幕位置...";
  try{
    const result = await postJson("/api/detect-crop", {id:selectedItem.id});
    if(!result.ok){
      $("cropDetectStatus").textContent = result.error || "自动检测失败，先用默认裁切废料布局。";
      return;
    }
    cropState.preset = "auto";
    cropState.rect = applySubtitleCropSafety(result.rect || {x:0,y:0,w:100,h:74});
    const suggestedStart = Number(result.suggested_start || 0);
    if(suggestedStart > 0.2 && trimState.duration){
      trimState.start = Math.min(suggestedStart, Math.max(0, trimState.end - 0.2));
      $("cropVideo").currentTime = trimState.start;
      renderTrimTimeline();
    }
    $("cropDetectStatus").textContent = `${result.reason || "已自动检测"}；置信度 ${Math.round((result.confidence || 0) * 100)}%。`;
    renderCropBox();
  }finally{
    btn.disabled = false;
    btn.textContent = "自动检测字幕线";
  }
}
function applySubtitleCropSafety(rect){
  const safe = normalizeCropRect(rect);
  if(safe.h >= 70){
    safe.h = Math.max(45, safe.h - 2.5);
  }
  return normalizeCropRect(safe);
}
async function saveCurrentCropLayout(){
  const rect = normalizeCropRect(cropState.rect);
  const defaultName = `裁切废料_${fmtPct(rect.h)}`;
  const name = await openModal({
    title:"保存裁剪布局",
    body:"给这套字幕位置起个名字。以后同渠道素材可以直接套用。",
    value:defaultName,
    input:true,
    okText:"保存"
  });
  if(name === null || !name.trim()) return;
  const result = await postJson("/api/crop-layouts", {name:name.trim(), ...rect});
  if(!result.ok){
    await showMessage("保存布局失败", result.error || "保存布局失败");
    return;
  }
  cropLayouts = result.layouts || [];
  renderCropLayoutSelect();
  $("cropLayoutSelect").value = name.trim();
  $("cropDetectStatus").textContent = `已保存布局：${name.trim()}`;
}
async function deleteCurrentCropLayout(){
  const name = $("cropLayoutSelect").value;
  if(!name){
    await showMessage("还没选布局", "先选择一个已保存布局，再删除。");
    return;
  }
  const ok = await openModal({
    title:"删除裁剪布局",
    body:`只删除这个布局预设，不会删除任何视频素材：${name}`,
    input:false,
    okText:"删除布局"
  });
  if(!ok) return;
  const result = await postJson("/api/delete-crop-layout", {name});
  if(!result.ok){
    await showMessage("删除布局失败", result.error || "删除布局失败");
    return;
  }
  cropLayouts = result.layouts || [];
  renderCropLayoutSelect();
  $("cropDetectStatus").textContent = `已删除布局：${name}`;
}
function closeCropEditor(){
  $("cropBackdrop").classList.remove("open");
  cropState.drag = null;
  trimState.drag = null;
  const cropVideo = $("cropVideo");
  cropVideo.pause();
}
function setCropPreset(preset){
  const video = $("cropVideo");
  cropState.preset = preset;
  if(preset === "full"){
    cropState.rect = {x:0,y:0,w:100,h:100};
  }else if(preset === "vertical" || preset === "portrait34"){
    const vw = video.videoWidth || 9;
    const vh = video.videoHeight || 16;
    const target = preset === "portrait34" ? 3 / 4 : 9 / 16;
    const current = vw / vh;
    if(current > target){
      const w = Math.max(5, Math.min(100, target / current * 100));
      cropState.rect = {x:(100 - w) / 2, y:0, w, h:100};
    }else{
      const h = Math.max(5, Math.min(100, current / target * 100));
      cropState.rect = {x:0, y:(100 - h) / 2, w:100, h};
    }
  }else{
    cropState.rect = {x:0,y:0,w:100,h:74};
  }
  renderCropBox();
}
function videoDisplayRect(){
  const video = $("cropVideo");
  const stage = $("cropStage");
  const vr = video.getBoundingClientRect();
  const sr = stage.getBoundingClientRect();
  return {left:vr.left - sr.left, top:vr.top - sr.top, width:vr.width, height:vr.height};
}
function renderCropBox(){
  const layer = $("cropLayer");
  const rect = normalizeCropRect(cropState.rect);
  cropState.rect = rect;
  const vr = videoDisplayRect();
  if(!vr.width || !vr.height){
    layer.classList.remove("ready");
    return;
  }
  layer.classList.add("ready");
  layer.style.left = `${vr.left + rect.x / 100 * vr.width}px`;
  layer.style.top = `${vr.top + rect.y / 100 * vr.height}px`;
  layer.style.width = `${rect.w / 100 * vr.width}px`;
  layer.style.height = `${rect.h / 100 * vr.height}px`;
  $("cropReadout").textContent = `x ${fmtPct(rect.x)}% / y ${fmtPct(rect.y)}% / w ${fmtPct(rect.w)}% / h ${fmtPct(rect.h)}%`;
}
function startCropDrag(e){
  if(!$("cropLayer").classList.contains("ready")) return;
  e.preventDefault();
  e.stopPropagation();
  const handle = e.target.dataset.handle || "move";
  cropState.drag = {handle, startX:e.clientX, startY:e.clientY, startRect:{...cropState.rect}};
  $("cropLayer").setPointerCapture(e.pointerId);
}
function moveCropDrag(e){
  if(!cropState.drag) return;
  const vr = videoDisplayRect();
  if(!vr.width || !vr.height) return;
  const dx = (e.clientX - cropState.drag.startX) / vr.width * 100;
  const dy = (e.clientY - cropState.drag.startY) / vr.height * 100;
  const start = cropState.drag.startRect;
  let next = {...start};
  const handle = cropState.drag.handle;
  if(handle === "move"){
    next.x = start.x + dx;
    next.y = start.y + dy;
  }else{
    if(handle.includes("w")){
      next.x = start.x + dx;
      next.w = start.w - dx;
    }
    if(handle.includes("e")){
      next.w = start.w + dx;
    }
    if(handle.includes("n")){
      next.y = start.y + dy;
      next.h = start.h - dy;
    }
    if(handle.includes("s")){
      next.h = start.h + dy;
    }
  }
  cropState.rect = normalizeCropRect(next);
  renderCropBox();
}
function endCropDrag(){
  cropState.drag = null;
}
function normalizeCropRect(rect){
  const minSize = 5;
  let x = Number(rect.x) || 0;
  let y = Number(rect.y) || 0;
  let w = Number(rect.w) || 100;
  let h = Number(rect.h) || 100;
  if(w < minSize){ x -= (minSize - w); w = minSize; }
  if(h < minSize){ y -= (minSize - h); h = minSize; }
  x = Math.max(0, Math.min(100 - minSize, x));
  y = Math.max(0, Math.min(100 - minSize, y));
  w = Math.max(minSize, Math.min(100 - x, w));
  h = Math.max(minSize, Math.min(100 - y, h));
  return {x,y,w,h};
}
function fmtPct(value){
  return Math.round(value * 10) / 10;
}
async function applyManualCrop(deleteOriginal=false){
  if(!selectedItem) return;
  const sourceItem = selectedItem;
  const rect = normalizeCropRect(cropState.rect);
  const applyBtn = $("cropApply");
  const applyDeleteBtn = $("cropApplyDelete");
  const activeBtn = deleteOriginal ? applyDeleteBtn : applyBtn;
  applyBtn.disabled = true;
  applyDeleteBtn.disabled = true;
  activeBtn.textContent = deleteOriginal ? "输出并删除中..." : "输出中...";
  $("hint").textContent = deleteOriginal ? "正在输出新素材，成功后把原素材移到回收站..." : "正在输出新素材...";
  try{
    const result = await postJson("/api/manual-process", {id:selectedItem.id, ...rect, start:trimState.start, end:trimState.end});
    if(!result.ok){
      await showMessage("输出新素材失败", result.error || "输出新素材失败");
      return;
    }
    closeCropEditor();
    await refreshOptions();
    if(result.item){
      insertGeneratedItemNearSource(sourceItem, result.item);
    }else{
      await load({preserveScroll:true});
    }
    let deletedOriginal = false;
    if(deleteOriginal){
      releaseVideoHandlesForItem(sourceItem);
      const deleteResult = await postJson("/api/delete", {id:sourceItem.id});
      if(deleteResult.ok){
        deletedOriginal = true;
        removeDeletedItemFromView(sourceItem);
        await refreshOptions();
      }else{
        await showMessage("原素材未删除", deleteResult.error || "新素材已输出，但原素材移到回收站失败");
      }
    }
    if(result.item){
      const status = deletedOriginal ? "已输出新素材；原素材已移到回收站" : "已输出新素材";
      $("detail").innerHTML = `<b>${esc(result.item.name)}</b><br>${esc(result.item.kind)} / ${esc(result.item.location)} / ${esc(result.item.category)} / ${esc(result.item.keyword)}<br>${result.item.size_mb} MB<br>${status}<br>${esc(result.item.path)}`;
    }
  }finally{
    applyBtn.disabled = false;
    applyDeleteBtn.disabled = false;
    applyBtn.textContent = "输出新素材";
    applyDeleteBtn.textContent = "输出新素材并删除原素材";
  }
}
function initTrimRangeFromCropVideo(){
  const video = $("cropVideo");
  const duration = Number.isFinite(video.duration) ? video.duration : 0;
  trimState.duration = duration;
  trimState.start = duration > 0.3 ? 0.08 : 0;
  trimState.end = duration;
  renderTrimTimeline();
}
function renderTrimTimeline(){
  const duration = trimState.duration || 0;
  const start = clampTime(trimState.start, 0, duration);
  const end = clampTime(trimState.end || duration, start + 0.2, duration || start + 0.2);
  trimState.start = start;
  trimState.end = end;
  const startPct = duration ? start / duration * 100 : 0;
  const endPct = duration ? end / duration * 100 : 100;
  $("trimStartHandle").style.left = `${startPct}%`;
  $("trimEndHandle").style.left = `${endPct}%`;
  $("trimSelection").style.left = `${startPct}%`;
  $("trimSelection").style.width = `${Math.max(0, endPct - startPct)}%`;
  $("trimStartText").textContent = formatClock(start);
  $("trimEndText").textContent = formatClock(end);
  $("trimDurationText").textContent = formatClock(Math.max(0, end - start));
  $("trimReadout").textContent = `保留 ${formatClock(end - start)}，从 ${formatClock(start)} 到 ${formatClock(end)}。输出到：手动处理`;
}
function startTrimDrag(e){
  if(!trimState.duration) return;
  e.preventDefault();
  e.stopPropagation();
  const timeline = $("trimTimeline");
  const rect = timeline.getBoundingClientRect();
  const clickTime = clampTime((e.clientX - rect.left) / Math.max(1, rect.width) * trimState.duration, 0, trimState.duration);
  let handle = e.target.dataset.handle;
  if(!handle){
    handle = Math.abs(clickTime - trimState.start) <= Math.abs(clickTime - trimState.end) ? "start" : "end";
  }
  trimState.drag = {handle};
  timeline.setPointerCapture(e.pointerId);
  updateTrimFromPointer(e);
}
function moveTrimDrag(e){
  if(!trimState.drag) return;
  updateTrimFromPointer(e);
}
function endTrimDrag(e){
  if(!trimState.drag) return;
  try{ $("trimTimeline").releasePointerCapture(e.pointerId); }catch(_){}
  trimState.drag = null;
}
function updateTrimFromPointer(e){
  const timeline = $("trimTimeline");
  const rect = timeline.getBoundingClientRect();
  const value = clampTime((e.clientX - rect.left) / Math.max(1, rect.width) * trimState.duration, 0, trimState.duration);
  if(trimState.drag.handle === "start"){
    trimState.start = Math.min(value, Math.max(0, trimState.end - 0.2));
    $("cropVideo").currentTime = trimState.start;
  }else{
    trimState.end = Math.max(value, trimState.start + 0.2);
    $("cropVideo").currentTime = Math.max(trimState.start, Math.min(trimState.end, value));
  }
  renderTrimTimeline();
}
function setTrimBoundary(which){
  const video = $("cropVideo");
  const time = clampTime(video.currentTime || 0, 0, trimState.duration || 0);
  if(which === "start"){
    trimState.start = Math.min(time, Math.max(0, trimState.end - 0.2));
  }else{
    trimState.end = Math.max(time, trimState.start + 0.2);
  }
  renderTrimTimeline();
}
function resetTrimRange(){
  trimState.start = 0;
  trimState.end = trimState.duration || 0;
  $("cropVideo").currentTime = 0;
  renderTrimTimeline();
}
function clampTime(value, min, max){
  const number = Number(value);
  if(!Number.isFinite(number)) return min;
  return Math.max(min, Math.min(max, number));
}
function formatClock(seconds){
  const value = Math.max(0, Number(seconds) || 0);
  const minutes = Math.floor(value / 60);
  const rest = value - minutes * 60;
  return `${String(minutes).padStart(2,"0")}:${rest.toFixed(1).padStart(4,"0")}`;
}
function prepareDrag(e, item){
  const fileUrl = item.file_uri;
  const httpUrl = location.origin + item.media;
  e.dataTransfer.effectAllowed = "copy";
  e.dataTransfer.setData("text", item.path);
  e.dataTransfer.setData("text/plain", item.path);
  e.dataTransfer.setData("text/uri-list", `${fileUrl}\n${httpUrl}`);
  e.dataTransfer.setData("text/x-moz-url", `${fileUrl}\n${item.name}`);
  e.dataTransfer.setData("text/html", `<a href="${fileUrl}" data-path="${esc(item.path)}">${esc(item.name)}</a>`);
  e.dataTransfer.setData("DownloadURL", `${item.mime}:${item.name}:${httpUrl}`);
}
function showCardMenu(e, item, card){
  e.preventDefault();
  e.stopPropagation();
  selectItem(item, card);
  contextMenuItem = item;
  const menu = $("cardMenu");
  menu.classList.add("open");
  const x = Math.min(e.clientX, window.innerWidth - menu.offsetWidth - 10);
  const y = Math.min(e.clientY, window.innerHeight - menu.offsetHeight - 10);
  menu.style.left = `${Math.max(10, x)}px`;
  menu.style.top = `${Math.max(10, y)}px`;
}
function hideCardMenu(){
  const menu = $("cardMenu");
  if(menu) menu.classList.remove("open");
}
async function handleCardMenuAction(e){
  const btn = e.target.closest("button");
  if(!btn || !contextMenuItem) return;
  e.stopPropagation();
  const item = contextMenuItem;
  hideCardMenu();
  if(btn.dataset.action === "rename"){
    await renameItem(item);
  }else if(btn.dataset.action === "tag"){
    await addTag(item);
  }else if(btn.dataset.action === "crop"){
    openCropEditor(item, "subtitle");
  }else if(btn.dataset.action === "reveal"){
    await fetch(item.reveal).catch(()=>{});
  }else if(btn.dataset.action === "copy"){
    await navigator.clipboard.writeText(item.path).catch(()=>{});
  }else if(btn.dataset.action === "delete"){
    await deleteItem(item);
  }
}
function openModal({title, body="", value="", input=true, okText="确定", inputType="text"}){
  return new Promise(resolve => {
    modalResolve = resolve;
    $("modalTitle").textContent = title;
    $("modalBody").textContent = body;
    $("modalOk").textContent = okText;
    $("modalInput").style.display = input ? "block" : "none";
    $("modalInput").type = inputType;
    $("modalInput").value = value || "";
    $("modalBackdrop").classList.add("open");
    if(input){
      setTimeout(() => {
        $("modalInput").focus();
        $("modalInput").select();
      }, 30);
    }else{
      setTimeout(() => $("modalOk").focus(), 30);
    }
  });
}
function closeModal(value){
  $("modalBackdrop").classList.remove("open");
  const resolve = modalResolve;
  modalResolve = null;
  if(resolve) resolve(value);
}
async function showMessage(title, body){
  await openModal({title, body, input:false, okText:"知道了"});
}
async function renameItem(item){
  const sourceItem = item;
  const dot = item.name.lastIndexOf(".");
  const stem = dot > 0 ? item.name.slice(0, dot) : item.name;
  const suffix = dot > 0 ? item.name.slice(dot) : "";
  const next = await openModal({
    title:"重命名素材",
    body:`当前文件：${item.name}`,
    value:stem,
    input:true,
    okText:"保存"
  });
  if(next === null) return;
  const clean = next.trim();
  if(!clean) return;
  const video = $("video");
  video.pause();
  video.removeAttribute("src");
  video.load();
  const requested = clean.toLowerCase().endsWith(suffix.toLowerCase()) ? clean : clean + suffix;
  const result = await postJson("/api/rename", {id:item.id, name: requested});
  if(!result.ok){
    await showMessage("重命名失败", result.error || "重命名失败");
    return;
  }
  selectedItem = result.item;
  selectedId = result.item.id;
  await refreshOptions();
  if(!replaceItemInView(sourceItem, result.item)){
    await load({preserveScroll:true});
  }
  $("detail").innerHTML = `<b>${esc(result.item.name)}</b><br>${esc(result.item.kind)} / ${esc(result.item.location)} / ${esc(result.item.category)} / ${esc(result.item.keyword)}<br>${result.item.size_mb} MB<br>${esc(result.item.path)}`;
}
async function addTag(item){
  const tag = await openModal({
    title:"添加标签",
    body:"例如：干净素材 / 待裁剪 / 可做转场",
    value:"",
    input:true,
    okText:"添加"
  });
  if(tag === null || !tag.trim()) return;
  const result = await postJson("/api/tag", {id:item.id, tag:tag.trim()});
  if(!result.ok){
    await showMessage("添加标签失败", result.error || "添加标签失败");
    return;
  }
  selectedItem = result.item;
  await refreshOptions();
  if(!replaceItemInView(item, result.item)){
    await load({preserveScroll:true});
  }
}
async function cropSubtitleTop(item){
  const keep = await openModal({
    title:"裁切废料",
    body:"保留画面上方百分比。默认 74；字幕越高，数值越小。会生成新视频，不覆盖原片。",
    value:"74",
    input:true,
    inputType:"number",
    okText:"下一步"
  });
  if(keep === null) return;
  const keepPct = Number(keep);
  if(!Number.isFinite(keepPct) || keepPct < 45 || keepPct > 95){
    await showMessage("裁剪比例不对", "请输入 45 到 95 之间的数字");
    return;
  }
  const ok = await openModal({
    title:"确认开始裁剪",
    body:`会生成一个新视频，不覆盖原片。保留上方 ${keepPct}% 画面，继续吗？`,
    input:false,
    okText:"开始裁剪"
  });
  if(!ok) return;
  $("hint").textContent = "正在裁剪素材...";
  const result = await postJson("/api/crop-subtitle-top", {id:item.id, keep_pct:keepPct});
  if(!result.ok){
    await showMessage("裁剪失败", result.error || "裁剪失败");
    $("hint").textContent = `找到 ${lastTotal} 条素材`;
    return;
  }
  if(result.item){
    insertGeneratedItemNearSource(item, result.item);
  }
  await refreshOptions();
  if(result.item){
    $("detail").innerHTML = `<b>${esc(result.item.name)}</b><br>${esc(result.item.kind)} / ${esc(result.item.location)} / ${esc(result.item.category)} / ${esc(result.item.keyword)}<br>${result.item.size_mb} MB<br>已生成裁切废料版<br>${esc(result.item.path)}`;
  }
}
async function deleteItem(item){
  const ok = await openModal({
    title:"移到回收站",
    body:`会把这个素材和同名文案记录移动到电脑回收站，可从系统回收站恢复：${item.name}`,
    input:false,
    okText:"移到回收站"
  });
  if(!ok) return;
  releaseVideoHandlesForItem(item);
  const result = await postJson("/api/delete", {id:item.id});
  if(!result.ok){
    await showMessage("删除失败", result.error || "删除失败");
    return;
  }
  if(selectedId === item.id){
    selectedId = "";
    selectedItem = null;
    const video = $("video");
    video.pause();
    video.removeAttribute("src");
    video.load();
    $("detail").textContent = `已移到电脑回收站：${item.name}`;
  }
  removeDeletedItemFromView(item);
  await refreshOptions();
}
async function deleteItemDirect(item){
  releaseVideoHandlesForItem(item);
  const result = await postJson("/api/delete", {id:item.id});
  if(!result.ok){
    await showMessage("删除失败", result.error || "删除失败");
    return;
  }
  if(selectedId === item.id){
    selectedId = "";
    selectedItem = null;
    const video = $("video");
    video.pause();
    video.removeAttribute("src");
    video.load();
    $("detail").textContent = `已移到电脑回收站：${item.name}`;
  }
  removeDeletedItemFromView(item);
  await refreshOptions();
}
function removeDeletedItemFromView(item){
  const grid = $("grid");
  const card = grid ? grid.querySelector(`[data-item-id="${CSS.escape(item.id)}"]`) : null;
  const pane = document.querySelector("main");
  const scrollTop = pane ? pane.scrollTop : 0;
  if(card) card.remove();
  currentVisibleItemIds = currentVisibleItemIds.filter(id => id !== item.id);
  lastTotal = Math.max(0, Number(lastTotal || 0) - 1);
  $("resultCount").textContent = lastTotal;
  $("hint").textContent = `找到 ${lastTotal} 条素材，已加载 ${currentVisibleItemIds.length} 条`;
  $("pageText").textContent = hasMoreItems
    ? `已加载 ${currentVisibleItemIds.length} / 共 ${lastTotal} 条，继续下滑自动加载`
    : `已全部加载 ${currentVisibleItemIds.length} 条`;
  if(pane) requestAnimationFrame(() => { pane.scrollTop = scrollTop; });
  if(hasMoreItems && currentVisibleItemIds.length < lastTotal){
    loadNextPage();
  }
}
function bindDeleteKey(){
  document.addEventListener("keydown", async e => {
    if(e.key !== "Delete" || !selectedItem) return;
    const target = e.target;
    const editing = target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable);
    if(editing || $("modalBackdrop").classList.contains("open") || $("cropBackdrop").classList.contains("open")) return;
    e.preventDefault();
    await deleteItemDirect(selectedItem);
  });
}
function releaseVideoHandlesForItem(item){
  ["video", "cropVideo", "editPreviewVideo"].forEach(id => {
    const video = $(id);
    if(!video) return;
    const src = video.currentSrc || video.src || "";
    const shouldRelease = !item || !item.name || src.includes(encodeURIComponent(item.id || "")) || src.includes("/media/") || src.includes("/preview/");
    if(!shouldRelease) return;
    try{
      video.pause();
      video.removeAttribute("src");
      video.load();
    }catch(err){}
  });
}
function thumbFail(img){
  img.onerror = null;
  img.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" width="360" height="560"><rect width="100%" height="100%" fill="#e5e7eb"/><text x="50%" y="50%" text-anchor="middle" fill="#667085" font-size="28">无预览图</text></svg>`);
}
function esc(s){ return String(s||"").replace(/[&<>"']/g, m => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m])); }
async function reloadWithOptions(){
  page = 1;
  await refreshOptions();
  await load();
  saveFilterState();
}
$("reset").onclick = async () => { ["q","kind","location","category","keyword"].forEach(id=>$(id).value=""); await reloadWithOptions(); };
$("copyWorkflowPrompt").onclick = async () => {
  const prompt = [
    "使用本地 Skill：C:\\Users\\z\\.codex\\skills\\teambuilding-video-scene-library",
    "当前目标：素材初加工。",
    "总原则：本地工具只做预览、选择、确定性执行和记录；需要视觉理解、语义匹配、审美判断、修复复核的环节，必须交给 Codex/视觉 AI Skill，不要强行写成本地假智能按钮。",
    "对指定视频或文件夹执行：抽取 3 帧判断底部字幕顶部，裁掉字幕以下生成不覆盖原片的新素材；如果检测不准，使用已保存的手动裁剪布局；裁剪后再按需切分镜头；视觉优先识别画面关键词；归入对应智能镜头分类目录；更新 CSV/JSON 记录。",
    "可直接用工具做：扫描、抽帧、裁切废料、提取音频、复制编号素材、打开文件夹、移动到回收站。",
    "必须交给 AI 做：深度修复定位、智能剪辑配镜、素材分类纠错、成片质检、参考视频学习。",
    "规则：不覆盖原片，不保留重复下载副本，大字贴纸/标题卡/无法处理的废料片段直接废弃或待复核。"
  ].join("\\n");
  await navigator.clipboard.writeText(prompt).catch(()=>{});
  $("copyWorkflowPrompt").textContent = "已复制";
  setTimeout(() => $("copyWorkflowPrompt").textContent = "复制给其他工具", 1000);
};
$("prev").onclick = () => { if(page>1){ page--; load(); saveFilterState(); } };
$("next").onclick = () => { if(page < Math.ceil(lastTotal/pageSize)){ page++; load(); saveFilterState(); } };
$("q").addEventListener("keydown", e => { if(e.key==="Enter"){ page=1; load(); saveFilterState(); } });
["kind","location","category","keyword"].forEach(id => {
  $(id).addEventListener("change", () => { if(!refreshingOptions) reloadWithOptions(); });
});
if($("sort")){
  $("sort").addEventListener("change", () => { page = 1; load(); saveFilterState(); });
}
let searchTimer = null;
$("q").addEventListener("input", () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => { reloadWithOptions(); }, 260);
});
$("muteToggle").onclick = () => {
  const video = $("video");
  audioWanted = video.muted;
  localStorage.setItem("teamVideoBrowserAudioWanted", audioWanted ? "1" : "0");
  syncAudioButton();
  $("muteToggle").textContent = video.muted ? "打开声音" : "静音播放";
  syncAudioButton();
  const p = video.play();
  if(p && typeof p.catch === "function") p.catch(() => {});
};
$("revealBtn").onclick = async () => {
  if(!selectedItem) return;
  await fetch(selectedItem.reveal).catch(()=>{});
};
$("copyPathBtn").onclick = async () => {
  if(!selectedItem) return;
  await navigator.clipboard.writeText(selectedItem.path).catch(()=>{});
  $("copyPathBtn").textContent = "已复制";
  setTimeout(() => $("copyPathBtn").textContent = "复制路径", 900);
};
$("copyTranscriptBtn").onclick = async () => {
  if(!selectedItem || !["已整理原片","未分类/未整理素材","分镜素材","原片音频素材"].includes(selectedItem.kind)) return;
  const btn = $("copyTranscriptBtn");
  const oldText = btn.textContent;
  btn.disabled = true;
  btn.textContent = selectedItem.has_transcript ? "复制中..." : "识别中...";
  try{
    const data = await getJson(selectedItem.transcript);
    if(!data.ok || !data.text){
      btn.textContent = data.error || "没有识别到文案";
      setTimeout(() => updateTranscriptButton(selectedItem), 1800);
      return;
    }
    await navigator.clipboard.writeText(data.text);
    selectedItem.has_transcript = true;
    btn.textContent = "文案已复制";
    $("detail").innerHTML = `<b>${esc(selectedItem.name)}</b><br>${esc(selectedItem.kind)} / ${esc(selectedItem.location)} / ${esc(selectedItem.category)} / ${esc(selectedItem.keyword)}<br>${selectedItem.size_mb} MB<br>文案来源：${esc(data.source)}<br>${esc(selectedItem.path)}`;
    setTimeout(() => updateTranscriptButton(selectedItem), 1200);
  }catch(err){
    btn.textContent = "复制失败";
    setTimeout(() => { btn.textContent = oldText; btn.disabled = false; }, 1800);
  }
};
function bindBatchCropUi(){
  $("batchProcessQuickBtn").addEventListener("click", openBatchProcess);
  $("batchCropClose").addEventListener("click", closeBatchCrop);
  $("batchCropBackdrop").addEventListener("click", e => { if(e.target.id === "batchCropBackdrop") closeBatchCrop(); });
  $("batchStopBtn").addEventListener("click", stopBatchCrop);
  $("batchRefreshBtn").addEventListener("click", async () => { await refreshOptions(); await load({preserveScroll:true}); closeBatchCrop(); });

  $("batchTranscribeClose").addEventListener("click", closeBatchTranscribe);
  $("batchTranscribeBackdrop").addEventListener("click", e => { if(e.target.id === "batchTranscribeBackdrop") closeBatchTranscribe(); });
  $("batchTranscribeStopBtn").addEventListener("click", stopBatchTranscribe);
  $("batchTranscribeRefreshBtn").addEventListener("click", async () => { await refreshOptions(); await load({preserveScroll:true}); closeBatchTranscribe(); });
}
let batchPollTimer = null;
function openBatchProcess(){
  $("batchTranscribeBackdrop").classList.add("open");
  $("batchProcessTitle").textContent = "批量处理";
  $("batchTranscribeTitle").textContent = "统一处理入口：先选你要做的批处理，再进入对应设置。所有任务都会显示进度和结果。";
  $("batchTranscribeContent").innerHTML = `
    <div class="batch-action-grid">
      <div class="batch-action-card">
        <span class="batch-badge">素材初加工</span>
        <h4>裁切废料</h4>
        <p>批量检测分镜素材的顶部/底部字幕线和废片头，先干跑检查，再输出“裁切废料”新素材。适合清洗已经分镜的素材。</p>
        <div class="action-row">
          <button class="primary" id="batchProcessOpenCrop">展开处理</button>
        </div>
      </div>
      <div class="batch-action-card">
        <span class="batch-badge">文案资产</span>
        <h4>提取音频和文案素材</h4>
        <p>给原片、分镜或音频素材转写文案；同时产出带时间戳 TXT 给剪辑用、纯文案 TXT 给创作用。已有有效文案会自动跳过。</p>
        <div class="action-row">
          <button class="primary" id="batchProcessOpenTranscribe">展开处理</button>
        </div>
      </div>
      <div class="batch-action-card">
        <span class="batch-badge">智能剪辑主线</span>
        <h4>提取音频/文案素材</h4>
        <p>从“已整理原片”提取可复用口播音频；也可以顺带补齐时间戳文案，给后面的文案配镜使用。</p>
        <div class="action-row">
          <button id="batchProcessAudioOnly">只提取音频</button>
          <button class="primary" id="batchProcessAudioText">音频+文案</button>
        </div>
      </div>
    </div>
  `;
  const batchCards = $("batchTranscribeContent").querySelectorAll(".batch-action-card");
  if(batchCards[1]) batchCards[1].remove();
  const audioOnlyBtn = $("batchProcessAudioOnly");
  if(audioOnlyBtn) audioOnlyBtn.remove();
  const audioTextBtn = $("batchProcessAudioText");
  if(audioTextBtn) audioTextBtn.textContent = "展开处理";
  $("batchTranscribeToolbar").style.display = "none";
  $("batchProcessOpenCrop").addEventListener("click", () => {
    closeBatchTranscribe();
    openBatchCrop();
  });
  const openTranscribeBtn = $("batchProcessOpenTranscribe");
  if(openTranscribeBtn) openTranscribeBtn.addEventListener("click", openBatchTranscribe);
  if(audioTextBtn) audioTextBtn.addEventListener("click", () => startBatchExtractAudio(true));
}
function openBatchCrop(){
  $("batchCropBackdrop").classList.add("open");
  $("batchCropContent").innerHTML = `
    <div style="padding:20px; text-align:center;">
      <p style="color:var(--muted);">点击下方按钮开始干跑检测，确认后再执行裁剪。</p>
      <p style="color:#41d5cf; margin-top:10px;">正式执行会在原位置旁边生成“裁切废料”新素材，不覆盖原分镜。</p>
      <div style="margin-top:20px; display:flex; gap:10px; justify-content:center;">
        <button class="primary" id="batchDryRunBtn">先干跑检测（不裁剪）</button>
        <button class="primary" id="batchExecuteBtn">输出裁切废料新素材</button>
      </div>
    </div>
  `;
  $("batchCropToolbar").style.display = "none";
  $("batchDryRunBtn").addEventListener("click", () => startBatchCrop(true));
  $("batchExecuteBtn").addEventListener("click", () => startBatchCrop(false));
}
function closeBatchCrop(){
  $("batchCropBackdrop").classList.remove("open");
  stopBatchCrop();
}
async function startBatchCrop(dryRun){
  const confirmText = dryRun ? "开始干跑检测？这不会修改任何文件，只是检测并报告结果。" :
    "确定要批量输出裁切废料新素材吗？\n\n系统会在每个分镜素材旁边创建“裁切废料”文件夹并输出新视频，原分镜文件不覆盖、不删除。\n\n已在“裁切废料/裁去字幕/字幕之上/手动处理”里的二次产物会自动跳过。";
  const ok = await openModal({
    title: dryRun ? "确认干跑检测" : "确认批量裁剪",
    body: confirmText,
    input: false,
    okText: dryRun ? "开始检测" : "输出新素材"
  });
  if(!ok) return;
  $("batchCropContent").innerHTML = `<div style="padding:20px; text-align:center;"><div style="width:50px;height:50px;border:4px solid var(--accent);border-top-color:transparent;border-radius:50%;animation:spin 1s linear infinite;margin:0 auto 16px;"></div><p>任务启动中...</p></div>`;
  $("batchCropToolbar").style.display = "grid";
  $("batchProgressText").textContent = "连接中...";
  const result = await postJson("/api/batch-crop-subtitles", {dry_run: dryRun, confidence_threshold: 0.35, item_ids: currentVisibleItemIds});
  if(!result.ok){
    await showMessage("启动失败", result.error || "启动失败");
    openBatchCrop();
    return;
  }
  startBatchPoll();
}
function stopBatchCrop(){
  if(batchPollTimer){
    clearInterval(batchPollTimer);
    batchPollTimer = null;
  }
}
function openBatchExtractAudio(){
  $("batchTranscribeBackdrop").classList.add("open");
  $("batchProcessTitle").textContent = "批量提取音频/文案素材";
  $("batchTranscribeTitle").textContent = "从已整理原片提取可复用口播音频；可只提取音频，也可以顺带生成时间戳文案。";
  $("batchTranscribeContent").innerHTML = `
    <div style="padding:20px; text-align:center;">
      <p style="color:var(--muted);">从“已整理原片”里提取口播音频，输出到“已整理原片音频”素材库。</p>
      <p style="color:#41d5cf; margin-top:10px;">已有同名音频或有效文案会自动跳过；原视频不会被修改。</p>
      <div style="margin-top:20px; display:flex; gap:10px; justify-content:center;">
        <button id="batchExtractAudioOnlyBtn">只提取音频</button>
        <button class="primary" id="batchExtractAudioWithTextBtn">提取音频+时间戳文案</button>
      </div>
    </div>
  `;
  $("batchTranscribeToolbar").style.display = "none";
  $("batchExtractAudioOnlyBtn").addEventListener("click", () => startBatchExtractAudio(false));
  $("batchExtractAudioWithTextBtn").addEventListener("click", () => startBatchExtractAudio(true));
}
async function startBatchExtractAudio(transcribe=false){
  const title = transcribe ? "确认提取音频和时间戳文案" : "确认只提取音频";
  const body = transcribe
    ? "系统会扫描所有“已整理原片”，先提取 .m4a 音频，再复用同名文案或进行语音转文字，生成可用于文案配镜的时间戳文案。已有音频/有效文案会跳过。"
    : "系统会扫描所有“已整理原片”，把有音轨的视频提取成 .m4a，放入“已整理原片音频”素材库。已提取过的会跳过。";
  const ok = await openModal({
    title,
    body,
    input: false,
    okText: transcribe ? "开始提取并转写" : "开始提取"
  });
  if(!ok) return;
  $("batchProcessTitle").textContent = transcribe ? "批量提取音频+时间戳文案" : "批量提取音频";
  $("batchTranscribeTitle").textContent = transcribe ? "正在提取音频并补齐文案..." : "正在提取可复用口播音频...";
  $("batchTranscribeContent").innerHTML = `<div style="padding:20px; text-align:center;"><div style="width:50px;height:50px;border:4px solid var(--accent);border-top-color:transparent;border-radius:50%;animation:spin 1s linear infinite;margin:0 auto 16px;"></div><p>任务启动中...</p></div>`;
  $("batchTranscribeToolbar").style.display = "grid";
  $("batchTranscribeProgressText").textContent = "连接中...";
  const result = await postJson("/api/batch-extract-audio", {location: "", transcribe});
  if(!result.ok){
    await showMessage("启动失败", result.error || "启动失败");
    openBatchExtractAudio();
    return;
  }
  startTranscribePoll(transcribe ? "批量提取音频+文案完成" : "批量提取音频完成");
}
function openBatchTranscribe(){
  $("batchTranscribeBackdrop").classList.add("open");
  $("batchProcessTitle").textContent = "批量提取音频和文案素材";
  $("batchTranscribeTitle").textContent = "批量转写文案：时间戳版 + 纯文案版";
  $("batchTranscribeContent").innerHTML = `
    <div style="padding:20px; text-align:center;">
      <p style="color:var(--muted);">点击下方按钮开始批量识别。首次加载模型或长视频会比较慢。</p>
      <p style="color:#41d5cf; margin-top:10px;">每条素材会尽量生成两份 TXT：.transcript.txt 带时间戳给剪辑用，.plain.txt 纯文案给创作用。</p>
      <div style="margin-top:20px; display:flex; gap:10px; justify-content:center;">
        <button class="primary" id="batchTranscribeExecuteBtn">开始识别</button>
      </div>
    </div>
  `;
  $("batchTranscribeToolbar").style.display = "none";
  $("batchTranscribeExecuteBtn").addEventListener("click", startBatchTranscribe);
}
function closeBatchTranscribe(){
  $("batchTranscribeBackdrop").classList.remove("open");
  stopBatchTranscribe();
}
async function startBatchTranscribe(){
  const ok = await openModal({
    title: "确认批量提取文案素材",
    body: "开始对所有支持的素材进行语音识别？\n\n已有有效 TXT/缓存会自动跳过；只有标题和话题的 TXT 不算已识别。\n\n结果会写入缓存，并生成带时间戳 .transcript.txt 和纯文案 .plain.txt 两种文案素材。",
    input: false,
    okText: "开始识别"
  });
  if(!ok) return;
  $("batchTranscribeContent").innerHTML = `<div style="padding:20px; text-align:center;"><div style="width:50px;height:50px;border:4px solid var(--cyan);border-top-color:transparent;border-radius:50%;animation:spin 1s linear infinite;margin:0 auto 16px;"></div><p>任务启动中...</p></div>`;
  $("batchTranscribeToolbar").style.display = "grid";
  $("batchTranscribeProgressText").textContent = "连接中...";
  const result = await postJson("/api/batch-transcribe", {skip_existing: true, kind: ""});
  if(!result.ok){
    await showMessage("启动失败", result.error || "启动失败");
    openBatchTranscribe();
    return;
  }
  startTranscribePoll("批量文案素材提取完成");
}
function stopBatchTranscribe(){
  if(batchPollTimer){
    clearInterval(batchPollTimer);
    batchPollTimer = null;
  }
}
function startTranscribePoll(doneTitle="批量任务完成"){
  stopBatchTranscribe();
  batchPollTimer = setInterval(async () => {
    try{
      const result = await getJson("/api/batch-progress");
      if(!result.ok) return;
      const p = result;
      updateBatchQueueFromProgress(p);
      $("batchTranscribeProgressText").textContent = `${p.processed}/${p.total} | 成功 ${p.success} | 跳过 ${p.skipped} | 失败 ${p.failed}`;
      if(p.current_item){
        $("batchTranscribeTitle").textContent = `当前：${p.current_item}`;
      }
      if(p.message){
        $("batchTranscribeContent").innerHTML = renderBatchResults(p);
      }
      if(!p.running){
        stopBatchTranscribe();
        $("batchTranscribeTitle").textContent = doneTitle;
      }
    }catch(err){
      console.error("批量转写进度查询失败", err);
    }
  }, 1500);
}
function startBatchPoll(){
  stopBatchCrop();
  batchPollTimer = setInterval(async () => {
    try{
      const result = await getJson("/api/batch-progress");
      if(!result.ok) return;
      const p = result;
      updateBatchQueueFromProgress(p);
      $("batchProgressText").textContent = `${p.processed}/${p.total} | 成功 ${p.success} | 跳过 ${p.skipped} | 失败 ${p.failed}`;
      if(p.current_item){
        $("batchCropTitle").textContent = `当前：${p.current_item}`;
      }
      if(p.message){
        $("batchCropContent").innerHTML = renderBatchResults(p);
      }
      if(!p.running){
        stopBatchCrop();
        $("batchCropTitle").textContent = "批量任务完成";
      }
    }catch(err){
      console.error("批量进度查询失败", err);
    }
  }, 1500);
}
function renderBatchResults(p){
  const results = p.results || [];
  let html = `<div style="padding:16px;">`;
  html += `<div style="display:flex; gap:16px; margin-bottom:16px; padding:12px; border-radius:16px; background:var(--panel-light);">`;
  html += `<div><strong>总数</strong><div>${p.total}</div></div>`;
  html += `<div><strong>成功</strong><div style="color:#22c55e;">${p.success}</div></div>`;
  html += `<div><strong>跳过</strong><div style="color:#ecaa1c;">${p.skipped}</div></div>`;
  html += `<div><strong>失败</strong><div style="color:#ef4444;">${p.failed}</div></div>`;
  html += `</div>`;
  if(p.dry_run){
    html += `<div style="margin-bottom:12px; padding:10px; border-radius:14px; background:rgba(48,126,255,.12); color:var(--accent); font-size:12px;">`;
    html += `这是干跑结果，没有实际裁剪任何文件。如果确认没问题，点击“输出裁切废料新素材”。`;
    html += `</div>`;
  }
  if(results.length > 0){
    html += `<div style="max-height:40vh; overflow:auto;">`;
    html += `<table style="width:100%; border-collapse:collapse; font-size:12px;">`;
    html += `<thead><tr><th style="text-align:left; padding:8px; border-bottom:1px solid var(--line);">文件名</th><th style="text-align:left; padding:8px; border-bottom:1px solid var(--line);">状态</th><th style="text-align:left; padding:8px; border-bottom:1px solid var(--line);">说明</th></tr></thead>`;
    html += `<tbody>`;
    results.slice(-50).forEach(r => {
      let statusColor = "";
      let statusText = "";
      if(r.status === "success"){ statusColor = "#22c55e"; statusText = "成功"; }
      else if(r.status === "dry_run"){ statusColor = "#307eff"; statusText = "待裁剪"; }
      else if(r.status === "skipped"){ statusColor = "#ecaa1c"; statusText = "跳过"; }
      else if(r.status === "failed"){ statusColor = "#ef4444"; statusText = "失败"; }
      html += `<tr>`;
      html += `<td style="padding:6px 8px; border-bottom:1px solid rgba(195,208,220,.2); max-width:300px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">${esc(r.name)}</td>`;
      html += `<td style="padding:6px 8px; border-bottom:1px solid rgba(195,208,220,.2);"><span style="color:${statusColor}; font-weight:650;">${statusText}</span></td>`;
      html += `<td style="padding:6px 8px; border-bottom:1px solid rgba(195,208,220,.2); color:var(--muted);">${esc(r.reason || "")}</td>`;
      html += `</tr>`;
    });
    html += `</tbody></table>`;
    if(results.length > 50){
      html += `<div style="text-align:center; padding:8px; color:var(--muted); font-size:11px;">只显示最后 50 条</div>`;
    }
    html += `</div>`;
  }
  html += `</div>`;
  return html;
}
init();
</script>
</body>
</html>"""


if __name__ == "__main__":
    raise SystemExit(main())
