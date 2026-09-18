#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Online Gallery LAN Service for Device Share Hub & Mobile Gallery
===============================================================
提供面向手机相册客户端的在线读取、缩略图流式分发、文案获取与协同打标服务。
端口：默认 45835
真源目录：D:\\AICode\\项目推进\\projects\\江湖有旅人\\主项目\\成品库（GPT+本地脚本制作）

核心铁律：
- 严格仅扫描「已发送0次（抖音小红书可发）」与根目录直出合法成品；
- 彻底排除「已发送1次」、「已发送2次」及所有「_」或「.」开头的忽略目录；
- 电脑真源安全防护：手机端在线浏览与取用均不破坏电脑文件。
"""

import os
import sys

if sys.stdout is None:
    try:
        sys.stdout = open(os.path.join(os.path.dirname(__file__), "online_gallery_service.log"), "a", encoding="utf-8")
    except Exception:
        sys.stdout = open(os.devnull, "w", encoding="utf-8")
elif hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

if sys.stderr is None:
    try:
        sys.stderr = open(os.path.join(os.path.dirname(__file__), "online_gallery_service_err.log"), "a", encoding="utf-8")
    except Exception:
        sys.stderr = open(os.devnull, "w", encoding="utf-8")
elif hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

import json
import time
import socket
import threading
import hashlib
import urllib.parse
import re
import shutil
from io import BytesIO
from http.server import HTTPServer, ThreadingHTTPServer, BaseHTTPRequestHandler
from typing import Dict, List, Any, Optional

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

DEFAULT_PORT = 45835
DEFAULT_LIBRARY_ROOT = r"D:\AICode\项目推进\projects\江湖有旅人\主项目\成品库（GPT+本地脚本制作）"
DESTINATIONS = [
    # 具体目的地与景区优先检测
    "舟山", "嵊泗", "安吉", "莫干山", "千岛湖", "桐庐", "象山", "临安",
    "余杭", "溧阳", "宜兴", "乌镇", "黄山", "崇明", "阳澄湖", "西山岛",
    "宁波", "绍兴", "温州", "台州", "金华", "义乌", "南京", "无锡", "湖州",
    # 核心大城市及宏观主题
    "苏州", "杭州", "上海", "中秋", "国庆", "江浙沪"
]
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}

# 内存缩略图缓存 (path+thumb -> bytes)
THUMB_CACHE: Dict[str, bytes] = {}
THUMB_CACHE_LOCK = threading.Lock()
MAX_CACHE_ENTRIES = 500


def get_local_ip() -> str:
    """获取当前局域网 IP"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def detect_destination(title: str) -> str:
    """从作品标题检测所属目的地（剔除公司名称前缀干扰，优先具体风景点）"""
    cleaned = title
    for comp in ["杭州聚吧", "杭州聚米", "杭州聚航", "上海手工", "宣宋沙龙", "嗨森创意", "知合团建", "企星团建", "知旅团建", "趣定制", "翠羊湾"]:
        cleaned = cleaned.replace(comp, "")
    for dest in DESTINATIONS:
        if dest in cleaned:
            return dest
    return "其他"


DISK_THUMB_DIR = os.path.join(os.environ.get("TEMP", os.path.expanduser("~")), "gallery_thumb_cache")
try:
    os.makedirs(DISK_THUMB_DIR, exist_ok=True)
except Exception:
    pass

