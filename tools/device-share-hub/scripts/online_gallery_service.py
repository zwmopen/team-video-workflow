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
import threading

# ------------------------------ 日志轮转（DSH-126） ------------------------------
#
# 为什么需要：服务是常驻进程，stdout / stderr 一直往脚本目录下的同一个 .log 里追加，
# 从来没有任何上限。跑上几周就是几百 MB 甚至 GB 级，而且它就躺在脚本目录里，
# 磁盘被自己吃光了也不会有人发现（实测 online_gallery_service.log 已经 1MB）。
#
# 为什么不直接用 logging.handlers.RotatingFileHandler：这里接管的是**整个进程**的
# sys.stdout / sys.stderr（服务日志基本都是 print() 打出来的），只能自己写一个最小文本流。

LOG_MAX_BYTES = 8 * 1024 * 1024    # 单个日志超过 8MB 就切一刀
LOG_BACKUP_COUNT = 3               # 最多留 3 份历史：.1 / .2 / .3
LOG_CHECK_EVERY_CHARS = 200000     # 每累计写入约 20 万个字符才 stat 一次磁盘


def rotate_log_if_needed(path, max_bytes=LOG_MAX_BYTES, backups=LOG_BACKUP_COUNT):
    """日志文件超过阈值就轮转：x.log -> x.log.1 -> x.log.2 -> x.log.3（最老的丢掉）。

    返回 True 表示真的切过一刀。出错一律吞掉 —— 日志轮转失败绝不能把服务拖崩。
    """
    try:
        if not os.path.exists(path):
            return False
        if os.path.getsize(path) < max_bytes:
            return False
    except Exception:
        return False

    # 先把最老的踢掉，再从老到新逐级改名：必须倒着来，正着来会互相覆盖
    oldest = "%s.%d" % (path, backups)
    try:
        if os.path.exists(oldest):
            os.remove(oldest)
    except Exception:
        pass
    for index in range(backups - 1, 0, -1):
        src = "%s.%d" % (path, index)
        dst = "%s.%d" % (path, index + 1)
        if os.path.exists(src):
            try:
                os.replace(src, dst)
            except Exception:
                pass
    try:
        os.replace(path, "%s.%d" % (path, 1))
        return True
    except Exception:
        return False


class RotatingLogStream(object):
    """边写文件边按大小自动轮转的最小文本流，只实现 print() 真正会用到的那几个接口。

    线程安全：服务是 ThreadingHTTPServer，print() 可能同时从好几个线程进来；而轮转要
    关掉旧文件再开新的，跟并发写入撞上就会往已关闭的文件里写。所以写和切共用一把锁。
    """

    def __init__(self, path, max_bytes=LOG_MAX_BYTES, backups=LOG_BACKUP_COUNT,
                 check_every=LOG_CHECK_EVERY_CHARS):
        self.path = path
        self.max_bytes = max_bytes
        self.backups = backups
        self.check_every = check_every
        self.encoding = "utf-8"
        self.errors = "replace"
        self.name = path
        self._lock = threading.Lock()
        self._since_check = 0
        # 启动时先切一次：上次跑大的文件别一直顶着，等写满 20 万字符才切太晚
        rotate_log_if_needed(path, max_bytes, backups)
        self._fp = open(path, "a", encoding="utf-8", errors="replace", buffering=1)

    def _is_too_big(self):
        try:
            return os.path.getsize(self.path) >= self.max_bytes
        except Exception:
            return False

    def _rotate(self):
        """关掉旧文件 -> 走一遍改名链条 -> 重新打开。

        ⚠️ Windows 上**不能**在文件还开着的时候改名：os.replace 会抛 PermissionError，
           而本类所有异常都是吞掉的，结果就是日志**永远轮转不了且零报错** —— 这正是
           DSH-126 第一版在真机上表现出的症状。顺序必须是「先关 -> 再切 -> 再开」，
           跟 Linux 上「先改名再关」的直觉恰好相反。
        """
        try:
            self._fp.close()
        except Exception:
            pass
        rotate_log_if_needed(self.path, self.max_bytes, self.backups)
        try:
            self._fp = open(self.path, "a", encoding="utf-8",
                            errors="replace", buffering=1)
        except Exception:
            # 重开失败也要保证 print() 不会把调用方（HTTP 请求线程）炸掉：
            # 磁盘满 / 日志目录被删 / 权限变了都会走到这里。日志没了能忍，服务挂了不行。
            try:
                self._fp = open(os.devnull, "w", encoding="utf-8")
            except Exception:
                pass

    def write(self, text):
        if not text:
            return
        with self._lock:
            try:
                self._fp.write(text)
            except Exception:
                return
            # 按「字符数」而不是字节数估算：中文一个字 3 字节，逐条 stat 太亏
            self._since_check += len(text)
            if self._since_check >= self.check_every:
                self._since_check = 0
                if self._is_too_big():
                    self._rotate()

    def flush(self):
        with self._lock:
            try:
                self._fp.flush()
            except Exception:
                pass

    def fileno(self):
        return self._fp.fileno()

    def isatty(self):
        return False

    def writable(self):
        return True

    def close(self):
        try:
            self._fp.close()
        except Exception:
            pass


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
                # DSH-126：换成会自动轮转的流，常驻服务不再把日志写成无上限的巨文件
                f = RotatingLogStream(os.path.join(log_dir, log_name))
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
    from cross_shelf_linker import (
        is_junction_or_symlink,
        resolve_junction_source,
        unlink_junction,
        cleanup_junctions_for_source,
        prune_dangling_junctions,
        create_junction,
    )
except ImportError:
    _cur_dir = os.path.dirname(os.path.abspath(__file__))
    if _cur_dir not in sys.path:
        sys.path.insert(0, _cur_dir)
    from cross_shelf_linker import (
        is_junction_or_symlink,
        resolve_junction_source,
        unlink_junction,
        cleanup_junctions_for_source,
        prune_dangling_junctions,
        create_junction,
    )

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


_CODE_FRESHNESS_CACHE = {"at": 0.0, "data": None}
_CODE_FRESHNESS_TTL = 10.0


def code_freshness() -> Dict[str, Any]:
    """判断「正在跑的进程」是否落后于磁盘上的脚本（带 10s TTL 缓存，杜绝高频 I/O 拖垮响应）。"""
    now = time.time()
    cached = _CODE_FRESHNESS_CACHE.get("data")
    if cached is not None and (now - float(_CODE_FRESHNESS_CACHE.get("at", 0.0)) < _CODE_FRESHNESS_TTL):
        return cached

    disk_mtime, disk_sha = _script_fingerprint(SCRIPT_PATH)
    if disk_sha and SCRIPT_SHA_AT_START:
        stale = disk_sha != SCRIPT_SHA_AT_START
    else:
        stale = False
    result = {
        "startedAt": PROCESS_STARTED_STR,
        "uptimeSeconds": int(now - PROCESS_STARTED_AT),
        "scriptPath": SCRIPT_PATH,
        "scriptShaRunning": SCRIPT_SHA_AT_START,
        "scriptShaOnDisk": disk_sha,
        "scriptMtime": _fmt_ts(disk_mtime),
        "scriptMtimeAtStart": _fmt_ts(SCRIPT_MTIME_AT_START),
        "staleCode": stale,
        "hint": "磁盘上的脚本已改动（内容 sha 不一致）：正在运行的是旧代码，请重启在线相册服务" if stale else "",
    }
    _CODE_FRESHNESS_CACHE["at"] = now
    _CODE_FRESHNESS_CACHE["data"] = result
    return result

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
# 【DSH-114】作品扫描结果缓存时长（秒）。
# ⚠️ 之前写死 5.0，而一次全量扫描实测 **5.2~10 秒**（473 个作品，逐个读 文案.txt +
# 枚举图片 + 跑槽位守卫正则）。**扫描耗时 > 缓存时长 ⇒ 缓存永远命中不了**，
# 每一次 /api/online/status 都在全库重扫 —— 手机端「正在连接电脑在线相册…」
# 干等 8~11 秒的根因就在这里（实测三次间隔 7 秒的请求，分别耗时 11.4 / 10.6 / 8.0 秒）。
# 改成 30 秒后：命中即毫秒级返回。
# 新鲜度由 DSH-110 的 watchdog 兜底 —— 它每 60 秒轮询一次，发现增删改就
# scan(force=True) 主动作废缓存，所以放宽 TTL **不会**让手机看到更旧的数据。
SCAN_CACHE_TTL = 30.0
# DSH-117：refresh=1（强制全盘扫描）的最小间隔。
# 手机端下拉刷新连点、或多部手机同一秒一起下拉，会在几秒内叠 N 次 5~10 秒的全盘
# 扫描 —— 每次都要读完 473 套作品的文案文件，请求直接堆积成假死。
# 限速后：窗口内的第二次及以后一律读缓存（数据新鲜度仍由 60 秒 watchdog 兜底）。
FORCE_SCAN_MIN_INTERVAL = 3.0
# DSH-128：「作品被移走后还能按 id 找回」登记表（WorkScanner._moved_works）的上限。
# 这里存的不是路径字符串，而是**完整作品对象**（含 copyText —— V4.5 全系 11 个版本
# 的文案，外加 images 列表），单条就是几 KB 到十几 KB。此前只增不减，
# 服务常驻几个月就是上万条 ⇒ 常驻内存单调涨到上百 MB 且零报错。
# 800 条足够覆盖「最近移走的作品」，按作品库现规模相当于几个月的使用量。
MOVED_WORKS_LIMIT = 800
_WIRE_OMIT_FIELDS = ("searchBlob", "slotGuard", "_sourceLookup")

# ── 跨端视图模式实时联动状态 (手机端 <-> 电脑端双向同步) ──────────────────────
_VIEW_STATE_LOCK = threading.Lock()
_VIEW_STATE_FILE = r"D:\AICode\运行数据\江湖有旅人\gallery_ui_state.json"
_VIEW_STATE_MEM: Optional[Dict[str, Any]] = None
VALID_VIEW_MODES = ("grid", "list", "compare")


def get_gallery_view_state() -> Dict[str, Any]:
    """读取当前全局跨端视图模式状态（grid=图标视图, list=列表视图, compare=对比视图）。"""
    global _VIEW_STATE_MEM
    with _VIEW_STATE_LOCK:
        if _VIEW_STATE_MEM is not None:
            return dict(_VIEW_STATE_MEM)
        state: Dict[str, Any] = {"viewMode": "grid", "updatedAt": 0, "updatedBy": "default"}
        try:
            if os.path.exists(_VIEW_STATE_FILE):
                with open(_VIEW_STATE_FILE, "r", encoding="utf-8") as fp:
                    loaded = json.load(fp)
                if isinstance(loaded, dict) and loaded.get("viewMode") in VALID_VIEW_MODES:
                    state["viewMode"] = loaded["viewMode"]
                    state["updatedAt"] = int(loaded.get("updatedAt") or 0)
                    state["updatedBy"] = str(loaded.get("updatedBy") or "default")
        except Exception:
            pass
        _VIEW_STATE_MEM = state
        return dict(_VIEW_STATE_MEM)


def set_gallery_view_state(view_mode: str, updated_by: str = "unknown") -> Dict[str, Any]:
    """更新并持久化全局跨端视图模式状态。"""
    global _VIEW_STATE_MEM
    vm = (view_mode or "").strip().lower()
    if vm not in VALID_VIEW_MODES:
        vm = "grid"
    now_ms = int(time.time() * 1000)
    with _VIEW_STATE_LOCK:
        state: Dict[str, Any] = {
            "viewMode": vm,
            "updatedAt": now_ms,
            "updatedBy": (updated_by or "unknown").strip() or "unknown",
        }
        _VIEW_STATE_MEM = state
        try:
            os.makedirs(os.path.dirname(_VIEW_STATE_FILE), exist_ok=True)
            tmp_p = _VIEW_STATE_FILE + ".tmp"
            with open(tmp_p, "w", encoding="utf-8") as fp:
                json.dump(state, fp, ensure_ascii=False, indent=2)
            os.replace(tmp_p, _VIEW_STATE_FILE)
        except Exception:
            pass
        return dict(state)

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


def splice_platform_copy(original_full_text: str, version_key: str, new_slot_text: str) -> str:
    """DSH-138: 若原文是多槽位文案，且 new_slot_text 为单一版本内容，
    则精确定向替换对应槽位，保护其他平台槽位不丢失。
    支持 <<<VERSION_START>>>、<<<MK_START>>> 与 --- 方案X：... --- 三大协议。
    """
    if not original_full_text:
        return new_slot_text
    if "<<<COPY_FORMAT" in new_slot_text or "_START>>>" in new_slot_text:
        return new_slot_text

    v_trimmed = (version_key or "").strip()
    if not v_trimmed:
        return new_slot_text

    v_upper = v_trimmed.upper()
    alias_map = {
        "种草版": "XHS", "小红书": "XHS", "XHS": "XHS", "红书种草": "XHS", "自然种草": "XHS",
        "大纲方案版": "XHS_2", "方案版": "XHS_2", "XHS2": "XHS_2", "XHS_2": "XHS_2", "红书大纲": "XHS_2",
        "短文精选版": "XHS_3", "精选版": "XHS_3", "XHS3": "XHS_3", "XHS_3": "XHS_3",
        "规避营销版": "DOUYIN", "抖音": "DOUYIN", "抖音探店": "DOUYIN", "DOUYIN": "DOUYIN", "抖音无营销": "DOUYIN", "抖音避坑": "DOUYIN", "抖音攻略": "DOUYIN",
        "公众号版": "WECHAT", "微信": "WECHAT", "WECHAT": "WECHAT",
        "HR决策版": "HR", "HR": "HR", "决策版": "HR",
    }
    target_marker = alias_map.get(v_trimmed, v_upper)

    # 1. 尝试匹配 <<<VERSION_START: tag>>> ... <<<VERSION_END>>>
    version_pat = re.compile(r"(?s)<<<VERSION_START:\s*([^>\r\n]+?)\s*>>>[\r\n]*(.*?)[\r\n]*<<<VERSION_END>>>")
    def _repl_ver(m):
        tag = m.group(1).strip()
        matched = (tag == v_trimmed or tag.upper() == v_upper or tag.upper() == target_marker
                   or alias_map.get(tag) == target_marker
                   or v_trimmed in tag or tag in v_trimmed)
        if matched:
            return f"<<<VERSION_START:{tag}>>>\n{new_slot_text.strip()}\n<<<VERSION_END>>>"
        return m.group(0)

    replaced_ver, count_ver = version_pat.subn(_repl_ver, original_full_text)
    if count_ver > 0:
        return replaced_ver

    # 2. 尝试匹配 <<<MK_START>>> ... <<<MK_END>>>
    def _repl_marker(m):
        m_tag = m.group(1)
        matched = (m_tag.upper() == target_marker or m_tag.upper() == v_upper or m_tag == v_trimmed
                   or alias_map.get(m_tag) == target_marker)
        if matched:
            return f"<<<{m_tag}_START>>>\n{new_slot_text.strip()}\n<<<{m_tag}_END>>>"
        return m.group(0)

    replaced_mk, count_mk = _PLATFORM_BLOCK_RE.subn(_repl_marker, original_full_text)
    if count_mk > 0:
        return replaced_mk

    # 3. 尝试匹配 Format 3 方案分隔格式：--- 方案X：... ---
    plan_pat = re.compile(r"(?m)^(---+\s*方案(?:\d+|[一二三四五六七八九十]+)[：:]\s*([^\r\n]+?)\s*---+\s*$)")
    matches = list(plan_pat.finditer(original_full_text))
    if matches:
        target_idx = -1
        for idx, match in enumerate(matches):
            hdr_name = match.group(2).strip()
            if (v_trimmed in hdr_name or hdr_name in v_trimmed
                    or alias_map.get(hdr_name) == target_marker
                    or ("种草" in v_trimmed and "种草" in hdr_name)
                    or ("大纲" in v_trimmed and ("大纲" in hdr_name or "决策" in hdr_name))
                    or ("HR" in v_trimmed and "HR" in hdr_name)
                    or ("抖音" in v_trimmed and "抖音" in hdr_name)):
                target_idx = idx
                break

        if target_idx >= 0:
            target_match = matches[target_idx]
            content_start = target_match.end()
            content_end = matches[target_idx + 1].start() if target_idx + 1 < len(matches) else len(original_full_text)
            header_str = target_match.group(1)
            before = original_full_text[:content_start]
            after = original_full_text[content_end:]
            return f"{before}\n\n{new_slot_text.strip()}\n\n{after}"

    return new_slot_text




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
    "舟山海岛", "舟山", "嵊泗", "安吉", "莫干山", "千岛湖", "桐庐", "象山", "临安",
    "余杭", "宜兴溧阳", "溧阳", "宜兴", "乌镇", "黄山", "崇明", "阳澄湖", "西山岛",
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
#
# 【DSH-129】这里原来是 `Dict[str, threading.Lock]`，只有 `_inflight_lock()` 往里塞、
# **全文件没有任何一行往外拿** ⇒ 服务是 7x24 常驻进程，跑几个月字典里就堆着每一个
# 曾经请求过的 (路径, mtime) 组合的锁对象，单调增长、零报错、零日志（典型慢变量隐患）。
# 现改成「带引用计数的票」：acquire 时 users+1，release 时 users-1，归零才真正删除。
# ⛔ 不能按 LRU / 上限直接删：删掉一把还有线程正在等的锁，单飞保护会**静默失效**
#    （不报错、不崩溃，只是同一张图被重复生成 —— 正是这类 BUG 最难查的地方）。
MAX_INFLIGHT_ENTRIES = 2000      # 安全网：正常路径靠引用计数归零回收，走不到这里
THUMB_INFLIGHT: "Dict[str, dict]" = {}
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


