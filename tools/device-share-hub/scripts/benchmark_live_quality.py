#!/usr/bin/env python3
"""Isolated Lossless Live Photo Quality Benchmark.

Safely copies a few real sample images from D:\\江湖有旅人\\03-生产车间\\05-成品库
(strictly read-only; source folder is NEVER modified) plus synthetic edge-case samples
(>3072px, EXIF Orientation=6, 4-grid + small Chinese typography, PNG), then compares:
  - Old pipeline: decode -> max 3072px downscale -> JPEG q=92 re-encode
  - New pipeline: lossless JPEG XMP+MP4 injection (0 decode/re-encode, SOS..EOI bit-identical)
                  and 3 dynamic video tiers (1080p/3M, 1440p/8M, 2048p/16M).
Outputs a sanitized JSON report to tools/device-share-hub/docs/benchmark_live_quality_report.json.
"""

from __future__ import annotations

import json
import math
import os
import shutil
import struct
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
from PIL import Image, ImageDraw, ImageFont

LIBRARY_ROOT = Path(r"D:\江湖有旅人\03-生产车间\05-成品库")
SCRIPT_DIR = Path(__file__).resolve().parent
DOCS_DIR = SCRIPT_DIR.parent / "docs"
REPORT_PATH = DOCS_DIR / "benchmark_live_quality_report.json"

TIERS = [
    ("smooth", 1080, 3_000_000),
    ("hd", 1440, 8_000_000),
    ("original", 2048, 16_000_000),
]


def _pick_real_samples(max_samples: int = 3) -> List[Path]:
    picked: List[Path] = []
    if not LIBRARY_ROOT.is_dir():
        return picked
    for root, dirs, files in os.walk(LIBRARY_ROOT):
        dirs[:] = [d for d in dirs if not d.startswith(("_", ".")) and "已发送" not in d]
        for fn in sorted(files):
            if fn.lower().endswith((".jpg", ".jpeg")) and not fn.startswith(("._", "tmp")):
                cand = Path(root) / fn
                if cand.is_file() and cand.stat().st_size > 150_000:
                    picked.append(cand)
                    if len(picked) >= max_samples:
                        return picked
    return picked


