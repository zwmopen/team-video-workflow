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
import gzip
import time
import socket
import threading
import hashlib
import urllib.parse
import urllib.request
import tempfile
import re
import shutil
import subprocess
from io import BytesIO
from collections import OrderedDict
from http.server import HTTPServer, ThreadingHTTPServer, BaseHTTPRequestHandler
from typing import Dict, List, Any, Optional, Tuple, Set

try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

# ── 代码新鲜度自检（2026-09-20 新增）────────────────────────────────────────
# 事故背景：服务进程启动于 06:58，而脚本在 14:44 被改过 —— 进程一直在跑旧代码，
# thumb=1 被静默忽略成原图，手机端在线回收站直接卡死，而没有任何信号提示这件事。
# 现在把「进程启动时的脚本指纹」与「磁盘上脚本的实时指纹」一起暴露到 /api/online/status，
# 两者不一致就说明该重启服务了（staleCode=true）。
SCRIPT_PATH = os.path.abspath(__file__)


def _script_fingerprint(path: str) -> "tuple[float, str]":
    """取脚本指纹 (mtime, sha12)；读不到时返回 (0.0, "")。

    ⚠️ 计算 sha 前**必须归一化行尾符**（CRLF -> LF）。
    本仓库 `core.autocrlf=true`，git 把工作区文件写成 CRLF、对象库存 LF，
    一次 `git checkout` / `git pull` 就会在**代码内容完全没变**的情况下改变文件字节。
    不归一化的话，staleCode 会因这种纯行尾差异误报并一直亮着，
    久而久之就没人再看这个信号了 —— 等于又造了一道噪声闸门。

    实测证据（2026-09-20）：`git checkout` 后磁盘文件原样 sha = ec52055b6144，
    归一化 LF 后 = 212f2a86b697，与 HEAD 内容 / 服务启动快照完全一致。
    """
    try:
        mtime = os.path.getmtime(path)
    except Exception:
        mtime = 0.0
    try:
        with open(path, "rb") as _fp:
            raw = _fp.read().replace(b"\r\n", b"\n")     # 归一化行尾，见上方说明
        sha = hashlib.sha1(raw).hexdigest()[:12]
    except Exception:
        sha = ""
    return mtime, sha


def _fmt_ts(ts: float) -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ts)) if ts else ""


# 「进程启动那一刻」的脚本指纹快照，只在导入时取一次。
SCRIPT_MTIME_AT_START, SCRIPT_SHA_AT_START = _script_fingerprint(SCRIPT_PATH)
PROCESS_STARTED_AT = time.time()
PROCESS_STARTED_STR = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(PROCESS_STARTED_AT))


def code_freshness() -> Dict[str, Any]:
    """判断「正在跑的进程」是否落后于磁盘上的脚本。

    ⚠️ 2026-09-20 二次修复：初版拿「启动时快照的 mtime」去和「启动时刻」比，
    而那个条件在启动瞬间必然不成立（文件肯定早于进程存在），staleCode 恒为 False
    —— 是一道**假闸门**，比没有更危险。
    正确判据是「磁盘上脚本的当前内容」 vs 「启动时的脚本内容」：
    内容不一致 ⇒ 磁盘上的代码已经不是正在跑的那份 ⇒ 该重启。
    用内容 SHA 而不是 mtime：mtime 会被 checkout / 复制 / 时区干扰，内容哈希不会。
    """
    disk_mtime, disk_sha = _script_fingerprint(SCRIPT_PATH)      # ← 实时读盘，不是快照
    if disk_sha and SCRIPT_SHA_AT_START:
        stale = disk_sha != SCRIPT_SHA_AT_START
    else:
        stale = False
    return {
        "startedAt": PROCESS_STARTED_STR,
        "uptimeSeconds": int(time.time() - PROCESS_STARTED_AT),
        "scriptPath": SCRIPT_PATH,
        "scriptShaRunning": SCRIPT_SHA_AT_START,      # 正在跑的代码（启动快照）
        "scriptShaOnDisk": disk_sha,                  # 磁盘上的代码（实时）
        "scriptMtime": _fmt_ts(disk_mtime),           # 磁盘当前 mtime
        "scriptMtimeAtStart": _fmt_ts(SCRIPT_MTIME_AT_START),
        "staleCode": stale,
        "hint": "磁盘上的脚本已改动（内容 sha 不一致）：正在运行的是旧代码，请重启在线相册服务" if stale else "",
    }

# 手机端「使用次数」回读同步 + 局域网手机在线探测（见 phone_sync.py 顶部注释）
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import phone_sync  # noqa: E402

DEFAULT_PORT = 45835
DEFAULT_LIBRARY_ROOT = r"D:\AICode\项目推进\projects\江湖有旅人\主项目\成品库（GPT+本地脚本制作）"

# ============================================================================
# 已授权设备白名单（用户最终决定：默认放行所有设备，无需扫码/弹框）
# 设计目标：
#   1) 任何手机首次连上 → 直接 allow + 写白名单（不弹框不扫码）
#   2) 白名单持久化（重启 PC 不丢），换电脑配置都在
#   3) PC 端 share.html 能列出所有已授权设备 + 单台移除
# ============================================================================
AUTHORIZED_DEVICES_FILE = os.path.join(
    os.path.expanduser("~"), ".device-share-hub-authorized-devices.json"
)
AUTHORIZED_DEVICES_LOCK = threading.Lock()


def load_authorized_devices() -> Dict[str, Dict[str, Any]]:
    """从磁盘读取已授权设备列表。文件不存在或损坏返回空 dict，不抛。"""
    try:
        with open(AUTHORIZED_DEVICES_FILE, "r", encoding="utf-8") as fp:
            data = json.load(fp)
        if isinstance(data, dict):
            return data
    except FileNotFoundError:
        return {}
    except (OSError, json.JSONDecodeError):
        return {}
    return {}


def save_authorized_devices(devices: Dict[str, Dict[str, Any]]) -> None:
    """原子写已授权设备列表到磁盘：tmp + rename，掉电不残留半截文件。"""
    AUTHORIZED_DEVICES_FILE_DIR = os.path.dirname(AUTHORIZED_DEVICES_FILE)
    os.makedirs(AUTHORIZED_DEVICES_FILE_DIR, exist_ok=True)
    tmp_path = AUTHORIZED_DEVICES_FILE + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as fp:
        json.dump(devices, fp, ensure_ascii=False, indent=2, sort_keys=True)
    os.replace(tmp_path, AUTHORIZED_DEVICES_FILE)

# ============================================================================
# 【空壳文案守卫】判定真源优先复用 copy_formatter.assert_copy_usable 的阈值体系；
# 该服务以 pythonw 常驻运行，跨盘导入失败时退回本地等效实现，保证永不因缺依赖而放行空壳。
# 与 Android 端 MainActivity.isCopySubstanceMissing（阈值 30）严格一致。
# ============================================================================
MIN_COPY_DISTRIBUTABLE = 30
_COPY_MARKER_RE = re.compile(r"<<<[^>\n]*>>>")
_COPY_INVISIBLE_RE = re.compile(r"[\s\u2800\u200b\u200c\u200d\ufeff]+")


def _load_copy_formatter():
    try:
        fdir = r"d:\AICode\.agents\skills\copy-collab-distributor\scripts"
        if fdir not in sys.path:
            sys.path.insert(0, fdir)
        import copy_formatter  # type: ignore
        return copy_formatter
    except Exception:
        return None


_COPY_FORMATTER = _load_copy_formatter()


def copy_substance_len(text) -> int:
    """剔除全部 <<<...>>> 标记、空白与盲文/零宽占位符后的实质字数。"""
    if not text:
        return 0
    s = str(text)
    if _COPY_FORMATTER is not None:
        try:
            return len(_COPY_FORMATTER.copy_substance(s))
        except Exception:
            pass
    return len(_COPY_INVISIBLE_RE.sub("", _COPY_MARKER_RE.sub("", s)))


def copy_search_text(text) -> str:
    """搜索专用正文：仅剥离 <<<...>>> 协议标记，保留真实文字（含薄文案），供关键词检索。"""
    if not text:
        return ""
    return _COPY_MARKER_RE.sub(" ", str(text))


# ============================================================================
# 【未填模板守卫】2026-09-21 新增 —— 第四层空壳守卫（互补于「平台槽位守卫」）
#
# 事故：20260913_213026 等作品的文案**没有任何 <<<...>>> 标记**，长这样：
#     【小红书文案】
#     [小红书主标题]
#     [小红书种草正文，带两日详细行程排期、亮点提炼与真实避坑，拒绝空话]
#     [12个同行热门话题标签]
#     【HR方案决策版】
#     [HR方案决策版大纲，包含方案名称、适用对象、预算参考、决策亮点与服务保障]
#     【抖音口播脚本】
#     [抖音短平快口播脚本，痛点切入+亮点+留资号召]
#   实测线上：hasCopyText=True / copyMissing=False / copyText=158 字——一字正文都没有。
#
# 为什么「平台槽位守卫」漏了它：那道守卫按 <<<XHS_START>>> 之类**标记**切槽位，
#   而这份文案一个标记都没有 → 切不出槽位 → 不触发 → 整段 158 字照原样下发。
#   两者是正交的两类：有标记的未填模板 vs 无标记的未填模板（【】/[] 骨架）。
#
# 修法：整段粒度判定「剔除占位脚手架后还剩多少正文」，不依赖任何标记格式。
#   未填模板的有效正文趋近 0，而任何真实文案（数百字）几乎不受影响。
# ============================================================================


def copy_effective_len(text) -> int:
    """剔除占位脚手架行后的正文长度；未填模板在此口径下为 0。"""
    if not text:
        return 0
    s = str(text)
    if _COPY_FORMATTER is not None:
        try:
            return len(_COPY_FORMATTER.copy_substance_effective(s))
        except Exception:
            pass
    return copy_substance_len(s)


def is_placeholder_copy(text) -> bool:
    """整段只数得到占位指令、数不到正文 → 未填模板。"""
    if not text:
        return False
    s = str(text)
    if _COPY_FORMATTER is not None:
        try:
            return bool(_COPY_FORMATTER.is_placeholder_only(s))
        except Exception:
            pass
    return False


def copy_is_real(text, min_substance: int = MIN_COPY_DISTRIBUTABLE) -> bool:
    """下发总闸：正文（不含占位脚手架）达到下限，才算真有文案。"""
    return copy_effective_len(text) >= min_substance