def _inflight_acquire(key: str) -> threading.Lock:
    """取某个缓存键的专属互斥锁（保证同一张图只被生成一次），并把引用计数 +1。

    必须与 `_inflight_release(key)` 成对调用（用 try/finally 包住），
    否则条目永远归不了零 —— DSH-129 之前那种「只增不减」的慢泄漏就又回来了。
    """
    with THUMB_INFLIGHT_LOCK:
        entry = THUMB_INFLIGHT.get(key)
        if entry is None:
            entry = {"lock": threading.Lock(), "users": 0}
            THUMB_INFLIGHT[key] = entry
        entry["users"] += 1
        return entry["lock"]


def _inflight_release(key: str) -> None:
    """引用计数 -1；归零时把条目真正删掉。

    这就是 DSH-129 要补的那个「谁负责往外拿」—— 之前整份文件里没有这一行。
    """
    with THUMB_INFLIGHT_LOCK:
        entry = THUMB_INFLIGHT.get(key)
        if entry is None:
            return
        entry["users"] -= 1
        if entry["users"] <= 0:
            THUMB_INFLIGHT.pop(key, None)


def prune_inflight_entries(limit: Optional[int] = None) -> int:
    """安全网：只清「当前没人引用」的条目，清到不超过 limit 为止。

    正常路径下引用计数会把条目清干净，所以这个函数平时几乎总是返回 0；
    它存在的意义是兜住「未来某处调用方忘了 release」这类回归。
    ⛔ 绝不能删 users > 0 的条目：正在等这把锁的线程会静默失去单飞保护。
    """
    cap = MAX_INFLIGHT_ENTRIES if limit is None else limit
    with THUMB_INFLIGHT_LOCK:
        if len(THUMB_INFLIGHT) <= cap:
            return 0
        removed = 0
        for key in list(THUMB_INFLIGHT.keys()):
            if len(THUMB_INFLIGHT) <= cap:
                break
            entry = THUMB_INFLIGHT.get(key)
            if entry is not None and int(entry.get("users", 0)) <= 0:
                THUMB_INFLIGHT.pop(key, None)
                removed += 1
        return removed


def inflight_entries_health() -> Dict[str, int]:
    """单飞登记表健康快照（供 thumbnail_health / /api/online/status 一眼看穿）。"""
    with THUMB_INFLIGHT_LOCK:
        return {
            "entries": len(THUMB_INFLIGHT),
            "inUse": sum(int(e.get("users", 0)) for e in THUMB_INFLIGHT.values()),
            "limit": MAX_INFLIGHT_ENTRIES,
        }


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
    # DSH-129：锁改成「带引用计数的票」，用完必须 release（try/finally 保证）。
    #          否则登记表只增不减 —— 常驻几个月就堆出几万个锁对象、零报错。
    inflight = _inflight_acquire(cache_key)
    try:
        with inflight:
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
    finally:
        _inflight_release(cache_key)


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
    # DSH-129：单飞登记表大小要能一眼看见 —— 之前它只增不减且**没有任何出口暴露**，
    # 这正是这类慢变量能潜伏数月的原因。
    inflight = inflight_entries_health()
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
        "inflightEntries": inflight["entries"],
        "inflightInUse": inflight["inUse"],
        "inflightLimit": inflight["limit"],
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
# 出网代理：环境变量显式指定时只用它。
# 【DSH-115】不再写死 7897 —— Clash 混合端口会在 7890/7897 之间回跳
# （2026-09-25 CDP 产线就因此停产过一次），节点还偶发掉线（实测 WinError 10054）。
# 所以每次出网按序轮试：上次成功的 > 显式指定 > 7897 > 7890 > 7891 > 7892 > 直连兜底。
UPDATE_UPSTREAM_PROXY = os.environ.get("DSH_UPDATE_PROXY", "")
_UPDATE_PROXY_CANDIDATES = (
    "http://127.0.0.1:7897",
    "http://127.0.0.1:7890",
    "http://127.0.0.1:7891",
    "http://127.0.0.1:7892",
)
_UPDATE_PROXY_HINT: Dict[str, str] = {"url": ""}
UPDATE_MANIFEST_TTL = 300.0
UPDATE_CACHE_DIR = os.path.join(tempfile.gettempdir(), "dsh-update-relay")

_UPDATE_LOCK = threading.Lock()
_UPDATE_MANIFEST_CACHE: Dict[str, Any] = {"at": 0.0, "data": None}
# 改写前的**原始**下载地址（改写后 apk_url 变成 /download/apk，不能再拿去出网）
_UPDATE_ORIGIN_URLS: Dict[str, str] = {}
_UPDATE_RELAY_STATS: Dict[str, Any] = {
    "manifestFetches": 0, "manifestFailures": 0,
    "downloads": 0, "downloadFailures": 0,
    "shaMismatch": 0, "cachedSha": "",
    "lastError": "", "lastManifestAt": 0.0,
}


def _update_openers():
    """按优先级给出 (标签, opener) 列表：上次成功的最前，直连兜底最后。"""
    cands: List[str] = []
    if UPDATE_UPSTREAM_PROXY:
        cands.append(UPDATE_UPSTREAM_PROXY)
    hint = _UPDATE_PROXY_HINT.get("url")
    if hint and hint not in cands:
        cands.insert(0, hint)
    cands += [c for c in _UPDATE_PROXY_CANDIDATES if c not in cands]
    out = []
    for c in cands:
        out.append((c, urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": c, "https": c}))))
    out.append(("", urllib.request.build_opener()))     # 直连兜底
    return out


def _update_fetch(req, timeout):
    """带代理轮试的出网 GET。第一个成功的赢，并把它的地址记成下次的提示；
    全部失败才抛（抛最后一个异常，调用方自行降级，绝不拖垮主流程）。"""
    last_err: Optional[Exception] = None
    for proxy_url, opener in _update_openers():
        try:
            resp = opener.open(req, timeout=timeout)
            _UPDATE_PROXY_HINT["url"] = proxy_url
            return resp
        except Exception as e:
            last_err = e
    assert last_err is not None
    raise last_err


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


LOCAL_OUT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "android", "out"))
LOCAL_LATEST_JSON = os.path.join(LOCAL_OUT_DIR, "local_latest.json")


def _read_local_update_manifest() -> Optional[Dict[str, Any]]:
    """优先读取本机刚编译落盘的 local_latest.json（若存在且对应的本地 APK 文件真实有效）。"""
    try:
        if not os.path.isfile(LOCAL_LATEST_JSON):
            return None
        with open(LOCAL_LATEST_JSON, "r", encoding="utf-8") as fp:
            data = json.load(fp)
        if not isinstance(data, dict):
            return None
        local_apk = data.get("local_apk_path") or ""
        if local_apk and os.path.isfile(local_apk) and os.path.getsize(local_apk) > 64 * 1024:
            return data
    except Exception:
        pass
    return None


def fetch_update_manifest(force: bool = False) -> Dict[str, Any]:
    """取发布清单（TTL 缓存 + 本地构建优先）。失败返回空 dict —— 中转挂了不能拖垮相册服务。"""
    local_data = _read_local_update_manifest()
    with _UPDATE_LOCK:
        now = time.time()
        cached = _UPDATE_MANIFEST_CACHE.get("data")
        if (not force) and cached and (
                now - float(_UPDATE_MANIFEST_CACHE.get("at", 0.0)) < UPDATE_MANIFEST_TTL):
            if local_data:
                local_vc = int(local_data.get("version_code", local_data.get("versionCode", 0)) or 0)
                cached_vc = int(cached.get("version_code", cached.get("versionCode", 0)) or 0)
                if local_vc >= cached_vc:
                    _UPDATE_ORIGIN_URLS["local_apk"] = local_data.get("local_apk_path", "")
                    merged = dict(cached)
                    merged.update({k: v for k, v in local_data.items() if k != "local_apk_path"})
                    merged = _rewrite_manifest_for_lan(merged)
                    _UPDATE_MANIFEST_CACHE["data"] = merged
                    return merged
            return cached
    try:
        req = urllib.request.Request(
            UPDATE_MANIFEST_URL,
            headers={"User-Agent": "dsh-online-gallery", "Accept": "application/json"})
        with _update_fetch(req, 12) as resp:
            raw = resp.read()
        data = json.loads(raw.decode("utf-8"))
        if not isinstance(data, dict) or not data.get("apk_url"):
            raise ValueError("发布清单里没有 apk_url")
        if local_data:
            local_vc = int(local_data.get("version_code", local_data.get("versionCode", 0)) or 0)
            remote_vc = int(data.get("version_code", data.get("versionCode", 0)) or 0)
            if local_vc >= remote_vc:
                for k, v in local_data.items():
                    if k != "local_apk_path":
                        data[k] = v
        with _UPDATE_LOCK:
            # 先把原始地址存下来（改写后就找不回来了）
            _UPDATE_ORIGIN_URLS["apk"] = data.get("apk_url", "")
            _UPDATE_ORIGIN_URLS["ipa"] = (data.get("ios") or {}).get("ipa_url", "")
            if local_data:
                _UPDATE_ORIGIN_URLS["local_apk"] = local_data.get("local_apk_path", "")
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
        if local_data:
            with _UPDATE_LOCK:
                _UPDATE_ORIGIN_URLS["local_apk"] = local_data.get("local_apk_path", "")
                fallback = _rewrite_manifest_for_lan({k: v for k, v in local_data.items() if k != "local_apk_path"})
                _UPDATE_MANIFEST_CACHE["at"] = time.time()
                _UPDATE_MANIFEST_CACHE["data"] = fallback
                _UPDATE_RELAY_STATS["manifestFetches"] = int(
                    _UPDATE_RELAY_STATS.get("manifestFetches", 0)) + 1
            return fallback
        with _UPDATE_LOCK:
            _UPDATE_RELAY_STATS["manifestFailures"] = int(
                _UPDATE_RELAY_STATS.get("manifestFailures", 0)) + 1
            _UPDATE_RELAY_STATS["lastError"] = str(e)
        print("[UpdateRelay] 取发布清单失败：%s" % e)
        return {}


def _update_expected_sha(manifest: Dict[str, Any], kind: str) -> str:
    """清单里声明的该包 sha256（小写）；取不到就返回空，调用方跳过校验。"""
    if kind == "apk":
        return (manifest.get("sha256") or "").strip().lower()
    ios = manifest.get("ios") or {}
    return (ios.get("sha256") or "").strip().lower()


def _update_cache_file(kind: str, version: str, sha: str) -> str:
    """缓存文件名把 sha256 编进去。

    【DSH-116】GitHub 会以同一个 version_name 重发**重新构建过**的包：
    实测 v0.8.61 被重发过一次，字节不同、体积却一模一样（都是 883308）。
    只按版本落名 ⇒ 命中旧字节、却配新清单里的 sha256 ⇒ 手机端
    UpdatePackageValidator 必判「更新包校验失败」并删包，
    而中转这边 downloads++、downloadFailures=0 —— 全绿，实则永远装不上。
    """
    tag = sha[:12] if sha else "nosha"
    return os.path.join(UPDATE_CACHE_DIR, "album-%s-%s-%s.%s"
                        % ("Android" if kind == "apk" else "iOS", version, tag, kind))


def _download_upstream(kind: str) -> Tuple[Optional[bytes], str]:
    """代取安装包（按版本落磁盘缓存，两台手机只出网一次；若本机有最新编译包则 0 秒直供）。"""
    manifest = fetch_update_manifest()
    if not manifest:
        return None, "拿不到发布清单（检查 7897 代理）"
    if kind == "apk":
        local_data = _read_local_update_manifest()
        if local_data:
            local_apk = local_data.get("local_apk_path", "")
            expected_sha = _update_expected_sha(manifest, "apk")
            if local_apk and os.path.isfile(local_apk):
                try:
                    with open(local_apk, "rb") as lfh:
                        lpayload = lfh.read()
                    if (not expected_sha) or hashlib.sha256(lpayload).hexdigest().lower() == expected_sha:
                        with _UPDATE_LOCK:
                            _UPDATE_RELAY_STATS["downloads"] = int(
                                _UPDATE_RELAY_STATS.get("downloads", 0)) + 1
                            _UPDATE_RELAY_STATS["cachedSha"] = expected_sha
                        return lpayload, ""
                except Exception:
                    pass
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
    expected = _update_expected_sha(manifest, kind)
    cache_file = _update_cache_file(kind, version, expected)
    if os.path.isfile(cache_file) and os.path.getsize(cache_file) > 64 * 1024:
        try:
            with open(cache_file, "rb") as fh:
                payload = fh.read()
            if (not expected) or hashlib.sha256(payload).hexdigest().lower() == expected:
                with _UPDATE_LOCK:
                    _UPDATE_RELAY_STATS["downloads"] = int(
                        _UPDATE_RELAY_STATS.get("downloads", 0)) + 1
                    _UPDATE_RELAY_STATS["cachedSha"] = expected
                return payload, ""
            print("[UpdateRelay] %s 缓存字节与清单 sha256 不一致，丢弃重下" % kind.upper())
            with _UPDATE_LOCK:
                _UPDATE_RELAY_STATS["shaMismatch"] = int(
                    _UPDATE_RELAY_STATS.get("shaMismatch", 0)) + 1
            try:
                os.remove(cache_file)
            except Exception:
                pass
        except Exception:
            pass
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "dsh-online-gallery"})
        with _update_fetch(req, 180) as resp:
            payload = resp.read()
        if not payload or len(payload) < 64 * 1024:
            raise ValueError("下载到的安装包太小（%d 字节），疑似失败" % len(payload))
        # 判据用 sha256，**不用体积**：实测这个 APK 只有 0.86 MB，
        # 早先用「必须 >1MB」当闸门把它整包判废了（自创判据的代价）。
        # 清单里本来就带 sha256，手机端也会校验同一个值 —— 与客户端口径完全一致。
        actual = hashlib.sha256(payload).hexdigest()
        if expected and actual.lower() != expected.lower():
            raise ValueError("安装包校验不一致：期望 %s... 实际 %s..."
                             % (expected[:12], actual[:12]))
        tmp = cache_file + ".part"
        with open(tmp, "wb") as fh:
            fh.write(payload)
        os.replace(tmp, cache_file)
        with _UPDATE_LOCK:
            _UPDATE_RELAY_STATS["downloads"] = int(
                _UPDATE_RELAY_STATS.get("downloads", 0)) + 1
            _UPDATE_RELAY_STATS["cachedSha"] = expected
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
        "proxy": _UPDATE_PROXY_HINT.get("url") or UPDATE_UPSTREAM_PROXY or "",
        "manifestTtlSec": UPDATE_MANIFEST_TTL,
        "cachedVersion": manifest.get("version_name", ""),
        "cachedVersionCode": manifest.get("version_code", manifest.get("versionCode", 0)),
        "iosCachedVersion": ios.get("version_name", ""),
        "manifestFetches": stats.get("manifestFetches", 0),
        "manifestFailures": stats.get("manifestFailures", 0),
        "downloads": stats.get("downloads", 0),
        "downloadFailures": stats.get("downloadFailures", 0),
        "cachedSha": stats.get("cachedSha", ""),
        "shaMismatch": stats.get("shaMismatch", 0),
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