def _build_synthetic_samples(tmp_dir: Path) -> List[Tuple[str, Path]]:
    samples: List[Tuple[str, Path]] = []

    # 1. >3072px vertical 3:4
    p1 = tmp_dir / "synth_over_3072_3x4.jpg"
    im1 = Image.new("RGB", (3600, 4800), (242, 238, 228))
    d1 = ImageDraw.Draw(im1)
    for y in range(0, 4800, 120):
        d1.line([(0, y), (3600, y)], fill=(195, 210, 225), width=4)
    d1.rectangle([180, 180, 3420, 4620], outline=(40, 90, 140), width=16)
    d1.text((320, 320), "TEAM VIDEO 3600x4800 DETAIL CHECK", fill=(25, 25, 30))
    im1.save(p1, format="JPEG", quality=96, subsampling=0)
    samples.append(("synth_over_3072_3x4", p1))

    # 2. EXIF Orientation=6 rotated JPEG
    p2 = tmp_dir / "synth_exif_rot6.jpg"
    im2 = Image.new("RGB", (3200, 2400), (210, 228, 240))
    d2 = ImageDraw.Draw(im2)
    d2.rectangle([100, 100, 900, 600], fill=(220, 80, 60))
    exif = Image.Exif()
    exif[274] = 6  # Orientation = Rotate 90 CW
    im2.save(p2, format="JPEG", quality=95, exif=exif.tobytes())
    samples.append(("synth_exif_rot6", p2))

    # 3. 4-grid + small Chinese text + map lines
    p3 = tmp_dir / "synth_4grid_small_text.jpg"
    im3 = Image.new("RGB", (2160, 2880), (252, 250, 246))
    d3 = ImageDraw.Draw(im3)
    d3.rectangle([80, 80, 1040, 1360], fill=(70, 130, 180), outline=(255, 255, 255), width=8)
    d3.rectangle([1120, 80, 2080, 1360], fill=(80, 160, 110), outline=(255, 255, 255), width=8)
    d3.rectangle([80, 1440, 1040, 2760], fill=(210, 150, 90), outline=(255, 255, 255), width=8)
    d3.rectangle([1120, 1440, 2080, 2760], fill=(245, 242, 232), outline=(40, 40, 40), width=6)
    for k in range(20):
        d3.line([(1160, 1500 + k * 55), (2040, 1500 + k * 55)], fill=(50, 70, 95), width=2)
    font = None
    for fp in (r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"):
        if os.path.isfile(fp):
            try:
                font = ImageFont.truetype(fp, 28)
                break
            except Exception:
                pass
    d3.text((1180, 1520), "路线攻略：A口步行380米至集合点\n小字清晰度基准测试 123456", fill=(15, 15, 20), font=font)
    im3.save(p3, format="JPEG", quality=95, subsampling=0)
    samples.append(("synth_4grid_small_text", p3))

    # 4. Lossless PNG source
    p4 = tmp_dir / "synth_lossless_source.png"
    im4 = Image.new("RGB", (2400, 3200), (236, 244, 248))
    d4 = ImageDraw.Draw(im4)
    d4.text((220, 240), "PNG LOSSLESS INPUT CHECK 2400x3200", fill=(20, 30, 45), font=font)
    im4.save(p4, format="PNG")
    samples.append(("synth_lossless_source", p4))

    return samples


def _find_jpeg_sos_offset(data: bytes) -> int:
    pos = 2
    n = len(data)
    while pos + 4 <= n:
        if data[pos] != 0xFF:
            break
        marker = data[pos + 1]
        if marker == 0xDA:  # SOS
            return pos
        if marker in (0xD8, 0xD9) or (0xD0 <= marker <= 0xD7):
            pos += 2
            continue
        seg_len = struct.unpack(">H", data[pos + 2 : pos + 4])[0]
        pos += 2 + seg_len
    return -1


def _extract_jpeg_sos_to_eoi(data: bytes) -> bytes:
    sos = _find_jpeg_sos_offset(data)
    if sos < 0:
        return b""
    eoi = data.rfind(b"\xff\xd9")
    if eoi < sos:
        return data[sos:]
    return data[sos : eoi + 2]


def _inject_motion_photo_lossless(jpg_bytes: bytes, mp4_bytes: bytes) -> bytes:
    assert jpg_bytes[:2] == b"\xff\xd8"
    eoi_pos = jpg_bytes.rfind(b"\xff\xd9")
    if eoi_pos < 2:
        eoi_pos = len(jpg_bytes)
    xmp_xml = (
        '<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>'
        '<x:xmpmeta xmlns:x="adobe:ns:meta/">'
        '<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
        '<rdf:Description rdf:about="" '
        'xmlns:GCamera="http://ns.google.com/cameras/1.0/camera/" '
        'xmlns:Container="http://ns.google.com/photos/1.0/container/" '
        'xmlns:Item="http://ns.google.com/photos/1.0/container/item/" '
        'GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" '
        'GCamera:MotionPhotoPresentationTimestampUs="800000">'
        '<Container:Directory><rdf:Seq>'
        '<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/></rdf:li>'
        f'<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="{len(mp4_bytes)}"/></rdf:li>'
        '</rdf:Seq></Container:Directory>'
        '</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end="w"?>'
    ).encode("utf-8")
    xmp_payload = b"http://ns.adobe.com/xap/1.0/\x00" + xmp_xml
    app1_seg = b"\xff\xe1" + struct.pack(">H", len(xmp_payload) + 2) + xmp_payload
    # Insert APP1 right before SOS so existing SOI/EXIF/ICC/DQT/DHT stay untouched!
    insert_at = _find_jpeg_sos_offset(jpg_bytes)
    if insert_at < 2:
        insert_at = 2
    return jpg_bytes[:insert_at] + app1_seg + jpg_bytes[insert_at : eoi_pos + 2] + mp4_bytes


def _extract_primary_jpeg_from_motion(packed: bytes, mp4_len: int) -> bytes:
    if mp4_len > 0 and len(packed) > mp4_len:
        return packed[:-mp4_len]
    return packed


def _compute_psnr_ssim(orig_rgb: np.ndarray, test_rgb: np.ndarray) -> Tuple[Optional[float], float]:
    if orig_rgb.shape != test_rgb.shape:
        # Resize test back ONLY to report dimensional degradation impact
        test_img = Image.fromarray(test_rgb).resize((orig_rgb.shape[1], orig_rgb.shape[0]), Image.BICUBIC)
        test_rgb = np.asarray(test_img)
    diff = orig_rgb.astype(np.float64) - test_rgb.astype(np.float64)
    mse = float(np.mean(diff ** 2))
    if mse == 0.0:
        return None, 1.0  # Infinity PSNR, exact match
    psnr = 10.0 * math.log10((255.0 ** 2) / mse)
    # Global simplified luminance SSIM
    o_y = np.dot(orig_rgb[..., :3], [0.299, 0.587, 0.114])
    t_y = np.dot(test_rgb[..., :3], [0.299, 0.587, 0.114])
    c1, c2 = 6.5025, 58.5225
    mu_x, mu_y = float(np.mean(o_y)), float(np.mean(t_y))
    vx, vy = float(np.var(o_y)), float(np.var(t_y))
    cov = float(np.mean((o_y - mu_x) * (t_y - mu_y)))
    ssim = ((2 * mu_x * mu_y + c1) * (2 * cov + c2)) / ((mu_x ** 2 + mu_y ** 2 + c1) * (vx + vy + c2))
    return round(psnr, 2), round(float(ssim), 6)


def _find_ffmpeg() -> str:
    for cand in (r"C:\ffmpeg\bin\ffmpeg.exe", shutil.which("ffmpeg") or "ffmpeg"):
        if cand and os.path.isfile(cand):
            return cand
    return "ffmpeg"


def _encode_tier_video(src_img: Path, out_mp4: Path, max_dim: int, bitrate: int) -> Dict[str, Any]:
    with Image.open(src_img) as im:
        w, h = im.size
    long_side = max(w, h)
    scale = min(1.0, max_dim / float(long_side)) if long_side > max_dim else 1.0
    tw = max(64, int(round(w * scale)) & ~1)
    th = max(64, int(round(h * scale)) & ~1)
    t0 = time.perf_counter()
    cmd = [
        _find_ffmpeg(), "-y", "-hide_banner", "-loglevel", "error",
        "-loop", "1", "-i", str(src_img),
        "-vf", f"scale={tw}:{th},zoompan=z='1.0+0.018*in/48':d=48:s={tw}x{th}:fps=30",
        "-t", "1.6", "-c:v", "libx264", "-pix_fmt", "yuv420p",
        "-b:v", str(bitrate), "-maxrate", str(int(bitrate * 1.3)),
        "-bufsize", str(bitrate), "-movflags", "+faststart", str(out_mp4),
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    ms = int((time.perf_counter() - t0) * 1000)
    if proc.returncode != 0 or not out_mp4.is_file():
        return {"ok": False, "error": proc.stderr[:200], "ms": ms}
    return {
        "ok": True,
        "videoWidth": tw,
        "videoHeight": th,
        "upscaled": False,
        "videoBytes": out_mp4.stat().st_size,
        "targetBitrate": bitrate,
        "elapsedMs": ms,
    }


def main() -> None:
    DOCS_DIR.mkdir(parents=True, exist_ok=True)
    results: List[Dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="live_bench_") as tmp:
        tmp_path = Path(tmp)
        real_paths = _pick_real_samples(3)
        samples: List[Tuple[str, Path]] = []
        for idx, rp in enumerate(real_paths, 1):
            copy_dst = tmp_path / f"real_sample_{idx}{rp.suffix.lower()}"
            shutil.copy2(rp, copy_dst)
            samples.append((f"real_sample_{idx}", copy_dst))
        samples.extend(_build_synthetic_samples(tmp_path))

        # Encode video tiers once cleanly on synthetic PNG image (universal FFmpeg input)
        tier_video_stats: Dict[str, Any] = {}
        bench_img = [p for name, p in samples if "lossless_source" in name][0]
        demo_mp4 = tmp_path / "demo_hd.mp4"
        for tier_id, max_dim, bitrate in TIERS:
            out_mp4 = tmp_path / f"tier_{tier_id}.mp4"
            tier_video_stats[tier_id] = _encode_tier_video(bench_img, out_mp4, max_dim, bitrate)
            if tier_id == "hd" and out_mp4.is_file():
                demo_mp4 = out_mp4

        mp4_bytes = demo_mp4.read_bytes() if demo_mp4.is_file() else b"ftypmp42" * 64

        for label, src_path in samples:
            orig_bytes = src_path.read_bytes()
            is_jpeg = src_path.suffix.lower() in (".jpg", ".jpeg") and orig_bytes[:2] == b"\xff\xd8"
            with Image.open(src_path) as im:
                orig_w, orig_h = im.size
                exif_ori = im.getexif().get(274, 1)
                orig_rgb = np.asarray(im.convert("RGB"))

            # OLD pipeline: downscale to max 3072px + re-encode q=92
            t_old = time.perf_counter()
            old_scale = min(1.0, 3072.0 / float(max(orig_w, orig_h)))
            old_w = max(1, int(round(orig_w * old_scale)))
            old_h = max(1, int(round(orig_h * old_scale)))
            old_img = Image.fromarray(orig_rgb).resize((old_w, old_h), Image.LANCZOS) if old_scale < 1.0 else Image.fromarray(orig_rgb)
            old_jpg_path = tmp_path / f"old_{label}.jpg"
            old_img.save(old_jpg_path, format="JPEG", quality=92)
            old_jpg_bytes = old_jpg_path.read_bytes()
            old_ms = int((time.perf_counter() - t_old) * 1000)
            old_decoded = np.asarray(Image.open(old_jpg_path).convert("RGB"))
            old_psnr, old_ssim = _compute_psnr_ssim(orig_rgb, old_decoded)

            # NEW pipeline: true lossless cover for JPEG, q=98 full-res fallback for PNG
            t_new = time.perf_counter()
            if is_jpeg:
                packed_bytes = _inject_motion_photo_lossless(orig_bytes, mp4_bytes)
                new_cover = _extract_primary_jpeg_from_motion(packed_bytes, len(mp4_bytes))
                sos_exact = _extract_jpeg_sos_to_eoi(orig_bytes) == _extract_jpeg_sos_to_eoi(new_cover)
                reencode_reason = "none_lossless_bitstream"
            else:
                new_jpg_path = tmp_path / f"new_{label}.jpg"
                Image.fromarray(orig_rgb).save(new_jpg_path, format="JPEG", quality=98, subsampling=0)
                new_cover = new_jpg_path.read_bytes()
                packed_bytes = _inject_motion_photo_lossless(new_cover, mp4_bytes)
                sos_exact = False
                reencode_reason = "source_not_jpeg_converted_q98_fullres"
            new_ms = int((time.perf_counter() - t_new) * 1000)

            new_cover_path = tmp_path / f"new_cover_{label}.jpg"
            new_cover_path.write_bytes(new_cover)
            with Image.open(new_cover_path) as nim:
                new_w, new_h = nim.size
                new_exif_ori = nim.getexif().get(274, 1)
                new_decoded = np.asarray(nim.convert("RGB"))
            new_psnr, new_ssim = _compute_psnr_ssim(orig_rgb, new_decoded)

            results.append({
                "sample": label,
                "sourceFormat": "JPEG" if is_jpeg else "PNG",
                "origResolution": f"{orig_w}x{orig_h}",
                "origExifOrientation": int(exif_ori),
                "origBytes": len(orig_bytes),
                "oldPipeline": {
                    "resolution": f"{old_w}x{old_h}",
                    "staticBytes": len(old_jpg_bytes),
                    "psnrDb": old_psnr,
                    "ssim": old_ssim,
                    "elapsedMs": old_ms,
                },
                "newPipeline": {
                    "resolution": f"{new_w}x{new_h}",
                    "packedExifOrientation": int(new_exif_ori),
                    "staticCoverBytes": len(new_cover),
                    "packedTotalBytes": len(packed_bytes),
                    "jpegSosBitIdentical": sos_exact,
                    "reencodeReason": reencode_reason,
                    "psnrDb": new_psnr if new_psnr is not None else "INF_LOSSLESS",
                    "ssim": new_ssim,
                    "elapsedMs": new_ms,
                },
            })

        report = {
            "ok": True,
            "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S+08:00"),
            "realSampleCount": len(real_paths),
            "staticCoverComparisons": results,
            "dynamicVideoTiers": tier_video_stats,
        }
        REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