# ============================================================================
# 【平台槽位守卫】2026-09-20 新增 —— 第三层空壳守卫，与 MIN_COPY_DISTRIBUTABLE 并列
#
# 事故：8 套作品躺在「已发送0次（抖音小红书可发）」里，文案是**未填充的模板骨架**：
#     <<<DOUYIN_START>>>  抖音短平快口播脚本，痛点切入+亮点+留资号召  <<<DOUYIN_END>>>
#     <<<XHS_START>>>     小红书主标题 / [小红书种草正文，带两日详细行程排期…]  <<<XHS_END>>>
#     <<<XHS_2_START>>>   HR方案决策版大纲，包含方案名称、适用对象、预算参考…  <<<XHS_2_END>>>
#   整段实质 107 字 >= 30，被 MIN_COPY_DISTRIBUTABLE 放行 ——
#   而该守卫第 147 行注释明写它的目的正是「杜绝标记齐全但正文全空」。
#
# 根因：**守卫的粒度是「整段」，用户消费的粒度是「单个平台槽位」**，两者不在同一层级。
#   手机端 `PlatformCopyParser.parseAvailablePlatforms` 是按槽位独立出按钮的，
#   于是用户点「规避营销版」拿到的就是那句说明文字。与 staleCode 那次同源：闸门测错了对象。
#
# 修法：按槽位判定，下发前把「占位骨架 / 实质不足」的槽位从文案里剔除（仅作用于下发载荷，
#   不动磁盘上的原始文件）。若剔除后已无真实槽位，整段实质字数自然 < 30，
#   手机端既有逻辑会整块置灰并标红「文案缺失」，无需更新 APK。
# ============================================================================
MIN_PLATFORM_SUBSTANCE = 30

# ============================================================================
# 【列表载荷瘦身】2026-09-20
# 实测 /api/online/works 全量响应 2751.8 KB 的构成：
#     copyText    1149.4 KB (41.8%)   ← 客户端要（渲染平台按钮），保留
#     searchBlob  1132.6 KB (41.2%)   ← **仅服务端关键词检索用**，两端客户端都不解析
#     path          91.4 KB ( 3.3%)   ← 客户端要（「复制路径」按钮），保留
#     slotGuard     39.0 KB ( 1.4%)   ← 槽位守卫诊断信息，两端客户端都不解析
# 已核对 android/ 与 ios/ 全仓：searchBlob / slotGuard 命中数为 0。
# 故从**列表响应**里剥离这两个字段（体积立减 ~43%），
# 服务端内部检索仍用完整 dict（work_text_blob 依赖 searchBlob），不受影响。
# ============================================================================
GZIP_MIN_BYTES = 1024
_WIRE_OMIT_FIELDS = ("searchBlob", "slotGuard")

# gzip 结果缓存。实测瘦身后的全量列表 1568.5 KB，gzip.compress(body, 6) 要烧约 66 ms CPU，
# 而这份内容在两次扫描之间是**字节级不变**的（手机端一次进页面只发一次请求，
# 但计时探测/重试/多端同时打开都会重复打）。故缓存压缩结果。
# 键用**内容摘要**而非"扫描时间戳"：blake2b 摘要 1.5 MB 约 1~2 ms，
# 比重新压缩便宜 30 倍以上，且天然没有"数据变了但键没变"的陈旧风险。
_GZIP_CACHE: "OrderedDict[bytes, bytes]" = OrderedDict()
_GZIP_CACHE_MAX = 6
_GZIP_LOCK = threading.Lock()
_gzip_hit = 0
_gzip_miss = 0


def gzip_bytes(body: bytes, level: int = 6) -> bytes:
    """压缩并缓存结果（内容寻址）。"""
    global _gzip_hit, _gzip_miss
    key = hashlib.blake2b(body, digest_size=16).digest()
    with _GZIP_LOCK:
        hit = _GZIP_CACHE.get(key)
        if hit is not None:
            _GZIP_CACHE.move_to_end(key)
            _gzip_hit += 1
            return hit
        _gzip_miss += 1
    gz = gzip.compress(body, level)
    with _GZIP_LOCK:
        _GZIP_CACHE[key] = gz
        while len(_GZIP_CACHE) > _GZIP_CACHE_MAX:
            _GZIP_CACHE.popitem(last=False)
    return gz


def gzip_cache_health() -> Dict[str, Any]:
    with _GZIP_LOCK:
        total = _gzip_hit + _gzip_miss
        return {
            "hit": _gzip_hit,
            "miss": _gzip_miss,
            "hitRate": round(_gzip_hit / total, 4) if total else 0.0,
            "entries": len(_GZIP_CACHE),
            "maxEntries": _GZIP_CACHE_MAX,
        }