def extract_category_from_folder_name(folder_name: str) -> str:
    """去除目录名后缀（成品/作品/合集）作为默认分类（如 安吉成品 -> 安吉）"""
    cat = folder_name.strip()
    if cat.startswith("已发送0次"):
        return ""
    for suffix in ("成品", "作品", "合集"):
        if cat.endswith(suffix) and len(cat) > len(suffix):
            cat = cat[:-len(suffix)].strip()
            break
    return cat



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


# DSH-117：扫描期被跳过的异常目录留痕（Debug 回传铁律：错误路径必须有诊断日志）。
# 此前「一个目录炸掉 ⇒ 整轮扫描被外层 try 吞掉」是**零报错**的，用户只会看到
# 「作品少了」却查不到任何线索。这里保留最近 50 条，并由 /api/online/status 外抛。
_SCAN_ERRORS: "deque" = None  # 延迟到下方 import 完成后初始化


def _get_portfolio_move_logs_dir(root_dir: str) -> str:
    # 严格遵守规范：所有操作与移动流水日志一律归集到全局运行数据目录，严禁污染成品库！
    runtime_dir = r"D:\AICode\运行数据\江湖有旅人\portfolio_move_logs"
    os.makedirs(runtime_dir, exist_ok=True)
    return runtime_dir

def _get_device_usage_log_file(root_dir: str) -> str:
    runtime_dir = r"D:\AICode\运行数据\江湖有旅人"
    os.makedirs(runtime_dir, exist_ok=True)
    return os.path.join(runtime_dir, "device-usage-log.csv")

def _scan_error(tag: str, path: str, exc: "Exception") -> None:
    """记录一次被跳过的扫描异常（有界，不增长、不落盘、不阻塞）。"""
    global _SCAN_ERRORS
    try:
        if _SCAN_ERRORS is None:
            from collections import deque as _dq
            _SCAN_ERRORS = _dq(maxlen=50)
        _SCAN_ERRORS.append({
            "t": int(time.time()),
            "tag": tag,
            "path": str(path)[:300],
            "err": "%s: %s" % (type(exc).__name__, exc),
        })
    except Exception:
        pass
    try:
        print("[Scan] 跳过异常目录 [%s] %s -> %s: %s" % (tag, path, type(exc).__name__, exc))
    except Exception:
        pass


def scan_errors() -> Dict[str, Any]:
    """供 /api/online/status 外抛：一眼看出扫描链路有没有在静默吞异常。"""
    items = list(_SCAN_ERRORS) if _SCAN_ERRORS else []
    return {"count": len(items), "recent": items[-10:]}


