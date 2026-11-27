#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Google Camera Motion Photo (Android 实况图) 封包与检测引擎
============================================================

严格遵循公开的 Google Motion Photo XMP 规范（同时兼容 V1 MicroVideo 与
V2 MotionPhoto Container:Directory 语义），不伪造相机型号或 EXIF 信息，
仅保留原图 EXIF/MakerNote 并在标准 APP1 XMP 段声明后置 MP4 偏移与长度。
"""

from __future__ import annotations

import hashlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import tempfile
import threading
import uuid
from typing import Any, Dict, List, Optional, Tuple, Union

try:
    from PIL import Image, ImageOps
    HAS_PIL = True
except ImportError:
    Image = None
    ImageOps = None
    HAS_PIL = False

XMP_HEADER = b"http://ns.adobe.com/xap/1.0/\x00"
FFMPEG_FALLBACKS = [
    r"C:\ffmpeg\bin\ffmpeg.exe",
    r"C:\Users\z\ffmpeg\ffmpeg.exe",
    r"C:\Program Files\ffmpeg\bin\ffmpeg.exe",
    r"d:\Program Files\TRAE SOLO CN\resources\app\bin\ffmpeg.exe",
]
EXIFTOOL_FALLBACKS = [
    r"C:\Users\z\.local\bin\exiftool.bat",
    r"C:\Users\z\.local\bin\exiftool.exe",
    r"C:\Windows\exiftool.exe",
]
APPLE_MAKERNOTE_TEMPLATE = r"C:\Users\z\.local\exiftool\t\images\Apple.jpg"
DEFAULT_CACHE_LIMIT = 200
DEFAULT_LIVE_CONFIG_FILE = r"D:\AICode\运行数据\江湖有旅人\live_motion_config.json"

MOTION_PRESETS: List[Dict[str, str]] = [
    {"id": "natural_hold", "label": "自然手持微动（推荐）", "desc": "模拟真实持机拍摄的轻微呼吸稳态，最接近真机实拍"},
    {"id": "handheld_push_drift_v1", "label": "自然推近·轻晃侧移", "desc": "前快后慢推近，后段轻微侧移，参考实况样本曲线"},
    {"id": "subtle_still", "label": "几乎静止（极微呼吸）", "desc": "极小幅度变化，几乎不改构图，仅保留实况标识"},
    {"id": "zoom_in", "label": "轻柔推近", "desc": "画面极轻微平滑向前推近（缓入缓出）"},
    {"id": "zoom_out", "label": "轻柔拉远", "desc": "画面极轻微平滑向后舒展（缓入缓出）"},
    {"id": "pan_up", "label": "微幅上移", "desc": "画面极轻微平滑向上移动"},
    {"id": "pan_down", "label": "微幅下移", "desc": "画面极轻微平滑向下移动"},
    {"id": "pan_left", "label": "微幅左移", "desc": "画面极轻微平滑向左移动"},
    {"id": "pan_right", "label": "微幅右移", "desc": "画面极轻微平滑向右移动"},
    {"id": "handheld", "label": "温和手持轻晃", "desc": "克制的小幅自然手持微晃"},
]
VALID_MOTION_MODES = {item["id"] for item in MOTION_PRESETS}
DEFAULT_LIVE_CONFIG: Dict[str, Any] = {
    "mode": "natural_hold",
    "ratio": 1.018,
    "duration": 1.6,
    "fps": 30,
}
_CONFIG_LOCK = threading.Lock()


def find_exiftool() -> Optional[str]:
    """查找本机可用的 exiftool 可执行文件。"""
    found = shutil.which("exiftool")
    if found and os.path.isfile(found):
        return found
    for candidate in EXIFTOOL_FALLBACKS:
        if os.path.isfile(candidate):
            return candidate
    return None


def normalize_live_config(raw: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    """规范化微动参数，夹紧至安全美观区间。"""
    src = raw if isinstance(raw, dict) else {}
    mode = str(src.get("mode") or DEFAULT_LIVE_CONFIG["mode"]).strip().lower()
    if mode not in VALID_MOTION_MODES:
        mode = DEFAULT_LIVE_CONFIG["mode"]
    try:
        ratio = float(src.get("ratio", DEFAULT_LIVE_CONFIG["ratio"]))
    except (TypeError, ValueError):
        ratio = DEFAULT_LIVE_CONFIG["ratio"]
    ratio = round(max(1.005, min(1.12 if mode == "handheld_push_drift_v1" else 1.10, ratio)), 3)

    try:
        duration = float(src.get("duration", DEFAULT_LIVE_CONFIG["duration"]))
    except (TypeError, ValueError):
        duration = DEFAULT_LIVE_CONFIG["duration"]
    duration = round(max(1.0, min(3.5, duration)), 2)

    try:
        fps = int(src.get("fps", DEFAULT_LIVE_CONFIG["fps"]))
    except (TypeError, ValueError):
        fps = DEFAULT_LIVE_CONFIG["fps"]
    fps = max(24, min(30, fps))
    return {"mode": mode, "ratio": ratio, "duration": duration, "fps": fps}


def config_signature(cfg: Optional[Dict[str, Any]]) -> str:
    norm = normalize_live_config(cfg)
    raw = f"v2|{norm['mode']}|{norm['ratio']:.3f}|{norm['duration']:.2f}|{norm['fps']}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:12]


def load_live_config(config_file: Optional[str] = None) -> Dict[str, Any]:
    path = config_file or DEFAULT_LIVE_CONFIG_FILE
    with _CONFIG_LOCK:
        try:
            if os.path.isfile(path):
                with open(path, "r", encoding="utf-8-sig") as fp:
                    loaded = json.load(fp)
                return normalize_live_config(loaded)
        except Exception:
            pass
    return dict(DEFAULT_LIVE_CONFIG)


def save_live_config(updates: Dict[str, Any], config_file: Optional[str] = None) -> Dict[str, Any]:
    path = config_file or DEFAULT_LIVE_CONFIG_FILE
    with _CONFIG_LOCK:
        current = dict(DEFAULT_LIVE_CONFIG)
        try:
            if os.path.isfile(path):
                with open(path, "r", encoding="utf-8-sig") as fp:
                    loaded = json.load(fp)
                if isinstance(loaded, dict):
                    current.update(loaded)
        except Exception:
            pass
        if isinstance(updates, dict):
            previous_mode = current.get("mode")
            new_mode = updates.get("mode", previous_mode)
            if new_mode != previous_mode and "handheld_push_drift_v1" in (new_mode, previous_mode):
                current.update({"ratio": 1.116 if new_mode == "handheld_push_drift_v1" else DEFAULT_LIVE_CONFIG["ratio"],
                                "duration": 2.0 if new_mode == "handheld_push_drift_v1" else DEFAULT_LIVE_CONFIG["duration"]})
            current.update(updates)
        norm = normalize_live_config(current)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        tmp_path = f"{path}.{uuid.uuid4().hex[:8]}.tmp"
        with open(tmp_path, "w", encoding="utf-8") as fp:
            json.dump(norm, fp, ensure_ascii=False, indent=2)
        os.replace(tmp_path, path)
        return norm

_XMP_MP_RE = re.compile(
    rb'(?:Camera|GCamera):(?:MotionPhoto|MicroVideo)\s*=\s*["\']1["\']'
    rb'|<(?:Camera|GCamera):(?:MotionPhoto|MicroVideo)>1</(?:Camera|GCamera):(?:MotionPhoto|MicroVideo)>'
)
_XMP_OFFSET_RE = re.compile(
    rb'(?:GCamera:MicroVideoOffset|Item:Length)\s*=\s*["\'](\d+)["\']'
)


def find_ffmpeg() -> Optional[str]:
    """查找本机可用的完整版本 ffmpeg 可执行文件（优先独立安装目录，避免命中 Electron 精简版）。"""
    for candidate in FFMPEG_FALLBACKS:
        if "TRAE SOLO" in candidate.upper():
            continue
        if os.path.isfile(candidate):
            return candidate
    found = shutil.which("ffmpeg")
    if found and os.path.isfile(found) and "TRAE SOLO" not in found.upper():
        return found
    for candidate in FFMPEG_FALLBACKS:
        if os.path.isfile(candidate):
            return candidate
    return found if (found and os.path.isfile(found)) else None


def _default_cache_root() -> str:
    local_app = os.environ.get("LOCALAPPDATA") or tempfile.gettempdir()
    return os.path.join(local_app, "ZwmDeviceShareHub", "motion_photo_cache")


def _evict_cache_if_needed(cache_dir: str, limit: int = DEFAULT_CACHE_LIMIT) -> None:
    try:
        entries = []
        for entry in os.scandir(cache_dir):
            if entry.is_file() and not entry.name.endswith(".tmp"):
                try:
                    entries.append((entry.stat().st_mtime_ns, entry.path))
                except OSError:
                    pass
        if len(entries) <= limit:
            return
        entries.sort(key=lambda item: item[0])
        for _, path in entries[: len(entries) - limit]:
            try:
                os.remove(path)
            except OSError:
                pass
    except OSError:
        pass


def _atomic_write_bytes(target_path: str, data: bytes) -> None:
    os.makedirs(os.path.dirname(target_path), exist_ok=True)
    tmp_path = f"{target_path}.{uuid.uuid4().hex[:8]}.tmp"
    with open(tmp_path, "wb") as fp:
        fp.write(data)
    os.replace(tmp_path, target_path)


def is_motion_photo_jpeg(path_or_bytes: Union[str, os.PathLike, bytes, bytearray, memoryview]) -> bool:
    """快速判断 JPEG 文件或字节流是否为已封装 MP4 的 Google Motion Photo。

    仅读取前 128KB 的 XMP 声明与 offset 处的 MP4 ftyp 盒（O(1) 磁盘 I/O），
    不把整张几 MB 大图全部读入内存。
    """
    if isinstance(path_or_bytes, (bytes, bytearray, memoryview)):
        raw = bytes(path_or_bytes)
        if len(raw) < 32 or raw[:2] != b"\xFF\xD8":
            return False
        head = raw[:131072]
        xmp_pos = head.find(XMP_HEADER)
        if xmp_pos < 0:
            return False
        if not _XMP_MP_RE.search(head[xmp_pos:]):
            return False
        for m in _XMP_OFFSET_RE.finditer(head[xmp_pos:]):
            try:
                offset = int(m.group(1))
            except ValueError:
                continue
            if 16 <= offset < len(raw) - 20:
                mp4_start = len(raw) - offset
                if raw[mp4_start + 4 : mp4_start + 8] == b"ftyp":
                    return True
        eoi = raw.find(b"\xFF\xD9")
        if eoi > 0 and b"ftyp" in raw[eoi : eoi + 64]:
            return True
        return False

    path = os.fspath(path_or_bytes)
    try:
        file_size = os.path.getsize(path)
        if file_size < 32:
            return False
        with open(path, "rb") as fp:
            head = fp.read(131072)
            if head[:2] != b"\xFF\xD8":
                return False
            xmp_pos = head.find(XMP_HEADER)
            if xmp_pos < 0:
                return False
            if not _XMP_MP_RE.search(head[xmp_pos:]):
                return False
            for m in _XMP_OFFSET_RE.finditer(head[xmp_pos:]):
                try:
                    offset = int(m.group(1))
                except ValueError:
                    continue
                if 16 <= offset < file_size - 20:
                    fp.seek(file_size - offset)
                    box = fp.read(16)
                    if len(box) >= 8 and box[4:8] == b"ftyp":
                        return True
            tail_start = max(0, file_size - 16384)
            fp.seek(tail_start)
            tail = fp.read(16384)
            return b"ftyp" in head and b"moov" in tail
    except OSError:
        return False


def ensure_mp4_bytes(video_path: Union[str, os.PathLike], cache_dir: Optional[str] = None) -> bytes:
    """将配套实况视频（.mp4 / .mov）统一转换为标准 MP4 字节流。"""
    src = os.path.realpath(os.fspath(video_path))
    if not os.path.isfile(src):
        raise FileNotFoundError(f"伴生视频不存在: {src}")

    if src.lower().endswith(".mp4"):
        with open(src, "rb") as fp:
            data = fp.read()
        if not data:
            raise ValueError(f"MP4 视频为空: {src}")
        return data

    st = os.stat(src)
    digest = hashlib.sha256(f"{src}|{st.st_mtime_ns}|{st.st_size}".encode("utf-8")).hexdigest()[:24]
    mp4_cache_dir = os.path.join(cache_dir or _default_cache_root(), "mp4")
    cached_mp4 = os.path.join(mp4_cache_dir, f"{digest}.mp4")
    if os.path.isfile(cached_mp4) and os.path.getsize(cached_mp4) > 0:
        with open(cached_mp4, "rb") as fp:
            return fp.read()

    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        raise RuntimeError("未找到 ffmpeg，无法将 .mov 转为安卓 Motion Photo MP4")

    os.makedirs(mp4_cache_dir, exist_ok=True)
    tmp_mp4 = os.path.join(mp4_cache_dir, f"{digest}.{uuid.uuid4().hex[:8]}.tmp.mp4")
    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

    try:
        copy_cmd = [
            ffmpeg, "-y", "-loglevel", "error",
            "-i", src,
            "-c", "copy",
            "-movflags", "+faststart",
            tmp_mp4,
        ]
        res = subprocess.run(copy_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=creationflags)
        if res.returncode != 0 or not os.path.isfile(tmp_mp4) or os.path.getsize(tmp_mp4) == 0:
            encode_cmd = [
                ffmpeg, "-y", "-loglevel", "error",
                "-i", src,
                "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "128k",
                "-movflags", "+faststart",
                tmp_mp4,
            ]
            subprocess.run(encode_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=creationflags, check=True)

        with open(tmp_mp4, "rb") as fp:
            mp4_data = fp.read()
        if not mp4_data:
            raise RuntimeError("ffmpeg 转码后的 MP4 为空")
        os.replace(tmp_mp4, cached_mp4)
        _evict_cache_if_needed(mp4_cache_dir)
        return mp4_data
    finally:
        if os.path.exists(tmp_mp4):
            try:
                os.remove(tmp_mp4)
            except OSError:
                pass


def _ensure_jpeg_bytes(image_bytes: bytes) -> bytes:
    if len(image_bytes) >= 2 and image_bytes[:2] == b"\xFF\xD8":
        return image_bytes
    if not HAS_PIL:
        raise ValueError("输入图片不是 JPEG，且本环境未安装 Pillow 无法转码")
    with Image.open(io.BytesIO(image_bytes)) as img:
        if img.mode not in ("RGB", "L"):
            img = img.convert("RGB")
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=95, subsampling=0)
        return buf.getvalue()


def _build_xmp_payload(mp4_length: int) -> bytes:
    xmp_xml = (
        '<?xpacket begin="\ufeff" id="W5M0MpCehiHzreSzNTczkc9d"?>'
        '<x:xmpmeta xmlns:x="adobe:ns:meta/">'
        '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
        '<rdf:Description rdf:about="" '
        'xmlns:Camera="http://ns.google.com/photos/1.0/camera/" '
        'xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" '
        'xmlns:Container="http://ns.google.com/photos/1.0/container/" '
        'xmlns:Item="http://ns.google.com/photos/1.0/container/item/" '
        'Camera:MotionPhoto="1" '
        'Camera:MotionPhotoVersion="1" '
        'Camera:MotionPhotoPresentationTimestampUs="0" '
        'GCamera:MotionPhoto="1" '
        'GCamera:MotionPhotoVersion="1" '
        'GCamera:MotionPhotoPresentationTimestampUs="0" '
        'GCamera:MicroVideo="1" '
        'GCamera:MicroVideoVersion="1" '
        f'GCamera:MicroVideoOffset="{mp4_length}" '
        'GCamera:MicroVideoPresentationTimestampUs="0">'
        '<Container:Directory>'
        '<rdf:Seq>'
        '<rdf:li rdf:parseType="Resource">'
        '<Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>'
        '</rdf:li>'
        '<rdf:li rdf:parseType="Resource">'
        f'<Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="{mp4_length}" Item:Padding="0"/>'
        '</rdf:li>'
        '</rdf:Seq>'
        '</Container:Directory>'
        '</rdf:Description>'
        '</rdf:RDF>'
        '</x:xmpmeta>'
        '<?xpacket end="w"?>'
    )
    return XMP_HEADER + xmp_xml.encode("utf-8")


def _inject_xmp_app1(jpeg_bytes: bytes, xmp_payload: bytes) -> bytes:
    if len(jpeg_bytes) < 4 or jpeg_bytes[:2] != b"\xFF\xD8":
        raise ValueError("无效的 JPEG 数据（缺少 SOI）")
    if len(xmp_payload) + 2 > 65535:
        raise ValueError("XMP 元数据超过单个 JPEG APP1 段长度限制")

    app1_xmp = b"\xFF\xE1" + struct.pack(">H", len(xmp_payload) + 2) + xmp_payload

    out = bytearray()
    out.extend(b"\xFF\xD8")
    pos = 2
    n = len(jpeg_bytes)
    inserted = False

    while pos < n:
        if jpeg_bytes[pos] != 0xFF:
            if not inserted:
                out.extend(app1_xmp)
                inserted = True
            out.extend(jpeg_bytes[pos:])
            break

        # 跳过连续填充 0xFF
        marker_pos = pos
        while marker_pos < n and jpeg_bytes[marker_pos] == 0xFF:
            marker_pos += 1
        if marker_pos >= n:
            if not inserted:
                out.extend(app1_xmp)
                inserted = True
            out.extend(jpeg_bytes[pos:])
            break

        marker = jpeg_bytes[marker_pos]
        # SOS (DA) 或 EOI (D9) 或无长度 RST/TEM 标记：在此之前插入 XMP，后面整体原样拷贝
        if marker in (0xDA, 0xD9) or marker == 0x00 or (0xD0 <= marker <= 0xD7):
            if not inserted:
                out.extend(app1_xmp)
                inserted = True
            out.extend(jpeg_bytes[pos:])
            break

        if marker_pos + 2 >= n:
            if not inserted:
                out.extend(app1_xmp)
                inserted = True
            out.extend(jpeg_bytes[pos:])
            break

        seg_len = struct.unpack(">H", jpeg_bytes[marker_pos + 1 : marker_pos + 3])[0]
        seg_end = marker_pos + 1 + seg_len
        if seg_len < 2 or seg_end > n:
            if not inserted:
                out.extend(app1_xmp)
                inserted = True
            out.extend(jpeg_bytes[pos:])
            break

        seg_payload = jpeg_bytes[marker_pos + 3 : seg_end]

        # APP0 与 Exif APP1 必须排在前面，完整原样保留
        if marker == 0xE0 or (marker == 0xE1 and seg_payload.startswith(b"Exif\x00\x00")):
            out.extend(jpeg_bytes[pos:seg_end])
            pos = seg_end
            continue

        # 替换已有的 XMP APP1 段（若存在）
        if marker == 0xE1 and seg_payload.startswith(XMP_HEADER):
            pos = seg_end
            continue

        # 遇到其它段时先插入我们的 XMP APP1，再保留原段与后续全部图像熵流
        if not inserted:
            out.extend(app1_xmp)
            inserted = True
        out.extend(jpeg_bytes[pos:])
        break

    if not inserted:
        out.extend(app1_xmp)
    return bytes(out)


def build_motion_photo_bytes(still_img_bytes: bytes, mp4_bytes: bytes) -> bytes:
    """将静态图与 MP4 字节流合成为标准 Google Motion Photo JPEG 字节流。"""
    if not still_img_bytes:
        raise ValueError("静态图数据为空")
    if not mp4_bytes:
        raise ValueError("MP4 视频数据为空")

    jpg_bytes = _ensure_jpeg_bytes(still_img_bytes)
    if is_motion_photo_jpeg(jpg_bytes):
        return jpg_bytes

    xmp_payload = _build_xmp_payload(len(mp4_bytes))
    injected_jpg = _inject_xmp_app1(jpg_bytes, xmp_payload)
    return injected_jpg + mp4_bytes


def build_motion_photo_file(
    img_path: Union[str, os.PathLike],
    video_path: Union[str, os.PathLike],
    out_path: Union[str, os.PathLike],
    cache_dir: Optional[str] = None,
) -> str:
    """读取图片和伴生视频（.mov/.mp4），生成单文件 Android Motion Photo JPEG。"""
    with open(os.fspath(img_path), "rb") as fp:
        still_bytes = fp.read()
    mp4_bytes = ensure_mp4_bytes(video_path, cache_dir=cache_dir)
    merged = build_motion_photo_bytes(still_bytes, mp4_bytes)
    target = os.path.abspath(os.fspath(out_path))
    _atomic_write_bytes(target, merged)
    return target


def get_or_build_motion_photo_bytes(
    img_path: Union[str, os.PathLike],
    video_path: Union[str, os.PathLike],
    cache_dir: Optional[str] = None,
) -> bytes:
    """带磁盘缓存的 Motion Photo 构建：以 (图片stat, 视频stat) 哈希命盘，命中即毫秒返回。"""
    img_real = os.path.realpath(os.fspath(img_path))
    vid_real = os.path.realpath(os.fspath(video_path))
    st_i = os.stat(img_real)
    st_v = os.stat(vid_real)
    key = f"{img_real}|{st_i.st_mtime_ns}|{st_i.st_size}|{vid_real}|{st_v.st_mtime_ns}|{st_v.st_size}"
    digest = hashlib.sha256(key.encode("utf-8")).hexdigest()[:24]

    merged_cache_dir = os.path.join(cache_dir or _default_cache_root(), "merged")
    cached_jpg = os.path.join(merged_cache_dir, f"{digest}.jpg")
    if os.path.isfile(cached_jpg) and os.path.getsize(cached_jpg) > st_v.st_size:
        with open(cached_jpg, "rb") as fp:
            return fp.read()

    with open(img_real, "rb") as fp:
        still_bytes = fp.read()
    mp4_bytes = ensure_mp4_bytes(vid_real, cache_dir=cache_dir)
    merged = build_motion_photo_bytes(still_bytes, mp4_bytes)
    try:
        _atomic_write_bytes(cached_jpg, merged)
        _evict_cache_if_needed(merged_cache_dir)
    except OSError:
        pass
    return merged


def _evict_cache_dirs(parent_dir: str, limit: int = DEFAULT_CACHE_LIMIT) -> None:
    try:
        entries = []
        for entry in os.scandir(parent_dir):
            if entry.is_dir() and not entry.name.endswith(".tmp"):
                try:
                    entries.append((entry.stat().st_mtime_ns, entry.path))
                except OSError:
                    pass
        if len(entries) <= limit:
            return
        entries.sort(key=lambda item: item[0])
        for _, path in entries[: len(entries) - limit]:
            try:
                shutil.rmtree(path, ignore_errors=True)
            except OSError:
                pass
    except OSError:
        pass


def _compute_even_output_size(img_path: str, max_long: int = 1440, min_short: int = 320) -> Tuple[int, int]:
    src_w, src_h = 1080, 1440
    if HAS_PIL:
        with Image.open(img_path) as im:
            if ImageOps is not None:
                im = ImageOps.exif_transpose(im)
            src_w, src_h = im.size
    long_side = max(src_w, src_h)
    short_side = min(src_w, src_h)
    scale = 1.0
    if long_side > max_long:
        scale = max_long / float(long_side)
    elif short_side < min_short and short_side > 0:
        scale = min_short / float(short_side)
    out_w = max(2, int(round(src_w * scale)) // 2 * 2)
    out_h = max(2, int(round(src_h * scale)) // 2 * 2)
    return out_w, out_h


# Reference relative-scale delta and center translation as fractions of output dimensions.
_PUSH_Z = (0.000000, 0.005147, 0.010059, 0.015035, 0.020014, 0.024423, 0.029570, 0.034068, 0.038547, 0.043122,
        0.047424, 0.051740, 0.055742, 0.059774, 0.063531, 0.067648, 0.071081, 0.074225, 0.077471, 0.080498,
        0.083755, 0.086177, 0.088734, 0.090810, 0.092911, 0.094454, 0.096326, 0.097324, 0.098396, 0.098922,
        0.099791, 0.099937, 0.100696, 0.101306, 0.102127, 0.103062, 0.103789, 0.104631, 0.105781, 0.106773,
        0.106803, 0.107560, 0.108322, 0.109408, 0.110110, 0.110823, 0.111166, 0.111724, 0.112540, 0.112768,
        0.113310, 0.113580, 0.114407, 0.114073, 0.114779, 0.114588, 0.114858, 0.115460, 0.115503, 0.115388)
_PUSH_X = (0.000000, 0.001051, 0.000706, 0.000370, 0.001428, 0.000721, 0.001817, 0.001116, 0.001828, 0.001107,
        0.000033, 0.000758, -0.000358, 0.000027, 0.000410, 0.000783, 0.002294, 0.002302, 0.002298, 0.000778,
        0.000778, 0.000372, 0.000007, -0.000736, 0.000401, 0.001168, 0.001882, 0.002320, 0.002744, 0.001626,
        0.000460, -0.001149, -0.000786, 0.001178, 0.000026, -0.001123, -0.003840, -0.005091, -0.006197, -0.007365,
        -0.008945, -0.010121, -0.009741, -0.010887, -0.010531, -0.011712, -0.014800, -0.015999, -0.017265, -0.020384,
        -0.021461, -0.023100, -0.022755, -0.024382, -0.024274, -0.025846, -0.025574, -0.027083, -0.028628, -0.031778)
_PUSH_Y = (0.000000, 0.000512, 0.000790, 0.001052, 0.000225, 0.000248, 0.000548, 0.000537, 0.000528, 0.001633,
        0.001690, 0.001402, 0.001110, 0.000848, 0.000290, 0.000021, 0.000586, -0.000272, 0.000008, 0.001440,
        0.001696, 0.001703, 0.001715, 0.001769, 0.001436, 0.000857, 0.000588, 0.000006, -0.000558, -0.000250,
        0.000039, 0.000017, 0.001445, 0.001751, 0.002010, 0.001470, 0.001720, 0.000834, 0.000278, 0.000525,
        -0.000278, -0.001148, -0.000874, -0.000277, 0.000015, 0.001469, 0.001777, 0.001759, 0.002020, 0.002399,
        0.001468, 0.000277, 0.000601, -0.000580, -0.000326, -0.000255, -0.000317, -0.000006, 0.001204, 0.001151)


def _push_curve_expr(values, p):
    # Piecewise linear via a sum of clamped ramps; avoids deep nested if expressions.
    terms = [f"{values[0]:.6f}"]
    for i in range(1, len(values)):
        d = values[i] - values[i - 1]
        terms.append(f"({d:.6f})*clip(({p})*{len(values)-1}-{i-1},0,1)")
    return "(" + "+".join(terms) + ")"


def _build_zoompan_filter(
    mode: str,
    ratio: float,
    total_frames: int,
    out_w: int,
    out_h: int,
    fps: int,
) -> str:
    den = max(1, total_frames - 1)
    p = f"min(1,on/{den})"
    e = f"((1-cos(PI*{p}))/2)"
    cx = "max(0,min(iw-iw/zoom,(iw-iw/zoom)/2))"
    cy = "max(0,min(ih-ih/zoom,(ih-ih/zoom)/2))"

    if mode == "handheld_push_drift_v1":
        gain = (max(1.005, min(1.12, ratio)) - 1.0) / 0.115503
        z_expr = f"1+{gain:.9f}*{_push_curve_expr(_PUSH_Z, p)}"
        dx = _push_curve_expr(_PUSH_X, p)
        dy = _push_curve_expr(_PUSH_Y, p)
        x_expr = f"max(0,min(iw-iw/zoom,(iw-iw/zoom)/2-iw*{gain:.9f}*{dx}/zoom))"
        y_expr = f"max(0,min(ih-ih/zoom,(ih-ih/zoom)/2-ih*{gain:.9f}*{dy}/zoom))"
    elif mode == "natural_hold":
        delta = max(0.008, ratio - 1.0)
        z_expr = f"1.0+{delta:.4f}*(0.15+0.85*{e})"
        x_expr = f"max(0,min(iw-iw/zoom,(iw-iw/zoom)*(0.5+0.14*sin(PI*{p}))))"
        y_expr = f"max(0,min(ih-ih/zoom,(ih-ih/zoom)*(0.5+0.10*sin(PI*{p}))))"
    elif mode == "subtle_still":
        eff_delta = max(0.005, (ratio - 1.0) * 0.45)
        z_expr = f"1.0+{eff_delta:.4f}*{e}"
        x_expr, y_expr = cx, cy
    elif mode == "zoom_out":
        z_expr = f"{ratio:.4f}-{ratio - 1.0:.4f}*{e}"
        x_expr, y_expr = cx, cy
    elif mode == "pan_up":
        z_expr = f"{ratio:.4f}"
        x_expr = cx
        y_expr = f"max(0,min(ih-ih/zoom,(ih-ih/zoom)*(1-{e})))"
    elif mode == "pan_down":
        z_expr = f"{ratio:.4f}"
        x_expr = cx
        y_expr = f"max(0,min(ih-ih/zoom,(ih-ih/zoom)*{e}))"
    elif mode == "pan_left":
        z_expr = f"{ratio:.4f}"
        x_expr = f"max(0,min(iw-iw/zoom,(iw-iw/zoom)*(1-{e})))"
        y_expr = cy
    elif mode == "pan_right":
        z_expr = f"{ratio:.4f}"
        x_expr = f"max(0,min(iw-iw/zoom,(iw-iw/zoom)*{e}))"
        y_expr = cy
    elif mode == "handheld":
        z_expr = f"1.0+{ratio - 1.0:.4f}*(0.45+0.35*sin(PI*{p}))"
        x_expr = f"max(0,min(iw-iw/zoom,(iw-iw/zoom)*(0.5+0.20*sin(2*PI*{p})*sin(PI*{p}))))"
        y_expr = f"max(0,min(ih-ih/zoom,(ih-ih/zoom)*(0.5+0.14*sin(1.5*PI*{p})*sin(PI*{p}))))"
    else:  # zoom_in
        z_expr = f"1.0+{ratio - 1.0:.4f}*{e}"
        x_expr, y_expr = cx, cy

    scale_mult = min(3.0, 4096.0 / float(max(out_w, out_h, 1)))
    scale_mult = max(2.0, scale_mult)
    pre_w = max(2, int(round(out_w * scale_mult)) // 2 * 2)
    pre_h = max(2, int(round(out_h * scale_mult)) // 2 * 2)
    return (
        f"scale={pre_w}:{pre_h}:flags=lanczos,"
        f"zoompan=z='{z_expr}':x='{x_expr}':y='{y_expr}':"
        f"d={total_frames}:s={out_w}x{out_h}:fps={fps}"
    )


def _inject_apple_live_pair_metadata(jpg_path: str, mov_path: str, asset_id: str) -> bool:
    exiftool = find_exiftool()
    if not exiftool:
        return False
    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
    try:
        if os.path.isfile(APPLE_MAKERNOTE_TEMPLATE):
            subprocess.run(
                [exiftool, "-overwrite_original", "-TagsFromFile", APPLE_MAKERNOTE_TEMPLATE, "-makernotes", jpg_path],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=creationflags,
            )
        subprocess.run(
            [exiftool, "-overwrite_original", f"-MakerNotes:ContentIdentifier={asset_id}", jpg_path],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=creationflags,
            check=True,
        )
        subprocess.run(
            [exiftool, "-overwrite_original", f"-Keys:ContentIdentifier={asset_id}", mov_path],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=creationflags,
            check=True,
        )
        return True
    except Exception:
        return False


def synthesize_live_pair(
    img_path: Union[str, os.PathLike],
    config: Optional[Dict[str, Any]] = None,
    cache_dir: Optional[str] = None,
) -> Dict[str, Any]:
    """将静态照片用纯本地 FFmpeg 微动渲染引擎合成为跨端实况组（Apple JPG+MOV 与 Android Motion Photo JPG）。"""
    src = os.path.realpath(os.fspath(img_path))
    if not os.path.isfile(src):
        raise FileNotFoundError(f"输入图片不存在: {src}")

    cfg = normalize_live_config(config)
    sig = config_signature(cfg)
    st = os.stat(src)
    cache_key = f"{src}|{st.st_mtime_ns}|{st.st_size}|{sig}"
    digest = hashlib.sha256(cache_key.encode("utf-8")).hexdigest()[:24]

    synth_root = os.path.join(cache_dir or _default_cache_root(), "synth")
    entry_dir = os.path.join(synth_root, digest)
    apple_jpg = os.path.join(entry_dir, "apple.jpg")
    apple_mov = os.path.join(entry_dir, "apple.mov")
    motion_jpg = os.path.join(entry_dir, "motion.jpg")

    if (
        os.path.isfile(apple_jpg) and os.path.getsize(apple_jpg) > 0
        and os.path.isfile(apple_mov) and os.path.getsize(apple_mov) > 0
        and os.path.isfile(motion_jpg) and os.path.getsize(motion_jpg) > 0
    ):
        return {
            "apple_jpg": apple_jpg,
            "apple_mov": apple_mov,
            "motion_jpg": motion_jpg,
            "config": cfg,
            "signature": sig,
            "cached": True,
        }

    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        raise RuntimeError("未找到 ffmpeg，无法在本地合成微动实况")

    out_w, out_h = _compute_even_output_size(src)
    total_frames = max(2, int(round(cfg["duration"] * cfg["fps"])))
    vf_filter = _build_zoompan_filter(cfg["mode"], cfg["ratio"], total_frames, out_w, out_h, cfg["fps"])

    os.makedirs(synth_root, exist_ok=True)
    tmp_dir = os.path.join(synth_root, f"{digest}.{uuid.uuid4().hex[:8]}.tmp")
    os.makedirs(tmp_dir, exist_ok=True)
    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0

    try:
        tmp_mov = os.path.join(tmp_dir, "apple.mov")
        tmp_jpg = os.path.join(tmp_dir, "apple.jpg")
        tmp_motion = os.path.join(tmp_dir, "motion.jpg")

        cmd_mov = [
            ffmpeg, "-y", "-loglevel", "error",
            "-loop", "1", "-i", src,
            "-vf", vf_filter,
            "-t", f"{cfg['duration']:.2f}",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart",
            tmp_mov,
        ]
        subprocess.run(cmd_mov, stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=creationflags, check=True)

        cmd_cover = [
            ffmpeg, "-y", "-loglevel", "error",
            "-i", tmp_mov, "-vframes", "1", "-q:v", "2",
            tmp_jpg,
        ]
        subprocess.run(cmd_cover, stdout=subprocess.PIPE, stderr=subprocess.PIPE, creationflags=creationflags, check=True)

        asset_id = str(uuid.uuid4()).upper()
        _inject_apple_live_pair_metadata(tmp_jpg, tmp_mov, asset_id)

        with open(tmp_jpg, "rb") as fp:
            jpg_bytes = fp.read()
        mp4_bytes = ensure_mp4_bytes(tmp_mov, cache_dir=cache_dir)
        merged_bytes = build_motion_photo_bytes(jpg_bytes, mp4_bytes)
        with open(tmp_motion, "wb") as fp:
            fp.write(merged_bytes)

        os.makedirs(entry_dir, exist_ok=True)
        os.replace(tmp_mov, apple_mov)
        os.replace(tmp_jpg, apple_jpg)
        os.replace(tmp_motion, motion_jpg)
        _evict_cache_dirs(synth_root)
        return {
            "apple_jpg": apple_jpg,
            "apple_mov": apple_mov,
            "motion_jpg": motion_jpg,
            "config": cfg,
            "signature": sig,
            "cached": False,
        }
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)