def slim_works_for_wire(works: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """列表接口下发给客户端前剥离客户端不解析的重字段。

    ⚠️ 只剥响应副本，不改 self._cached_works / _works_by_id 里的原始 dict，
    否则服务端的 searchBlob 关键词过滤会失效。
    """
    if not works:
        return works
    return [{k: v for k, v in w.items() if k not in _WIRE_OMIT_FIELDS} for w in works]


# 形如 <<<MK_START>>> … <<<MK_END>>>（MK 可为 XHS / XHS_2 / DOUYIN / 任意中文标记）
_PLATFORM_BLOCK_RE = re.compile(
    r"<<<([A-Za-z0-9_\u4e00-\u9fa5]+)_START>>>[\r\n]*(.*?)[\r\n]*<<<\1_END>>>",
    re.S,
)

# 模板占位语特征：产线只落了骨架、没跑填充步骤时出现的「指令式说明」，不是真实文案。
# 注意必须作用在**带括号的原文**上（占位语常被 [] 包裹）。
_PLACEHOLDER_SLOT_RE = re.compile(
    r"抖音短平快口播脚本"
    r"|痛点切入\s*[+＋]\s*亮点\s*[+＋]\s*留资号召"
    r"|小红书主标题"
    r"|\[小红书种草正文"
    r"|\[?\s*\d{1,3}\s*个?同行热门话题标签\s*\]?"
    r"|HR方案决策版大纲"
    r"|包含方案名称、适用对象、预算参考"
    r"|拒绝空话\]"
    r"|待补充|此处填写|请填写|【待填】|\bTODO\b"
)


def is_placeholder_slot(text: str) -> bool:
    """槽位正文是否为「模板占位说明」而非真实文案。"""
    return bool(text) and bool(_PLACEHOLDER_SLOT_RE.search(str(text)))


def audit_platform_slots(copy_text: str) -> Dict[str, Any]:
    """逐槽位体检（只读，不改文案）。用于 /status 诊断与测试断言。"""
    out = {"slots": [], "dropped": [], "keptCount": 0, "droppedCount": 0}
    if not copy_text:
        return out
    for m in _PLATFORM_BLOCK_RE.finditer(copy_text):
        marker, body = m.group(1), m.group(2)
        n = copy_substance_len(body)
        bad = (n < MIN_PLATFORM_SUBSTANCE) or is_placeholder_slot(body)
        rec = {"marker": marker, "substance": n,
               "head": body.strip()[:48], "placeholder": is_placeholder_slot(body)}
        out["slots"].append(rec)
        if bad:
            out["dropped"].append(rec)
        else:
            out["keptCount"] += 1
    out["droppedCount"] = len(out["dropped"])
    return out


def sanitize_platform_copy(copy_text: str) -> "tuple[str, Dict[str, Any]]":
    """剔除占位/过薄槽位，返回 (净化后文案, 诊断)。

    用「按 span 删除」而不是「按块重建」，这样可原样保留头部标记
    (`<<<COPY_FORMAT:n>>>`) 以及 `<<<VERSION_START:名>>>` 这类本函数不解析的块。
    """
    diag = audit_platform_slots(copy_text)
    if not copy_text or not diag["dropped"]:
        return copy_text, diag

    spans = []
    for m in _PLATFORM_BLOCK_RE.finditer(copy_text):
        body = m.group(2)
        if (copy_substance_len(body) < MIN_PLATFORM_SUBSTANCE) or is_placeholder_slot(body):
            spans.append(m.span())

    out = copy_text
    for s, e in reversed(spans):          # 从后往前删，避免下标位移
        out = out[:s] + out[e:]
    out = re.sub(r"\n{3,}", "\n\n", out).strip()
    return out, diag


def work_text_blob(w: dict, with_title: bool = True) -> str:
    """作品的关键词匹配文本：标题/目的地 + 搜索专用正文（回退 copyText）。"""
    blob = (w.get("searchBlob") or w.get("copyText") or "")
    if with_title:
        return (w.get("rawTitle", "") or w.get("title", "")) + " " + blob
    return (w.get("title", "") or "") + " " + (w.get("destination", "") or "") + " " + blob

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

# 产线标准布局：成品图不一定在作品根目录，也可能在 `产出素材/` 子目录里。
# 【2026-09-21 修】老实现只 os.listdir 看顶层 ⇒ 图在子目录的 103 套作品
# 在手机在线相册里完全不可见（实测 totalWorks 389，磁盘实为 483）。
IMAGE_SUBDIR_FALLBACKS = ("产出素材",)

# 内存缩略图缓存 (cache_key -> bytes)，带 LRU 淘汰。
# ⚠️ 2026-09-20 修复：此前 THUMB_CACHE 只有声明、从未被读取（死代码），
#    每个缩略图请求都要读磁盘、冷缓存时还要跑 PIL，是手机端「一直在读取」的放大器。
THUMB_CACHE: "OrderedDict[str, bytes]" = OrderedDict()
THUMB_CACHE_LOCK = threading.Lock()
MAX_CACHE_ENTRIES = 500

# 磁盘缓存的 key 是「路径 + mtime」，图片一改就生成新条目、旧条目永不失效。
# 不加上限的话，图库长到万张级会累积到几百 MB（且不会自动回收）。
# 2026-09-20 补：低频检查 + 超限时按 mtime 淘汰最旧的一批（只留 90%，避免刚淘汰完又立刻再触发）。
MAX_DISK_ENTRIES = 2000
DISK_EVICT_CHECK_EVERY = 200      # 每 N 次写盘才检查一次，避免每次写盘都全目录 stat
_disk_write_count = 0
DISK_EVICT_LOCK = threading.Lock()

# 同一张图并发生成去重：手机端一屏会同时要几十张图，无去重时会同时对同一文件跑 PIL（CPU 密集）
THUMB_INFLIGHT: Dict[str, threading.Lock] = {}
THUMB_INFLIGHT_LOCK = threading.Lock()

# 缩略图链路健康计数（供 /api/online/status 一眼诊断，避免同类静默失败再次发生）
THUMB_STATS: Dict[str, int] = {
    "memoryHit": 0, "diskHit": 0, "generated": 0,
    "fallbackOriginal": 0, "diskEvicted": 0,
}
THUMB_STATS_LOCK = threading.Lock()

# 【平台槽位守卫】计数：本轮扫描中有多少作品/槽位被净化（/api/online/status 暴露）
_SLOT_GUARD_STATS: Dict[str, int] = {"worksAffected": 0, "slotsDropped": 0}
_SLOT_GUARD_LOCK = threading.Lock()


def _slot_guard_reset() -> None:
    with _SLOT_GUARD_LOCK:
        _SLOT_GUARD_STATS["worksAffected"] = 0
        _SLOT_GUARD_STATS["slotsDropped"] = 0


def _slot_guard_stat(key: str, delta: int = 1) -> None:
    with _SLOT_GUARD_LOCK:
        _SLOT_GUARD_STATS[key] = _SLOT_GUARD_STATS.get(key, 0) + delta


def _thumb_stat(key: str, delta: int = 1) -> None:
    with THUMB_STATS_LOCK:
        THUMB_STATS[key] = THUMB_STATS.get(key, 0) + delta


def thumb_cache_get(key: str) -> Optional[bytes]:
    """取内存缩略图缓存（命中即刷新 LRU 位置）。"""
    with THUMB_CACHE_LOCK:
        data = THUMB_CACHE.get(key)
        if data is not None:
            THUMB_CACHE.move_to_end(key)
        return data


def thumb_cache_put(key: str, data: bytes) -> None:
    """写内存缩略图缓存，超限按 LRU 淘汰。"""
    with THUMB_CACHE_LOCK:
        THUMB_CACHE[key] = data
        THUMB_CACHE.move_to_end(key)
        while len(THUMB_CACHE) > MAX_CACHE_ENTRIES:
            THUMB_CACHE.popitem(last=False)


def evict_disk_cache_if_needed() -> int:
    """磁盘缩略图缓存上限淘汰：超过 MAX_DISK_ENTRIES 时按 mtime 删掉最旧的一批。

    低频触发（每 DISK_EVICT_CHECK_EVERY 次写盘才真扫目录），且只在超限时才排序，
    正常使用下这个函数几乎零开销。
    """
    global _disk_write_count
    with DISK_EVICT_LOCK:
        _disk_write_count += 1
        if _disk_write_count % DISK_EVICT_CHECK_EVERY != 0:
            return 0
        try:
            names = os.listdir(DISK_THUMB_DIR)
        except Exception:
            return 0
        if len(names) <= MAX_DISK_ENTRIES:
            return 0
        entries = []
        for n in names:
            p = os.path.join(DISK_THUMB_DIR, n)
            try:
                entries.append((os.path.getmtime(p), p))
            except OSError:
                pass
        entries.sort()                              # 最旧在前
        keep = int(MAX_DISK_ENTRIES * 0.9)
        removed = 0
        for _, p in entries[: max(0, len(entries) - keep)]:
            try:
                os.remove(p)
                removed += 1
            except OSError:
                pass
        if removed:
            _thumb_stat("diskEvicted", removed)
            print(f"[Thumb] 磁盘缓存淘汰 {removed} 个（{len(entries)} -> {len(entries) - removed}，上限 {MAX_DISK_ENTRIES}）")
        return removed


def _inflight_lock(key: str) -> threading.Lock:
    """取某个缓存键的专属互斥锁（保证同一张图只被生成一次）。"""
    with THUMB_INFLIGHT_LOCK:
        lock = THUMB_INFLIGHT.get(key)
        if lock is None:
            lock = threading.Lock()
            THUMB_INFLIGHT[key] = lock
        return lock


def build_thumbnail_bytes(img_path: str, mtime: float) -> Optional[bytes]:
    """生成 320×320 缩略图字节（内存 → 磁盘 → PIL 三级，同级并发去重）。

    返回 None 表示「本机无法生成缩略图」（通常是 Pillow 缺失），调用方据此走显式降级，
    而不是静默把几 MB 的原图当成缩略图发出去。
    """
    cache_key = hashlib.md5(f"{img_path}_{mtime}".encode("utf-8")).hexdigest()

    data = thumb_cache_get(cache_key)
    if data is not None:
        _thumb_stat("memoryHit")
        return data

    disk_path = os.path.join(DISK_THUMB_DIR, cache_key + ".jpg")
    if os.path.isfile(disk_path):
        try:
            with open(disk_path, "rb") as fp:
                data = fp.read()
            thumb_cache_put(cache_key, data)
            _thumb_stat("diskHit")
            return data
        except Exception:
            pass

    if not HAS_PIL:
        _thumb_stat("fallbackOriginal")
        return None

    # 单飞：并发的同一张图只让一个线程真正跑 PIL，其余等结果
    with _inflight_lock(cache_key):
        data = thumb_cache_get(cache_key)          # 等锁期间别人可能已经生成好了
        if data is not None:
            _thumb_stat("memoryHit")
            return data
        if os.path.isfile(disk_path):
            try:
                with open(disk_path, "rb") as fp:
                    data = fp.read()
                thumb_cache_put(cache_key, data)
                _thumb_stat("diskHit")
                return data
            except Exception:
                pass
        try:
            with Image.open(img_path) as im:
                im.thumbnail((320, 320), Image.Resampling.LANCZOS)
                buf = BytesIO()
                im.convert("RGB").save(buf, format="JPEG", quality=82)
                data = buf.getvalue()
        except Exception as e:
            print(f"[Thumb] 生成失败 {img_path}: {e}")
            _thumb_stat("fallbackOriginal")
            return None

        thumb_cache_put(cache_key, data)
        try:
            with open(disk_path, "wb") as fp:
                fp.write(data)
            evict_disk_cache_if_needed()
        except Exception:
            pass
        _thumb_stat("generated")
        return data


def thumbnail_health() -> Dict[str, Any]:
    """缩略图链路健康快照（/api/online/status 暴露，供运维与手机端自检）。"""
    try:
        disk_files = len(os.listdir(DISK_THUMB_DIR))
    except Exception:
        disk_files = -1
    with THUMB_STATS_LOCK:
        stats = dict(THUMB_STATS)
    with THUMB_CACHE_LOCK:
        mem_entries = len(THUMB_CACHE)
    return {
        "ok": bool(HAS_PIL),
        "pil": bool(HAS_PIL),
        "pilVersion": getattr(Image, "__version__", "") if HAS_PIL else "",
        "thumbSize": 320,
        "memoryCacheEntries": mem_entries,
        "memoryCacheLimit": MAX_CACHE_ENTRIES,
        "diskCacheDir": DISK_THUMB_DIR,
        "diskCacheFiles": disk_files,
        "diskCacheLimit": MAX_DISK_ENTRIES,
        "stats": stats,
        "hint": "" if HAS_PIL else "Pillow 未安装：thumb=1 会降级返回原图，请在该服务的解释器里 pip install Pillow",
    }


def slot_guard_health() -> Dict[str, Any]:
    """平台槽位守卫健康快照（/api/online/status 暴露）。

    slotsDropped>0 说明有作品文案是「骨架未填充」，必须回流产线重生成——
    这是一条**响铃**，不是正常状态，不要当成噪音调低日志等级。
    """
    with _SLOT_GUARD_LOCK:
        stats = dict(_SLOT_GUARD_STATS)
    return {
        "minPlatformSubstance": MIN_PLATFORM_SUBSTANCE,
        "minWholeTextSubstance": MIN_COPY_DISTRIBUTABLE,
        "scanWorksAffected": stats.get("worksAffected", 0),
        "scanSlotsDropped": stats.get("slotsDropped", 0),
        "hint": ("本轮扫描剔除了占位/过薄槽位：文案骨架未填充，请回流产线重生成"
                 if stats.get("slotsDropped", 0) else ""),
    }


# -- DSH-113：局域网更新中转（手机拿不到 GitHub，让电脑代取）--------------------
# 踩到的坑（2026-09-25 实测）：手机端 UpdateChecker 会先问电脑要 /latest.json，
# 拿不到才回落 raw.githubusercontent.com。而本机实测：
#   直连 raw.githubusercontent.com -> **10 秒超时**（http_code=000）
#   PC 必须走 7897 代理才能通（0.46s / 200）
# 手机没有代理，所以永远拿不到清单 => 一直停在旧版本。
# 表现就是「我这边发版了，手机上还是旧的 / 苹果端按钮还是没有」——
# 不是没发布，是**发布根本没送到手机上**。
# 修法：电脑有代理，让电脑把 GitHub 的发布清单和安装包代取回来，从局域网发给手机。
UPDATE_MANIFEST_URL = "https://raw.githubusercontent.com/zwmopen/gallery-updates/main/latest.json"
# 出网代理：本机 7897 常驻。留空即直连（大概率失败，但绝不拖垮相册主流程）
UPDATE_UPSTREAM_PROXY = os.environ.get("DSH_UPDATE_PROXY", "http://127.0.0.1:7897")
UPDATE_MANIFEST_TTL = 300.0
UPDATE_CACHE_DIR = os.path.join(tempfile.gettempdir(), "dsh-update-relay")

_UPDATE_LOCK = threading.Lock()
_UPDATE_MANIFEST_CACHE: Dict[str, Any] = {"at": 0.0, "data": None}
# 改写前的**原始**下载地址（改写后 apk_url 变成 /download/apk，不能再拿去出网）
_UPDATE_ORIGIN_URLS: Dict[str, str] = {}
_UPDATE_RELAY_STATS: Dict[str, Any] = {
    "manifestFetches": 0, "manifestFailures": 0,
    "downloads": 0, "downloadFailures": 0,
    "lastError": "", "lastManifestAt": 0.0,
}


def _update_opener():
    """出网 opener：有代理挂代理，没有就直连（直连通常失败，但不会抛）。"""
    if UPDATE_UPSTREAM_PROXY:
        return urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": UPDATE_UPSTREAM_PROXY,
                                         "https": UPDATE_UPSTREAM_PROXY}))
    return urllib.request.build_opener()


def _rewrite_manifest_for_lan(data: Dict[str, Any]) -> Dict[str, Any]:
    """把 GitHub 清单里的下载地址改写成局域网地址。

    Android 端只读 version_name / apk_url / sha256 / source 四个字段，
    所以这里**只动 URL，不动版本号和校验值**：sha256 仍是 GitHub 原包的，
    而中转是逐字节转发，校验自然一致。
    """
    out = dict(data)
    if out.get("apk_url"):
        out["apk_url"] = "/download/apk"
    if out.get("url"):
        out["url"] = "/download/apk"
    ios = out.get("ios")
    if isinstance(ios, dict) and ios.get("ipa_url"):
        ios = dict(ios)
        ios["ipa_url"] = "/download/ipa"
        out["ios"] = ios
    out["source"] = "lan-relay"
    out["relayedBy"] = get_local_ip()
    return out


