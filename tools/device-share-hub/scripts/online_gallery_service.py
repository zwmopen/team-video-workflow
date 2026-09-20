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

def _setup_streams():
    log_dir = os.path.dirname(__file__)
    for stream_name, log_name in [("stdout", "online_gallery_service.log"), ("stderr", "online_gallery_service_err.log")]:
        stream = getattr(sys, stream_name, None)
        needs_redirect = False
        if stream is None:
            needs_redirect = True
        else:
            try:
                # 在 pythonw 下 fileno 探测会抛出 io.UnsupportedOperation 或返回负数
                stream.write("")
                stream.flush()
            except Exception:
                needs_redirect = True
        if needs_redirect:
            try:
                f = open(os.path.join(log_dir, log_name), "a", encoding="utf-8", buffering=1)
                setattr(sys, stream_name, f)
            except Exception:
                setattr(sys, stream_name, open(os.devnull, "w", encoding="utf-8"))
        else:
            if hasattr(stream, "reconfigure"):
                try:
                    stream.reconfigure(encoding="utf-8", errors="replace")
                except Exception:
                    pass

_setup_streams()

import json
import csv
import time
import socket
import threading
import hashlib
import urllib.parse
import re
import shutil
import subprocess
from io import BytesIO
from http.server import HTTPServer, ThreadingHTTPServer, BaseHTTPRequestHandler
from typing import Dict, List, Any, Optional, Tuple

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