class WorkScanner:
    """负责扫描成品库作品与元数据（覆盖已发送0次、已发送1次、已发送2次及根目录直出合法成品）"""

    def __init__(self, root: str):
        self.root = os.path.abspath(root)
        self._lock = threading.Lock()
        self._cached_works: List[Dict[str, Any]] = []
        self._works_by_id: Dict[str, Dict[str, Any]] = {}
        self._last_scan_time = 0.0

    def get_work(self, work_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            if not self._works_by_id:
                self.scan()
            return self._works_by_id.get(work_id)

    def scan(self, force: bool = False) -> List[Dict[str, Any]]:
        now = time.time()
        with self._lock:
            if not force and self._cached_works and (now - self._last_scan_time < 5.0):
                return self._cached_works

            results = []
            seen_ids = set()

            # 1. 严格只扫描「已发送0次（抖音小红书可发）」目录
            stage0_dir = os.path.join(self.root, "已发送0次（抖音小红书可发）")
            if os.path.isdir(stage0_dir):
                try:
                    entries = os.listdir(stage0_dir)
                except Exception:
                    entries = []

                for entry in entries:
                    if entry.startswith(".") or entry.startswith("_"):
                        continue
                    full_path = os.path.join(stage0_dir, entry)
                    if not os.path.isdir(full_path):
                        continue

                    # 处理作品集子目录（如 作品集_099 下面的作品）
                    if entry.startswith("作品集"):
                        try:
                            sub_entries = os.listdir(full_path)
                            for sub in sub_entries:
                                if sub.startswith(".") or sub.startswith("_"):
                                    continue
                                sub_path = os.path.join(full_path, sub)
                                if os.path.isdir(sub_path):
                                    work = self._inspect_work_dir(sub_path, sub, "已发送0次", 0)
                                    if work and work["id"] not in seen_ids:
                                        seen_ids.add(work["id"])
                                        results.append(work)
                        except Exception:
                            pass
                    else:
                        work = self._inspect_work_dir(full_path, entry, "已发送0次", 0)
                        if work and work["id"] not in seen_ids:
                            seen_ids.add(work["id"])
                            results.append(work)

            # 扫描根目录下最新直出的合法作品，排除忽略目录与特殊目录
            IGNORED_NAMES = {
                "已发送0次（抖音小红书可发）", "已发送1次（微信公众号可发）", "_已发送1次（微信公众号可发）",
                "_已发送一次", "已发送2次（其他平台可发）", "_已发送2次（其他平台可发）",
                "已废弃-负面样本库", "归档", "不合格成品", "temp", "cache", "scripts",
                "_portfolio_backup", "_portfolio_move_logs", "_不合格成品合集", "_作品历史数据",
                "_制作中", "_待补全_单封面作品集", "_测试验收", "_生产计划与排产参考", "_重复待处理",
                "_垃圾作品（后续参考分析）",
                "发布空间", "待制作待补全", "抖音小红书"
            }
            try:
                root_entries = os.listdir(self.root)
                for entry in root_entries:
                    if entry.startswith(".") or entry.startswith("_") or entry in IGNORED_NAMES:
                        continue
                    full_path = os.path.join(self.root, entry)
                    if not os.path.isdir(full_path):
                        continue
                    work = self._inspect_work_dir(full_path, entry, "待首发", 0)
                    if work and work["id"] not in seen_ids:
                        seen_ids.add(work["id"])
                        results.append(work)
            except Exception:
                pass

            # 按时间倒序（标题通常带时间戳）
            results.sort(key=lambda w: w.get("title", ""), reverse=True)
            self._cached_works = results
            self._works_by_id = {w["id"]: w for w in results}
            self._last_scan_time = now
            return results

    def _inspect_work_dir(self, dir_path: str, folder_name: str, stage_name: str, default_count: int) -> Optional[Dict[str, Any]]:
        try:
            files = os.listdir(dir_path)
        except Exception:
            return None

        images = [f for f in files if os.path.splitext(f.lower())[1] in IMAGE_EXTENSIONS]
        if not images:
            return None

        images.sort()

        # 读取文案（优先多平台文案，检查内容是否实质非空，空时回退到原料目录或根据标题合成）
        copy_text = ""
        txt_candidates = [f for f in files if f.lower().endswith(".txt")]
        priority = {'三平台文案.txt': 0, '文案.txt': 1, '小红书文案.txt': 2, '全量生成记录.txt': 3}
        txt_candidates.sort(key=lambda x: priority.get(x, 10))

        for f in txt_candidates:
            try:
                with open(os.path.join(dir_path, f), "r", encoding="utf-8", errors="ignore") as fp:
                    raw_c = fp.read().strip()
                clean = raw_c.replace("<<<COPY_FORMAT:2>>>", "").replace("<<<COPY_FORMAT:3>>>", "")
                clean = clean.replace("<<<XHS_START>>>", "").replace("<<<XHS_END>>>", "")
                clean = clean.replace("<<<XHS_2_START>>>", "").replace("<<<XHS_2_END>>>", "")
                clean = clean.replace("<<<DOUYIN_START>>>", "").replace("<<<DOUYIN_END>>>", "")
                clean = clean.strip()
                if len(clean) > 15:
                    copy_text = raw_c
                    break
            except Exception:
                pass

        # 若依然为空，尝试从 manifest.json 读取 rawMaterialPath 的文案
        manifest_file = os.path.join(dir_path, "manifest.json")
        manifest_data = {}
        if os.path.exists(manifest_file):
            try:
                with open(manifest_file, "r", encoding="utf-8", errors="ignore") as fp:
                    manifest_data = json.load(fp)
                if not copy_text and manifest_data.get("copy_content"):
                    copy_text = manifest_data.get("copy_content", "").strip()
                if not copy_text and manifest_data.get("rawMaterialPath"):
                    raw_p = manifest_data["rawMaterialPath"]
                    if os.path.exists(raw_p):
                        for rf in os.listdir(raw_p):
                            if rf.lower().endswith(".txt"):
                                try:
                                    with open(os.path.join(raw_p, rf), "r", encoding="utf-8", errors="ignore") as rfp:
                                        rc = rfp.read().strip()
                                    if len(rc) > 15:
                                        copy_text = rc
                                        break
                                except Exception:
                                    pass
            except Exception:
                pass

        # 兜底：若全无文案，根据作品标题合成基础大纲文案
        if not copy_text:
            clean_title = folder_name
            for prefix in ["202609", "202608", "202607", "网页CDP-", "Codex-", "CodexAPI-"]:
                clean_title = clean_title.replace(prefix, "")
            clean_title = clean_title.lstrip("0123456789_ -")
            copy_text = f"{clean_title}\n\n江浙沪周边游/公司团建必看！逃离城市喧嚣，开启山野度假模式。\n特色活动、打卡拍照、互动玩法全攻略~"

        # 读取作品标签.json
        tag_file = os.path.join(dir_path, "作品标签.json")
        tag_data = {}
        if os.path.exists(tag_file):
            try:
                with open(tag_file, "r", encoding="utf-8", errors="ignore") as fp:
                    tag_data = json.load(fp)
            except Exception:
                pass

        distribution = tag_data.get("distribution", {})
        # 发送次数判定：优先从作品标签读取，其次以目录默认阶数为基准
        use_count = distribution.get("useCount")
        if use_count is None:
            dispatched = distribution.get("dispatchedTo", [])
            use_count = len(dispatched) if dispatched else default_count

        destination = detect_destination(folder_name)

        # 优雅标题清洗：剥离时间戳与机器流水线前缀，让手机端直显方案名
        clean_title = folder_name
        clean_title = re.sub(r'^\d{8}[_\-]\d{6}[_\-]?', '', clean_title)
        clean_title = re.sub(r'^\d{8}[_\-]?', '', clean_title)
        clean_title = re.sub(r'^(网页CDP|CodexAPI|Codex|CDP)[_\-]?', '', clean_title)
        clean_title = re.sub(r'^[（\(\[【_\-\s]+', '', clean_title)
        clean_title = re.sub(r'[\)\]】_\-\s]+$', '', clean_title)
        clean_title = re.sub(r'[\(（]?_{0,3}COPY_FORMAT_\d+_{0,3}[\)）]?', '', clean_title)
        clean_title = re.sub(r'<{1,3}COPY_FORMAT:\d+>{1,3}', '', clean_title)
        clean_title = clean_title.replace("[转]", "").strip()
        if not clean_title:
            clean_title = folder_name

        return {
            "id": folder_name,
            "title": clean_title,
            "rawTitle": folder_name,
            "destination": destination,
            "stage": stage_name,
            "path": dir_path,
            "useCount": int(use_count),
            "maxUses": 2,
            "used": bool(use_count > 0),
            "remainingUses": max(0, 2 - int(use_count)),
            "statusLabel": "已使用" if use_count > 0 else "",
            "dispatchedTo": distribution.get("dispatchedTo", []),
            "imageCount": len(images),
            "images": images,
            "copyText": copy_text,
            "hasCopyText": bool(copy_text),
            "updatedAt": os.path.getmtime(dir_path)
        }


class OnlineGalleryHandler(BaseHTTPRequestHandler):
    scanner: WorkScanner = None

    def log_message(self, format, *args):
        # 彻底静默 HTTP 高频请求日志（如大量缩略图拉取），杜绝控制台刷屏与I/O开销
        pass

    def send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Requested-With")

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_cors_headers()
        self.end_headers()

    def send_json(self, status: int, data: Any):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        if path == "/" or path == "/api/online/status":
            works = self.scanner.scan()
            ip = get_local_ip()
            data = {
                "ok": True,
                "server": "DeviceShareHub-OnlineGallery",
                "version": "1.0.0",
                "ip": ip,
                "port": self.server.server_port,
                "totalWorks": len(works),
                "libraryRoot": self.scanner.root,
                "timestamp": int(time.time())
            }
            self.send_json(200, data)
            return

        if path == "/api/online/categories":
            works = self.scanner.scan()
            counts: Dict[str, int] = {}
            count_stage0 = 0
            count_stage1 = 0
            count_stage2 = 0
            for w in works:
                uc = w.get("useCount", 0)
                if uc == 0:
                    count_stage0 += 1
                elif uc == 1:
                    count_stage1 += 1
                else:
                    count_stage2 += 1
                dest = w.get("destination", "其他")
                counts[dest] = counts.get(dest, 0) + 1

            # 节日时令专题聚合（中秋、国庆优先置顶）
            mid_autumn_count = sum(1 for w in works if "中秋" in (w.get("rawTitle", "") + " " + w.get("copyText", "")))
            national_day_count = sum(1 for w in works if ("国庆" in (w.get("rawTitle", "") + " " + w.get("copyText", "")) or "十一" in (w.get("rawTitle", "") + " " + w.get("copyText", ""))))

            # 纯净分类聚合：节日专题置顶，其余按数量倒序的目的地
            categories = []
            if mid_autumn_count > 0:
                categories.append({"name": "🌕 中秋", "count": mid_autumn_count})
            if national_day_count > 0:
                categories.append({"name": "🇨🇳 国庆", "count": national_day_count})

            dest_categories = []
            for d in DESTINATIONS:
                if d in ("中秋", "国庆"):
                    continue
                if d in counts and counts[d] > 0:
                    dest_categories.append({"name": d, "count": counts[d]})
            if "其他" in counts and counts["其他"] > 0:
                dest_categories.append({"name": "其他", "count": counts["其他"]})
            dest_categories.sort(key=lambda c: -c["count"])
            categories.extend(dest_categories)

            self.send_json(200, {"ok": True, "categories": categories, "stages": [], "total": len(works)})
            return

        if path == "/api/online/works":
            category = query.get("category", ["全部"])[0]
            search_query = query.get("query", [""])[0].strip().lower()
            force_refresh = query.get("refresh", ["0"])[0] == "1"

            works = self.scanner.scan(force=force_refresh)
            tokens = search_query.split() if search_query else []

            filtered = []
            for w in works:
                # 分类过滤（支持专题分类与地域分类）
                if category and category != "全部":
                    if category in ("🌕 中秋", "中秋"):
                        if "中秋" not in (w.get("rawTitle", "") + " " + w.get("copyText", "")):
                            continue
                    elif category in ("🇨🇳 国庆", "国庆"):
                        blob = w.get("rawTitle", "") + " " + w.get("copyText", "")
                        if "国庆" not in blob and "十一" not in blob:
                            continue
                    elif category == "待首发" and w.get("useCount", 0) != 0:
                        continue
                    elif category == "已发1次" and w.get("useCount", 0) != 1:
                        continue
                    elif category == "已发2次" and w.get("useCount", 0) < 2:
                        continue
                    elif category not in ("待首发", "已发1次", "已发2次"):
                        if w.get("destination") != category:
                            continue

                # 搜索关键词过滤
                if tokens:
                    text_blob = (w.get("title", "") + " " + w.get("destination", "") + " " + w.get("copyText", "")).lower()
                    if not all(token in text_blob for token in tokens):
                        continue

                filtered.append(w)

            self.send_json(200, {
                "ok": True,
                "category": category,
                "total": len(filtered),
                "works": filtered
            })
            return

        if path == "/api/online/image":
            work_id = query.get("id", [""])[0]
            file_name = query.get("file", [""])[0]
            thumb = query.get("thumb", ["0"])[0] == "1"

            if not work_id or not file_name:
                self.send_error(400, "Missing id or file")
                return

            target_work = self.scanner.get_work(work_id)
            if not target_work:
                self.send_error(404, "Work not found")
                return

            img_path = os.path.join(target_work["path"], file_name)
            if not os.path.exists(img_path):
                self.send_error(404, "Image file not found")
                return

            try:
                mtime = os.path.getmtime(img_path)
            except Exception:
                mtime = 0

            data = None
            mime = "image/jpeg"

            if thumb:
                cache_file_name = hashlib.md5(f"{img_path}_{mtime}".encode("utf-8")).hexdigest() + ".jpg"
                cache_file_path = os.path.join(DISK_THUMB_DIR, cache_file_name)
                if os.path.isfile(cache_file_path):
                    try:
                        with open(cache_file_path, "rb") as fp:
                            data = fp.read()
                    except Exception:
                        data = None

                if data is None and HAS_PIL:
                    try:
                        with Image.open(img_path) as im:
                            im.thumbnail((320, 320), Image.Resampling.LANCZOS)
                            buf = BytesIO()
                            rgb_im = im.convert("RGB")
                            rgb_im.save(buf, format="JPEG", quality=82)
                            data = buf.getvalue()
                        with open(cache_file_path, "wb") as fp:
                            fp.write(data)
                    except Exception:
                        data = None

            if data is None:
                try:
                    with open(img_path, "rb") as fp:
                        data = fp.read()
                    mime = "image/jpeg" if img_path.lower().endswith((".jpg", ".jpeg")) else "image/png"
                except Exception:
                    self.send_error(500, "Failed to read image")
                    return

            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "public, max-age=86400")
            self.send_header("Connection", "close")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(data)
            return

        self.send_error(404, "Not Found")

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/api/online/use-work":
            content_length = int(self.headers.get("Content-Length", 0))
            raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
            try:
                req = json.loads(raw_body)
            except Exception:
                self.send_error(400, "Invalid JSON body")
                return

            work_id = req.get("workId", "").strip()
            device_name = req.get("device", "手机端未知设备").strip()
            platform = req.get("platform", "小红书/抖音").strip()

            if not work_id:
                self.send_error(400, "Missing workId")
                return

            works = self.scanner.scan()
            target_work = next((w for w in works if w["id"] == work_id), None)
            if not target_work:
                self.send_error(404, f"Work {work_id} not found")
                return

            dir_path = target_work["path"]
            tag_file = os.path.join(dir_path, "作品标签.json")
            tag_data = {}
            if os.path.exists(tag_file):
                try:
                    with open(tag_file, "r", encoding="utf-8") as fp:
                        tag_data = json.load(fp)
                except Exception:
                    pass

            if "distribution" not in tag_data:
                tag_data["distribution"] = {}

            dist = tag_data["distribution"]
            current_count = int(dist.get("useCount", target_work.get("useCount", 0)))
            new_count = current_count + 1
            dist["useCount"] = new_count

            dispatched_list = dist.get("dispatchedTo", [])
            record_str = f"{device_name} ({platform} @ {time.strftime('%Y-%m-%d %H:%M:%S')})"
            if record_str not in dispatched_list:
                dispatched_list.append(record_str)
            dist["dispatchedTo"] = dispatched_list
            dist["lastDispatchedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S+08:00")
            dist["status"] = f"已使用{new_count}次"

            # 保存更新作品标签.json
            try:
                with open(tag_file, "w", encoding="utf-8") as fp:
                    json.dump(tag_data, fp, ensure_ascii=False, indent=2)
            except Exception as e:
                print(f"[Warn] Failed to write tag file: {e}")

            # 写入设备使用日志 (device-usage-log.csv)
            log_file = os.path.join(self.scanner.root, "device-usage-log.csv")
            try:
                exists = os.path.exists(log_file)
                with open(log_file, "a", encoding="utf-8") as fp:
                    if not exists:
                        fp.write("时间,设备名,源作品,使用次数,平台,操作\n")
                    fp.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')},{device_name},{work_id},{new_count},{platform},手机在线直用打标\n")
            except Exception as e:
                print(f"[Warn] Failed to write usage log: {e}")

            # 核心业务铁律：使用次数 < 2 时绝对不物理移动文件夹，保留在原地！
            remaining = max(0, 2 - new_count)
            msg = f"已成功记录第 {new_count} 次使用"

            # 强制刷新扫描缓存
            self.scanner.scan(force=True)

            self.send_json(200, {
                "ok": True,
                "workId": work_id,
                "useCount": new_count,
                "remainingUses": remaining,
                "moved": False,
                "message": msg,
                "dispatchedTo": dispatched_list
            })
            return

        if path == "/api/online/reset-work":
            content_length = int(self.headers.get("Content-Length", 0))
            raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
            try:
                req = json.loads(raw_body)
            except Exception:
                self.send_error(400, "Invalid JSON")
                return

            work_id = req.get("workId", "").strip()
            works = self.scanner.scan()
            target_work = next((w for w in works if w["id"] == work_id), None)
            if not target_work:
                self.send_error(404, "Work not found")
                return

            dir_path = target_work["path"]
            tag_file = os.path.join(dir_path, "作品标签.json")
            if os.path.exists(tag_file):
                try:
                    with open(tag_file, "r", encoding="utf-8") as fp:
                        tag_data = json.load(fp)
                    if "distribution" in tag_data:
                        tag_data["distribution"]["useCount"] = 0
                        tag_data["distribution"]["dispatchedTo"] = []
                        tag_data["distribution"]["status"] = "待发手机"
                    with open(tag_file, "w", encoding="utf-8") as fp:
                        json.dump(tag_data, fp, ensure_ascii=False, indent=2)
                except Exception as e:
                    print(f"Error resetting tags: {e}")

            self.scanner.scan(force=True)
            self.send_json(200, {"ok": True, "workId": work_id, "useCount": 0, "message": "已重置为待首发状态"})
            return

        if path in ("/api/online/delete-work", "/api/online/delete"):
            content_length = int(self.headers.get("Content-Length", 0))
            raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
            try:
                req = json.loads(raw_body)
            except Exception:
                self.send_error(400, "Invalid JSON")
                return

            work_id = req.get("workId") or req.get("id") or ""
            work_id = work_id.strip()
            device_name = req.get("deviceName", "移动相册客户端")

            if not work_id:
                self.send_error(400, "Missing workId")
                return

            works = self.scanner.scan()
            target_work = next((w for w in works if w["id"] == work_id), None)
            if not target_work:
                self.send_error(404, "Work not found")
                return

            src_path = target_work["path"]
            folder_name = os.path.basename(src_path)
            use_count = target_work.get("useCount", 0)

            # 确定目标目录
            root_dir = self.scanner.root
            if use_count > 0:
                # 发过的作品：移动到 _已发送1次（微信公众号可发）
                cand_dirs = [
                    os.path.join(root_dir, "_已发送1次（微信公众号可发）"),
                    os.path.join(root_dir, "_已发送一次"),
                    os.path.join(root_dir, "已发送1次（微信公众号可发）"),
                ]
                dest_base = None
                for cd in cand_dirs:
                    if os.path.exists(cd):
                        dest_base = cd
                        break
                if not dest_base:
                    dest_base = cand_dirs[0]
                    os.makedirs(dest_base, exist_ok=True)
                action_type = "dispatched"
                action_desc = "已移入「_已发送一次」"
            else:
                # 没用过的作品：移动到垃圾作品/负面样本库，作为后续参考分析
                cand_dirs = [
                    os.path.join(root_dir, "_垃圾作品（后续参考分析）"),
                    os.path.join(root_dir, "_不合格成品合集"),
                    os.path.join(root_dir, "已废弃-负面样本库"),
                ]
                dest_base = None
                for cd in cand_dirs:
                    if os.path.exists(cd):
                        dest_base = cd
                        break
                if not dest_base:
                    dest_base = cand_dirs[0]
                    os.makedirs(dest_base, exist_ok=True)
                action_type = "trash"
                action_desc = "已移入「垃圾作品库（供后续参考分析）」"

            # 目标文件夹路径（若已存在同名文件夹，附加时间戳避免覆盖冲突）
            target_dest = os.path.join(dest_base, folder_name)
            if os.path.exists(target_dest):
                ts = time.strftime("%Y%m%d_%H%M%S")
                target_dest = os.path.join(dest_base, f"{folder_name}_{ts}")

            try:
                shutil.move(src_path, target_dest)
            except Exception as e:
                self.send_json(500, {"ok": False, "error": f"物理移动失败: {str(e)}"})
                return

            # 记录删除流转审计日志
            log_dir = os.path.join(root_dir, "_portfolio_move_logs")
            os.makedirs(log_dir, exist_ok=True)
            log_file = os.path.join(log_dir, f"delete_move_log_{time.strftime('%Y%m')}.csv")
            try:
                header_needed = not os.path.exists(log_file)
                with open(log_file, "a", encoding="utf-8-sig") as fp:
                    if header_needed:
                        fp.write("时间,设备,作品ID,原路径,目标路径,原使用次数,动作类型\n")
                    fp.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')},{device_name},{work_id},{src_path},{target_dest},{use_count},{action_type}\n")
            except Exception:
                pass

            # 强制刷新扫描缓存
            self.scanner.scan(force=True)

            self.send_json(200, {
                "ok": True,
                "workId": work_id,
                "action": action_type,
                "message": action_desc,
                "targetPath": target_dest,
                "remainingWorks": len(self.scanner.scan())
            })
            return

        self.send_error(404, "Not Found")