def fetch_update_manifest(force: bool = False) -> Dict[str, Any]:
    """取发布清单（TTL 缓存）。失败返回空 dict —— 中转挂了不能拖垮相册服务。"""
    with _UPDATE_LOCK:
        now = time.time()
        cached = _UPDATE_MANIFEST_CACHE.get("data")
        if (not force) and cached and (
                now - float(_UPDATE_MANIFEST_CACHE.get("at", 0.0)) < UPDATE_MANIFEST_TTL):
            return cached
    try:
        req = urllib.request.Request(
            UPDATE_MANIFEST_URL,
            headers={"User-Agent": "dsh-online-gallery", "Accept": "application/json"})
        with _update_opener().open(req, timeout=12) as resp:
            raw = resp.read()
        data = json.loads(raw.decode("utf-8"))
        if not isinstance(data, dict) or not data.get("apk_url"):
            raise ValueError("发布清单里没有 apk_url")
        with _UPDATE_LOCK:
            # 先把原始地址存下来（改写后就找不回来了）
            _UPDATE_ORIGIN_URLS["apk"] = data.get("apk_url", "")
            _UPDATE_ORIGIN_URLS["ipa"] = (data.get("ios") or {}).get("ipa_url", "")
        data = _rewrite_manifest_for_lan(data)
        with _UPDATE_LOCK:
            _UPDATE_MANIFEST_CACHE["at"] = time.time()
            _UPDATE_MANIFEST_CACHE["data"] = data
            _UPDATE_RELAY_STATS["manifestFetches"] = int(
                _UPDATE_RELAY_STATS.get("manifestFetches", 0)) + 1
            _UPDATE_RELAY_STATS["lastManifestAt"] = time.time()
            _UPDATE_RELAY_STATS["lastError"] = ""
        return data
    except Exception as e:
        with _UPDATE_LOCK:
            _UPDATE_RELAY_STATS["manifestFailures"] = int(
                _UPDATE_RELAY_STATS.get("manifestFailures", 0)) + 1
            _UPDATE_RELAY_STATS["lastError"] = str(e)
        print("[UpdateRelay] 取发布清单失败：%s" % e)
        return {}


def _download_upstream(kind: str) -> Tuple[Optional[bytes], str]:
    """代取安装包（按版本落磁盘缓存，两台手机只出网一次）。"""
    manifest = fetch_update_manifest()
    if not manifest:
        return None, "拿不到发布清单（检查 7897 代理）"
    with _UPDATE_LOCK:
        url = _UPDATE_ORIGIN_URLS.get(kind, "")
    if not url:
        return None, "发布清单里没有 %s 下载地址" % ("APK" if kind == "apk" else "IPA")
    version = (manifest.get("version_name") or manifest.get("tag_name") or "0").lstrip("vV")
    if kind == "ipa":
        version = (manifest.get("ios") or {}).get("version_name", version)
    try:
        os.makedirs(UPDATE_CACHE_DIR, exist_ok=True)
    except Exception:
        pass
    cache_file = os.path.join(UPDATE_CACHE_DIR, "album-%s-%s.%s"
                              % ("Android" if kind == "apk" else "iOS", version, kind))
    try:
        if os.path.isfile(cache_file) and os.path.getsize(cache_file) > 64 * 1024:
            with open(cache_file, "rb") as fh:
                payload = fh.read()
            with _UPDATE_LOCK:
                _UPDATE_RELAY_STATS["downloads"] = int(
                    _UPDATE_RELAY_STATS.get("downloads", 0)) + 1
            return payload, ""
    except Exception:
        pass
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "dsh-online-gallery"})
        with _update_opener().open(req, timeout=180) as resp:
            payload = resp.read()
        if not payload or len(payload) < 64 * 1024:
            raise ValueError("下载到的安装包太小（%d 字节），疑似失败" % len(payload))
        # 判据用 sha256，**不用体积**：实测这个 APK 只有 0.86 MB，
        # 早先用「必须 >1MB」当闸门把它整包判废了（自创判据的代价）。
        # 清单里本来就带 sha256，手机端也会校验同一个值 —— 与客户端口径完全一致。
        expected = ""
        if kind == "apk":
            expected = manifest.get("sha256") or ""
        else:
            expected = (manifest.get("ios") or {}).get("sha256") or ""
        if expected:
            actual = hashlib.sha256(payload).hexdigest()
            if actual.lower() != expected.lower():
                raise ValueError("安装包校验不一致：期望 %s... 实际 %s..."
                                 % (expected[:12], actual[:12]))
        tmp = cache_file + ".part"
        with open(tmp, "wb") as fh:
            fh.write(payload)
        os.replace(tmp, cache_file)
        with _UPDATE_LOCK:
            _UPDATE_RELAY_STATS["downloads"] = int(
                _UPDATE_RELAY_STATS.get("downloads", 0)) + 1
        return payload, ""
    except Exception as e:
        with _UPDATE_LOCK:
            _UPDATE_RELAY_STATS["downloadFailures"] = int(
                _UPDATE_RELAY_STATS.get("downloadFailures", 0)) + 1
            _UPDATE_RELAY_STATS["lastError"] = str(e)
        print("[UpdateRelay] 代取 %s 失败：%s" % (kind, e))
        return None, str(e)