DEFAULT_PORT = 45835
DEFAULT_LIBRARY_ROOT = r"D:\AICode\项目推进\projects\江湖有旅人\主项目\成品库（GPT+本地脚本制作）"
DESTINATIONS = [
    # 专题与游戏类优先
    "游戏", "中秋", "国庆",
    # 具体目的地与景区优先检测
    "舟山", "嵊泗", "安吉", "莫干山", "千岛湖", "桐庐", "象山", "临安",
    "余杭", "溧阳", "宜兴", "乌镇", "黄山", "崇明", "阳澄湖", "西山岛",
    "宁波", "绍兴", "温州", "台州", "金华", "义乌", "南京", "无锡", "湖州",
    # 核心大城市及宏观主题
    "苏州", "杭州", "上海", "江浙沪"
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


def is_game_work(title: str, dir_path: str = "") -> bool:
    """精准判断是否属于游戏/破冰/桌游专题"""
    norm_p = dir_path.replace("\\", "/")
    if "/团建游戏" in norm_p:
        return True
    game_keywords = ["小游戏", "破冰游戏", "聚会游戏", "年会游戏", "惩罚小游戏", "桌游", "晨会小游戏", "团建游戏", "互动游戏", "暖场小游戏", "爆笑小游戏", "无道具游戏"]
    return any(k in title for k in game_keywords)


def detect_destination(title: str, dir_path: str = "") -> str:
    """从作品标题或目录路径检测所属目的地/主题（剔除公司名称前缀干扰，优先具体风景点与游戏主题）"""
    if is_game_work(title, dir_path):
        return "游戏"
    cleaned = title
    for comp in ["杭州聚吧", "杭州聚米", "杭州聚航", "上海手工", "宣宋沙龙", "嗨森创意", "知合团建", "企星团建", "知旅团建", "趣定制", "翠羊湾"]:
        cleaned = cleaned.replace(comp, "")
    for dest in DESTINATIONS:
        if dest in ("游戏", "中秋", "国庆"):
            continue
        if dest in cleaned:
            return dest
    return "其他"


DISK_THUMB_DIR = os.path.join(os.environ.get("TEMP", os.path.expanduser("~")), "gallery_thumb_cache")
try:
    os.makedirs(DISK_THUMB_DIR, exist_ok=True)
except Exception:
    pass

def read_garbage_meta(dir_path: str) -> Dict[str, Any]:
    """读取作品的垃圾标记信息。

    优先取 quality_tag.json（全渠道硬拦截标记），为空时回退到 manifest.json 的 garbage 块。
    两个文件都由 _move_work_to_garbage() 在手机端判垃圾时写入。
    """
    info: Dict[str, Any] = {"marked": False, "remark": "", "markedBy": "", "markedAt": ""}
    qt = os.path.join(dir_path, "quality_tag.json")
    if os.path.exists(qt):
        try:
            with open(qt, "r", encoding="utf-8", errors="ignore") as fp:
                data = json.load(fp)
            if isinstance(data, dict):
                info["marked"] = bool(data.get("garbage"))
                info["remark"] = (data.get("garbage_remark") or "").strip()
                info["markedBy"] = data.get("marked_by") or ""
                info["markedAt"] = data.get("marked_at") or ""
        except Exception:
            pass

    mf = os.path.join(dir_path, "manifest.json")
    if os.path.exists(mf) and (not info["remark"] or not info["marked"]):
        try:
            with open(mf, "r", encoding="utf-8", errors="ignore") as fp:
                data = json.load(fp)
            g = data.get("garbage") if isinstance(data, dict) else None
            if isinstance(g, dict):
                info["marked"] = info["marked"] or bool(g.get("marked"))
                info["remark"] = info["remark"] or (g.get("remark") or "").strip()
                info["markedBy"] = info["markedBy"] or (g.get("markedBy") or "")
                info["markedAt"] = info["markedAt"] or (g.get("markedAt") or "")
        except Exception:
            pass
    return info


class WorkScanner:
    """负责扫描成品库「可发布」作品与元数据。

    注意：真正的在线相册列表只包含「已发送0次（抖音小红书可发）」与根目录直出成品；
    「已发送1次」「已发送2次」「_垃圾作品」都被 IGNORED_NAMES 排除，只能通过
    在线回收站接口（list_stage_works）按阶段库单独读取。
    """

    # 三个阶段库的物理目录名（与成品库实际结构一致）
    STAGE0_FOLDER = "已发送0次（抖音小红书可发）"
    STAGE1_FOLDER = "_已发送1次（微信公众号可发）"
    GARBAGE_FOLDER = "_垃圾作品（后续参考分析）"
    # 需要下钻一层的中间目录前缀（作品集/游戏类会在阶段库里再套一层）
    NESTED_PREFIXES = ("作品集", "团建游戏", "游戏", "游戏类")

    def __init__(self, root: str):
        self.root = os.path.abspath(root)
        self._lock = threading.Lock()
        self._cached_works: List[Dict[str, Any]] = []
        self._works_by_id: Dict[str, Dict[str, Any]] = {}
        self._moved_works: Dict[str, Dict[str, Any]] = {}
        self._last_scan_time = 0.0
        # 在线回收站各 Tab 的列表缓存：{folder: (时间戳, 作品列表)}
        # 阶段库作品数可达 500+，每条都要读文案文件，不缓存的话手机每次切 Tab 都要等 1.5s+
        self._stage_cache: Dict[str, Any] = {}

    def _iter_work_dirs(self, base: str):
        """遍历某个阶段库下的作品目录，兼容「作品集_xxx[转]」这类中间层。

        与 scan() 中「已发送0次」的层级规则保持一致：遇到作品集/游戏类目录再下钻一层。
        不做下钻的话，_已发送1次 里嵌套的作品会既列不出来、也拉不到缩略图。
        """
        try:
            entries = os.listdir(base)
        except Exception:
            return
        for entry in entries:
            if entry.startswith(".") or entry.startswith("_"):
                continue
            full = os.path.join(base, entry)
            if not os.path.isdir(full):
                continue
            if entry.startswith(self.NESTED_PREFIXES):
                try:
                    subs = os.listdir(full)
                except Exception:
                    continue
                for sub in subs:
                    if sub.startswith(".") or sub.startswith("_"):
                        continue
                    sub_p = os.path.join(full, sub)
                    if os.path.isdir(sub_p):
                        yield sub, sub_p
            else:
                yield entry, full

    def list_stage_works(self, folder_name: str, stage_name: str, default_count: int,
                         force: bool = False) -> List[Dict[str, Any]]:
        """列出指定阶段库下的全部作品（在线回收站的两个 Tab 用）。

        结果缓存 5 秒：手机端在「已使用 / 已标记垃圾」之间来回切 Tab 时秒开。
        force=True（手机下拉刷新）时绕过缓存。
        """
        now = time.time()
        with self._lock:
            cached = self._stage_cache.get(folder_name)
            if cached and not force and (now - cached[0] < 5.0):
                return cached[1]

        base = os.path.join(self.root, folder_name)
        out: List[Dict[str, Any]] = []
        for name, full in self._iter_work_dirs(base):
            w = self._inspect_work_dir(full, name, stage_name, default_count)
            if not w:
                continue
            w["folder"] = folder_name
            # 登记到 _moved_works，保证 /api/online/image 能按 id 找回嵌套作品的原图
            self._moved_works[w["id"]] = w
            out.append(w)
        out.sort(key=lambda w: w.get("updatedAt", 0), reverse=True)

        with self._lock:
            self._stage_cache[folder_name] = (now, out)
        return out

    def resolve_stage_work(self, work_id: str) -> Optional[Dict[str, Any]]:
        """在三个阶段库（已发送0次 / _已发送1次 / _垃圾作品）里按 id 定位作品。"""
        work_id = (work_id or "").strip()
        if not work_id:
            return None
        for folder, stage, base_count in (
            (self.STAGE1_FOLDER, "已发送1次", 1),
            (self.GARBAGE_FOLDER, "已废弃-垃圾", 0),
            (self.STAGE0_FOLDER, "已发送0次", 0),
        ):
            base = os.path.join(self.root, folder)
            if not os.path.isdir(base):
                continue
            for name, full in self._iter_work_dirs(base):
                if name != work_id:
                    continue
                w = self._inspect_work_dir(full, name, stage, base_count)
                if w:
                    w["folder"] = folder
                    self._moved_works[w["id"]] = w
                    return w
        return None

    def get_work(self, work_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            if not self._works_by_id:
                self.scan()
            work = self._works_by_id.get(work_id)
            if work and os.path.exists(work.get("path", "")):
                return work
            if work_id in self._moved_works:
                mw = self._moved_works[work_id]
                if os.path.exists(mw.get("path", "")):
                    return mw
            # 兜底到 _已发送1次 / _垃圾作品 查找（必须下钻作品集中间层）
            for folder, stage, base_count in (
                (self.STAGE1_FOLDER, "已发送1次", 1),
                (self.GARBAGE_FOLDER, "已废弃-垃圾", 0),
            ):
                base = os.path.join(self.root, folder)
                if not os.path.isdir(base):
                    continue
                for name, full in self._iter_work_dirs(base):
                    if name != work_id:
                        continue
                    w = self._inspect_work_dir(full, name, stage, base_count)
                    if w:
                        self._moved_works[work_id] = w
                        return w
            return None

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

                    # 处理作品集子目录（如 作品集_099）以及 团建游戏 子目录
                    if entry.startswith("作品集") or entry in ("团建游戏", "游戏", "游戏类"):
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

        destination = detect_destination(folder_name, dir_path)

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
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(body)
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
            pass

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

            # 节日时令专题聚合（中秋、国庆优先置顶）与游戏专题
            mid_autumn_count = sum(1 for w in works if "中秋" in (w.get("rawTitle", "") + " " + w.get("copyText", "")))
            national_day_count = sum(1 for w in works if ("国庆" in (w.get("rawTitle", "") + " " + w.get("copyText", "")) or "十一" in (w.get("rawTitle", "") + " " + w.get("copyText", ""))))
            game_count = sum(1 for w in works if is_game_work(w.get("rawTitle", ""), w.get("path", "")))

            # 纯净分类聚合：节日专题置顶，游戏专题同级别优先展示，其余按数量倒序的目的地
            categories = []
            if mid_autumn_count > 0:
                categories.append({"name": "🌕 中秋", "count": mid_autumn_count})
            if national_day_count > 0:
                categories.append({"name": "🇨🇳 国庆", "count": national_day_count})
            if game_count > 0:
                categories.append({"name": "🎮 游戏", "count": game_count})

            dest_categories = []
            for d in DESTINATIONS:
                if d in ("中秋", "国庆", "游戏"):
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
                # 分类过滤（支持专题分类、游戏与地域分类）
                if category and category != "全部":
                    if category in ("🌕 中秋", "中秋"):
                        if "中秋" not in (w.get("rawTitle", "") + " " + w.get("copyText", "")):
                            continue
                    elif category in ("🇨🇳 国庆", "国庆"):
                        blob = w.get("rawTitle", "") + " " + w.get("copyText", "")
                        if "国庆" not in blob and "十一" not in blob:
                            continue
                    elif category in ("🎮 游戏", "游戏"):
                        if not is_game_work(w.get("rawTitle", ""), w.get("path", "")):
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

        if path == "/api/online/recycle":
            # 在线回收站：顶部两个 Tab —— 已使用（_已发送1次）/ 已标记垃圾（_垃圾作品）
            tab_raw = (query.get("tab", ["sent"])[0] or "sent").strip().lower()
            is_garbage = tab_raw in ("garbage", "trash", "已标记垃圾", "垃圾")
            force = query.get("refresh", ["0"])[0] == "1"

            if is_garbage:
                folder, stage, base_count = self.scanner.GARBAGE_FOLDER, "已废弃-垃圾", 0
                tab, label = "garbage", "已标记垃圾"
            else:
                folder, stage, base_count = self.scanner.STAGE1_FOLDER, "已发送1次", 1
                tab, label = "sent", "已使用"

            works = self.scanner.list_stage_works(folder, stage, base_count, force=force)
            for w in works:
                w["garbage"] = read_garbage_meta(w.get("path", ""))

            # 另一个 Tab 也列一遍（垃圾库很小，代价可忽略），
            # 让两个 Tab 的角标数字与各自列表的 total 严格一致。
            if is_garbage:
                other = self.scanner.list_stage_works(
                    self.scanner.STAGE1_FOLDER, "已发送1次", 1, force=force)
                counts = {"sent": len(other), "garbage": len(works)}
            else:
                other = self.scanner.list_stage_works(
                    self.scanner.GARBAGE_FOLDER, "已废弃-垃圾", 0, force=force)
                counts = {"sent": len(works), "garbage": len(other)}

            self.send_json(200, {
                "ok": True,
                "tab": tab,
                "label": label,
                "folder": folder,
                "total": len(works),
                "counts": counts,
                "works": works,
                "refreshed": force,
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

    def _move_work_to_stage1(self, target_work: Dict[str, Any], device_name: str, action_type: str = "dispatched") -> Tuple[bool, str, str]:
        src_path = target_work["path"]
        folder_name = os.path.basename(src_path)
        use_count = target_work.get("useCount", 0)
        work_id = target_work.get("id", "")

        root_dir = self.scanner.root
        dest_base = os.path.join(root_dir, "_已发送1次（微信公众号可发）")
        os.makedirs(dest_base, exist_ok=True)

        target_dest = os.path.join(dest_base, folder_name)
        if os.path.exists(target_dest) and os.path.abspath(target_dest) != os.path.abspath(src_path):
            ts = time.strftime("%Y%m%d_%H%M%S")
            target_dest = os.path.join(dest_base, f"{folder_name}_{ts}")

        if os.path.abspath(target_dest) == os.path.abspath(src_path):
            return True, target_dest, "作品已位于「_已发送1次（微信公众号可发）」"

        move_err = None
        for attempt in range(3):
            try:
                shutil.move(src_path, target_dest)
                move_err = None
                break
            except Exception as e:
                move_err = e
                time.sleep(0.3)

        if move_err is not None:
            try:
                shutil.copytree(src_path, target_dest, dirs_exist_ok=True)
                shutil.rmtree(src_path, ignore_errors=True)
                move_err = None
            except Exception as e2:
                move_err = e2

        if move_err is not None:
            return False, "", f"物理移动失败: {str(move_err)}"

        target_work["path"] = target_dest
        self.scanner._moved_works[work_id] = target_work

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

        return True, target_dest, "已移入「_已发送1次（微信公众号可发）」"

    def _move_work_to_garbage(self, target_work: Dict[str, Any], device_name: str, remark: str = "") -> Tuple[bool, str, str]:
        """
        未发送作品的人工判定删除：物理移入垃圾样本库「_垃圾作品（后续参考分析）」，
        并在元数据（manifest.json + quality_tag.json）永久标记为垃圾，全渠道分发引擎硬拦截。
        """
        src_path = target_work["path"]
        folder_name = os.path.basename(src_path)
        work_id = target_work.get("id", "")
        remark = (remark or "").strip()
        ts = time.strftime("%Y-%m-%d %H:%M:%S")

        root_dir = self.scanner.root
        dest_base = os.path.join(root_dir, "_垃圾作品（后续参考分析）")
        os.makedirs(dest_base, exist_ok=True)

        target_dest = os.path.join(dest_base, folder_name)
        if os.path.exists(target_dest) and os.path.abspath(target_dest) != os.path.abspath(src_path):
            ts_suffix = time.strftime("%Y%m%d_%H%M%S")
            target_dest = os.path.join(dest_base, f"{folder_name}_{ts_suffix}")

        if os.path.abspath(target_dest) == os.path.abspath(src_path):
            return True, target_dest, "作品已位于垃圾样本库「_垃圾作品（后续参考分析）」"

        move_err = None
        for attempt in range(3):
            try:
                shutil.move(src_path, target_dest)
                move_err = None
                break
            except Exception as e:
                move_err = e
                time.sleep(0.3)

        if move_err is not None:
            try:
                shutil.copytree(src_path, target_dest, dirs_exist_ok=True)
                shutil.rmtree(src_path, ignore_errors=True)
                move_err = None
            except Exception as e2:
                move_err = e2

        if move_err is not None:
            return False, "", f"物理移动失败: {str(move_err)}"

        # 1) quality_tag.json：全渠道硬拦截标记
        try:
            quality = {
                "usable_for_wechat": False,
                "usable_for_other_platforms": False,
                "garbage": True,
                "garbage_remark": remark,
                "marked_by": device_name,
                "marked_at": ts,
                "source_stage": f"{target_work.get('stage', '未发送')}（手机端在线相册人工判定删除）",
            }
            with open(os.path.join(target_dest, "quality_tag.json"), "w", encoding="utf-8") as fp:
                json.dump(quality, fp, ensure_ascii=False, indent=2)
        except Exception:
            pass

        # 2) manifest.json：写入 garbage 块
        try:
            manifest_file = os.path.join(target_dest, "manifest.json")
            if os.path.exists(manifest_file):
                with open(manifest_file, "r", encoding="utf-8") as fp:
                    manifest = json.load(fp)
                manifest["garbage"] = {
                    "marked": True,
                    "remark": remark,
                    "markedBy": device_name,
                    "markedAt": ts,
                }
                with open(manifest_file, "w", encoding="utf-8") as fp:
                    json.dump(manifest, fp, ensure_ascii=False, indent=2)
        except Exception:
            pass

        target_work["path"] = target_dest
        self.scanner._moved_works[work_id] = target_work

        log_dir = os.path.join(root_dir, "_portfolio_move_logs")
        os.makedirs(log_dir, exist_ok=True)
        log_file = os.path.join(log_dir, f"delete_move_log_{time.strftime('%Y%m')}.csv")
        try:
            header_needed = not os.path.exists(log_file)
            with open(log_file, "a", encoding="utf-8-sig") as fp:
                if header_needed:
                    fp.write("时间,设备,作品ID,原路径,目标路径,原使用次数,动作类型,备注\n")
                fp.write(f"{ts},{device_name},{work_id},{src_path},{target_dest},0,garbage_delete,{remark}\n")
        except Exception:
            pass

        msg = "已移入垃圾样本库「_垃圾作品（后续参考分析）」并在元数据标记为垃圾"
        if remark:
            msg += f"（备注：{remark}）"
        return True, target_dest, msg

    def _original_parent_dir(self, work_id: str) -> Optional[str]:
        """从移动日志里反查作品搬家前的父目录（供「恢复」放回原位）。

        _已发送1次 里的作品可能原本住在「已发送0次/作品集_xxx」这类合集子目录里；
        若直接恢复到 已发送0次 根目录，会把作品从它的合集里抖出来、破坏成品库结构。
        _move_work_to_stage1() 会把原路径写进 _portfolio_move_logs/delete_move_log_*.csv，
        这里反查最近一次「移出发布池」记录的原路径，取它的父目录。
        """
        if not work_id:
            return None
        log_dir = os.path.join(self.scanner.root, "_portfolio_move_logs")
        if not os.path.isdir(log_dir):
            return None
        try:
            files = [f for f in os.listdir(log_dir)
                     if f.startswith("delete_move_log_") and f.lower().endswith(".csv")]
        except Exception:
            return None
        files.sort(reverse=True)   # 文件名带年月，倒序即最新优先
        for name in files:
            try:
                with open(os.path.join(log_dir, name), "r", encoding="utf-8-sig",
                          errors="ignore", newline="") as fp:
                    rows = list(csv.DictReader(fp))
            except Exception:
                continue
            for row in reversed(rows):
                if (row.get("作品ID") or "").strip() != work_id:
                    continue
                if (row.get("动作类型") or "").strip() not in ("use_auto_dispatched", "dispatched"):
                    continue
                src = (row.get("原路径") or "").strip()
                parent = os.path.dirname(src) if src else ""
                if parent:
                    return parent
        return None

    def _is_album_dir_under_stage0(self, path: str) -> bool:
        """path 是否为「已发送0次」下面的合集子目录（只允许恢复到这一类原位）。"""
        try:
            stage0 = os.path.abspath(os.path.join(self.scanner.root, self.scanner.STAGE0_FOLDER))
            target = os.path.abspath(path)
            if target == stage0:
                return False
            return os.path.commonpath([target, stage0]) == stage0
        except Exception:
            return False

    def _log_stage_move(self, root_dir: str, device_name: str, work_id: str,
                        src_path: str, dest_path: str, use_count: int,
                        action_type: str, remark: str = "") -> None:
        log_dir = os.path.join(root_dir, "_portfolio_move_logs")
        try:
            os.makedirs(log_dir, exist_ok=True)
            log_file = os.path.join(log_dir, f"delete_move_log_{time.strftime('%Y%m')}.csv")
            header_needed = not os.path.exists(log_file)
            with open(log_file, "a", encoding="utf-8-sig") as fp:
                if header_needed:
                    fp.write("时间,设备,作品ID,原路径,目标路径,原使用次数,动作类型,备注\n")
                fp.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')},{device_name},{work_id},"
                         f"{src_path},{dest_path},{use_count},{action_type},{remark}\n")
        except Exception:
            pass

    def _restore_work_to_stage0(self, target_work: Dict[str, Any], device_name: str) -> Tuple[bool, str, str]:
        """在线回收站「恢复」：移回「已发送0次（抖音小红书可发）」、使用次数归零、撤销垃圾标记。

        已与用户确认口径：两个 Tab（已使用 / 已标记垃圾）的「恢复」都是这个语义 ——
        让作品重新变回可发手机的全新作品。
        """
        src_path = target_work["path"]
        folder_name = os.path.basename(src_path)
        work_id = target_work.get("id", "")
        was_garbage = target_work.get("folder") == self.scanner.GARBAGE_FOLDER
        root_dir = self.scanner.root
        dest_base = os.path.join(root_dir, self.scanner.STAGE0_FOLDER)
        # 若作品原本住在「已发送0次/作品集_xxx」合集里，优先放回原位，
        # 否则会把作品从它的合集里抖到根目录，破坏成品库结构。
        restored_to_album = False
        home = self._original_parent_dir(work_id)
        if home and os.path.isdir(home) and self._is_album_dir_under_stage0(home):
            dest_base = home
            restored_to_album = True
        os.makedirs(dest_base, exist_ok=True)

        target_dest = os.path.join(dest_base, folder_name)
        if os.path.exists(target_dest) and os.path.abspath(target_dest) != os.path.abspath(src_path):
            target_dest = os.path.join(dest_base, f"{folder_name}_{time.strftime('%Y%m%d_%H%M%S')}")

        if os.path.abspath(target_dest) != os.path.abspath(src_path):
            move_err = None
            for _ in range(3):
                try:
                    shutil.move(src_path, target_dest)
                    move_err = None
                    break
                except Exception as e:
                    move_err = e
                    time.sleep(0.3)
            if move_err is not None:
                try:
                    shutil.copytree(src_path, target_dest, dirs_exist_ok=True)
                    shutil.rmtree(src_path, ignore_errors=True)
                    move_err = None
                except Exception as e2:
                    move_err = e2
            if move_err is not None:
                return False, "", f"物理移动失败: {move_err}"

        restored_at = time.strftime("%Y-%m-%d %H:%M:%S")

        # 1) 作品标签.json：使用次数归零，重回「待发手机」
        tag_file = os.path.join(target_dest, "作品标签.json")
        if os.path.exists(tag_file):
            try:
                with open(tag_file, "r", encoding="utf-8", errors="ignore") as fp:
                    tag = json.load(fp)
                if isinstance(tag, dict):
                    dist = tag.get("distribution")
                    if not isinstance(dist, dict):
                        dist = {}
                        tag["distribution"] = dist
                    dist["useCount"] = 0
                    dist["dispatchedTo"] = []
                    dist["status"] = "待发手机"
                    dist["restoredAt"] = restored_at
                    with open(tag_file, "w", encoding="utf-8") as fp:
                        json.dump(tag, fp, ensure_ascii=False, indent=2)
            except Exception as e:
                print(f"[Warn] restore tag write failed: {e}")

        # 2) manifest.json：清 shareCount、摘掉 garbage 块并留档
        #    注意：不要给本来就没有 manifest.json 的作品凭空造一个，
        #    否则会给成品库塞进只有 shareCount 的空壳，干扰其它脚本。
        manifest_file = os.path.join(target_dest, "manifest.json")
        if os.path.exists(manifest_file) or was_garbage:
            try:
                manifest = {}
                if os.path.exists(manifest_file):
                    with open(manifest_file, "r", encoding="utf-8", errors="ignore") as fp:
                        manifest = json.load(fp)
                if isinstance(manifest, dict):
                    old = manifest.pop("garbage", None)
                    if isinstance(old, dict):
                        manifest.setdefault("restore_history", []).append({
                            "at": restored_at,
                            "by": device_name,
                            "from": target_work.get("stage", ""),
                            "previousRemark": old.get("remark", ""),
                        })
                    manifest["shareCount"] = 0
                    with open(manifest_file, "w", encoding="utf-8") as fp:
                        json.dump(manifest, fp, ensure_ascii=False, indent=2)
            except Exception as e:
                print(f"[Warn] restore manifest write failed: {e}")

        # 3) quality_tag.json：撤销全渠道垃圾硬拦截
        quality_file = os.path.join(target_dest, "quality_tag.json")
        needs_quality_write = was_garbage or os.path.exists(quality_file)
        if needs_quality_write:
            try:
                quality: Dict[str, Any] = {}
                if os.path.exists(quality_file):
                    with open(quality_file, "r", encoding="utf-8", errors="ignore") as fp:
                        loaded = json.load(fp)
                    if isinstance(loaded, dict):
                        quality = loaded
                quality["usable_for_wechat"] = True
                quality["usable_for_other_platforms"] = True
                quality["garbage"] = False
                quality["restored_at"] = restored_at
                quality["restored_by"] = device_name
                with open(quality_file, "w", encoding="utf-8") as fp:
                    json.dump(quality, fp, ensure_ascii=False, indent=2)
            except Exception as e:
                print(f"[Warn] restore quality write failed: {e}")

        self._log_stage_move(root_dir, device_name, work_id, src_path, target_dest,
                             int(target_work.get("useCount", 0) or 0), "restore_to_stage0")

        self.scanner._moved_works.pop(work_id, None)
        target_work["path"] = target_dest
        target_work["stage"] = "已发送0次"
        target_work["useCount"] = 0
        target_work["folder"] = self.scanner.STAGE0_FOLDER

        label = "垃圾样本库" if was_garbage else "已发送1次"
        where = "原作品集合集" if restored_to_album else "「已发送0次（抖音小红书可发）」"
        return True, target_dest, f"已从「{label}」恢复：移回{where}，使用次数归零，可重新发布"

    def _annotate_garbage(self, target_work: Dict[str, Any], remark: str, device_name: str) -> Tuple[bool, str]:
        """给垃圾作品写/改人工判断备注（quality_tag.json + manifest.json 同时落盘）。"""
        dir_path = target_work["path"]
        remark = (remark or "").strip()
        ts = time.strftime("%Y-%m-%d %H:%M:%S")

        quality_file = os.path.join(dir_path, "quality_tag.json")
        try:
            quality: Dict[str, Any] = {}
            if os.path.exists(quality_file):
                with open(quality_file, "r", encoding="utf-8", errors="ignore") as fp:
                    loaded = json.load(fp)
                if isinstance(loaded, dict):
                    quality = loaded
            if not quality.get("garbage"):
                quality.setdefault("garbage", True)
                quality.setdefault("usable_for_wechat", False)
                quality.setdefault("usable_for_other_platforms", False)
            quality["garbage_remark"] = remark
            quality["remark_updated_at"] = ts
            quality["remark_updated_by"] = device_name
            with open(quality_file, "w", encoding="utf-8") as fp:
                json.dump(quality, fp, ensure_ascii=False, indent=2)
        except Exception as e:
            return False, f"写入 quality_tag.json 失败: {e}"

        manifest_file = os.path.join(dir_path, "manifest.json")
        if os.path.exists(manifest_file):
            try:
                with open(manifest_file, "r", encoding="utf-8", errors="ignore") as fp:
                    manifest = json.load(fp)
                if isinstance(manifest, dict):
                    g = manifest.get("garbage")
                    if not isinstance(g, dict):
                        g = {"marked": True, "markedAt": ts, "markedBy": device_name}
                    g["remark"] = remark
                    g["remarkUpdatedAt"] = ts
                    manifest["garbage"] = g
                    with open(manifest_file, "w", encoding="utf-8") as fp:
                        json.dump(manifest, fp, ensure_ascii=False, indent=2)
            except Exception as e:
                print(f"[Warn] annotate manifest write failed: {e}")

        return True, ("已记录垃圾备注" if remark else "已清空垃圾备注")

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

            target_work = self.scanner.get_work(work_id)
            if not target_work:
                # 注意：HTTP 状态行按 latin-1 编码，中文作品名直接放进 reason 会抛
                # UnicodeEncodeError 并把请求打成 500，因此这里只回 ASCII 文案。
                self.send_error(404, "Work not found")
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

            try:
                with open(tag_file, "w", encoding="utf-8") as fp:
                    json.dump(tag_data, fp, ensure_ascii=False, indent=2)
            except Exception as e:
                print(f"[Warn] Failed to write tag file: {e}")

            log_file = os.path.join(self.scanner.root, "device-usage-log.csv")
            try:
                exists = os.path.exists(log_file)
                with open(log_file, "a", encoding="utf-8") as fp:
                    if not exists:
                        fp.write("时间,设备名,源作品,使用次数,平台,操作\n")
                    fp.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')},{device_name},{work_id},{new_count},{platform},手机在线直用打标\n")
            except Exception as e:
                print(f"[Warn] Failed to write usage log: {e}")

            # 核心业务铁律：只要手机端点击分享并使用过一次，电脑端后台自动将该作品物理移入「_已发送1次（微信公众号可发）」
            moved = False
            target_dest_path = ""
            if new_count >= 1:
                ok, target_dest_path, move_msg = self._move_work_to_stage1(target_work, device_name, "use_auto_dispatched")
                moved = ok

            msg = f"已成功记录第 {new_count} 次使用" + ("，电脑端已自动移入「_已发送1次」" if moved else "")

            self.scanner.scan(force=True)

            self.send_json(200, {
                "ok": True,
                "workId": work_id,
                "useCount": new_count,
                "remainingUses": 0,
                "moved": moved,
                "targetPath": target_dest_path,
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
            target_work = self.scanner.get_work(work_id)
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

            # 同步归零 manifest.json 的分发计数，确保「用过 → 重置 → 再删除」
            # 这条路径能稳定被判定为垃圾并移入垃圾样本库。
            manifest_file = os.path.join(dir_path, "manifest.json")
            if os.path.exists(manifest_file):
                try:
                    with open(manifest_file, "r", encoding="utf-8") as fp:
                        manifest = json.load(fp)
                    if isinstance(manifest, dict):
                        manifest["useCount"] = 0
                        if isinstance(manifest.get("distribution"), dict):
                            manifest["distribution"]["useCount"] = 0
                            manifest["distribution"]["dispatchedTo"] = []
                            manifest["distribution"]["status"] = "待发手机"
                        with open(manifest_file, "w", encoding="utf-8") as fp:
                            json.dump(manifest, fp, ensure_ascii=False, indent=2)
                except Exception as e:
                    print(f"Error resetting manifest: {e}")

            # 同步归零 manifest.json，确保扫描器与手机端看到的「使用次数」一致为 0，
            # 这样「用过 → 重置 → 再删除」才能被正确判定为人工垃圾样本。
            manifest_file = os.path.join(dir_path, "manifest.json")
            if os.path.exists(manifest_file):
                try:
                    with open(manifest_file, "r", encoding="utf-8") as fp:
                        manifest = json.load(fp)
                    manifest["useCount"] = 0
                    manifest["used"] = False
                    if isinstance(manifest.get("distribution"), dict):
                        manifest["distribution"]["useCount"] = 0
                        manifest["distribution"]["dispatchedTo"] = []
                    manifest["resetAt"] = time.strftime("%Y-%m-%d %H:%M:%S")
                    with open(manifest_file, "w", encoding="utf-8") as fp:
                        json.dump(manifest, fp, ensure_ascii=False, indent=2)
                except Exception as e:
                    print(f"Error resetting manifest: {e}")

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
            remark = str(req.get("remark", "") or "").strip()

            if not work_id:
                self.send_error(400, "Missing workId")
                return

            target_work = self.scanner.get_work(work_id)
            if not target_work:
                self.send_error(404, "Work not found")
                return

            # 判定权在手机端：只要手机点了删除（含「用过 → 重置 → 再删除」），
            # 一律视为人工判定垃圾：物理移入「_垃圾作品（后续参考分析）」永久保留，
            # 并在元数据写死垃圾标记（全渠道硬拦截）。电脑端绝不自动清理该样本库。
            ok, target_dest, action_desc = self._move_work_to_garbage(target_work, device_name, remark)
            if not ok:
                self.send_json(200, {"ok": False, "error": action_desc})
                return

            self.scanner.scan(force=True)

            self.send_json(200, {
                "ok": True,
                "workId": work_id,
                "action": "garbage_deleted",
                "message": action_desc,
                "targetPath": target_dest,
                "remark": remark,
                "remainingWorks": len(self.scanner.scan())
            })
            return

        if path == "/api/online/restore":
            # 在线回收站「恢复」：两个 Tab 都适用，移回「已发送0次」+ 次数归零 + 撤销垃圾标记
            content_length = int(self.headers.get("Content-Length", 0))
            raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
            try:
                req = json.loads(raw_body)
            except Exception:
                self.send_error(400, "Invalid JSON")
                return

            work_id = (req.get("workId") or "").strip()
            device_name = (req.get("device") or "手机端在线回收站").strip()
            target_work = self.scanner.resolve_stage_work(work_id)
            if not target_work:
                self.send_json(404, {"ok": False, "workId": work_id,
                                     "message": "作品不存在（可能已被恢复或移走），请刷新后重试"})
                return

            ok, target_dest, msg = self._restore_work_to_stage0(target_work, device_name)
            self.scanner.scan(force=True)
            self.send_json(200 if ok else 500, {
                "ok": ok,
                "workId": work_id,
                "action": "restore",
                "message": msg,
                "targetPath": target_dest,
                "useCount": 0,
            })
            return

        if path == "/api/online/remark-garbage":
            # 允许对垃圾特点标注人工判断（写入 quality_tag.json + manifest.json）
            content_length = int(self.headers.get("Content-Length", 0))
            raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
            try:
                req = json.loads(raw_body)
            except Exception:
                self.send_error(400, "Invalid JSON")
                return

            work_id = (req.get("workId") or "").strip()
            remark = (req.get("remark") or "").strip()
            device_name = (req.get("device") or "手机端在线回收站").strip()
            target_work = self.scanner.resolve_stage_work(work_id)
            if not target_work:
                self.send_json(404, {"ok": False, "workId": work_id, "message": "作品不存在，请刷新后重试"})
                return

            ok, msg = self._annotate_garbage(target_work, remark, device_name)
            if ok:
                self._log_stage_move(self.scanner.root, device_name, work_id,
                                     target_work["path"], target_work["path"],
                                     int(target_work.get("useCount", 0) or 0),
                                     "remark_garbage", remark)
            self.send_json(200 if ok else 500, {
                "ok": ok,
                "workId": work_id,
                "action": "remark_garbage",
                "remark": remark,
                "message": msg,
            })
            return

        self.send_error(404, "Not Found")


def start_adb_reverse_daemon(port: int):
    """
    后台静默守护线程：为所有已连接的 Android 设备自动配置 adb reverse tcp:port tcp:port。
    严格使用 0x08000000 (CREATE_NO_WINDOW)，100% 绝对无任何黑色 CMD 控制台窗口，纯静默安全执行。
    """
    import threading
    CREATE_NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0

    def _daemon_loop():
        adb_candidates = [
            r"D:\Program Files\scrcpy-win32-v3.1\platform-tools\adb.exe",
            r"C:\Users\z\AppData\Local\Android\Sdk\platform-tools\adb.exe",
            "adb"
        ]
        adb_bin = "adb"
        for c in adb_candidates:
            if os.path.exists(c):
                adb_bin = c
                break

        while True:
            try:
                res = subprocess.run(
                    [adb_bin, "devices"],
                    capture_output=True,
                    text=True,
                    creationflags=CREATE_NO_WINDOW,
                    timeout=5
                )
                lines = res.stdout.strip().splitlines()
                for line in lines[1:]:
                    parts = line.strip().split()
                    if len(parts) >= 2 and parts[1] == "device":
                        dev_id = parts[0]
                        subprocess.run(
                            [adb_bin, "-s", dev_id, "reverse", f"tcp:{port}", f"tcp:{port}"],
                            capture_output=True,
                            creationflags=CREATE_NO_WINDOW,
                            timeout=5
                        )
            except Exception:
                pass
            time.sleep(20)

    t = threading.Thread(target=_daemon_loop, daemon=True, name="AdbReverseDaemon")
    t.start()


# ============================ 局域网信标（手机零扫描秒级发现） ============================
BEACON_PORT = 45832
BEACON_MAGIC = "ZWMDS2_GALLERY_DISCOVER"
BEACON_INTERVAL = 2.0


def get_all_local_ips() -> List[str]:
    """枚举本机所有非回环 IPv4 地址"""
    ips: List[str] = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    primary = get_local_ip()
    if primary and not primary.startswith("127.") and primary not in ips:
        ips.insert(0, primary)
    return ips


def get_broadcast_targets() -> List[str]:
    """本机所有网段的广播地址 + 全局广播"""
    targets = ["255.255.255.255"]
    for ip in get_all_local_ips():
        parts = ip.split(".")
        if len(parts) == 4:
            b = ".".join(parts[:3] + ["255"])
            if b not in targets:
                targets.append(b)
    return targets


def start_lan_beacon(port: int = DEFAULT_PORT, beacon_port: int = BEACON_PORT):
    """
    后台线程：向局域网周期广播「在线相册服务」信标（UDP 45832），
    同时应答手机端主动探测。

    价值：
    - 手机端不必再全 /24 端口扫描，2 秒内即可拿到电脑地址；
    - 电脑 IP 变化后手机会自动跟随，在线相册读取稳定不掉线；
    - 不依赖 ADB 隧道，纯 Wi-Fi 设备（vivo / 华为 / 苹果）同样可用。
    """
    def _payload(ip: str) -> bytes:
        return json.dumps({
            "service": "DeviceShareHub-OnlineGallery",
            "type": "beacon",
            "v": 1,
            "port": port,
            "url": f"http://{ip}:{port}",
            "ip": ip,
            "ts": int(time.time() * 1000),
        }, ensure_ascii=False).encode("utf-8")

    def _loop():
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.bind(("0.0.0.0", beacon_port))
            sock.settimeout(1.0)
        except Exception as exc:
            print(f"⚠️ 局域网信标启动失败（端口 {beacon_port}）：{exc}")
            return

        broadcast_targets = get_broadcast_targets()
        ip_cache = get_local_ip()
        local_ips = set(get_all_local_ips())
        last_broadcast = 0.0
        last_refresh = time.time()
        print(f"📣 局域网信标已启动：UDP {beacon_port} → {broadcast_targets}（每 {BEACON_INTERVAL:.0f}s 一次）")

        while True:
            now = time.time()
            # 每 30 秒刷新一次本机 IP 与广播目标（切换网络/IP 变化时自动跟上）
            if now - last_refresh > 30:
                ip_cache = get_local_ip()
                local_ips = set(get_all_local_ips())
                broadcast_targets = get_broadcast_targets()
                last_refresh = now

            if now - last_broadcast >= BEACON_INTERVAL:
                data = _payload(ip_cache)
                for target in broadcast_targets:
                    try:
                        sock.sendto(data, (target, beacon_port))
                    except Exception:
                        pass
                last_broadcast = now

            # 应答手机主动探测（广播被路由器拦截时的兜底通路）
            try:
                packet, addr = sock.recvfrom(2048)
                text = packet.decode("utf-8", "ignore").strip()
                # 关键防护：信标本身是 JSON，若不排除会被误判成探测包，
                # 导致服务给自己回信、无限自问自答形成广播风暴。
                if text.startswith("{"):
                    continue
                # 同样忽略来自本机的报文（多网卡/回环场景）
                if addr and addr[0] in local_ips:
                    continue
                if BEACON_MAGIC in text or "ZWMDS2" in text.upper():
                    sock.sendto(_payload(ip_cache), addr)
            except socket.timeout:
                pass
            except Exception:
                pass

    t = threading.Thread(target=_loop, daemon=True, name="LanBeacon")
    t.start()


def run_service(port: int = DEFAULT_PORT, library_root: str = DEFAULT_LIBRARY_ROOT, enable_adb: bool = False):
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

    # 启动纯静默 ADB 隧道守护（0 弹窗 0 黑框）
    start_adb_reverse_daemon(port)

    # 启动局域网信标广播：手机端零扫描、秒级发现，IP 变化自动跟随
    start_lan_beacon(port)

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
    parser.add_argument("--enable-adb", action="store_true", default=False, help="Enable legacy ADB reverse daemon")
    args = parser.parse_args()

    target_port = args.port or args.pos_port or DEFAULT_PORT
    target_root = args.root or args.pos_root or DEFAULT_LIBRARY_ROOT
    run_service(target_port, target_root, enable_adb=args.enable_adb)