class WorkScanner:
    """负责扫描成品库「可发布」作品与元数据。

    注意：真正的在线相册列表只包含「已发送0次（抖音小红书可发）」与根目录直出成品；
    「已发送1次」「已发送2次」「_垃圾作品」都被 IGNORED_NAMES 排除，只能通过
    在线回收站接口（list_stage_works）按阶段库单独读取。
    """

    # 三个阶段库的物理目录名（与成品库实际结构一致）
    STAGE0_FOLDER = "已发送0次（抖音小红书可发）"
    STAGE1_FOLDER = "_已发送1次（微信公众号可发）"
    GARBAGE_FOLDER = "_垃圾作品样本"
    # 需要下钻一层的中间目录前缀（作品集/游戏类会在阶段库里再套一层）
    NESTED_PREFIXES = ("作品集", "团建游戏", "游戏", "游戏类")

    def __init__(self, root: str):
        self.root = os.path.abspath(root)
        # 【DSH-117】必须是**可重入锁**：get_work() 已经持有 _lock 时内部会再调
        # scan()，而 scan() 开头又要拿同一把锁 —— 普通 Lock 非重入 ⇒ 自死锁。
        # 现场：服务刚起、缓存还没建时，手机端点开任意一个作品详情，
        # 整个 45835 端口就此永久卡死（不报错、不超时、线程数只增不减）。
        self._lock = threading.RLock()
        self._cached_works: List[Dict[str, Any]] = []
        self._works_by_id: Dict[str, Dict[str, Any]] = {}
        self._moved_works: Dict[str, Dict[str, Any]] = {}
        self._last_scan_time = 0.0
        # DSH-117：HTTP 入口 refresh=1 的强制扫描限速（FORCE_SCAN_MIN_INTERVAL 秒一次）
        self._last_force_scan = 0.0
        self._force_scan_throttled = 0
        # 在线回收站各 Tab 的列表缓存：{folder: (时间戳, 作品列表)}
        # 阶段库作品数可达 500+，每条都要读文案文件，不缓存的话手机每次切 Tab 都要等 1.5s+
        self._stage_cache: Dict[str, Any] = {}
        # 单作品目录级 mtime+size 极速增量缓存：避免每次 force scan 重复跑 414 万次正则与磁盘递归
        self._inspect_cache: Dict[Tuple[str, str, str], Tuple[Any, Dict[str, Any], Dict[str, Any]]] = {}
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

    def _resolve_source_compare_info(
        self, dir_path: str, images: List[str], manifest_data: Dict[str, Any]
    ) -> Tuple[List[str], List[str], Dict[str, str], float, str]:
        """解析作品每页对应的原素材对比图（供手机端与电脑端「对比视图」同框展示）。"""
        source_images: List[str] = []
        source_names: List[str] = []
        source_lookup: Dict[str, str] = {}

        if not isinstance(manifest_data, dict):
            manifest_data = {}

        page_map = manifest_data.get("pageMap") if isinstance(manifest_data.get("pageMap"), list) else []
        src_root = str(
            manifest_data.get("sourceMaterialPath")
            or manifest_data.get("rawMaterialPath")
            or ""
        ).strip()

        pm_by_out: Dict[str, Dict[str, Any]] = {}
        for item in page_map:
            if isinstance(item, dict):
                out_k = os.path.basename(str(item.get("output") or "").strip().replace("\\", "/"))
                if out_k:
                    pm_by_out[out_k] = item

        src_dir_imgs: List[str] = []
        if not page_map and src_root and os.path.isdir(src_root):
            try:
                for fn in sorted(os.listdir(src_root)):
                    if os.path.splitext(fn)[1].lower() in IMAGE_EXTENSIONS:
                        full_s = os.path.join(src_root, fn)
                        if os.path.isfile(full_s):
                            src_dir_imgs.append(full_s)
            except Exception:
                pass

        for idx, img_rel in enumerate(images):
            out_bn = os.path.basename(str(img_rel).replace("\\", "/"))
            pm = pm_by_out.get(out_bn)
            if not pm and idx < len(page_map) and isinstance(page_map[idx], dict):
                pm = page_map[idx]

            src_phys = ""
            if pm:
                sp = str(pm.get("sourcePath") or "").strip()
                if sp and os.path.isfile(sp):
                    src_phys = sp
                elif src_root and pm.get("sourceImage"):
                    cand = os.path.join(src_root, str(pm.get("sourceImage")).strip())
                    if os.path.isfile(cand):
                        src_phys = cand
            elif idx < len(src_dir_imgs):
                src_phys = src_dir_imgs[idx]

            if src_phys:
                token = f"__src__:{out_bn}"
                source_images.append(token)
                source_names.append(os.path.basename(src_phys))
                source_lookup[token] = src_phys
                source_lookup[out_bn] = src_phys
            else:
                source_images.append("")
                source_names.append("")

        sim_audit = (
            manifest_data.get("similarityAudit")
            if isinstance(manifest_data.get("similarityAudit"), dict)
            else {}
        )
        try:
            max_sim = round(float(sim_audit.get("maxSimilarity") or 0.0), 4)
        except Exception:
            max_sim = 0.0
        sim_tag = str(sim_audit.get("warningTag") or "").strip()

        return source_images, source_names, source_lookup, max_sim, sim_tag

    def resolve_image_for_work(self, work: Dict[str, Any], file_name: str) -> Optional[str]:
        """严格在指定作品目录及其子目录内解析图片路径，绝对物理隔离，彻底杜绝跨作品串图。"""
        if not work:
            return None
        work_path = work.get("path") or ""
        if not work_path or not os.path.exists(work_path):
            return None

        raw_f = (file_name or "").strip()
        if raw_f.startswith("__src__:"):
            target_out = raw_f[len("__src__:"):].strip().replace("\\", "/").rsplit("/", 1)[-1]
            lookup = work.get("_sourceLookup") or {}
            hit = lookup.get(raw_f) or lookup.get(target_out)
            if hit and os.path.isfile(hit):
                return os.path.realpath(hit)
            mf = os.path.join(work_path, "manifest.json")
            if os.path.isfile(mf):
                try:
                    with open(mf, "r", encoding="utf-8", errors="ignore") as fp:
                        mdata = json.load(fp)
                    _, _, dyn_lookup, _, _ = self._resolve_source_compare_info(
                        work_path, work.get("images") or [], mdata
                    )
                    hit = dyn_lookup.get(raw_f) or dyn_lookup.get(target_out)
                    if hit and os.path.isfile(hit):
                        return os.path.realpath(hit)
                except Exception:
                    pass
            return None

        clean_file = raw_f.replace("\\", "/")
        bn = clean_file.rsplit("/", 1)[-1]
        if not bn:
            return None

        # 1. 尝试直接作品根目录下寻找 bn (例如 "P1.png")
        cand = os.path.join(work_path, bn)
        if os.path.isfile(cand):
            return os.path.realpath(cand)

        # 2. 尝试标准产线素材子目录（IMAGE_SUBDIR_FALLBACKS，如 "产出素材/P1.png"）
        for subdir in IMAGE_SUBDIR_FALLBACKS:
            cand = os.path.join(work_path, subdir, bn)
            if os.path.isfile(cand):
                return os.path.realpath(cand)

        # 3. 常见其他素材子目录
        for subdir in ("素材", "images", "output", "imgs"):
            cand = os.path.join(work_path, subdir, bn)
            if os.path.isfile(cand):
                return os.path.realpath(cand)

        # 4. 如果 clean_file 中包含作品目录名之后的子相对路径（如 ".../20260928.../产出素材/P1.png"）
        work_name = os.path.basename(work_path)
        if work_name in clean_file:
            rel = clean_file.split(work_name, 1)[-1].lstrip("/\\")
            cand = os.path.join(work_path, rel)
            if os.path.isfile(cand):
                return os.path.realpath(cand)

        # 5. 如果 clean_file 直接是作品内相对路径（如 "产出素材/P1.png"）
        cand = os.path.join(work_path, clean_file)
        if os.path.isfile(cand):
            return os.path.realpath(cand)

        # 6. 对照 work["images"] 列表中的相对/绝对路径
        for img_entry in (work.get("images") or []):
            entry_clean = str(img_entry).replace("\\", "/")
            if entry_clean.endswith("/" + bn) or entry_clean == bn:
                cand = os.path.join(work_path, entry_clean)
                if os.path.isfile(cand):
                    return os.path.realpath(cand)
                cand_root = os.path.join(self.root, entry_clean)
                if os.path.isfile(cand_root):
                    return os.path.realpath(cand_root)

        # 7. 作品私有目录极速递归搜索（单作品通常 < 30 文件，os.walk 毫秒级命中且绝对杜绝跨作品逃逸）
        try:
            for root_dir, _, files in os.walk(work_path):
                if bn in files:
                    cand = os.path.join(root_dir, bn)
                    if os.path.isfile(cand):
                        return os.path.realpath(cand)
        except Exception:
            pass

        return None

    def resolve_image_path(self, raw: str) -> Optional[str]:
        """把客户端传来的图片定位信息解析成本机绝对路径。

        支持三种形态：
        1. 绝对路径（必须在成品库根目录内，防目录穿越）；
        2. 相对成品库根目录的路径（如「_已发送1次.../xxx/产出素材/P1.png」）；
        3. 移库前相对路径自动智能重映射（如客户端请求「莫干山成品/xxx/P1.png」，但作品已被移入「_已发送1次」）；
        4. 全库唯一文件名索引兜底（碰撞同名如 P1.png / P2.png 严禁盲目首命中，杜绝串图）。

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

        # 关键加固：如果 raw 不存在（典型于作品被移库，如从 莫干山成品 移到 _已发送1次）
        # 尝试从路径中识别出作品 ID / 目录名，并在该作品的最新物理路径下解析
        parts = raw.strip("/").split("/")
        if len(parts) >= 2:
            for part in reversed(parts[:-1]):
                matched_work = self.get_work(part) or self.resolve_stage_work(part)
                if matched_work:
                    hit = self.resolve_image_for_work(matched_work, raw)
                    if hit and os.path.isfile(hit):
                        return hit

        # 作品库完整相对路径索引直接命中：
        hit = self.image_name_index().get(raw)
        if hit and os.path.isfile(hit):
            return hit

        # 兜底到 basename（仅当 index 中该 basename 唯一无冲突时）
        if "/" in raw:
            bn = raw.rsplit("/", 1)[-1]
            hit = self.image_name_index().get(bn)
            if hit and os.path.isfile(hit):
                return hit
        return None

    def prune_moved_works(self, limit: Optional[int] = None) -> int:
        """清掉 _moved_works 里的陈旧条目，并把总量压回上限以内。返回清掉几条。

        【DSH-128】_moved_works 是「作品被移走之后还能按 id 找回原图」的登记表，
        但它在 __init__ 之后**只增不减**：扫阶段目录、按 id 定位、作品被移走都在往里
        塞，只有 restore 回「已发送0次」时 pop 一条。服务是 7x24 常驻进程 ⇒
        这个字典单调增长；而每条存的是**完整作品对象**（含 copyText 与 images），
        单条几 KB 到十几 KB，跑几个月就是上百 MB 常驻内存，**且没有任何报错**。

        ⛔ 判据只能用「路径还在不在」，**绝不能**按「这次 scan 的结果里有没有」来删：
           scan 只扫「已发送0次」，而这里登记的绝大多数是「已发送1次」/「垃圾作品」
           里的作品 —— 按 scan 结果删会当场误删一大片，把「移走后取原图」的兜底干掉。
           路径都没了的条目本来就救不了任何请求（get_work / image_name_index 里
           也都有 exists 校验），留着只是白占内存。

        limit 做成可注入参数，是为了让测试不必造上千个目录；生产一律用默认值。
        """
        cap = MOVED_WORKS_LIMIT if limit is None else limit
        with self._lock:
            stale = [key for key, work in self._moved_works.items()
                     if not os.path.exists(work.get("path", ""))]
            for key in stale:
                self._moved_works.pop(key, None)

            removed = len(stale)
            extra = len(self._moved_works) - cap
            if extra > 0:
                # 超限时按「先登记的先走」：dict 保序（Python 3.7+），
                # 最早登记的通常也是最久没被用到的
                for key in list(self._moved_works.keys())[:extra]:
                    self._moved_works.pop(key, None)
                removed += extra
            return removed

    def image_name_index(self, rebuild: bool = False) -> Dict[str, str]:
        """构建「文件名 -> 绝对路径」索引（只读快照，供路径兜底解析用）。

        数据源是已扫描到的作品表（_works_by_id + _moved_works）。
        重要防串图策略：
        对于全局重名/通用文件名（如 P1.png、P2.png、01.png 等），严禁盲目首命中覆盖！
        发生重名的 basename 一律剔除索引，避免不同作品间串图。
        """
        with self._lock:
            if self._image_name_index is None or rebuild:
                idx: Dict[str, str] = {}
                bn_seen: Dict[str, str] = {}
                bn_duplicates: Set[str] = set()

                for w in list(self._works_by_id.values()) + list(self._moved_works.values()):
                    base = w.get("path") or ""
                    if not base:
                        continue
                    for fn in (w.get("images") or []):
                        fp = os.path.join(base, fn)
                        if not os.path.isfile(fp):
                            alt = os.path.join(self.root, fn)
                            if os.path.isfile(alt):
                                fp = alt
                        if not os.path.isfile(fp):
                            continue
                        if fn not in idx:
                            idx[fn] = fp
                        bn = fn.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
                        if bn:
                            if bn in bn_seen and bn_seen[bn] != fp:
                                bn_duplicates.add(bn)
                            else:
                                bn_seen[bn] = fp

                # 仅将全局唯一的 basename 加入索引；重名的 basename 严禁注入，彻底斩断串图通道
                for bn, fp in bn_seen.items():
                    if bn not in bn_duplicates and bn not in idx:
                        idx[bn] = fp

                self._image_name_index = idx
            return dict(self._image_name_index)

    def get_work(self, work_id: str) -> Optional[Dict[str, Any]]:
        # 极速快路（无锁读取内存字典，0ms 避免并发阻塞 /api/online/image）
        if self._works_by_id:
            work = self._works_by_id.get(work_id)
            if work and os.path.exists(work.get("path", "")):
                return work
            if "__link_" in work_id:
                base_id = work_id.split("__link_")[0]
                base_work = self._works_by_id.get(base_id)
                if base_work and os.path.exists(base_work.get("path", "")):
                    return base_work
            if work_id in self._moved_works:
                mw = self._moved_works[work_id]
                if os.path.exists(mw.get("path", "")):
                    return mw

        with self._lock:
            if not self._works_by_id:
                self.scan()
            work = self._works_by_id.get(work_id)
            if work and os.path.exists(work.get("path", "")):
                return work
            # 兼容软链接镜像 ID 反查或本体 ID 查找
            if "__link_" in work_id:
                base_id = work_id.split("__link_")[0]
                base_work = self._works_by_id.get(base_id)
                if base_work and os.path.exists(base_work.get("path", "")):
                    return base_work
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

    def snapshot_count(self) -> int:
        """只读计数：非阻塞毫秒级返回，绝不阻塞在全盘扫描锁上。"""
        cached = self._cached_works
        if cached:
            return len(cached)
        return 0

    def scan_throttled(self, force: bool = False) -> List[Dict[str, Any]]:
        """HTTP 入口专用：把 refresh=1 的强制全盘扫描限速到 FORCE_SCAN_MIN_INTERVAL 秒一次。

        只包 HTTP 入口、不包 scan() 本身，是为了让单测里连续 scan(force=True)
        仍然是「真的重扫」—— 限速是**入口流量整形**，不是扫描语义。
        """
        if force:
            now = time.time()
            with self._lock:
                if self._last_force_scan and (now - self._last_force_scan < FORCE_SCAN_MIN_INTERVAL):
                    self._force_scan_throttled += 1
                    force = False
                else:
                    self._last_force_scan = now
        return self.scan(force=force)

    def scan(self, force: bool = False) -> List[Dict[str, Any]]:
        now = time.time()
        if not force and self._cached_works and (now - self._last_scan_time < SCAN_CACHE_TTL):
            return self._cached_works
        with self._lock:
            if force:
                # 【2026-09-21 修复】force 的语义是「磁盘已变，缓存一律作废」。
                # 此前只清 _cached_works / _last_scan_time，漏了 _stage_cache
                # （回收站两个 Tab 的 5 秒缓存）⇒ 手机端点「重置」后立刻重拉列表，
                # 仍拿到 useCount=1 的旧值，表现为「重置失败」（5 秒后又自己好）。
                self._stage_cache.clear()
            if not force and self._cached_works and (now - self._last_scan_time < SCAN_CACHE_TTL):
                return self._cached_works

            # 自愈扫描：自动清理失效死链软链接 (DSH Junction Healer)，每 300s 触发一次
            if (now - getattr(self, "_last_healer_time", 0) > 300):
                self._last_healer_time = now
                try:
                    prune_dangling_junctions(self.root)
                except Exception:
                    pass

            results = []
            seen_ids = set()
            _slot_guard_reset()   # 槽位守卫计数按「本轮扫描」统计，避免累积值误导

            IGNORED_NAMES = {
                "已发送1次（微信公众号可发）", "_已发送1次（微信公众号可发）",
                "_已发送一次", "已发送2次（其他平台可发）", "_已发送2次（其他平台可发）",
                "已废弃-负面样本库", "归档", "不合格成品", "temp", "cache", "scripts",
                "_portfolio_backup", "_portfolio_move_logs", "_不合格成品合集", "_作品历史数据",
                "_制作中", "_待补全_单封面作品集", "_测试验收", "_生产计划与排产参考", "_重复待处理",
                "_垃圾作品样本",
                "发布空间", "待制作待补全", "抖音小红书"
            }
            try:
                root_entries = os.listdir(self.root)
            except Exception as _e:
                _scan_error("root-listdir", self.root, _e)
                root_entries = []

            for entry in root_entries:
                if entry.startswith(".") or entry.startswith("_") or entry in IGNORED_NAMES:
                    continue
                full_path = os.path.join(self.root, entry)
                if not os.path.isdir(full_path):
                    continue

                # 兼容根目录下直接出图的单个作品
                try:
                    direct_work = self._inspect_work_dir(full_path, entry, "待首发", 0)
                except Exception as _e:
                    _scan_error("root-entry", full_path, _e)
                    direct_work = None

                if direct_work:
                    if direct_work["id"] not in seen_ids:
                        seen_ids.add(direct_work["id"])
                        results.append(direct_work)
                    continue

                # 扫描各分类目录（如 安吉成品、莫干山成品、杭州成品、中秋国庆成品、团建游戏成品、综合与其它城市 等）
                default_cat = extract_category_from_folder_name(entry)
                try:
                    sub_entries = os.listdir(full_path)
                except Exception as _e:
                    _scan_error("cat-dir-list", full_path, _e)
                    continue

                for sub in sub_entries:
                    if sub.startswith(".") or sub.startswith("_"):
                        continue
                    sub_path = os.path.join(full_path, sub)
                    if not os.path.isdir(sub_path):
                        continue

                    try:
                        work = self._inspect_work_dir(sub_path, sub, "待首发", 0, default_category=default_cat)
                    except Exception as _e:
                        _scan_error("sub-entry", sub_path, _e)
                        work = None

                    if work:
                        if work["id"] not in seen_ids:
                            seen_ids.add(work["id"])
                            results.append(work)
                    else:
                        # 兼容作品集子目录或嵌套目录（如 作品集_099 等）
                        if sub.startswith("作品集") or sub in ("团建游戏", "游戏", "游戏类"):
                            try:
                                child_entries = os.listdir(sub_path)
                                for child in child_entries:
                                    if child.startswith(".") or child.startswith("_"):
                                        continue
                                    child_path = os.path.join(sub_path, child)
                                    if not os.path.isdir(child_path):
                                        continue
                                    try:
                                        c_work = self._inspect_work_dir(child_path, child, "待首发", 0, default_category=default_cat)
                                    except Exception as _ce:
                                        _scan_error("child-entry", child_path, _ce)
                                        c_work = None
                                    if c_work and c_work["id"] not in seen_ids:
                                        seen_ids.add(c_work["id"])
                                        results.append(c_work)
                            except Exception:
                                pass

            # 按时间倒序（标题通常带时间戳）
            results.sort(key=lambda w: w.get("title", ""), reverse=True)
            self._cached_works = results
            self._works_by_id = {w["id"]: w for w in results}
            self._last_scan_time = now
            # DSH-128：_moved_works 只增不减，趁每轮**真的**全盘扫描顺手清一次。
            # 挂在这里而不是缓存命中的提前返回路径上：清理要 stat 每一条，
            # 没必要在「读缓存」这种高频短路径上反复跑。
            self.prune_moved_works()
            return results

    # ==================== DSH-110：文件系统轮询监听 ====================
    # 用 stdlib os.scandir + mtime 对比实现，无第三方依赖。
    # 每 60 秒扫描一遍 self.root 下的 (path, mtime)，发现新增/删除/变更 → 触发 scan(force=True)
    # 这样 GPT API 实时产出的作品图只要落到 DEFAULT_LIBRARY_ROOT 下，
    # 最迟 60 秒内会被服务端扫到，手机端下次拉列表（refresh=1 或自动重扫）即看到最新。

    def _walk_root_paths(self) -> Set[Tuple[str, float]]:
        """遍历 self.root 下所有分类目录与作品目录的 (path, mtime)，跳过 ./_ 开头目录。"""
        out: Set[Tuple[str, float]] = set()
        if not os.path.isdir(self.root):
            return out

        def _safe_mtime(p: str) -> float:
            try:
                return os.path.getmtime(p)
            except (OSError, PermissionError):
                return 0.0

        IGNORED_NAMES = {
            "已发送1次（微信公众号可发）", "_已发送1次（微信公众号可发）",
            "_已发送一次", "已发送2次（其他平台可发）", "_已发送2次（其他平台可发）",
            "已废弃-负面样本库", "归档", "不合格成品", "temp", "cache", "scripts",
            "_portfolio_backup", "_portfolio_move_logs", "_不合格成品合集", "_作品历史数据",
            "_制作中", "_待补全_单封面作品集", "_测试验收", "_生产计划与排产参考", "_重复待处理",
            "_垃圾作品样本",
            "发布空间", "待制作待补全", "抖音小红书"
        }

        # 1. 扫描所有非 . 非 _ 开头的目录及根目录直出作品
        try:
            for entry in os.listdir(self.root):
                if entry.startswith(".") or entry.startswith("_") or entry in IGNORED_NAMES:
                    continue
                full = os.path.join(self.root, entry)
                if not os.path.isdir(full):
                    continue
                out.add((full, _safe_mtime(full)))
                # 遍历分类目录下的作品或中间层
                try:
                    for sub in os.listdir(full):
                        if sub.startswith(".") or sub.startswith("_"):
                            continue
                        sub_full = os.path.join(full, sub)
                        if os.path.isdir(sub_full):
                            out.add((sub_full, _safe_mtime(sub_full)))
                            # 如果是中间层（作品集/游戏等）再下钻一层
                            if sub.startswith("作品集") or sub in ("团建游戏", "游戏", "游戏类"):
                                try:
                                    for child in os.listdir(sub_full):
                                        if child.startswith(".") or child.startswith("_"):
                                            continue
                                        child_full = os.path.join(sub_full, child)
                                        if os.path.isdir(child_full):
                                            out.add((child_full, _safe_mtime(child_full)))
                                except (OSError, PermissionError):
                                    pass
                except (OSError, PermissionError):
                    pass
        except (OSError, PermissionError):
            pass

        # 2. 「_已发送1次」回收站（监控其变更以便回收站界面即时同步）
        stage1 = os.path.join(self.root, self.STAGE1_FOLDER)
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

        # 上帝视角加固：只有真实发生作品文件夹新增或删除时才触发，绝不因单纯的 mtime 变动频繁全盘重扫
        has_structural_change = bool(new_paths or deleted_paths)
        self._watchdog_paths = current
        self._watchdog_last_poll = now

        if has_structural_change:
            # 防抖与冷却：避免高频连续全盘重扫拖死手机端
            if now - self._last_scan_time > 120.0:
                self._watchdog_last_change = now
                try:
                    self.scan(force=True)
                    print(f"[DSH-110 watchdog] 检测到货架结构变更 → force scan: +{len(new_paths)} / -{len(deleted_paths)}")
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
            # 启动后等 5 秒再做第一次轮询（让首次 force scan 先完成）
            time.sleep(5.0)
            while not self._watchdog_stop.is_set():
                # DSH-140: 每次轮询优先主动巡检货架上到期的作品并自动移入回收站
                try:
                    self.cleanup_expired_works()
                except Exception as e:
                    print(f"[DSH-140 watchdog] 到期巡检异常：{e!r}")
                try:
                    self._poll_diff()
                except Exception as e:
                    print(f"[DSH-110 watchdog] 轮询异常：{e!r}")
                # DSH-140: 改为每 30 秒轮询一次，保障作品到期后快速自动下架
                if self._watchdog_stop.wait(30.0):
                    break
            self._watchdog_active = False

        self._watchdog_thread = threading.Thread(target=_run, name="DSH110-watchdog", daemon=True)
        self._watchdog_thread.start()
        print(f"[DSH-110 watchdog] 启动完成，每 30s 轮询一次")

    def stop_watchdog(self) -> None:
        self._watchdog_stop.set()
        if self._watchdog_thread is not None:
            self._watchdog_thread.join(timeout=3.0)

    def watchdog_status(self) -> Dict[str, Any]:
        """暴露给 /api/online/status：监控线程状态 + 上次轮询/变更时间。"""
        return {
            "active": self._watchdog_active,
            "intervalSec": 30.0,
            "lastPollAt": self._watchdog_last_poll,
            "lastChangeAt": self._watchdog_last_change,
            "trackedPaths": len(self._watchdog_paths),
            "rootDir": self.root,
        }

    def move_work_to_stage1(self, target_work: Dict[str, Any], device_name: str = "系统自动守护", action_type: str = "dispatched") -> Tuple[bool, str, str]:
        """DSH-140: 将作品从货架目录物理移入「_已发送1次（微信公众号可发）」。"""
        src_path = target_work.get("path", "")
        if not src_path or not os.path.exists(src_path):
            return False, "", "原作品路径不存在"
        folder_name = os.path.basename(src_path)
        use_count = target_work.get("useCount", 0)
        work_id = target_work.get("id", "")

        root_dir = self.root
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
        self._moved_works[work_id] = target_work

        log_dir = _get_portfolio_move_logs_dir(root_dir)
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

    def cleanup_expired_works(self) -> List[Dict[str, Any]]:
        """DSH-140: 主动巡检货架上到期未下架的作品，自动移入回收站并清理软链接镜像。"""
        now_ms = int(time.time() * 1000)
        cleaned: List[Dict[str, Any]] = []
        with self._lock:
            works_to_check = list(self._cached_works)

        for work in works_to_check:
            expire_at = int(work.get("expireAtMs") or 0)
            if expire_at > 0 and now_ms >= expire_at:
                work_id = work.get("id", "")
                work_path = work.get("path", "")
                is_link = work.get("is_symlink", False) or is_junction_or_symlink(work_path)

                if is_link:
                    unlink_ok, unlink_msg = unlink_junction(work_path, self.root)
                    cleaned.append({
                        "workId": work_id,
                        "type": "symlink_unlinked",
                        "path": work_path,
                        "message": unlink_msg
                    })
                    continue

                ok, dest_path, msg = self.move_work_to_stage1(work, "系统自动到期守护", "expired_auto_clean")
                if ok:
                    try:
                        cleanup_junctions_for_source(work_path, self.root)
                    except Exception as ce:
                        print(f"[Warn] 清理关联软链接失败: {ce}")
                    cleaned.append({
                        "workId": work_id,
                        "type": "moved_to_stage1",
                        "path": dest_path,
                        "message": msg
                    })
                    print(f"[DSH-140] 作品倒计时已到期，系统守护已自动移入「_已发送1次」: {work_id} -> {dest_path}")

        if cleaned:
            self.invalidate_stage_cache()
            self.scan(force=True)

        return cleaned

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

    def _inspect_work_dir(self, dir_path: str, folder_name: str, stage_name: str, default_count: int, default_category: str = "") -> Optional[Dict[str, Any]]:
        # Junction / 软链接检测与解析
        is_symlink = False
        source_path = dir_path
        if is_junction_or_symlink(dir_path):
            real_src = resolve_junction_source(dir_path)
            if not real_src or not os.path.exists(real_src):
                # 死链软链接，跳过展示（后续自愈例程会自动清理）
                return None
            is_symlink = True
            source_path = real_src

        try:
            files = os.listdir(dir_path)
        except Exception:
            return None

        images = self._collect_images(dir_path, files)
        if not images:
            return None

        images.sort()

        def _f_stamp(p: str) -> Tuple[int, int]:
            try:
                st = os.stat(p)
                return (st.st_mtime_ns, st.st_size)
            except OSError:
                return (0, 0)

        txt_candidates = [f for f in files if f.lower().endswith(".txt")]
        cache_key = (dir_path, stage_name, default_category)
        dir_stamp = (
            default_count,
            _f_stamp(dir_path)[0],
            tuple(images),
            tuple((f, _f_stamp(os.path.join(dir_path, f))) for f in txt_candidates),
            _f_stamp(os.path.join(dir_path, "作品标签.json")),
            _f_stamp(os.path.join(dir_path, "manifest.json")),
        )
        cached_entry = self._inspect_cache.get(cache_key)
        if cached_entry is not None and cached_entry[0] == dir_stamp:
            _, cached_work, cached_slot_diag = cached_entry
            if cached_slot_diag.get("droppedCount"):
                _slot_guard_stat("worksAffected")
                _slot_guard_stat("slotsDropped", cached_slot_diag["droppedCount"])
            return dict(cached_work)

        # 读取下发文案：【唯一真源 = 文案.txt】（2026-09-22 用户口径）
        # 「软件只识别 文案.txt」—— `三平台文案.txt` 是早期 Codex 产线遗留，不是它的改名版；
        # 实测库内 145 套两份都有、其中 32 套内容并不相同，按旧 priority 优先读它
        # ⇒ 手机端会显示一份未经确认的历史副本。故下发只认 文案.txt；
        # 其余历史 txt（三平台文案 / 小红书文案 / 全量生成记录…）仅供服务端关键词检索，不参与下发。
        copy_text = ""
        copy_search_blob = ""   # 搜索专用：即使判定为缺失也保留原文，避免空壳/薄文案失去关键词可检索性
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

        # 读取作品标签.json 与 manifest.json（双源合并兜底，直接复用已加载的 manifest_data）
        tag_file = os.path.join(dir_path, "作品标签.json")
        tag_data = {}
        if os.path.exists(tag_file):
            try:
                with open(tag_file, "r", encoding="utf-8", errors="ignore") as fp:
                    tag_data = json.load(fp)
            except Exception:
                pass

        manifest_dist = manifest_data.get("distribution", {}) if isinstance(manifest_data, dict) else {}

        distribution = tag_data.get("distribution", {})
        if not distribution and manifest_dist:
            distribution = dict(manifest_dist)
        elif distribution and manifest_dist:
            # 若作品标签已显式重置为 useCount == 0，则以作品标签为准，绝不把 manifest 旧残留合并回来
            if distribution.get("useCount") == 0 and not distribution.get("dispatchedTo") and not distribution.get("dispatchedVersions"):
                pass
            else:
                # 并集合并 dispatchedVersions 和 dispatchedTo，确保双源互补零遗漏
                d_vers = list(dict.fromkeys((distribution.get("dispatchedVersions") or []) + (manifest_dist.get("dispatchedVersions") or [])))
                d_to = list(dict.fromkeys((distribution.get("dispatchedTo") or []) + (manifest_dist.get("dispatchedTo") or [])))
                distribution["dispatchedVersions"] = d_vers
                distribution["dispatchedTo"] = d_to
                if not distribution.get("useCount") and manifest_dist.get("useCount"):
                    distribution["useCount"] = manifest_dist["useCount"]

        # 发送次数判定：优先从作品标签读取，其次以目录默认阶数为基准
        use_count = distribution.get("useCount")
        if use_count is None:
            dispatched = distribution.get("dispatchedTo", [])
            use_count = len(dispatched) if dispatched else default_count
        use_count_int = int(use_count or 0)

        if default_category:
            destination = default_category
        else:
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

        # 针对软链接镜像，生成独立 workId 避免与主货架本体在前端排重时冲突
        work_id = f"{folder_name}__link_{default_category}" if is_symlink and default_category else folder_name

        # 解析原素材对比映射与相似度审计指标（供手机端与电脑端「对比视图」同框展示）
        src_imgs, src_names, src_lookup, max_sim, sim_tag = self._resolve_source_compare_info(
            dir_path, images, manifest_data
        )

        work_result = {
            "id": work_id,
            "title": clean_title,
            "rawTitle": folder_name,
            "destination": destination,
            "stage": stage_name,
            "path": dir_path,
            "is_symlink": is_symlink,
            "source_path": source_path,
            "shelf": default_category or os.path.basename(os.path.dirname(dir_path)),
            "useCount": use_count_int,
            "maxUses": 2,
            "used": bool(use_count_int > 0),
            "remainingUses": max(0, 2 - use_count_int),
            "statusLabel": "已使用" if use_count_int > 0 else "",
            "dispatchedTo": (distribution.get("dispatchedTo") or []) if use_count_int > 0 else [],
            "firstSharedAtMs": int(distribution.get("firstSharedAtMs") or 0) if use_count_int > 0 else 0,
            "expireAtMs": int(distribution.get("expireAtMs") or 0) if use_count_int > 0 else 0,
            "originDevice": str(distribution.get("originDevice") or "") if use_count_int > 0 else "",
            "dispatchedVersions": (distribution.get("dispatchedVersions") or []) if use_count_int > 0 else [],
            "imageCount": len(images),
            "images": images,
            "sourceImages": src_imgs,
            "sourceNames": src_names,
            "hasSourceCompare": any(bool(x) for x in src_imgs),
            "maxSimilarity": max_sim,
            "similarityTag": sim_tag,
            "_sourceLookup": src_lookup,
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
            # 标签与时令元数据 (季节 season / 流量类型 flowType / 完整标签 tags / 生产溯源与精品标杆)
            "season": manifest_data.get("season") or ("秋季" if any(k in folder_name for k in ["秋", "中秋", "国庆"]) else ("夏季" if any(k in folder_name for k in ["夏", "避暑", "溯溪"]) else ("冬季" if any(k in folder_name for k in ["冬", "年会", "滑雪", "温泉"]) else "四季通用"))),
            "flowType": manifest_data.get("flowType") or ("泛流量游戏攻略" if any(k in folder_name for k in ["游戏", "桌游", "破冰", "冷场"]) else "精准流量团建"),
            "tags": manifest_data.get("tags") or [],
            "pipeline": manifest_data.get("pipeline") or ("CDP双浏览器产线" if "CDP" in folder_name else ("Codex-API产线" if "Codex" in folder_name else "GPT扩展+本地脚本产线")),
            "workflowVersion": manifest_data.get("workflowVersion") or manifest_data.get("packagerVersion") or ("gpt-ext-v2.0" if manifest_data.get("recordType") == "gpt_work_package" else "v2.0"),
            "productionMode": manifest_data.get("productionMode") or manifest_data.get("apiSubMode") or ("reuse-conversation" if manifest_data.get("conversationUrl") else "1_shuffle"),
            "productionModeName": manifest_data.get("productionModeName") or ("会话母版复刻模式" if manifest_data.get("conversationUrl") else "原图复刻打乱模式"),
            "templateId": manifest_data.get("templateId") or manifest_data.get("template") or ("会话锁定母版" if manifest_data.get("conversationUrl") else "原图自身构图"),
            "producedAt": manifest_data.get("producedAt") or manifest_data.get("completedAt") or manifest_data.get("created_at") or manifest_data.get("createdAt") or (str(manifest_data.get("completedAtUtc") or "")[:19].replace("T", " ") if manifest_data.get("completedAtUtc") else ""),
            "accountName": manifest_data.get("accountName") or manifest_data.get("account") or "",
            "conversationUrl": manifest_data.get("conversationUrl") or "",
            "isStarred": bool(manifest_data.get("isStarred") or ("⭐精品标杆" in (manifest_data.get("tags") or []))),
            "starRemark": str(manifest_data.get("starRemark") or ""),
        }
        self._inspect_cache[cache_key] = (dir_stamp, work_result, slot_diag)
        return dict(work_result)


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


def _normalize_sync_host(raw: str) -> Optional[str]:
    """把 "192.168.1.50" / "192.168.1.50:45833" 归一化成 "ip:port"；
    非私网地址一律返回 None（DSH-117 SSRF 收敛）。

    背景：/api/online/sync-phone-counts 会让**电脑端**主动向请求里的 host 发 HTTP。
    不加校验时，任何能访问 45835 的人都能拿这台电脑当跳板去打任意地址
    （内网端口扫描 + 探测路由器/NAS/摄像头），而电脑自己毫无察觉。
    """
    s = (raw or "").strip()
    if not s or "://" in s or "/" in s:
        return None
    host, port = s, None
    if ":" in s:
        host, _, p = s.rpartition(":")
        if not p.isdigit():
            return None
        port = int(p)
        if not (1 <= port <= 65535):
            return None
    octets = host.split(".")
    if len(octets) != 4 or not all(o.isdigit() for o in octets):
        return None
    nums = [int(o) for o in octets]
    if not all(0 <= n <= 255 for n in nums):
        return None
    private = (
        nums[0] == 10
        or (nums[0] == 172 and 16 <= nums[1] <= 31)
        or (nums[0] == 192 and nums[1] == 168)
        or (nums[0] == 169 and nums[1] == 254)
    )
    if not private:
        return None
    return "%s:%d" % (host, port or phone_sync.PHONE_ALBUM_PORT)


class OnlineGalleryHandler(BaseHTTPRequestHandler):
    timeout = 15.0  # 15秒 socket 读写超时，防手机息屏/脱网后 socket 挂在 CLOSE_WAIT 耗尽工作线程
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
        try:
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        except Exception as _je:
            # 【DSH-117】目录名含代理项/非法 UTF-8 时 json.dumps 会抛异常，
            # 此前整条响应直接 500（连接被重置）⇒ 手机端「列表空白、无任何报错」。
            # 三级降级：留痕 → 未知类型 str() 化 → 最小可解析错误体。
            _scan_error("send_json", "", _je)
            try:
                body = json.dumps(data, ensure_ascii=False, default=str).encode("utf-8", "replace")
            except Exception:
                body = json.dumps(
                    {"ok": False, "error": "响应序列化失败（详见 status.scanErrors）"},
                    ensure_ascii=False).encode("utf-8")
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
            self.send_header("Connection", "close")
            self.send_cors_headers()
            self.end_headers()
            self.wfile.write(payload)
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, socket.error):
            pass
        finally:
            self.close_connection = True

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
        log_dir = _get_portfolio_move_logs_dir(self.scanner.root)
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

    @classmethod
    def _prune_phone_sync_cooldown(cls, now: float, cooldown: float) -> int:
        """DSH-129：清掉「超过 2 倍冷却期都没再出现」的 IP 条目，返回清掉几条。

        `_phone_sync_last` 原来只增不减 —— 手机 DHCP 每换一个 IP 就永久留一条。
        单条只有几十字节，但同属「没人负责往外拿」的慢变量，而且它挂在每次心跳上。

        ⚠️ 调用方必须已经持有 `_phone_sync_lock`。
        ⛔ 阈值必须是 **2 倍** 冷却期：刚好 1 倍的话，一台手机正常间隔 5 分钟来一次
           心跳，就有可能被自己上一轮的冷却判定给清掉 ⇒ 冷却失效、回读变频繁。
        """
        stale_ips = [k for k, t in cls._phone_sync_last.items()
                     if now - t > cooldown * 2]
        for k in stale_ips:
            cls._phone_sync_last.pop(k, None)
        return len(stale_ips)

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
            # DSH-129：顺手清掉过了冷却期的陈旧 IP 条目。这张表原来只增不减 ——
            # 手机 DHCP 每换一个 IP 就永久留一条。单条虽小，但同属「没人负责往外拿」，
            # 而且就在心跳路径上，一起清掉成本几乎为零。
            OnlineGalleryHandler._prune_phone_sync_cooldown(now, self.PHONE_SYNC_COOLDOWN)
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
                    log_dir=_get_portfolio_move_logs_dir(scanner.root),
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

        if path == "/api/online/ping":
            # 极速轻量探活端点：纯内存直接返回，耗时 < 1ms，不碰锁、不碰磁盘，并发完全隔离
            self.send_json(200, {"ok": True, "ping": "pong", "ts": int(time.time())})
            return

        if path == "/" or path == "/api/online/status":
            # 【DSH-117】status 是手机端几秒一次的心跳，此前每次都走全量 scan()。
            # 改为只读缓存计数（启动预热 + 60 秒 watchdog 保证缓存恒非空）。
            total_works = self.scanner.snapshot_count()
            ip = get_local_ip()
            # 手机每次联上来都顺手回读一次它的本地分享次数（后台线程，不拖慢响应）
            self._maybe_background_phone_sync(self.client_address[0] if self.client_address else "")
            data = {
                "ok": True,
                "server": "DeviceShareHub-OnlineGallery",
                "version": "1.2.0",
                "ip": ip,
                "port": self.server.server_port,
                "totalWorks": total_works,
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
                    "viewModeSync",       # 手机端与电脑端视图模式（图标/列表/对比）实时联动
                    "sourceCompare",      # 手机端原素材 vs 成品对比视图支持
                ],
                "viewState": get_gallery_view_state(),
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
                # DSH-117：扫描链路有没有在静默吞异常（count>0 就是响铃，不是噪音）
                "scanErrors": scan_errors(),
                # DSH-117：refresh=1 被限速的次数（持续飙升说明有客户端在高频重刷）
                "scanThrottle": {"minIntervalSec": FORCE_SCAN_MIN_INTERVAL,
                                 "throttled": self.scanner._force_scan_throttled},
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

        if path == "/api/online/view-state":
            self.send_json(200, {"ok": True, **get_gallery_view_state()})
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

        if path == "/api/online/cleanup-expired":
            # DSH-140: 显式触发到期作品清理接口
            cleaned = self.scanner.cleanup_expired_works()
            self.send_json(200, {
                "ok": True,
                "cleanedCount": len(cleaned),
                "cleanedWorks": cleaned,
                "timestamp": int(time.time() * 1000)
            })
            return

        if path == "/api/online/categories":
            # DSH-140: 聚合分类前快速巡检到期作品，防止已到期作品污染分类计数
            try:
                self.scanner.cleanup_expired_works()
            except Exception as _ce:
                print(f"[DSH-140] categories 巡检异常: {_ce!r}")
            works = self.scanner.scan_throttled()
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

            # 纯净分类聚合（严格 1:1 对齐本地 24 大货架目录，零 Emoji，纯净高雅）：
            # 1. 时令节日与特色专题优先置顶（中秋、国庆、中秋国庆、团建游戏）
            priority_topics = ["中秋", "国庆", "中秋国庆", "团建游戏"]
            categories = []
            seen_cats = set()
            for topic in priority_topics:
                if topic in counts and counts[topic] > 0:
                    categories.append({"name": topic, "count": counts[topic]})
                    seen_cats.add(topic)

            # 2. 其余目的地按作品数量倒序排列
            dest_categories = []
            for d, cnt in counts.items():
                if d in seen_cats or d in ("其他", ""):
                    continue
                if cnt > 0:
                    dest_categories.append({"name": d, "count": cnt})
                    seen_cats.add(d)
            dest_categories.sort(key=lambda c: -c["count"])
            categories.extend(dest_categories)

            # 3. 兜底其它
            if "其他" in counts and counts["其他"] > 0:
                categories.append({"name": "其他", "count": counts["其他"]})

            self.send_json(200, {"ok": True, "categories": categories, "stages": [], "total": len(works)})
            return

        if path == "/api/online/filter-options":
            works = self.scanner.scan_throttled()
            season_counts: Dict[str, int] = {k: 0 for k in ("春季", "夏季", "秋季", "冬季", "四季通用")}
            flow_counts: Dict[str, int] = {k: 0 for k in ("精准流量团建", "泛流量游戏攻略")}
            tag_counts: Dict[str, int] = {}
            for w in works:
                s = w.get("season") or "四季通用"
                season_counts[s] = season_counts.get(s, 0) + 1
                ft = w.get("flowType") or "精准流量团建"
                flow_counts[ft] = flow_counts.get(ft, 0) + 1
                for t in w.get("tags") or []:
                    if t not in season_counts and t not in flow_counts:
                        tag_counts[t] = tag_counts.get(t, 0) + 1
            self.send_json(200, {
                "ok": True,
                "seasons": [{"name": k, "count": season_counts.get(k, 0)} for k in ("春季", "夏季", "秋季", "冬季", "四季通用")],
                "flowTypes": [{"name": k, "count": flow_counts.get(k, 0)} for k in ("精准流量团建", "泛流量游戏攻略")],
                "tags": [{"name": k, "count": v} for k, v in sorted(tag_counts.items(), key=lambda x: -x[1])[:20]],
                "total": len(works),
            })
            return

        if path == "/api/online/works":
            # DSH-140: 拉取作品列表前快速巡检到期作品，杜绝已到期僵尸作品滞留货架
            try:
                self.scanner.cleanup_expired_works()
            except Exception as _we:
                print(f"[DSH-140] works 巡检异常: {_we!r}")
            category = query.get("category", ["全部"])[0]
            search_query = query.get("query", [""])[0].strip().strip("\"'").strip().lower()
            is_path_search = ("\\" in search_query or "/" in search_query or ":" in search_query)
            if is_path_search:
                search_query = search_query.replace("/", "\\").rstrip("\\")
            force_refresh = query.get("refresh", ["0"])[0] == "1"
            # DSH-109：排序键 = default|time_asc|time_desc|name_asc|name_desc|size_desc|size_asc
            # 默认 default 保持 DSH-104 行为（useCount desc 已用置顶），向后兼容
            sort_key = query.get("sort", ["default"])[0]
            if sort_key not in SORT_KEYS:
                sort_key = "default"

            # 多选标签筛选参数（支持逗号分隔或多参数：seasons, flow_types, tags）
            seasons_raw = ",".join(query.get("seasons", []))
            target_seasons = {s.strip() for s in seasons_raw.split(",") if s.strip()}
            flow_raw = ",".join(query.get("flow_types", []) + query.get("flowTypes", []))
            target_flows = {f.strip() for f in flow_raw.split(",") if f.strip()}
            tags_raw = ",".join(query.get("tags", []))
            target_tags = {t.strip() for t in tags_raw.split(",") if t.strip()}

            works = self.scanner.scan_throttled(force=force_refresh)
            tokens = [search_query] if is_path_search else (search_query.split() if search_query else [])

            # 手机打开在线相册（拉列表）也顺手回读一次本地分享次数
            self._maybe_background_phone_sync(self.client_address[0] if self.client_address else "")

            filtered = []
            for w in works:
                # 分类过滤（支持专题分类、游戏与地域分类，严格 1:1 匹配本地货架；若粘贴路径搜索则跨全部分类匹配）
                if category and category != "全部" and not is_path_search:
                    clean_cat = category.replace("🌕", "").replace("🇨🇳", "").replace("🎮", "").replace("🏷️", "").strip()
                    if clean_cat in ("游戏", "团建游戏"):
                        if w.get("destination") not in ("游戏", "团建游戏") and w.get("shelf") not in ("游戏", "团建游戏"):
                            continue
                    elif clean_cat == "待首发":
                        if w.get("useCount", 0) != 0:
                            continue
                    elif clean_cat in ("已发1次", "已发1"):
                        if w.get("useCount", 0) != 1:
                            continue
                    elif clean_cat in ("已发2次", "已发2", "已用满"):
                        if w.get("useCount", 0) < 2:
                            continue
                    else:
                        if (w.get("destination") != clean_cat and w.get("shelf") != clean_cat
                                and w.get("destination") != category and w.get("shelf") != category):
                            continue

                # 季节多选过滤
                if target_seasons:
                    w_season = w.get("season") or "四季通用"
                    if w_season not in target_seasons:
                        continue

                # 流量类型多选过滤
                if target_flows:
                    w_flow = w.get("flowType") or "精准流量团建"
                    if w_flow not in target_flows:
                        continue

                # 自定义标签多选过滤
                if target_tags:
                    w_tags = set(w.get("tags") or [])
                    if not target_tags.intersection(w_tags):
                        continue

                # 搜索关键词过滤（含标题、路径、ID、季节、流量类型、标签与正文）
                if tokens:
                    extra_meta = f"{w.get('title', '')} {w.get('rawTitle', '')} {w.get('path', '')} {w.get('id', '')} {w.get('season', '')} {w.get('flowType', '')} {' '.join(w.get('tags') or [])}".lower()
                    text_blob = (extra_meta + " " + work_text_blob(w, with_title=False).lower())
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
                "viewState": get_gallery_view_state(),
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
            work_id = query.get("id", [""])[0] or query.get("workId", [""])[0]
            file_name = query.get("file", [""])[0]
            raw_path = query.get("path", [""])[0]
            thumb = query.get("thumb", ["0"])[0] == "1"

            img_path = ""
            if work_id and file_name:
                # 严格物理隔离：有 work_id 时，必须且只能在目标作品范围内解析
                target_work = self.scanner.get_work(work_id)
                if not target_work:
                    target_work = self.scanner.resolve_stage_work(work_id)

                if target_work:
                    if file_name.startswith("__src__:"):
                        img_path = self.scanner.resolve_image_for_work(target_work, file_name) or ""
                    else:
                        bn = file_name.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
                        direct_p = os.path.join(target_work["path"], bn)
                        if os.path.isfile(direct_p):
                            img_path = direct_p
                        else:
                            img_path = self.scanner.resolve_image_for_work(target_work, file_name) or ""

                if not img_path and "__link_" in work_id:
                    base_id = work_id.split("__link_")[0]
                    base_work = self.scanner.get_work(base_id) or self.scanner.resolve_stage_work(base_id)
                    if base_work:
                        img_path = self.scanner.resolve_image_for_work(base_work, file_name) or ""

                # 兜底：若目标作品解析未中，尝试在 file_name 中反查
                if not img_path:
                    resolved = self.scanner.resolve_image_path(file_name)
                    # 绝对铁律：解析出的路径必须属于目标作品，严禁返回其他作品图片
                    if resolved and target_work and target_work.get("path"):
                        tw_p = os.path.realpath(target_work["path"])
                        res_p = os.path.realpath(resolved)
                        if res_p.startswith(tw_p):
                            img_path = resolved
                    elif resolved and work_id in resolved:
                        img_path = resolved

                if not img_path:
                    # 触发一次索引重建尝试再次带 work_id 寻找
                    self.scanner.image_name_index(rebuild=True)
                    if target_work:
                        img_path = self.scanner.resolve_image_for_work(target_work, file_name) or ""
                    if not img_path:
                        print(f"[Image] !! 严防串图拦截：作品 [{work_id}] 找不到对应图片 [{file_name}]，坚决不降级为全库随机图片！")
            elif raw_path:
                img_path = self.scanner.resolve_image_path(raw_path) or ""
                if not img_path:
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

            try:
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
            except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError, socket.error):
                pass
            finally:
                self.close_connection = True
            return

        # ===== 已授权设备白名单列表（GET · share.html 用）=====
        if path == "/api/online/authorized-devices":
            return self._handle_authorized_devices_get(query)

        # ===== 静态资源：share.html（PC 端分发页 · GET）=====
        if path == "/share.html" or path == "/share":
            return self._handle_share_html()

        self.send_error(404, "Not Found")

    def _move_work_to_stage1(self, target_work: Dict[str, Any], device_name: str, action_type: str = "dispatched") -> Tuple[bool, str, str]:
        return self.scanner.move_work_to_stage1(target_work, device_name, action_type)

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

        # 优先反查原始归属目录（如 安吉成品、莫干山成品、作品集_xxx 等）
        dest_base = None
        home = self._original_parent_dir(work_id)
        if home and os.path.isdir(home) and self._is_valid_restore_parent(home):
            dest_base = home
        if not dest_base:
            dest_base = self._find_target_shelf_dir(target_work.get("destination", ""), folder_name)

        os.makedirs(dest_base, exist_ok=True)

        target_dest = os.path.join(dest_base, folder_name)
        if os.path.abspath(target_dest) == os.path.abspath(src_path):
            return True, src_path, f"作品已位于「{os.path.basename(dest_base)}」"
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

        log_dir = _get_portfolio_move_logs_dir(root_dir)
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

        shelf_name = os.path.basename(dest_base)
        return True, target_dest, f"已移回待发货架「{shelf_name}」"

    def _move_work_to_garbage(self, target_work: Dict[str, Any], device_name: str, remark: str = "") -> Tuple[bool, str, str]:
        """
        未发送作品的人工判定删除：物理移入垃圾样本库「_垃圾作品样本」，
        并在元数据（manifest.json + quality_tag.json）永久标记为垃圾，全渠道分发引擎硬拦截。
        """
        src_path = target_work["path"]
        folder_name = os.path.basename(src_path)
        work_id = target_work.get("id", "")
        remark = (remark or "").strip()
        ts = time.strftime("%Y-%m-%d %H:%M:%S")

        root_dir = self.scanner.root
        dest_base = os.path.join(root_dir, "_垃圾作品样本")
        os.makedirs(dest_base, exist_ok=True)

        target_dest = os.path.join(dest_base, folder_name)
        if os.path.exists(target_dest) and os.path.abspath(target_dest) != os.path.abspath(src_path):
            ts_suffix = time.strftime("%Y%m%d_%H%M%S")
            target_dest = os.path.join(dest_base, f"{folder_name}_{ts_suffix}")

        if os.path.abspath(target_dest) == os.path.abspath(src_path):
            return True, target_dest, "作品已位于垃圾样本库「_垃圾作品样本」"

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

        log_dir = _get_portfolio_move_logs_dir(root_dir)
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

        msg = "已移入垃圾样本库「_垃圾作品样本」并在元数据标记为垃圾"
        if remark:
            msg += f"（备注：{remark}）"
        return True, target_dest, msg

    def _delete_single_image(self, target_work: Dict[str, Any], image_name: str, device_name: str = "") -> Tuple[bool, str, List[str]]:
        """
        DSH-141: 预览界面直接删除单张图片
        - 安全移入 _垃圾作品样本/_deleted_images/{work_id}/{image_name}（零丢失可恢复）
        - 联动清理缓存 (%TEMP%/gallery_thumb_cache)
        - 联动更新 manifest.json
        - 保证作品至少保留 1 张图（最后 1 张图拦截提醒整套删除）
        """
        src_path = target_work["path"]
        real_dir = os.path.realpath(src_path)
        work_id = target_work.get("id", "")
        img_name = os.path.basename(image_name).strip()

        file_path = os.path.join(real_dir, img_name)
        if not os.path.exists(file_path):
            matched = False
            for f in os.listdir(real_dir):
                if f.lower() == img_name.lower():
                    file_path = os.path.join(real_dir, f)
                    img_name = f
                    matched = True
                    break
            if not matched:
                return False, f"图片文件 {img_name} 不存在", target_work.get("images", [])

        current_images = [img for img in target_work.get("images", [])]
        if len(current_images) <= 1:
            return False, "作品至少需保留 1 张图片，如需整套下架请直接点击删除作品", current_images

        # 1. 物理备份移入 _垃圾作品样本/_deleted_images/ (零丢失)
        root_dir = self.scanner.root
        trash_dir = os.path.join(root_dir, "_垃圾作品样本", "_deleted_images", work_id)
        os.makedirs(trash_dir, exist_ok=True)
        dest_img_path = os.path.join(trash_dir, f"{time.strftime('%Y%m%d_%H%M%S')}_{img_name}")
        try:
            shutil.move(file_path, dest_img_path)
        except Exception as e:
            return False, f"删除图片失败: {e}", current_images

        # 2. 清理临时缩略图和预览图缓存
        try:
            thumb_cache_dir = os.path.join(tempfile.gettempdir(), "gallery_thumb_cache")
            if os.path.isdir(thumb_cache_dir):
                for cf in os.listdir(thumb_cache_dir):
                    if img_name in cf or work_id in cf:
                        try:
                            os.remove(os.path.join(thumb_cache_dir, cf))
                        except Exception:
                            pass
        except Exception:
            pass

        # 3. 联动更新 manifest.json
        manifest_path = os.path.join(real_dir, "manifest.json")
        if os.path.exists(manifest_path):
            try:
                with open(manifest_path, "r", encoding="utf-8") as fp:
                    mdata = json.load(fp)
                if "images" in mdata and isinstance(mdata["images"], list):
                    new_m_imgs = []
                    for item in mdata["images"]:
                        if isinstance(item, dict):
                            fname = item.get("filename", "")
                            if fname != img_name:
                                new_m_imgs.append(item)
                        elif isinstance(item, str):
                            if os.path.basename(item) != img_name:
                                new_m_imgs.append(item)
                    mdata["images"] = new_m_imgs
                    mdata["image_count"] = len(new_m_imgs)
                with open(manifest_path, "w", encoding="utf-8") as fp:
                    json.dump(mdata, fp, ensure_ascii=False, indent=2)
            except Exception as _me:
                print(f"[Warn] 更新 manifest.json 失败: {_me}")

        # 4. 强制刷新扫描器缓存
        self.scanner.scan(force=True)
        updated_work = self.scanner.get_work(work_id)
        remaining_images = updated_work.get("images", []) if updated_work else [img for img in current_images if img != img_name]

        return True, f"已成功删除图片 {img_name}", remaining_images

    def _find_target_shelf_dir(self, destination: str = "", folder_name: str = "") -> str:
        """智能查找作品在待发货架中的目标归属目录（支持 24 大细分货架与主题货架）"""
        root_dir = self.scanner.root
        dest_clean = (destination or "").strip()

        # 1. 明确专题与目的地映射表
        DEST_MAP = {
            "游戏": "团建游戏成品",
            "团建游戏": "团建游戏成品",
            "中秋": "中秋国庆成品",
            "国庆": "中秋国庆成品",
            "中秋国庆": "中秋国庆成品",
            "江浙沪": "江浙沪成品",
        }
        candidates = []
        if dest_clean in DEST_MAP:
            candidates.append(DEST_MAP[dest_clean])
        if dest_clean and dest_clean not in ("其他", ""):
            candidates.append(f"{dest_clean}成品")
            candidates.append(dest_clean)

        # 2. 从文件夹名 / 标题中提取关键词
        for kw, shelf_name in [
            ("安吉", "安吉成品"), ("莫干山", "莫干山成品"), ("杭州", "杭州成品"),
            ("上海", "上海成品"), ("苏州", "苏州成品"), ("千岛湖", "千岛湖成品"),
            ("桐庐", "桐庐成品"), ("宁波", "宁波成品"), ("南京", "南京成品"),
            ("宜兴", "宜兴溧阳成品"), ("溧阳", "宜兴溧阳成品"), ("舟山", "舟山海岛成品"),
            ("海岛", "舟山海岛成品"), ("绍兴", "绍兴成品"), ("无锡", "无锡成品"),
            ("湖州", "湖州成品"), ("台州", "台州成品"), ("金华", "金华成品"),
            ("义乌", "义乌成品"), ("乌镇", "乌镇成品"), ("游戏", "团建游戏成品"),
            ("中秋", "中秋国庆成品"), ("国庆", "中秋国庆成品"),
        ]:
            if kw in folder_name:
                candidates.append(shelf_name)

        for c in candidates:
            cand_p = os.path.join(root_dir, c)
            if os.path.isdir(cand_p):
                return cand_p

        # 3. 兜底货架：综合与其它城市 / 已发送0次（抖音小红书可发）
        for fallback_name in ("综合与其它城市", "已发送0次（抖音小红书可发）"):
            fb = os.path.join(root_dir, fallback_name)
            if os.path.isdir(fb):
                return fb

        return root_dir

    def _original_parent_dir(self, work_id: str) -> Optional[str]:
        """从移动日志里反查作品搬家前的父目录（供「恢复」放回原位）。

        _已发送1次 里的作品可能原本住在「已发送0次/作品集_xxx」这类合集子目录里；
        若直接恢复到 已发送0次 根目录，会把作品从它的合集里抖出来、破坏成品库结构。
        _move_work_to_stage1() 会把原路径写进 _portfolio_move_logs/delete_move_log_*.csv，
        这里反查最近一次「移出发布池」记录的原路径，取它的父目录。
        """
        if not work_id:
            return None
        log_dir = _get_portfolio_move_logs_dir(self.scanner.root)
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

    def _is_valid_restore_parent(self, path: str) -> bool:
        """检查恢复目标父目录是否合法（必须在成品库内，且不是 _ 或 . 开头的系统阶段目录）"""
        try:
            target = os.path.abspath(path)
            root = os.path.abspath(self.scanner.root)
            if target == root:
                return True
            if os.path.commonpath([target, root]) != root:
                return False
            rel = os.path.relpath(target, root)
            first_part = rel.split(os.sep)[0]
            if first_part.startswith(".") or first_part.startswith("_"):
                return False
            return True
        except Exception:
            return False

    def _is_album_dir_under_stage0(self, path: str) -> bool:
        """向后兼容：检查是否为合法的成品库归属目录。"""
        return self._is_valid_restore_parent(path)

    def _record_work_usage(self, dir_path: str, device_name: str, platform: str, work_id: str, version_tag: str = "") -> None:
        """为物理作品目录更新使用标签和设备使用日志（软链接镜像被使用时触发）"""
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
        current_count = int(dist.get("useCount", 0))
        new_count = current_count + 1
        dist["useCount"] = new_count

        # 记录具体分发的文案版本标签（优先 version_tag，兜底 platform）
        v_tag = (version_tag or platform).strip()
        dispatched_versions = dist.get("dispatchedVersions", [])
        if v_tag and v_tag not in dispatched_versions:
            dispatched_versions.append(v_tag)
        dist["dispatchedVersions"] = dispatched_versions

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
        except Exception:
            pass

        # 同步双写 manifest.json（如果存在）
        manifest_file = os.path.join(dir_path, "manifest.json")
        if os.path.exists(manifest_file):
            try:
                with open(manifest_file, "r", encoding="utf-8") as mfp:
                    m_data = json.load(mfp)
                m_data["distribution"] = dist
                m_data["used"] = True
                with open(manifest_file, "w", encoding="utf-8") as mfp:
                    json.dump(m_data, mfp, ensure_ascii=False, indent=2)
            except Exception:
                pass

        log_file = _get_device_usage_log_file(self.scanner.root)
        try:
            exists = os.path.exists(log_file)
            with open(log_file, "a", encoding="utf-8") as fp:
                if not exists:
                    fp.write("时间,设备名,源作品,使用次数,平台,操作\n")
                fp.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')},{device_name},{work_id},{new_count},{platform},软链接镜像直用打标\n")
        except Exception:
            pass


    def _log_stage_move(self, root_dir: str, device_name: str, work_id: str,
                        src_path: str, dest_path: str, use_count: int,
                        action_type: str, remark: str = "") -> None:
        log_dir = _get_portfolio_move_logs_dir(root_dir)
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
        """在线回收站「恢复」：移回原归属分类目录、使用次数归零、撤销垃圾标记。

        已与用户确认口径：两个 Tab（已使用 / 已标记垃圾）的「恢复」都是这个语义 ——
        让作品重新变回可发手机的全新作品。
        """
        src_path = target_work["path"]
        folder_name = os.path.basename(src_path)
        work_id = target_work.get("id", "")
        was_garbage = target_work.get("folder") == self.scanner.GARBAGE_FOLDER
        root_dir = self.scanner.root

        # 优先反查作品搬家前的父目录，放回原归属目录（安吉成品、莫干山成品、合集等）
        dest_base = None
        restored_to_album = False
        home = self._original_parent_dir(work_id)
        if home and os.path.isdir(home) and self._is_valid_restore_parent(home):
            dest_base = home
            restored_to_album = True
        if not dest_base:
            dest_base = self._find_target_shelf_dir(target_work.get("destination", ""), folder_name)
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
                    dist["dispatchedVersions"] = []
                    dist["firstSharedAtMs"] = 0
                    dist["expireAtMs"] = 0
                    dist["originDevice"] = ""
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
                    manifest["useCount"] = 0
                    manifest["used"] = False
                    if isinstance(manifest.get("distribution"), dict):
                        manifest["distribution"]["useCount"] = 0
                        manifest["distribution"]["dispatchedTo"] = []
                        manifest["distribution"]["dispatchedVersions"] = []
                        manifest["distribution"]["firstSharedAtMs"] = 0
                        manifest["distribution"]["expireAtMs"] = 0
                        manifest["distribution"]["originDevice"] = ""
                        manifest["distribution"]["status"] = "待发手机"
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
        target_work["used"] = False
        target_work["dispatchedTo"] = []
        target_work["dispatchedVersions"] = []
        target_work["firstSharedAtMs"] = 0
        target_work["expireAtMs"] = 0
        target_work["originDevice"] = ""
        target_work["folder"] = self.scanner.STAGE0_FOLDER

        label = "垃圾样本库" if was_garbage else "已发送1次"
        where = f"原作品集合集「{os.path.basename(dest_base)}」" if restored_to_album else f"「{os.path.basename(dest_base)}」"
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

            # 【DSH-117】SSRF 收敛：只允许局域网私网地址。
            safe_hosts = []
            rejected = []
            for _h in hosts:
                _n = _normalize_sync_host(str(_h))
                (safe_hosts if _n else rejected).append(_n if _n else str(_h))
            if rejected:
                self.send_json(200, {
                    "ok": False,
                    "error": "host 只允许局域网私网地址（10.x / 172.16-31.x / 192.168.x / 169.254.x）",
                    "rejected": rejected,
                    "appliedCount": 0,
                })
                return
            report = self._run_phone_sync(safe_hosts, dry_run=dry_run)
            report["message"] = (
                f"共回写 {report['appliedCount']} 个作品的使用次数"
                + ("（预演，未落盘）" if dry_run else "")
            )
            self.send_json(200, report)
            return

        if path == "/api/online/star-work":
            content_length = int(self.headers.get("Content-Length", 0))
            raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
            try:
                req = json.loads(raw_body) if raw_body else {}
            except Exception:
                self.send_error(400, "Invalid JSON body")
                return
            work_id = str(req.get("workId") or req.get("id") or "").strip()
            starred = bool(req.get("starred", True))
            remark = str(req.get("remark") or "").strip()
            device_name = str(req.get("deviceName") or "人工标注").strip()
            target_work = self.scanner.get_work(work_id) or self.scanner.resolve_stage_work(work_id)
            if not target_work or not target_work.get("path"):
                self.send_json(404, {"ok": False, "error": f"找不到作品: {work_id}"})
                return
            w_dir = os.path.realpath(target_work["path"])
            ts = time.strftime("%Y-%m-%d %H:%M:%S")
            for fname in ("manifest.json", "作品标签.json", "GPT作品记录.json"):
                fpath = os.path.join(w_dir, fname)
                if os.path.isfile(fpath):
                    try:
                        with open(fpath, "r", encoding="utf-8", errors="ignore") as fp:
                            mdata = json.load(fp)
                        if isinstance(mdata, dict):
                            mdata["isStarred"] = starred
                            mdata["starRemark"] = remark
                            mdata["starredAt"] = ts if starred else ""
                            mdata["starredBy"] = device_name if starred else ""
                            tags = mdata.get("tags") if isinstance(mdata.get("tags"), list) else []
                            if starred and "⭐精品标杆" not in tags:
                                tags.insert(0, "⭐精品标杆")
                            elif not starred and "⭐精品标杆" in tags:
                                tags = [t for t in tags if t != "⭐精品标杆"]
                            mdata["tags"] = tags
                            with open(fpath, "w", encoding="utf-8") as fp:
                                json.dump(mdata, fp, ensure_ascii=False, indent=2)
                    except Exception:
                        pass
            self.scanner._inspect_cache.clear()
            self.send_json(200, {
                "ok": True,
                "workId": work_id,
                "isStarred": starred,
                "starRemark": remark,
                "message": "已标注为「⭐精品标杆」好作品，支持一键追溯复现" if starred else "已取消「⭐精品标杆」标注"
            })
            return

        if path == "/api/online/view-state":
            content_length = int(self.headers.get("Content-Length", 0))
            raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
            try:
                req = json.loads(raw_body) if raw_body else {}
            except Exception:
                self.send_error(400, "Invalid JSON body")
                return
            if not isinstance(req, dict):
                req = {}
            view_mode = str(req.get("viewMode") or "grid").strip().lower()
            updated_by = str(req.get("device") or req.get("updatedBy") or "unknown").strip()
            st = set_gallery_view_state(view_mode, updated_by)
            self.send_json(200, {"ok": True, **st})
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
            version_tag = req.get("versionTag", "").strip() or platform

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
            is_link = target_work.get("is_symlink", False) or is_junction_or_symlink(dir_path)

            if is_link:
                # 【软链接镜像使用】：
                # 仅解绑当前副货架上的软链接（不破坏主货架物理本体）
                unlink_ok, unlink_msg = unlink_junction(dir_path, self.scanner.root)
                source_p = target_work.get("source_path") or resolve_junction_source(dir_path)
                if source_p and os.path.isdir(source_p):
                    try:
                        self._record_work_usage(source_p, device_name, platform, work_id, version_tag)
                    except Exception:
                        pass
                self.scanner.scan(force=True)
                self.send_json(200, {
                    "ok": True,
                    "workId": work_id,
                    "isSymlink": True,
                    "useCount": 1,
                    "remainingUses": 0,
                    "moved": False,
                    "unlinked": unlink_ok,
                    "message": "软链接镜像已从当前副货架安全解除，源物理本体完好保留在原货架",
                    "targetPath": dir_path
                })
                return

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

            # DSH-135 & DSH-139: 首发设备生命周期继承与防二次使用异常清空
            now_ms = int(time.time() * 1000)
            retention_ms = req.get("retentionDurationMs")
            if retention_ms is not None:
                retention_duration_ms = max(0, int(retention_ms))
            else:
                retention_duration_ms = 0

            # 判定作品当前是否已有活跃且未到期的首发倒计时
            has_active_lifecycle = bool(dist.get("expireAtMs") and dist["expireAtMs"] > now_ms)

            if not has_active_lifecycle:
                # 之前没有活跃倒计时（或已过期）：仅当本次显式要求保留时长时，才开启倒计时
                if retention_duration_ms > 0:
                    dist["firstSharedAtMs"] = now_ms
                    dist["expireAtMs"] = now_ms + retention_duration_ms
                    dist["originDevice"] = device_name
            else:
                # 继承原有首发生命周期：绝对保留原有 firstSharedAtMs、expireAtMs 与 originDevice！
                # 任何第二台设备的使用，绝不允许重置倒计时时间轴或覆盖首发设备标识
                pass

            # DSH-137: 记录具体分发的文案版本标签
            dispatched_versions = dist.get("dispatchedVersions", [])
            if version_tag and version_tag not in dispatched_versions:
                dispatched_versions.append(version_tag)
            dist["dispatchedVersions"] = dispatched_versions

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

            # 同步写回 manifest.json（如果存在）
            manifest_file = os.path.join(dir_path, "manifest.json")
            if os.path.exists(manifest_file):
                try:
                    with open(manifest_file, "r", encoding="utf-8") as mfp:
                        m_data = json.load(mfp)
                    m_data["distribution"] = dist
                    m_data["used"] = True
                    with open(manifest_file, "w", encoding="utf-8") as mfp:
                        json.dump(m_data, mfp, ensure_ascii=False, indent=2)
                except Exception as _me:
                    print(f"[Warn] Failed to sync manifest.json: {_me}")

            log_file = _get_device_usage_log_file(self.scanner.root)
            try:
                exists = os.path.exists(log_file)
                with open(log_file, "a", encoding="utf-8") as fp:
                    if not exists:
                        fp.write("时间,设备名,源作品,使用次数,平台,操作\n")
                    fp.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')},{device_name},{work_id},{new_count},{platform},手机在线直用打标\n")
            except Exception as e:
                print(f"[Warn] Failed to write usage log: {e}")

            # DSH-135 & DSH-139: 核心业务规则加固：
            # 1. 若显式声明 forceMove，则强制移动；
            # 2. 若存在活跃且未到期的首发倒计时 (has_active_lifecycle == True)，绝对不移动！作品安稳留在货架上；
            # 3. 仅当已到达过期时间 expireAtMs，才物理移库；
            # 4. 若从始至终无任何保留时间配置且无 expireAtMs，才物理移动。
            moved = False
            target_dest_path = ""
            force_move = bool(req.get("forceMove", False))

            if force_move:
                should_move = True
            elif has_active_lifecycle:
                should_move = False
            elif dist.get("expireAtMs"):
                should_move = bool(now_ms >= dist["expireAtMs"])
            else:
                should_move = bool(retention_duration_ms <= 0)

            if should_move and new_count >= 1:
                ok, target_dest_path, move_msg = self._move_work_to_stage1(target_work, device_name, "use_auto_dispatched")
                moved = ok
                if moved:
                    # 联动清理：自动物理清除所有指向该本体的软链接镜像
                    try:
                        cleanup_junctions_for_source(dir_path, self.scanner.root)
                    except Exception as _ce:
                        print(f"[Warn] 清理关联软链接失败: {_ce}")

            msg = f"已成功记录第 {new_count} 次使用" + ("，电脑端已自动移入「_已发送1次」" if moved else "，保留在货架并已继承首发生命周期")

            self.scanner.scan(force=True)

            self.send_json(200, {
                "ok": True,
                "workId": work_id,
                "useCount": new_count,
                "remainingUses": 0,
                "moved": moved,
                "targetPath": target_dest_path,
                "message": msg,
                "dispatchedTo": dispatched_list,
                "firstSharedAtMs": dist.get("firstSharedAtMs", 0),
                "expireAtMs": dist.get("expireAtMs", 0),
                "originDevice": dist.get("originDevice", ""),
                "dispatchedVersions": dist.get("dispatchedVersions", [])
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
            if not work_id:
                self.send_error(400, "Missing workId")
                return

            # 多重容错寻址：原ID -> 剥离软链接镜像ID -> 阶段库寻址
            target_work = self.scanner.get_work(work_id)
            if not target_work and "__link_" in work_id:
                target_work = self.scanner.get_work(work_id.split("__link_")[0])
            if not target_work:
                target_work = self.scanner.resolve_stage_work(work_id)

            if not target_work:
                self.send_error(404, "Work not found")
                return

            dir_path = target_work["path"]
            is_link = target_work.get("is_symlink", False) or is_junction_or_symlink(dir_path)

            if is_link:
                # 软链接镜像重置：解绑当前副货架软链接，并定位到主货架本体
                unlink_junction(dir_path, self.scanner.root)
                source_p = target_work.get("source_path") or resolve_junction_source(dir_path)
                if source_p and os.path.isdir(source_p):
                    dir_path = source_p
                    target_work["path"] = source_p

            tag_file = os.path.join(dir_path, "作品标签.json")
            if os.path.exists(tag_file):
                try:
                    with open(tag_file, "r", encoding="utf-8") as fp:
                        tag_data = json.load(fp)
                    if "distribution" in tag_data and isinstance(tag_data["distribution"], dict):
                        tag_data["distribution"]["useCount"] = 0
                        tag_data["distribution"]["dispatchedTo"] = []
                        tag_data["distribution"]["dispatchedVersions"] = []
                        tag_data["distribution"]["firstSharedAtMs"] = 0
                        tag_data["distribution"]["expireAtMs"] = 0
                        tag_data["distribution"]["originDevice"] = ""
                        tag_data["distribution"]["status"] = "待发手机"
                    with open(tag_file, "w", encoding="utf-8") as fp:
                        json.dump(tag_data, fp, ensure_ascii=False, indent=2)
                except Exception as e:
                    print(f"Error resetting tags: {e}")

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
                            manifest["distribution"]["dispatchedVersions"] = []
                            manifest["distribution"]["firstSharedAtMs"] = 0
                            manifest["distribution"]["expireAtMs"] = 0
                            manifest["distribution"]["originDevice"] = ""
                            manifest["distribution"]["status"] = "待发手机"
                        manifest["resetAt"] = time.strftime("%Y-%m-%d %H:%M:%S")
                        with open(manifest_file, "w", encoding="utf-8") as fp:
                            json.dump(manifest, fp, ensure_ascii=False, indent=2)
                except Exception as e:
                    print(f"Error resetting manifest: {e}")

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

            # 极速局部内存原子更新（毫秒级响应，彻底消灭 13 秒全盘扫描阻塞与超时）：
            target_work["useCount"] = 0
            target_work["used"] = False
            target_work["remainingUses"] = 2
            target_work["statusLabel"] = ""
            target_work["dispatchedTo"] = []
            target_work["dispatchedVersions"] = []
            target_work["firstSharedAtMs"] = 0
            target_work["expireAtMs"] = 0
            target_work["originDevice"] = ""
            target_work["path"] = new_path
            target_work["stage"] = "已发送0次"
            shelf_candidate = os.path.basename(os.path.dirname(new_path))
            target_work["shelf"] = shelf_candidate
            target_work["folder"] = shelf_candidate

            with self.scanner._lock:
                self.scanner._works_by_id[work_id] = target_work
                if "__link_" in work_id:
                    self.scanner._works_by_id[work_id.split("__link_")[0]] = target_work
                self.scanner._moved_works.pop(work_id, None)
                self.scanner._stage_cache.clear()

            # 将耗时的全盘深度重新扫描放入后台守护线程异步执行（彻底消除超时与死锁）
            threading.Thread(
                target=self.scanner.scan,
                kwargs={"force": True},
                name="bg-scan-after-reset",
                daemon=True
            ).start()

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

            dir_path = target_work["path"]
            is_link = target_work.get("is_symlink", False) or is_junction_or_symlink(dir_path)

            if is_link:
                # 【软链接镜像删除】：
                # 仅解绑当前副货架上的软链接，绝对不污染/移走主货架物理本体！
                unlink_ok, unlink_msg = unlink_junction(dir_path, self.scanner.root)
                self.scanner.scan(force=True)
                self.send_json(200, {
                    "ok": True,
                    "workId": work_id,
                    "isSymlink": True,
                    "action": "junction_unlinked",
                    "message": "软链接镜像已从当前货架安全解绑移除，物理本体保留在原货架不受影响",
                    "remainingWorks": len(self.scanner.scan())
                })
                return

            # 判定权在手机端：只要手机点了删除（含「用过 → 重置 → 再删除」），
            # 一律视为人工判定垃圾：物理移入「_垃圾作品样本」永久保留，
            # 并在元数据写死垃圾标记（全渠道硬拦截）。电脑端绝不自动清理该样本库。
            ok, target_dest, action_desc = self._move_work_to_garbage(target_work, device_name, remark)
            if not ok:
                self.send_json(200, {"ok": False, "error": action_desc})
                return

            # 联动清理：自动物理清除所有指向该本体的软链接镜像
            try:
                cleanup_junctions_for_source(dir_path, self.scanner.root)
            except Exception as _ce:
                print(f"[Warn] 清理关联软链接失败: {_ce}")

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

        if path in ("/api/online/delete-image", "/api/online/delete-single-image"):
            # DSH-141: 预览界面直接删除单张图片（零丢失安全物理备份至 _垃圾作品样本/_deleted_images/）
            content_length = int(self.headers.get("Content-Length", 0))
            raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
            try:
                req = json.loads(raw_body)
            except Exception:
                self.send_error(400, "Invalid JSON")
                return

            work_id = (req.get("workId") or req.get("id") or "").strip()
            image_name = (req.get("image") or req.get("imageName") or req.get("filename") or "").strip()
            device_name = (req.get("deviceName") or req.get("device") or "移动相册客户端").strip()

            if not work_id or not image_name:
                self.send_json(200, {"ok": False, "error": "缺少作品ID或图片文件名"})
                return

            target_work = self.scanner.get_work(work_id)
            if not target_work and "__link_" in work_id:
                target_work = self.scanner.get_work(work_id.split("__link_")[0])
            if not target_work:
                target_work = self.scanner.resolve_stage_work(work_id)
            if not target_work:
                self.send_json(200, {"ok": False, "error": "作品不存在或已被移除"})
                return

            ok, msg, remaining_images = self._delete_single_image(target_work, image_name, device_name)
            self.send_json(200, {
                "ok": ok,
                "workId": work_id,
                "image": image_name,
                "message": msg if ok else "",
                "error": "" if ok else msg,
                "remainingImages": remaining_images
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
            
            # 局部内存更新 + 作废回收站缓存
            with self.scanner._lock:
                target_work["useCount"] = 0
                target_work["used"] = False
                target_work["remainingUses"] = 2
                target_work["statusLabel"] = ""
                target_work["path"] = target_dest
                target_work["stage"] = "已发送0次"
                shelf_cand = os.path.basename(os.path.dirname(target_dest))
                target_work["shelf"] = shelf_cand
                target_work["folder"] = shelf_cand
                self.scanner._works_by_id[work_id] = target_work
                self.scanner._moved_works.pop(work_id, None)
                self.scanner._stage_cache.clear()

            # 将耗时全盘扫描丢入后台守护线程异步刷新
            threading.Thread(
                target=self.scanner.scan,
                kwargs={"force": True},
                name="bg-scan-after-restore",
                daemon=True
            ).start()

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
        # ===== DSH-138: 手机端长按文案编辑在线写回真源 =====
        if path == "/api/online/update-copy":
            content_length = int(self.headers.get("Content-Length", 0))
            raw_body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else "{}"
            try:
                req = json.loads(raw_body)
            except Exception:
                self.send_error(400, "Invalid JSON")
                return

            work_id = (req.get("workId") or "").strip()
            updated_copy = req.get("updatedCopy", "")
            device_name = (req.get("device") or req.get("deviceName") or "移动端").strip()
            version_key = (req.get("versionKey") or req.get("versionTag") or "").strip()

            if not work_id:
                self.send_json(400, {"ok": False, "message": "缺少 workId"})
                return

            # 防空壳防御：至少 30 个有效字符
            if not updated_copy or len(updated_copy.strip()) < 30:
                self.send_json(400, {
                    "ok": False,
                    "message": "文案内容过少（至少30字），为防止误删破坏已拦截保存",
                    "length": len(updated_copy.strip()) if updated_copy else 0
                })
                return

            target_work = self.scanner.get_work(work_id)
            if not target_work and "__link_" in work_id:
                target_work = self.scanner.get_work(work_id.split("__link_")[0])
            if not target_work:
                target_work = self.scanner.resolve_stage_work(work_id)

            if not target_work:
                self.send_json(404, {"ok": False, "message": "作品不存在或已下架"})
                return

            dir_path = target_work.get("path")
            if not dir_path or not os.path.isdir(dir_path):
                self.send_json(404, {"ok": False, "message": "作品物理目录不存在"})
                return

            copy_file = os.path.join(dir_path, "文案.txt")

            final_text_to_write = updated_copy
            # 若原文件存在，先做备份并智能判定多槽位定向替换
            try:
                if os.path.exists(copy_file):
                    shutil.copy2(copy_file, copy_file + ".bak")
                    with open(copy_file, "r", encoding="utf-8") as fp:
                        orig_text = fp.read()
                    final_text_to_write = splice_platform_copy(orig_text, version_key, updated_copy)
            except Exception as e:
                print(f"[Warn] 备份或解析原文案失败: {e}")

            # 写入文案真源（原子写入防断电损坏）
            try:
                tmp_file = copy_file + ".tmp"
                with open(tmp_file, "w", encoding="utf-8") as fp:
                    fp.write(final_text_to_write)
                if os.path.exists(copy_file):
                    os.replace(tmp_file, copy_file)
                else:
                    os.rename(tmp_file, copy_file)
            except Exception as e:
                return self.send_json(500, {"ok": False, "message": f"物理写盘失败: {e}"})

            # 更新内存缓存与时间戳
            target_work["copyText"] = final_text_to_write
            target_work["hasCopyText"] = True
            target_work["updatedAt"] = int(time.time() * 1000)

            # 更新作品标签与操作记录
            tag_file = os.path.join(dir_path, "作品标签.json")
            tag_data = {}
            if os.path.exists(tag_file):
                try:
                    with open(tag_file, "r", encoding="utf-8") as fp:
                        tag_data = json.load(fp)
                except Exception:
                    tag_data = {}
            if "copy_edits" not in tag_data or not isinstance(tag_data["copy_edits"], list):
                tag_data["copy_edits"] = []
            tag_data["copy_edits"].append({
                "device": device_name,
                "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                "version": version_key,
                "length": len(updated_copy)
            })
            try:
                with open(tag_file, "w", encoding="utf-8") as fp:
                    json.dump(tag_data, fp, ensure_ascii=False, indent=2)
            except Exception:
                pass

            self.send_json(200, {
                "ok": True,
                "workId": work_id,
                "message": "文案已成功同步保存至电脑真源，多端刷新即生效",
                "charCount": len(updated_copy),
                "device": device_name
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


_service_mutex = None

def _acquire_service_instance_mutex():
    global _service_mutex
    if os.name == "nt":
        try:
            import ctypes
            from ctypes import wintypes
            MUTEX_NAME = "Local\\DeviceShareHub_OnlineGallery_Service_Instance_Mutex"
            kernel32 = ctypes.windll.kernel32
            CreateMutexW = kernel32.CreateMutexW
            CreateMutexW.argtypes = [wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR]
            CreateMutexW.restype = wintypes.HANDLE
            h = CreateMutexW(None, True, MUTEX_NAME)
            ERROR_ALREADY_EXISTS = 183
            if kernel32.GetLastError() == ERROR_ALREADY_EXISTS:
                print("[SingleInstance] Another online_gallery_service is already running. Exiting.")
                sys.exit(0)
            _service_mutex = h
        except Exception:
            pass

def run_service(port: int = DEFAULT_PORT, library_root: str = DEFAULT_LIBRARY_ROOT, enable_adb: bool = False):
    _acquire_service_instance_mutex()

    scanner = WorkScanner(library_root)
    OnlineGalleryHandler.scanner = scanner

    # 【DSH-117】默认 ThreadingHTTPServer 的 request_queue_size 只有 5：
    # 多部手机同时拉列表 + 缩略图时，第 6 个连接直接被内核拒掉，
    # 手机端表现为「正在连接电脑在线相册…」一直转圈直到超时，服务端零报错。
    # daemon_threads 保证退出时工作线程不把进程拖住。
    class _LanHTTPServer(ThreadingHTTPServer):
        daemon_threads = True
        request_queue_size = 128
        allow_reuse_address = True

        def server_bind(self):
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                try:
                    self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 0)
                except Exception:
                    pass
            if self.allow_reuse_address:
                try:
                    self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                except Exception:
                    pass
            super().server_bind()

    server = None
    for attempt in range(15):
        try:
            server = _LanHTTPServer(("0.0.0.0", port), OnlineGalleryHandler)
            break
        except OSError as e:
            if attempt < 14:
                time.sleep(1.0)
            else:
                raise e

    local_ip = get_local_ip()
    print(f"================================================================")
    print(f"🚀 Device Share Hub - 电脑在线相册服务已就绪")
    print(f"📡 局域网访问地址: http://{local_ip}:{port}")
    print(f"📂 作品真源目录: {library_root}")
    print(f"🛡️ 纯净首发保障: 仅限「已发送0次」与根目录直出成品，排除忽略项")
    print(f"================================================================")

    # 立即启动 HTTP 监听循环（避免首次预热扫描耗时阻塞探活心跳）
    serve_thread = threading.Thread(target=server.serve_forever, daemon=True, name="HttpServe")
    serve_thread.start()

    # 启动纯静默 ADB 隧道守护（0 弹窗 0 黑框）
    start_adb_reverse_daemon(port)

    # 启动局域网信标广播：手机端零扫描、秒级发现，IP 变化自动跟随
    start_lan_beacon(port)

    # 首次预热扫描
    works = scanner.scan(force=True)
    print(f"✨ 初始加载完成，共发现 {len(works)} 套存量成品作品")

    # DSH-110：启动文件系统轮询线程（无第三方依赖，60 秒/次）
    scanner._start_watchdog_loop()

    try:
        serve_thread.join()
    except KeyboardInterrupt:
        print("\n服务正在平稳退出...")
    except Exception as e:
        import traceback
        traceback.print_exc()
        with open(os.path.join(os.path.dirname(__file__), "crash.log"), "w", encoding="utf-8") as fp:
            fp.write(traceback.format_exc())
    finally:
        scanner.stop_watchdog()
        try:
            server.shutdown()
            server.server_close()
        except Exception:
            pass
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