def update_relay_health() -> Dict[str, Any]:
    """中转健康快照（/api/online/status 暴露，一条命令看出手机能不能更新）。"""
    with _UPDATE_LOCK:
        stats = dict(_UPDATE_RELAY_STATS)
        manifest = _UPDATE_MANIFEST_CACHE.get("data") or {}
    ios = manifest.get("ios") or {}
    return {
        "proxy": UPDATE_UPSTREAM_PROXY or "",
        "manifestTtlSec": UPDATE_MANIFEST_TTL,
        "cachedVersion": manifest.get("version_name", ""),
        "cachedVersionCode": manifest.get("version_code", manifest.get("versionCode", 0)),
        "iosCachedVersion": ios.get("version_name", ""),
        "manifestFetches": stats.get("manifestFetches", 0),
        "manifestFailures": stats.get("manifestFailures", 0),
        "downloads": stats.get("downloads", 0),
        "downloadFailures": stats.get("downloadFailures", 0),
        "lastError": stats.get("lastError", ""),
        "hint": ("" if stats.get("manifestFetches")
                 else "还没成功取过清单：检查 7897 代理是否活着"),
    }


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
        # 「文件名 -> 绝对路径」索引：兼容 iOS 旧契约 /api/online/image?path=<文件名>
        self._image_name_index: Optional[Dict[str, str]] = None
        # DSH-110：文件系统监听（轮询版，无第三方依赖）
        # - _watchdog_paths：上次轮询记录到的 (路径, mtime) 集合
        # - _watchdog_thread：daemon 线程，每 60 秒跑一次 _poll_diff()
        # - _watchdog_active：线程是否在跑（暴露给 /api/online/status）
        # - _watchdog_last_poll / _watchdog_last_change：状态字段
        self._watchdog_paths: Set[Tuple[str, float]] = set()
        self._watchdog_thread: Optional[threading.Thread] = None
        self._watchdog_active = False
        self._watchdog_last_poll: float = 0.0
        self._watchdog_last_change: float = 0.0
        self._watchdog_interval = 60.0
        self._watchdog_stop = threading.Event()

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

    def invalidate_stage_cache(self) -> None:
        """作废回收站两个 Tab 的 5 秒缓存。

        任何会改变阶段库内容的入口（重置 / 删除 / 使用）改完磁盘后都应调用，
        否则手机端紧接着的那次列表请求会拿到陈旧值。
        """
        with self._lock:
            self._stage_cache.clear()

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

    def resolve_image_path(self, raw: str) -> Optional[str]:
        """把客户端传来的图片定位信息解析成本机绝对路径。

        支持三种形态（按 iOS 旧契约兼容，2026-09-20 补）：
        1. 绝对路径（必须在成品库根目录内，防目录穿越）；
        2. 相对成品库根目录的路径，如「_已发送1次（微信公众号可发）/xxx/P1.png」；
        3. 裸文件名，如「P1_封面.png」——回退到作品库文件名索引里查。

        返回 None 表示无法定位或不安全。
        """
        raw = (raw or "").strip().replace("\\", "/")
        if not raw:
            return None
        root = os.path.realpath(self.root)

        def _inside(p: str) -> bool:
            rp = os.path.realpath(p)
            return rp == root or rp.startswith(root + os.sep)

        cand = raw if os.path.isabs(raw) else os.path.join(root, raw)
        if _inside(cand) and os.path.isfile(cand):
            return os.path.realpath(cand)

        # 作品库图片标识索引兜底：
        # ① 完整标识（裸文件名 / 作品内相对路径 / 成品库根相对路径）直接命中；
        # ② 再退化到 basename，兼容「作品被移库后旧路径失效」与只发裸文件名的旧客户端。
        hit = self.image_name_index().get(raw)
        if hit and os.path.isfile(hit):
            return hit
        if "/" in raw:
            hit = self.image_name_index().get(raw.rsplit("/", 1)[-1])
            if hit and os.path.isfile(hit):
                return hit
        return None

    def image_name_index(self, rebuild: bool = False) -> Dict[str, str]:
        """构建「文件名 -> 绝对路径」索引（只读快照，供路径兜底解析用）。

        数据源是已扫描到的作品表（_works_by_id + _moved_works），因此调用前必须先拉过
        作品列表——这正好是手机端取图的真实时序。同名文件只保留首个命中。
        """
        with self._lock:
            if self._image_name_index is None or rebuild:
                idx: Dict[str, str] = {}
                for w in list(self._works_by_id.values()) + list(self._moved_works.values()):
                    base = w.get("path") or ""
                    if not base:
                        continue
                    for fn in (w.get("images") or []):
                        fp = os.path.join(base, fn)
                        if not os.path.isfile(fp):
                            # images 值也可能是「成品库根相对路径」（图在 产出素材/ 的作品）
                            alt = os.path.join(self.root, fn)
                            if os.path.isfile(alt):
                                fp = alt
                        if not os.path.isfile(fp):
                            continue
                        if fn not in idx:
                            idx[fn] = fp
                        bn = fn.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
                        if bn and bn not in idx:
                            idx[bn] = fp
                self._image_name_index = idx
            return dict(self._image_name_index)

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
            if force:
                # 【2026-09-21 修复】force 的语义是「磁盘已变，缓存一律作废」。
                # 此前只清 _cached_works / _last_scan_time，漏了 _stage_cache
                # （回收站两个 Tab 的 5 秒缓存）⇒ 手机端点「重置」后立刻重拉列表，
                # 仍拿到 useCount=1 的旧值，表现为「重置失败」（5 秒后又自己好）。
                self._stage_cache.clear()
            if not force and self._cached_works and (now - self._last_scan_time < 5.0):
                return self._cached_works

            results = []
            seen_ids = set()
            _slot_guard_reset()   # 槽位守卫计数按「本轮扫描」统计，避免累积值误导

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

    # ==================== DSH-110：文件系统轮询监听 ====================
    # 用 stdlib os.scandir + mtime 对比实现，无第三方依赖。
    # 每 60 秒扫描一遍 self.root 下的 (path, mtime)，发现新增/删除/变更 → 触发 scan(force=True)
    # 这样 GPT API 实时产出的作品图只要落到 DEFAULT_LIBRARY_ROOT 下，
    # 最迟 60 秒内会被服务端扫到，手机端下次拉列表（refresh=1 或自动重扫）即看到最新。

    def _walk_root_paths(self) -> Set[Tuple[str, float]]:
        """遍历 self.root 下所有 (path, mtime)，跳过 ./_ 开头目录。

        与 scan() 的扫描规则一致：只下钻「已发送0次」内的作品集/团建游戏/游戏/游戏类
        中间层（这些是用户最常手动拖新作品的入口），成品库根目录直出另算。
        """
        out: Set[Tuple[str, float]] = set()
        if not os.path.isdir(self.root):
            return out

        def _safe_mtime(p: str) -> float:
            try:
                return os.path.getmtime(p)
            except (OSError, PermissionError):
                return 0.0

        # 1. 成品库根目录直出的子文件夹
        try:
            for entry in os.listdir(self.root):
                if entry.startswith(".") or entry.startswith("_"):
                    continue
                full = os.path.join(self.root, entry)
                if os.path.isdir(full):
                    out.add((full, _safe_mtime(full)))
        except (OSError, PermissionError):
            pass

        # 2. 「已发送0次」+ 子作品集
        stage0 = os.path.join(self.root, "已发送0次（抖音小红书可发）")
        if os.path.isdir(stage0):
            try:
                for entry in os.listdir(stage0):
                    if entry.startswith(".") or entry.startswith("_"):
                        continue
                    full = os.path.join(stage0, entry)
                    if not os.path.isdir(full):
                        continue
                    out.add((full, _safe_mtime(full)))
                    # 中间层下钻一层
                    if entry.startswith("作品集") or entry in ("团建游戏", "游戏", "游戏类"):
                        try:
                            for sub in os.listdir(full):
                                if sub.startswith(".") or sub.startswith("_"):
                                    continue
                                sub_full = os.path.join(full, sub)
                                if os.path.isdir(sub_full):
                                    out.add((sub_full, _safe_mtime(sub_full)))
                        except (OSError, PermissionError):
                            pass
            except (OSError, PermissionError):
                pass

        # 3. 「_已发送1次」回收站（DSH-095 统一入口）
        stage1 = os.path.join(self.root, "_已发送1次（微信公众号可发）")
        if os.path.isdir(stage1):
            try:
                for entry in os.listdir(stage1):
                    if entry.startswith(".") or entry.startswith("_"):
                        continue
                    full = os.path.join(stage1, entry)
                    if os.path.isdir(full):
                        out.add((full, _safe_mtime(full)))
            except (OSError, PermissionError):
                pass

        return out

    def _poll_diff(self) -> bool:
        """单次轮询：对比 _watchdog_paths 与当前 _walk_root_paths()，有差异 → force scan。

        返回 True 表示本轮触发了重扫。
        """
        now = time.time()
        current = self._walk_root_paths()
        prev = self._watchdog_paths
        # 首次轮询：只记录不触发（避免启动时全量 noise）
        if not prev:
            self._watchdog_paths = current
            self._watchdog_last_poll = now
            return False

        prev_by_path = {p: m for p, m in prev}
        curr_paths = {p for p, _ in current}
        prev_paths = {p for p, _ in prev}
        new_paths = curr_paths - prev_paths
        deleted_paths = prev_paths - curr_paths
        mtime_changed = {
            path
            for path, mtime in current
            if path in prev_by_path and prev_by_path[path] != mtime
        }

        changed = bool(new_paths or deleted_paths or mtime_changed)
        self._watchdog_paths = current
        self._watchdog_last_poll = now

        if changed:
            self._watchdog_last_change = now
            try:
                self.scan(force=True)
                print(f"[DSH-110 watchdog] 检测到变更 → force scan: +{len(new_paths)} / -{len(deleted_paths)} / ~{len(mtime_changed)}")
            except Exception as e:
                print(f"[DSH-110 watchdog] scan 失败：{e!r}")
            return True
        return False

    def _start_watchdog_loop(self) -> None:
        """启动后台轮询线程（daemon 模式，服务退出时自动结束）。"""
        if self._watchdog_thread is not None and self._watchdog_thread.is_alive():
            return
        self._watchdog_stop.clear()

        def _run():
            self._watchdog_active = True
            # 启动后等 10 秒再做第一次轮询（让首次 force scan 先完成，避免重复触发）
            time.sleep(10.0)
            while not self._watchdog_stop.is_set():
                try:
                    self._poll_diff()
                except Exception as e:
                    print(f"[DSH-110 watchdog] 轮询异常：{e!r}")
                # wait_for 比 sleep 更快响应 stop
                if self._watchdog_stop.wait(self._watchdog_interval):
                    break
            self._watchdog_active = False

        self._watchdog_thread = threading.Thread(target=_run, name="DSH110-watchdog", daemon=True)
        self._watchdog_thread.start()
        print(f"[DSH-110 watchdog] 启动完成，每 {self._watchdog_interval:.0f}s 轮询一次")

    def stop_watchdog(self) -> None:
        self._watchdog_stop.set()
        if self._watchdog_thread is not None:
            self._watchdog_thread.join(timeout=3.0)

    def watchdog_status(self) -> Dict[str, Any]:
        """暴露给 /api/online/status：监控线程状态 + 上次轮询/变更时间。"""
        return {
            "active": self._watchdog_active,
            "intervalSec": self._watchdog_interval,
            "lastPollAt": self._watchdog_last_poll,
            "lastChangeAt": self._watchdog_last_change,
            "trackedPaths": len(self._watchdog_paths),
            "rootDir": self.root,
        }

    def _collect_images(self, dir_path: str, files: List[str]) -> List[str]:
        """收集作品成品图的客户端标识列表。

        两级策略（2026-09-21 修「图在 产出素材/ 子目录 ⇒ 手机看不到」）：
        ① 作品根目录有图 → 返回**成品库根相对路径**（2026-09-24 起与 ② 同口径，全局唯一）；
        ② 根目录无图 → 回退到产线标准素材子目录（IMAGE_SUBDIR_FALLBACKS），
           返回**成品库根相对路径**，保证全局唯一：
             - iOS 走 `?path=`，resolve_image_path() 原生支持「相对根路径」形态；
             - Android 走 `?id=..&file=`，/api/online/image 拼接失败后回退同一解析器。
           若只返回作品内相对路径（`产出素材/P1.png`），103 套作品会共用同一个
           图片标识 ⇒ iOS 端磁盘/内存缓存键与文件名索引双重串图，故必须用根相对路径。
        """
        root_real = os.path.realpath(self.root)
        top = [f for f in files if os.path.splitext(f.lower())[1] in IMAGE_EXTENSIONS]
        if top:
            # 【2026-09-24 修「iPhone 全库串图」】布局 A 同样禁止返回裸文件名：
            # 393 套作品共用 P1_封面.png / P1.png 这类同名标识，iOS 走
            # /api/online/image?path=<裸文件名> 会命中 image_name_index 的「同名首命中」，
            # 叠加客户端缓存键=路径字符串 ⇒ 全库作品在 iPhone 上显示同一套图
            # （实测复现：path=P1_封面.png 返回的是「评371-浙江省旅游全攻略」的封面，
            #  而非请求作品自己的封面）。与 ② 同口径：统一返回成品库根相对路径，全局唯一。
            return [
                os.path.relpath(os.path.join(dir_path, f), root_real).replace(os.sep, "/")
                for f in top
            ]
        for sub in IMAGE_SUBDIR_FALLBACKS:
            sub_dir = os.path.join(dir_path, sub)
            if not os.path.isdir(sub_dir):
                continue
            try:
                sub_files = os.listdir(sub_dir)
            except Exception:
                continue
            hits = []
            for f in sub_files:
                if os.path.splitext(f.lower())[1] not in IMAGE_EXTENSIONS:
                    continue
                rel = os.path.relpath(os.path.join(sub_dir, f), root_real)
                hits.append(rel.replace(os.sep, "/"))
            if hits:
                return hits
        return []

    def _inspect_work_dir(self, dir_path: str, folder_name: str, stage_name: str, default_count: int) -> Optional[Dict[str, Any]]:
        try:
            files = os.listdir(dir_path)
        except Exception:
            return None

        images = self._collect_images(dir_path, files)
        if not images:
            return None

        images.sort()

        # 读取下发文案：【唯一真源 = 文案.txt】（2026-09-22 用户口径）
        # 「软件只识别 文案.txt」—— `三平台文案.txt` 是早期 Codex 产线遗留，不是它的改名版；
        # 实测库内 145 套两份都有、其中 32 套内容并不相同，按旧 priority 优先读它
        # ⇒ 手机端会显示一份未经确认的历史副本。故下发只认 文案.txt；
        # 其余历史 txt（三平台文案 / 小红书文案 / 全量生成记录…）仅供服务端关键词检索，不参与下发。
        copy_text = ""
        copy_search_blob = ""   # 搜索专用：即使判定为缺失也保留原文，避免空壳/薄文案失去关键词可检索性
        txt_candidates = [f for f in files if f.lower().endswith(".txt")]
        AUTHORITATIVE_COPY = "文案.txt"
        txt_candidates.sort(key=lambda x: (x != AUTHORITATIVE_COPY, x))

        for f in txt_candidates:
            try:
                with open(os.path.join(dir_path, f), "r", encoding="utf-8", errors="ignore") as fp:
                    raw_c = fp.read().strip()
                if not copy_search_blob:
                    copy_search_blob = copy_search_text(raw_c)
                if f != AUTHORITATIVE_COPY:
                    continue
                if copy_is_real(raw_c):
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
                if not copy_text and copy_is_real(manifest_data.get("copy_content", "")):
                    copy_text = manifest_data.get("copy_content", "").strip()
                if not copy_text and manifest_data.get("rawMaterialPath"):
                    raw_p = manifest_data["rawMaterialPath"]
                    if os.path.exists(raw_p):
                        for rf in os.listdir(raw_p):
                            if rf.lower().endswith(".txt"):
                                try:
                                    with open(os.path.join(raw_p, rf), "r", encoding="utf-8", errors="ignore") as rfp:
                                        rc = rfp.read().strip()
                                    if not copy_search_blob:
                                        copy_search_blob = copy_search_text(rc)
                                    if copy_is_real(rc):
                                        copy_text = rc
                                        break
                                except Exception:
                                    pass
            except Exception:
                pass

        # 【文案缺失显式化】不再伪造通用文案：全无文案时保持 copy_text 为空，
        # 并以 copyMissing=true 通知手机端置灰文案按钮，杜绝空壳作品冒充有文案混进分发。
        copy_missing = not copy_text

        # 【平台槽位守卫】下发前剔除占位骨架/过薄槽位。
        # 只作用于下发载荷，不动磁盘原始文件；搜索仍用未净化的 copy_search_blob，
        # 保证「被剔除」的作品照样能被关键词搜到（可检索性不因守卫而丢失）。
        copy_raw = copy_text
        copy_text, slot_diag = sanitize_platform_copy(copy_raw)
        if slot_diag["droppedCount"]:
            _slot_guard_stat("worksAffected")
            _slot_guard_stat("slotsDropped", slot_diag["droppedCount"])
            print(f"[SlotGuard] 剔除 {slot_diag['droppedCount']} 个占位/过薄槽位："
                  f"{[d['marker'] for d in slot_diag['dropped']]} <- {folder_name[:52]}")
        # 净化后若已无真实槽位，视为「骨架作品」：按缺失下发，手机端自动置灰标红
        if copy_raw and not copy_text.strip():
            copy_missing = True
        elif copy_raw and not copy_is_real(copy_text):
            copy_missing = True

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
            # 【未填模板守卫】判定口径 = 剔除占位脚手架后的正文，未填模板不再算"有文案"
            "hasCopyText": copy_is_real(copy_text),
            "copyMissing": bool(copy_missing),
            # 【平台槽位守卫】诊断：哪些槽位被判为占位/过薄并被剔除
            "slotGuard": {
                "droppedCount": slot_diag["droppedCount"],
                "droppedMarkers": [d["marker"] for d in slot_diag["dropped"]],
                "keptCount": slot_diag["keptCount"],
                "rawSubstance": copy_substance_len(copy_raw),
                "servedSubstance": copy_substance_len(copy_text),
                "effectiveSubstance": copy_effective_len(copy_text),
                "placeholderOnly": is_placeholder_copy(copy_raw),
            },
            "searchBlob": copy_search_blob,
            "updatedAt": os.path.getmtime(dir_path),
            # DSH-109：作品目录总字节数（含子目录，用于 size_desc/size_asc 排序）
            "sizeBytes": _dir_size_bytes(dir_path),
        }