def run_service(port: int = DEFAULT_PORT, library_root: str = DEFAULT_LIBRARY_ROOT):
    scanner = WorkScanner(library_root)
    OnlineGalleryHandler.scanner = scanner

    server = ThreadingHTTPServer(("0.0.0.0", port), OnlineGalleryHandler)
    local_ip = get_local_ip()
    print(f"================================================================")
    print(f"🚀 Device Share Hub - 电脑在线相册服务已就绪")
    print(f"📡 局域网访问地址: http://{local_ip}:{port}")
    print(f"📂 作品真源目录: {library_root}")
    print(f"🛡️ 纯净首发保障: 仅限「已发送0次」与根目录直出成品，排除忽略项")
    print(f"================================================================")

    # 首次预热扫描
    works = scanner.scan(force=True)
    print(f"✨ 初始加载完成，共发现 {len(works)} 套存量成品作品")

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n服务正在平稳退出...")
        server.server_close()
    except Exception as e:
        import traceback
        traceback.print_exc()
        with open(os.path.join(os.path.dirname(__file__), "crash.log"), "w", encoding="utf-8") as fp:
            fp.write(traceback.format_exc())
    finally:
        with open(os.path.join(os.path.dirname(__file__), "exit.log"), "w", encoding="utf-8") as fp:
            fp.write(f"Exited at {time.strftime('%Y-%m-%d %H:%M:%S')}\n")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Online Gallery LAN Service")
    parser.add_argument("pos_port", nargs="?", type=int, default=None, help="Port (positional)")
    parser.add_argument("pos_root", nargs="?", type=str, default=None, help="Library root (positional)")
    parser.add_argument("--port", type=int, default=None, help="Port")
    parser.add_argument("--root", type=str, default=None, help="Library root")
    args = parser.parse_args()

    target_port = args.port or args.pos_port or DEFAULT_PORT
    target_root = args.root or args.pos_root or DEFAULT_LIBRARY_ROOT
    run_service(target_port, target_root)