# DSH-109：在线相册列表排序键（服务端 /api/online/works?sort=）
# - default（向后兼容）：保持 DSH-104 行为，useCount desc 已用置顶，其余保持扫描顺序
# - time_asc：updatedAt 升序，最新作品排在最底（用户口径）
# - time_desc：updatedAt 降序，最新作品排在最顶
# - name_asc / name_desc：按 title 字典序升降
# - size_desc / size_asc：按作品目录总字节数升降
SORT_KEYS = ("default", "time_asc", "time_desc", "name_asc", "name_desc", "size_desc", "size_asc")


class ReverseStr:
    """让字符串按字典序反序参与比较的轻量包装类（DSH-109 用于 name_desc）。"""

    __slots__ = ("value",)

    def __init__(self, value: str):
        self.value = value

    def __lt__(self, other: "ReverseStr") -> bool:
        return self.value > other.value

    def __eq__(self, other: object) -> bool:
        return isinstance(other, ReverseStr) and self.value == other.value


def _dir_size_bytes(root_path: str) -> int:
    """累加 root_path 下的总字节数（含子目录、单文件）。

    DSH-109：size_desc/size_asc 排序用。一个坏文件不能挂掉整次扫描，
    用 os.scandir + 容错，单文件 stat 失败直接跳过。
    """
    total = 0
    try:
        for entry in os.scandir(root_path):
            try:
                if entry.is_file(follow_symlinks=False):
                    total += entry.stat(follow_symlinks=False).st_size
                elif entry.is_dir(follow_symlinks=False):
                    total += _dir_size_bytes(entry.path)
            except (OSError, PermissionError):
                continue
    except (OSError, PermissionError):
        pass
    return total


class OnlineGalleryHandler(BaseHTTPRequestHandler):
    scanner: WorkScanner = None
    # 手机在线发现缓存（30s TTL）：面板与「顺手回读」共用，避免每次请求都全量扫网段
    phone_cache = phone_sync.DiscoverCache()
    # 「顺手回读」每台手机的冷却时间，防止手机每次轮询都触发一次同步
    _phone_sync_lock = threading.Lock()
    _phone_sync_last: Dict[str, float] = {}
    PHONE_SYNC_COOLDOWN = 300.0

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
        # 【传输层瘦身】2026-09-20：/api/online/works 全量裸发实测 **2751.8 KB**，
        # 手机端「正在连接电脑在线相册…」的绝大部分时间其实是在等这 2.75 MB 过 Wi-Fi。
        # 两步治理（实测）：剥离 searchBlob/slotGuard → 1568.5 KB；再按协商开 gzip
        # → **419.9 KB（原始体积的 15.3%，减少 85%）**。
        # Android 的 HttpURLConnection(OkHttp) 在未显式设置 Accept-Encoding 时
        # 会自动协商 gzip 并透明解压，所以这里只需按请求头决定是否压缩即可，客户端零改动。
        gz = None
        if len(body) >= GZIP_MIN_BYTES and "gzip" in (self.headers.get("Accept-Encoding") or "").lower():
            try:
                gz = gzip_bytes(body, 6)
            except Exception:
                gz = None
        payload = gz if gz is not None else body
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            if gz is not None:
                self.send_header("Content-Encoding", "gzip")
            # 无论压缩与否都要声明 Vary，否则中间层/代理可能缓存错版本
            self.send_header("Vary", "Accept-Encoding")
            self.send_header("Content-Length", str(len(payload)))
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(payload)
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
            pass

    # ── 手机次数回读同步 / 在线探测（见 phone_sync.py）─────────────────
    def _computer_works_index(self, force: bool = False) -> Dict[str, Dict[str, Any]]:
        """电脑端全部作品的 id -> work 索引（三个阶段库都收进来）。

        在线相册只暴露 stage0，但回读同步要能覆盖「已发送1次 / 垃圾样本库」，
        否则手机端对已发送作品追加的分享次数会被判成「找不到对应作品」而丢失。
        """
        index: Dict[str, Dict[str, Any]] = {}
        s = self.scanner
        try:
            for w in s.scan(force=force):
                if w.get("id"):
                    index.setdefault(w["id"], w)
        except Exception:
            pass
        for folder, stage, base in (
            (s.STAGE1_FOLDER, "已发送1次", 1),
            (s.GARBAGE_FOLDER, "已废弃-垃圾", 0),
        ):
            try:
                for w in s.list_stage_works(folder, stage, base, force=force):
                    if w.get("id"):
                        index.setdefault(w["id"], w)
            except Exception:
                pass
        return index

    def _run_phone_sync(self, hosts: List[str], dry_run: bool = False) -> Dict[str, Any]:
        """对给定手机列表执行「次数回读同步」（只补次数，绝不搬文件）。"""
        index = self._computer_works_index()
        log_dir = os.path.join(self.scanner.root, "_portfolio_move_logs")
        results: List[Dict[str, Any]] = []
        total_applied = 0

        for host in hosts:
            ip, port = phone_sync.normalize_host(host)
            label = f"{ip}:{port}"
            ok, works, err = phone_sync.fetch_phone_works(ip, port)
            if not ok:
                results.append({
                    "phone": label, "ok": False,
                    "error": err or "手机相册服务不可达（确认同网段且相册 App 在前台）",
                    "appliedCount": 0,
                })
                continue
            rep = phone_sync.sync_phone_counts(
                index, works, phone_label=label, dry_run=dry_run, log_dir=log_dir)
            rep["ok"] = True
            results.append(rep)
            total_applied += rep.get("appliedCount", 0)

        if total_applied and not dry_run:
            self.scanner.scan(force=True)

        return {
            "ok": True,
            "dryRun": bool(dry_run),
            "phoneCount": len(hosts),
            "appliedCount": total_applied,
            "results": results,
        }

    def _maybe_background_phone_sync(self, client_ip: str):
        """手机一联上 45835，就顺手回读它的本地分享次数（后台线程 + 冷却）。

        这是「上帝视角」的关键一环：不需要手机端新增任何按钮，也不需要用户记得
        手动点同步 —— 只要手机来读在线相册，电脑就自动把两端次数对齐。
        """
        ip = (client_ip or "").strip()
        if not ip or ip.startswith("127.") or ip.startswith("169.254."):
            return
        now = time.time()
        with OnlineGalleryHandler._phone_sync_lock:
            if now - OnlineGalleryHandler._phone_sync_last.get(ip, 0.0) < self.PHONE_SYNC_COOLDOWN:
                return
            OnlineGalleryHandler._phone_sync_last[ip] = now

        scanner = self.scanner

        def _worker():
            try:
                ok, works, _err = phone_sync.fetch_phone_works(ip)
                if not ok:
                    return
                index = self._computer_works_index()
                rep = phone_sync.sync_phone_counts(
                    index, works,
                    phone_label=f"{ip}:{phone_sync.PHONE_ALBUM_PORT}",
                    log_dir=os.path.join(scanner.root, "_portfolio_move_logs"),
                )
                if rep.get("appliedCount"):
                    scanner.scan(force=True)
                    print(f"[PhoneSync] {ip} 自动回读补记 {rep['appliedCount']} 个作品的使用次数")
            except Exception as e:
                print(f"[PhoneSync] {ip} 自动回读失败：{e}")

        threading.Thread(target=_worker, name=f"phone-sync-{ip}", daemon=True).start()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        if path == "/" or path == "/api/online/status":
            works = self.scanner.scan()
            ip = get_local_ip()
            # 手机每次联上来都顺手回读一次它的本地分享次数（后台线程，不拖慢响应）
            self._maybe_background_phone_sync(self.client_address[0] if self.client_address else "")
            data = {
                "ok": True,
                "server": "DeviceShareHub-OnlineGallery",
                "version": "1.0.0",
                "ip": ip,
                "port": self.server.server_port,
                "totalWorks": len(works),
                "libraryRoot": self.scanner.root,
                "timestamp": int(time.time()),
                # 能力清单：手机端可据此决定是否显示「同步次数 / 在线状态」等入口，
                # 老版本电脑端没有这个字段，手机端按「不存在即不支持」降级即可。
                "features": [
                    "onlineRecycle",      # 在线回收站双 Tab（已使用 / 已标记垃圾）
                    "garbageRemark",      # 垃圾样本备注
                    "workPath",           # 作品文件夹路径（复制路径按钮）
                    "phoneCountSync",     # 手机本地分享次数回读电脑
                    "phoneDiscovery",     # 电脑主动扫描在线手机
                    "lanUpdateRelay",     # 局域网更新中转：手机连不上 GitHub，电脑代取清单+安装包
                ],
                "phoneSyncApi": "/api/online/sync-phone-counts",
                "phonePanelApi": "/api/online/phones",
                # 缩略图链路自检：2026-09-20 曾因「进程跑的是旧代码 / Pillow 缺失」导致
                # thumb=1 静默回落成几 MB 原图，手机端在线回收站直接卡死。此字段让运维
                # 一条命令就能看出缩略图是否真的在生效，不再靠用户体感发现。
                "thumbnail": thumbnail_health(),
                # 代码新鲜度：一眼看出「正在跑的进程」有没有落后于磁盘上的脚本
                "code": code_freshness(),
                # 平台槽位守卫：剔除骨架/过薄槽位的计数。slotsDropped>0 是响铃，不是噪音。
                "slotGuard": slot_guard_health(),
                # DSH-110：文件系统轮询监听状态（手机端 statusText badge 用）
                "watchdog": self.scanner.watchdog_status(),
                # DSH-113：手机 OTA 中转健康度（手机拿不到 GitHub，只能靠电脑代取）
                "updateRelay": update_relay_health(),
                # 传输层瘦身：列表响应剥离的字段 + gzip 阈值（手机端「正在连接」快的根因在此）
                "wire": {
                    "gzipMinBytes": GZIP_MIN_BYTES,
                    "listOmitFields": list(_WIRE_OMIT_FIELDS),
                    "gzipCache": gzip_cache_health(),
                    "hint": "列表接口已剥离 searchBlob/slotGuard，并按 Accept-Encoding 自动 gzip",
                },
            }
            self.send_json(200, data)
            return

        # -- DSH-113：局域网更新中转路由（必须在 /api 兜底之前） --
        if path in ("/latest.json", "/altstore.json"):
            data = fetch_update_manifest()
            if not data:
                self.send_json(503, {"ok": False,
                                     "error": "电脑端代取发布清单失败（检查 7897 代理）"})
                return
            self.send_json(200, data)
            return

        if path.startswith("/download/"):
            kind = path.rsplit("/", 1)[-1].lower()
            if kind not in ("apk", "ipa"):
                self.send_error(404)
                return
            payload, err = _download_upstream(kind)
            if payload is None:
                self.send_json(502, {"ok": False, "error": err})
                return
            ctype = ("application/vnd.android.package-archive" if kind == "apk"
                     else "application/octet-stream")
            try:
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(payload)))
                self.send_cors_headers()
                self.end_headers()
                self.wfile.write(payload)
            except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
                pass
            return

        if path == "/api/online/phones":
            # 在线状态面板：主动扫本机所在 /24 网段探 45833，命中即算「此刻在线」。
            # 不依赖手机端广播，跨网段/手机切后台都能看见。
            force = query.get("refresh", ["0"])[0] == "1"
            try:
                devices = self.phone_cache.get(force=force)
            except Exception as e:
                devices = []
                print(f"[PhoneProbe] 扫描失败：{e}")

            for d in devices:
                d["lastSeen"] = d.get("lastSeen") or time.strftime("%Y-%m-%d %H:%M:%S")

            verified = [d for d in devices if d.get("verified")]
            self.send_json(200, {
                "ok": True,
                "ip": get_local_ip(),
                "port": self.server.server_port,
                "scannedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
                "subnets": phone_sync.candidate_subnets(),
                "onlineCount": len(verified),
                "totalResponded": len(devices),
                "devices": devices,
                "phonePort": phone_sync.PHONE_ALBUM_PORT,
                "ttlSeconds": phone_sync.DISCOVER_TTL,
            })
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
            mid_autumn_count = sum(1 for w in works if "中秋" in work_text_blob(w))
            national_day_count = sum(1 for w in works if ("国庆" in work_text_blob(w) or "十一" in work_text_blob(w)))
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
            # DSH-109：排序键 = default|time_asc|time_desc|name_asc|name_desc|size_desc|size_asc
            # 默认 default 保持 DSH-104 行为（useCount desc 已用置顶），向后兼容
            sort_key = query.get("sort", ["default"])[0]
            if sort_key not in SORT_KEYS:
                sort_key = "default"

            works = self.scanner.scan(force=force_refresh)
            tokens = search_query.split() if search_query else []

            # 手机打开在线相册（拉列表）也顺手回读一次本地分享次数
            self._maybe_background_phone_sync(self.client_address[0] if self.client_address else "")

            filtered = []
            for w in works:
                # 分类过滤（支持专题分类、游戏与地域分类）
                if category and category != "全部":
                    if category in ("🌕 中秋", "中秋"):
                        if "中秋" not in work_text_blob(w):
                            continue
                    elif category in ("🇨🇳 国庆", "国庆"):
                        blob = work_text_blob(w)
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
                    text_blob = work_text_blob(w, with_title=False).lower()
                    if not all(token in text_blob for token in tokens):
                        continue

                filtered.append(w)

            # DSH-104：在线相册分类下，已用过的作品立刻置顶。
            # 一级排序：useCount 降序（用得越多越靠前）。
            # 二级排序（DSH-109）：sort_key 决定同 useCount 内的相对顺序。
            #   - default（向后兼容）：保持扫描顺序（Python sort 稳定）
            #   - time_asc：updatedAt 升序（最新作品排在最底，用户口径）
            #   - time_desc：updatedAt 降序
            #   - name_asc/name_desc：按 title 字典序升降
            #   - size_desc/size_asc：按 sizeBytes 字节数升降
            # 「全部」分类下也生效（让用户一眼能看到最近分享过的）。
            if category not in ("待首发", "已发1次", "已发2次"):
                if sort_key == "default":
                    filtered.sort(key=lambda w: -int(w.get("useCount", 0) or 0))
                elif sort_key == "time_asc":
                    filtered.sort(key=lambda w: (
                        -int(w.get("useCount", 0) or 0),
                        float(w.get("updatedAt") or 0.0),
                    ))
                elif sort_key == "time_desc":
                    filtered.sort(key=lambda w: (
                        -int(w.get("useCount", 0) or 0),
                        -float(w.get("updatedAt") or 0.0),
                    ))
                elif sort_key == "size_asc":
                    filtered.sort(key=lambda w: (
                        -int(w.get("useCount", 0) or 0),
                        int(w.get("sizeBytes") or 0),
                    ))
                elif sort_key == "size_desc":
                    filtered.sort(key=lambda w: (
                        -int(w.get("useCount", 0) or 0),
                        -int(w.get("sizeBytes") or 0),
                    ))
                elif sort_key == "name_asc":
                    filtered.sort(key=lambda w: (
                        -int(w.get("useCount", 0) or 0),
                        (w.get("title") or "").lower(),
                    ))
                elif sort_key == "name_desc":
                    filtered.sort(key=lambda w: (
                        -int(w.get("useCount", 0) or 0),
                        ReverseStr((w.get("title") or "").lower()),
                    ))

            self.send_json(200, {
                "ok": True,
                "category": category,
                "total": len(filtered),
                # 剥离 searchBlob / slotGuard：两端客户端都不解析，47% 体积纯属白送
                "works": slim_works_for_wire(filtered)
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
                # 同 /api/online/works：剥离客户端不解析的重字段
                "works": slim_works_for_wire(works),
                "refreshed": force,
            })
            return

        if path == "/api/online/image":
            work_id = query.get("id", [""])[0]
            file_name = query.get("file", [""])[0]
            raw_path = query.get("path", [""])[0]
            thumb = query.get("thumb", ["0"])[0] == "1"

            img_path = ""
            if work_id and file_name:
                # DSH-102：work_id 必须用于定位作品目录，避免 image_name_index
                # 同名文件「同名」只保留首个命中带来的串图 BUG（用户报告：江浙沪秘境 Top9
                # 作品在 iOS 上图显示阳澄湖，根因就是服务端 P1_封面.png 同名冲突）。
                # 优先：作品目录 + basename(file_name) → basename 永远在作品目录里。
                # 兜底：resolve_image_path(file_name)（处理 DSH-101 use-work 移走 + 子目录路径）
                bn = file_name.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
                target_work = self.scanner.get_work(work_id)
                if target_work:
                    cand = os.path.join(target_work["path"], bn)
                    if os.path.isfile(cand):
                        img_path = cand
                    else:
                        # 文件名不在作品目录（极少见，子目录或路径变更） → 走 resolve_image_path
                        img_path = self.scanner.resolve_image_path(file_name) or ""
                else:
                    # DSH-101 场景：target_work 找不到（理论上不会，get_work 兜底所有分类） → 走 resolve_image_path
                    img_path = self.scanner.resolve_image_path(file_name) or ""
                if not img_path:
                    # 索引可能还没建全（未先拉列表），重建一次再试
                    self.scanner.image_name_index(rebuild=True)
                    img_path = self.scanner.resolve_image_path(file_name) or ""
            elif raw_path:
                # iOS 旧契约：只带 ?path=（裸文件名 / 相对 / 绝对路径）
                img_path = self.scanner.resolve_image_path(raw_path) or ""
                if not img_path:
                    # 索引可能还没建全（未先拉列表），重建一次再试
                    self.scanner.image_name_index(rebuild=True)
                    img_path = self.scanner.resolve_image_path(raw_path) or ""
            else:
                self.send_error(400, "Missing id+file or path")
                return

            if not img_path or not os.path.isfile(img_path):
                self.send_error(404, "Image file not found")
                return

            try:
                mtime = os.path.getmtime(img_path)
            except Exception:
                mtime = 0

            data = None
            mime = "image/jpeg"
            degraded = False

            if thumb:
                data = build_thumbnail_bytes(img_path, mtime)
                if data is None:
                    # 本机无法生成缩略图（通常是 Pillow 缺失）：显式降级为原图 + 明确标记，
                    # 客户端与运维都能一眼看出「缩略图链路挂了」，不会再静默发几 MB 原图。
                    degraded = True
                    print(f"[Thumb] !! 缩略图不可用，已降级返回原图：{img_path}")

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
            if thumb:
                # 健康标记：手机端可据此提示「电脑端缩略图未启用」；X-Thumb-Fallback=1 表示发的是原图
                self.send_header("X-Thumb", "fallback" if degraded else "ok")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(data)
            return

        # ===== 已授权设备白名单列表（GET · share.html 用）=====
        if path == "/api/online/authorized-devices":
            return self._handle_authorized_devices_get(query)

        # ===== 静态资源：share.html（PC 端分发页 · GET）=====
        if path == "/share.html" or path == "/share":
            return self._handle_share_html()

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

    def _move_work_to_stage0(self, target_work: Dict[str, Any]) -> Tuple[bool, str, str]:
        """把作品从「_已发送1次」移回「已发送0次」——「重置使用状态」的另一半。

        【2026-09-21 修复】此前 /api/online/reset-work 只把 useCount 归零、**不移动目录**，
        结果作品永远挂在「已使用」Tab，主页「全部」里也找不到它，与提示语
        「重置为待首发状态」不符。失败不致命：调用方仍返回「计数已归零」。
        """
        src_path = target_work["path"]
        folder_name = os.path.basename(src_path)
        work_id = target_work.get("id", "")
        root_dir = self.scanner.root
        dest_base = os.path.join(root_dir, self.scanner.STAGE0_FOLDER)
        os.makedirs(dest_base, exist_ok=True)

        target_dest = os.path.join(dest_base, folder_name)
        if os.path.abspath(target_dest) == os.path.abspath(src_path):
            return True, src_path, "作品已位于「已发送0次（抖音小红书可发）」"
        if os.path.exists(target_dest):
            ts_suffix = time.strftime("%Y%m%d_%H%M%S")
            target_dest = os.path.join(dest_base, f"{folder_name}_{ts_suffix}")

        move_err = None
        for _attempt in range(3):
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
            return False, src_path, f"物理移动失败: {str(move_err)}"

        target_work["path"] = target_dest
        self.scanner._moved_works[work_id] = target_work

        log_dir = os.path.join(root_dir, "_portfolio_move_logs")
        os.makedirs(log_dir, exist_ok=True)
        log_file = os.path.join(log_dir, f"delete_move_log_{time.strftime('%Y%m')}.csv")
        try:
            header_needed = not os.path.exists(log_file)
            row_ts = time.strftime("%Y-%m-%d %H:%M:%S")
            row = f"{row_ts},(重置按钮),{work_id},{src_path},{target_dest},0,reset_to_stage0"
            with open(log_file, "a", encoding="utf-8-sig") as fp:
                if header_needed:
                    fp.write("时间,设备,作品ID,原路径,目标路径,原使用次数,动作类型\n")
                fp.write(row + "\n")
        except Exception:
            pass

        return True, target_dest, "已移回「已发送0次（抖音小红书可发）」"

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

        if path == "/api/online/sync-phone-counts":
            # 手动/预演入口：手机在本地相册分享过的次数回写到电脑元数据。
            # 只补次数不搬文件；不传 host 时自动用刚扫出来的在线手机。
            content_length = int(self.headers.get("Content-Length", 0))
            raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
            try:
                req = json.loads(raw_body) if raw_body.strip() else {}
            except Exception:
                self.send_error(400, "Invalid JSON body")
                return
            if not isinstance(req, dict):
                req = {}

            dry_run = bool(req.get("dryRun", False))

            hosts = req.get("hosts")
            if not isinstance(hosts, list) or not hosts:
                single = str(req.get("host", "") or "").strip()
                hosts = [single] if single else []
            if not hosts:
                try:
                    devices = self.phone_cache.get(force=True)
                except Exception:
                    devices = []
                hosts = [
                    f"{d['ip']}:{d.get('port', phone_sync.PHONE_ALBUM_PORT)}"
                    for d in devices if d.get("ip")
                ]
            if not hosts:
                self.send_json(200, {
                    "ok": False,
                    "error": "未发现在线手机；可显式传 host（例如 192.168.1.200）",
                    "appliedCount": 0,
                })
                return

            report = self._run_phone_sync(hosts, dry_run=dry_run)
            report["message"] = (
                f"共回写 {report['appliedCount']} 个作品的使用次数"
                + ("（预演，未落盘）" if dry_run else "")
            )
            self.send_json(200, report)
            return

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

            # 同步归零 manifest.json，确保扫描器与手机端看到的「使用次数」一致为 0，
            # 这样「用过 → 重置 → 再删除」才能被正确判定为人工垃圾样本。
            # 注意：此项与上面的「作品标签.json」归零必须同时写入 useCount / used /
            # distribution / dispatchedTo / status，缺一项都会让扫描器读到非 0 次数。
            manifest_file = os.path.join(dir_path, "manifest.json")
            if os.path.exists(manifest_file):
                try:
                    with open(manifest_file, "r", encoding="utf-8") as fp:
                        manifest = json.load(fp)
                    if isinstance(manifest, dict):
                        manifest["useCount"] = 0
                        manifest["used"] = False
                        if isinstance(manifest.get("distribution"), dict):
                            manifest["distribution"]["useCount"] = 0
                            manifest["distribution"]["dispatchedTo"] = []
                            manifest["distribution"]["status"] = "待发手机"
                        manifest["resetAt"] = time.strftime("%Y-%m-%d %H:%M:%S")
                        with open(manifest_file, "w", encoding="utf-8") as fp:
                            json.dump(manifest, fp, ensure_ascii=False, indent=2)
                except Exception as e:
                    print(f"Error resetting manifest: {e}")

            # 【2026-09-21 修复②】「重置」的另一半：把作品从「_已发送1次」移回「已发送0次」。
            # 只对确实位于「_已发送1次」的作品回迁；垃圾库/已在待发区的作品不动，避免误挪。
            moved = False
            move_message = ""
            new_path = dir_path
            try:
                stage1_base = os.path.join(self.scanner.root, self.scanner.STAGE1_FOLDER)
                if os.path.abspath(dir_path).startswith(os.path.abspath(stage1_base)):
                    moved, new_path, move_message = self._move_work_to_stage0(target_work)
                else:
                    move_message = "作品不在「_已发送1次」，跳过回迁"
            except Exception as move_ex:
                move_message = f"回迁异常（计数已归零，不影响重置结果）: {move_ex}"

            # 【2026-09-21 修复①】force 扫描 + 显式作废阶段库缓存：
            # 手机端重置后会立刻重拉 /api/online/recycle，若不清 _stage_cache 会拿到
            # 5 秒内的旧值（useCount=1）⇒ 表现为「重置失败」。
            self.scanner.scan(force=True)
            self.scanner.invalidate_stage_cache()
            self.send_json(200, {
                "ok": True,
                "workId": work_id,
                "useCount": 0,
                "moved": moved,
                "newPath": new_path,
                "message": "已重置为待首发状态" + (f"；{move_message}" if move_message else ""),
            })
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

        # ===== 设备注册（直接白名单 · 下载即用）=====
        if path == "/api/online/device-register":
            content_length = int(self.headers.get("Content-Length", 0))
            raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
            try:
                req = json.loads(raw_body) if raw_body.strip() else {}
            except Exception:
                self.send_error(400, "Invalid JSON body")
                return
            return self._handle_device_register(req)

        # ===== PC 端 revoke 设备（POST · share.html 触发）=====
        if path.startswith("/api/online/authorized-devices/") and path.endswith("/revoke"):
            content_length = int(self.headers.get("Content-Length", 0))
            raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
            try:
                req = json.loads(raw_body) if raw_body.strip() else {}
            except Exception:
                self.send_error(400, "Invalid JSON")
                return
            device_id = path.split("/")[4]
            return self._handle_revoke(device_id, req)

        self.send_error(404, "Not Found")

    # ========================================================================
    # 设备注册 / 白名单管理（默认放行所有设备 · Phase 3 简化版）
    # ========================================================================
    # 设计决定（用户最终拍板）：
    #   - 不扫码 / 不弹框 / 不轮询；新设备首次连接时直接加入白名单
    #   - 后续同一 device_id 来访自动放行 + 更新 last_seen
    #   - PC 端 share.html 可查看白名单 + 单台 revoke
    #   - 白名单持久化到 ~/.device-share-hub-authorized-devices.json
    # ========================================================================

    def _handle_device_register(self, req: Dict[str, Any]) -> None:
        """POST /api/online/device-register {device_id, device_name} →
        默认放行所有设备：写入/更新白名单，立即返回 ok。
        后续移动端只需在首次启动时 POST 一次，之后凭 device_id 自动续期。
        """
        device_id = (req.get("device_id") or "").strip()
        device_name = (req.get("device_name") or "未命名设备").strip()
        if not device_id or len(device_id) < 8:
            self.send_json(400, {"ok": False, "message": "device_id 缺失或过短"})
            return

        client_ip = self.client_address[0] if self.client_address else ""
        now = int(time.time())

        with AUTHORIZED_DEVICES_LOCK:
            devices = load_authorized_devices()
            existing = devices.get(device_id)
            if existing:
                # 已知设备 → 更新 last_seen + ip
                existing["last_seen_at"] = now
                existing["last_seen_ip"] = client_ip
                # 如果 device_name 变了（用户改了手机名）也更新一下
                if existing.get("device_name") != device_name and device_name:
                    existing["device_name"] = device_name
                devices[device_id] = existing
                is_new = False
            else:
                # 新设备 → 直接写入白名单（无需用户操作）
                devices[device_id] = {
                    "device_name": device_name,
                    "allowed_at": now,
                    "last_seen_at": now,
                    "last_seen_ip": client_ip,
                }
                is_new = True
            save_authorized_devices(devices)

        if is_new:
            print(f"✅ [whitelist] 新设备已自动放行：{device_name} ({device_id[:8]}…) 来自 {client_ip}")
        else:
            print(f"🔄 [whitelist] 已知设备续期：{device_name} ({device_id[:8]}…) 来自 {client_ip}")

        self.send_json(200, {
            "ok": True,
            "device_id": device_id,
            "device_name": device_name,
            "authorized": True,
            "is_new": is_new,
            "message": "已自动加入授权名单" if is_new else "欢迎回来，已在名单中",
        })

    def _handle_authorized_devices_get(self, query: Dict[str, List[str]]) -> None:
        """GET /api/online/authorized-devices → 列出已授权设备（share.html 用）。
        本机访问校验，避免被局域网外人枚举。"""
        client_ip = self.client_address[0] if self.client_address else ""
        if client_ip not in ("127.0.0.1", "::1", get_local_ip()):
            self.send_json(403, {"ok": False, "message": "仅允许本机访问"})
            return
        with AUTHORIZED_DEVICES_LOCK:
            devices = load_authorized_devices()
        # 排序：最近活跃优先
        ordered = sorted(
            [{"device_id": did, **info} for did, info in devices.items()],
            key=lambda x: x.get("last_seen_at", 0),
            reverse=True,
        )
        self.send_json(200, {"ok": True, "devices": ordered})

    def _handle_revoke(self, device_id: str, req: Dict[str, Any]) -> None:
        """POST /api/online/authorized-devices/{id}/revoke → 从白名单移除。"""
        client_ip = self.client_address[0] if self.client_address else ""
        if client_ip not in ("127.0.0.1", "::1", get_local_ip()):
            self.send_json(403, {"ok": False, "message": "仅允许本机访问"})
            return
        with AUTHORIZED_DEVICES_LOCK:
            devices = load_authorized_devices()
            removed = devices.pop(device_id, None)
            if removed:
                save_authorized_devices(devices)
                print(f"🚫 [whitelist] 移除已授权设备：{removed.get('device_name', device_id[:8])} ({device_id[:8]}…)")
        self.send_json(200, {"ok": True, "removed": removed is not None, "device_id": device_id})

    def _handle_share_html(self) -> None:
        """GET /share.html → 返回 PC 端分发页（设备列表 + revoke）"""
        share_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "share.html")
        if not os.path.exists(share_path):
            self.send_error(503, "share.html not deployed")
            return
        try:
            with open(share_path, "r", encoding="utf-8") as fp:
                html = fp.read()
        except OSError:
            self.send_error(500, "share.html read failed")
            return
        body = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_cors_headers()
        self.end_headers()
        self.wfile.write(body)


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

    # DSH-110：启动文件系统轮询线程（无第三方依赖，60 秒/次）
    # GPT API 实时产出的作品图落到 DEFAULT_LIBRARY_ROOT 下，60 秒内自动入库；
    # 手机端下次刷新（refresh=1 或自动）即看到最新。
    scanner._start_watchdog_loop()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n服务正在平稳退出...")
        scanner.stop_watchdog()
        server.server_close()
    except Exception as e:
        import traceback
        traceback.print_exc()
        with open(os.path.join(os.path.dirname(__file__), "crash.log"), "w", encoding="utf-8") as fp:
            fp.write(traceback.format_exc())
    finally:
        scanner.stop_watchdog()
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
