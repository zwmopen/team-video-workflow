from __future__ import annotations

import argparse
import csv
import hashlib
import json
import subprocess
import sys
from dataclasses import asdict
from datetime import datetime
from pathlib import Path
from typing import Any


TOOL_ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = TOOL_ROOT / "src"
BROWSER_ROOT = Path(r"D:\AICode\AI\tools\team-video-library-browser")
if str(SRC_ROOT) not in sys.path:
    sys.path.insert(0, str(SRC_ROOT))
if str(BROWSER_ROOT) not in sys.path:
    sys.path.insert(0, str(BROWSER_ROOT))

from tb_scene.category_router import classify_from_metadata
from tb_scene.ffmpeg_utils import find_ffmpeg, find_ffprobe, probe_video
from tb_scene.models import SceneCut, VideoInfo
from tb_scene.path_utils import ensure_unique_path, sanitize_name
from tb_scene.scanner import orientation_for, sha256_file
from tb_scene.scene_detector import detect_scenes

import server as browser_server


VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm"}
DEFAULT_LIBRARY_ROOT = Path(r"D:\Download\素材下载\团建视频")
DEFAULT_LOCATIONS = ("千岛湖", "安吉", "莫干山")
GENERATED_DIR_MARKERS = {
    "裁切废料",
    "裁去字幕",
    "字幕之上",
    "手动处理",
    "裁切废料测试",
    "_check",
    "废料",
    "归档",
}
MANIFEST_FIELDS = [
    "run_time",
    "location",
    "source",
    "source_video_id",
    "scene_id",
    "start",
    "end",
    "crop_rect",
    "crop_confidence",
    "crop_reason",
    "start_floor",
    "category",
    "keyword",
    "status",
    "reason",
    "output",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Batch crop waste from organized original videos, split scenes, and write new clips into existing scene folders."
    )
    parser.add_argument("--library-root", default=str(DEFAULT_LIBRARY_ROOT))
    parser.add_argument("--locations", nargs="*", default=list(DEFAULT_LOCATIONS))
    parser.add_argument("--max-videos-per-location", type=int, default=None)
    parser.add_argument("--max-scenes-per-video", type=int, default=None)
    parser.add_argument("--orientation", choices=["vertical", "all"], default="vertical")
    parser.add_argument("--detector", choices=["adaptive", "content"], default="adaptive")
    parser.add_argument("--threshold", type=float, default=None)
    parser.add_argument("--min-scene-len", type=int, default=15)
    parser.add_argument("--crf", type=int, default=20)
    parser.add_argument("--preset", default="veryfast")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def path_id(path: Path) -> str:
    return hashlib.sha1(str(path.resolve()).encode("utf-8", errors="ignore")).hexdigest()[:16]


def is_generated_or_system_path(path: Path) -> bool:
    parts = set(path.parts)
    return bool(parts & GENERATED_DIR_MARKERS) or any(part.startswith("._") for part in path.parts)


def list_source_videos(source_dir: Path) -> list[Path]:
    return [
        path
        for path in sorted(source_dir.rglob("*"), key=lambda item: str(item).lower())
        if path.is_file()
        and path.suffix.lower() in VIDEO_EXTENSIONS
        and not is_generated_or_system_path(path)
    ]


def make_video_info(path: Path, video_id: str, ffmpeg: Path, ffprobe: Path | None) -> VideoInfo:
    probe = probe_video(path, ffmpeg, ffprobe)
    width = int(probe["width"])
    height = int(probe["height"])
    stat = path.stat()
    return VideoInfo(
        path=path,
        video_id=video_id,
        sha256=sha256_file(path),
        size=stat.st_size,
        mtime_ns=stat.st_mtime_ns,
        duration=float(probe["duration"]),
        width=width,
        height=height,
        fps=float(probe["fps"]),
        codec=str(probe["codec"]),
        orientation=orientation_for(width, height),
    )


def detect_crop(path: Path, location: str) -> dict[str, Any]:
    item = browser_server.LibraryItem(
        id=path_id(path),
        kind="已整理原片",
        location=location,
        category="原片",
        keyword="裁切废料",
        name=path.name,
        path=str(path),
        size_mb=round(path.stat().st_size / 1024 / 1024, 2),
    )
    try:
        return browser_server.detect_subtitle_crop_rect(item)
    except Exception as exc:
        return {
            "confidence": 0.0,
            "rect": {"x": 0.0, "y": 0.0, "w": 100.0, "h": 88.0},
            "reason": f"裁切检测失败，使用保守默认值: {exc}",
            "suggested_start": 0.0,
            "suggested_start_reason": "",
        }


def should_skip_pseudo_vertical(detection: dict[str, Any], source: VideoInfo) -> str:
    rect = detection.get("rect", {}) or {}
    confidence = float(detection.get("confidence") or 0.0)
    y = float(rect.get("y", 0.0))
    h = float(rect.get("h", 100.0))
    reason = str(detection.get("reason") or "")
    if source.orientation == "vertical" and confidence < 0.45 and y <= 2.0 and h <= 72.0 and "黑边" in reason:
        return "伪竖屏/横屏套壳黑边明显，跳过进入干净竖屏分镜库"
    return ""


def detect_letterbox_crop(video: Path, item_id: str) -> dict[str, Any]:
    try:
        import cv2
        import numpy as np
    except Exception:
        return {"detected": False}
    try:
        frames = browser_server.extract_detection_frames(video, f"{item_id}_letterbox")
    except Exception:
        frames = []
    if not frames:
        return {"detected": False}
    bar_sums: list[float] = []
    top_pcts: list[float] = []
    bottom_pcts: list[float] = []
    for frame in frames:
        image = cv2.imdecode(np.fromfile(str(frame), dtype=np.uint8), cv2.IMREAD_GRAYSCALE)
        if image is None:
            continue
        height, _width = image.shape[:2]
        dark_fraction = (image < 30).mean(axis=1)
        black_rows = dark_fraction >= 0.82
        top = 0
        for value in black_rows:
            if bool(value):
                top += 1
            else:
                break
        bottom = 0
        for value in black_rows[::-1]:
            if bool(value):
                bottom += 1
            else:
                break
        top_pct = top / max(1, height) * 100
        bottom_pct = 100 - bottom / max(1, height) * 100
        top_pcts.append(top_pct)
        bottom_pcts.append(bottom_pct)
        bar_sums.append(top_pct + (100 - bottom_pct))
    if not bar_sums:
        return {"detected": False}
    avg_bar = sum(bar_sums) / len(bar_sums)
    max_bar = max(bar_sums)
    if avg_bar >= 24.0 or max_bar >= 32.0:
        top = sorted(top_pcts)[len(top_pcts) // 2]
        bottom = sorted(bottom_pcts)[len(bottom_pcts) // 2]
        return {
            "detected": True,
            "top_pct": round(top, 3),
            "bottom_pct": round(bottom, 3),
            "avg_bar_pct": round(avg_bar, 3),
            "max_bar_pct": round(max_bar, 3),
            "reason": f"上下黑边/横屏套壳明显，先裁黑边：平均黑边 {avg_bar:.1f}%，最大 {max_bar:.1f}%",
        }
    return {"detected": False, "avg_bar_pct": round(avg_bar, 3), "max_bar_pct": round(max_bar, 3)}


def merge_letterbox_and_subtitle_rect(rect: dict[str, Any], letterbox: dict[str, Any]) -> tuple[dict[str, Any], str]:
    if not letterbox.get("detected"):
        return rect, ""
    top = max(0.0, float(letterbox.get("top_pct", 0.0)))
    bottom = min(100.0, float(letterbox.get("bottom_pct", 100.0)))
    subtitle_y = float(rect.get("y", 0.0))
    subtitle_bottom = subtitle_y + float(rect.get("h", 100.0))
    y = max(subtitle_y, top)
    bottom_limit = min(subtitle_bottom, bottom)
    if letterbox.get("detected"):
        useful_h = max(0.0, bottom_limit - y)
        extra_bottom_cut = min(8.0, max(3.0, useful_h * 0.18))
        bottom_limit = max(y, bottom_limit - extra_bottom_cut)
    h = max(0.0, bottom_limit - y)
    merged = {
        "x": float(rect.get("x", 0.0)),
        "y": round(y, 3),
        "w": float(rect.get("w", 100.0)),
        "h": round(h, 3),
    }
    return merged, str(letterbox.get("reason", ""))


def destination_dir_for(scene_library: Path, classification) -> Path:
    if classification.primary_category == "05_项目活动" and classification.subcategory:
        return scene_library / classification.primary_category / classification.subcategory
    if classification.primary_category == "90_待人工分类":
        return scene_library / "90_待人工分类" / "裁剪分割待复核"
    return scene_library / classification.primary_category


def first_existing_path(*paths: Path) -> Path:
    for path in paths:
        if path.exists():
            return path
    return paths[0]


def build_output_name(location: str, keyword: str, source: VideoInfo, scene: SceneCut, serial: int) -> str:
    keyword = sanitize_name(keyword or "未细分")
    loc = sanitize_name(location)
    return f"{serial:03d}_{loc}_{keyword}_裁剪分割__{source.video_id}_{scene.scene_id}.mp4"


def crop_filter(rect: dict[str, Any]) -> str:
    x = max(0.0, min(95.0, float(rect.get("x", 0.0))))
    y = max(0.0, min(95.0, float(rect.get("y", 0.0))))
    w = max(5.0, min(100.0 - x, float(rect.get("w", 100.0))))
    h = max(5.0, min(100.0 - y, float(rect.get("h", 88.0))))
    return (
        f"crop="
        f"trunc(iw*{w / 100:.6f}/2)*2:"
        f"trunc(ih*{h / 100:.6f}/2)*2:"
        f"trunc(iw*{x / 100:.6f}/2)*2:"
        f"trunc(ih*{y / 100:.6f}/2)*2"
    )


def write_split_clip(
    ffmpeg: Path,
    source: VideoInfo,
    scene: SceneCut,
    output_path: Path,
    rect: dict[str, Any],
    start_floor: float,
    crf: int,
    preset: str,
) -> tuple[bool, str]:
    actual_start = max(scene.start_time, start_floor)
    duration = max(0.01, scene.end_time - actual_start)
    if duration < 0.25:
        if scene.end_time <= start_floor:
            return False, "skip_intro_waste"
        return False, "skip_too_short"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    args = [
        str(ffmpeg),
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-ss",
        f"{actual_start:.3f}",
        "-i",
        str(source.path),
        "-t",
        f"{duration:.3f}",
        "-an",
        "-vf",
        crop_filter(rect),
        "-c:v",
        "libx264",
        "-preset",
        preset,
        "-crf",
        str(crf),
        "-pix_fmt",
        "yuv420p",
        "-movflags",
        "+faststart",
        str(output_path),
    ]
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=300)
    if result.returncode != 0 or not output_path.exists() or output_path.stat().st_size <= 0:
        output_path.unlink(missing_ok=True)
        return False, (result.stderr or result.stdout or "ffmpeg 输出失败").strip()[:300]
    return True, ""


def scene_has_large_title_overlay(ffmpeg: Path, source: VideoInfo, scene: SceneCut, work_dir: Path) -> bool:
    position = min(max(scene.start_time + min(scene.duration * 0.45, 0.8), 0.05), max(0.05, source.duration - 0.05))
    work_dir.mkdir(parents=True, exist_ok=True)
    frame = work_dir / f"{source.video_id}_{scene.scene_id}_title_check.jpg"
    result = subprocess.run(
        [
            str(ffmpeg),
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            f"{position:.3f}",
            "-i",
            str(source.path),
            "-frames:v",
            "1",
            "-vf",
            "scale=540:-1",
            "-q:v",
            "3",
            str(frame),
        ],
        capture_output=True,
        timeout=45,
        check=False,
    )
    if result.returncode != 0 or not frame.exists() or frame.stat().st_size <= 0:
        return False
    try:
        return bool(browser_server.detect_large_title_or_cover_frame(frame))
    except Exception:
        return False


def load_processed(record_path: Path) -> set[str]:
    if not record_path.exists():
        return set()
    try:
        data = json.loads(record_path.read_text(encoding="utf-8"))
    except Exception:
        return set()
    return set(data.get("processed_keys", []))


def save_processed(record_path: Path, processed: set[str]) -> None:
    record_path.parent.mkdir(parents=True, exist_ok=True)
    record_path.write_text(json.dumps({"processed_keys": sorted(processed)}, ensure_ascii=False, indent=2), encoding="utf-8")


def append_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    exists = path.exists()
    if exists:
        try:
            header = path.read_text(encoding="utf-8-sig", errors="ignore").splitlines()[0].split(",")
            if any(field not in header for field in MANIFEST_FIELDS):
                backup = path.with_suffix(path.suffix + ".bad")
                path.replace(ensure_unique_path(backup))
                exists = False
        except Exception:
            exists = False
    with path.open("a", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=MANIFEST_FIELDS, extrasaction="ignore")
        if not exists:
            writer.writeheader()
        writer.writerows([{field: row.get(field, "") for field in MANIFEST_FIELDS} for row in rows])


def extract_thumb(ffmpeg: Path, video: Path, output: Path) -> bool:
    output.parent.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        [
            str(ffmpeg),
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-ss",
            "00:00:00.300",
            "-i",
            str(video),
            "-frames:v",
            "1",
            "-vf",
            "scale=240:-1",
            "-q:v",
            "3",
            str(output),
        ],
        capture_output=True,
        timeout=45,
        check=False,
    )
    return result.returncode == 0 and output.exists() and output.stat().st_size > 0


def make_contact_sheet(ffmpeg: Path, outputs: list[Path], sheet_path: Path, limit: int = 24) -> None:
    try:
        import cv2
        import numpy as np
    except Exception:
        return
    thumbs: list[Any] = []
    temp_dir = sheet_path.parent / "_thumbs"
    for index, video in enumerate(outputs[:limit], start=1):
        thumb = temp_dir / f"{index:03d}.jpg"
        if not extract_thumb(ffmpeg, video, thumb):
            continue
        image = cv2.imdecode(np.fromfile(str(thumb), dtype=np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            continue
        canvas = np.full((260, 240, 3), 245, dtype=np.uint8)
        h, w = image.shape[:2]
        scale = min(240 / max(1, w), 210 / max(1, h))
        resized = cv2.resize(image, (max(1, int(w * scale)), max(1, int(h * scale))))
        y = 0
        x = (240 - resized.shape[1]) // 2
        canvas[y : y + resized.shape[0], x : x + resized.shape[1]] = resized
        label = video.parent.name[:16]
        cv2.putText(canvas, f"{index:02d} {label}", (8, 238), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (30, 30, 30), 1, cv2.LINE_AA)
        thumbs.append(canvas)
    if not thumbs:
        return
    cols = 4
    rows = (len(thumbs) + cols - 1) // cols
    sheet = np.full((rows * 260, cols * 240, 3), 235, dtype=np.uint8)
    for index, thumb in enumerate(thumbs):
        row = index // cols
        col = index % cols
        sheet[row * 260 : row * 260 + 260, col * 240 : col * 240 + 240] = thumb
    cv2.imencode(".jpg", sheet, [int(cv2.IMWRITE_JPEG_QUALITY), 88])[1].tofile(str(sheet_path))


def process_location(args: argparse.Namespace, ffmpeg: Path, ffprobe: Path | None, location: str) -> dict[str, Any]:
    library_root = Path(args.library_root)
    source_dir = first_existing_path(
        library_root / "01_原片素材库" / f"{location}-原视频素材",
        library_root / f"{location}-原视频素材",
    )
    scene_library = first_existing_path(
        library_root / "02_分镜素材库" / f"{location}智能镜头分类",
        library_root / f"{location}智能镜头分类",
    )
    system_dir = scene_library / "._系统记录" / "裁剪分割批处理"
    record_path = system_dir / "processed_state.json"
    manifest_path = system_dir / "裁剪分割_manifest.csv"
    summary_path = system_dir / "裁剪分割_summary.json"
    processed = load_processed(record_path)
    rows: list[dict[str, Any]] = []
    written_outputs: list[Path] = []
    summary: dict[str, Any] = {
        "location": location,
        "source_dir": str(source_dir),
        "scene_library": str(scene_library),
        "dry_run": args.dry_run,
        "videos_found": 0,
        "videos_selected": 0,
        "videos_skipped": 0,
        "scenes_detected": 0,
        "clips_written": 0,
        "clips_skipped": 0,
        "clips_failed": 0,
    }
    if not source_dir.exists() or not scene_library.exists():
        summary["error"] = "source or scene library folder missing"
        return summary

    videos = list_source_videos(source_dir)
    summary["videos_found"] = len(videos)
    selected_count = 0
    serial = 1 + len(list(scene_library.rglob("*裁剪分割*.mp4")))

    for video_index, video in enumerate(videos, start=1):
        if args.max_videos_per_location and selected_count >= args.max_videos_per_location:
            break
        try:
            source = make_video_info(video, f"CV{video_index:03d}", ffmpeg, ffprobe)
        except Exception as exc:
            summary["videos_skipped"] += 1
            rows.append({"location": location, "source": str(video), "status": "skip_probe_failed", "reason": str(exc)})
            continue
        if args.orientation == "vertical" and source.orientation != "vertical":
            summary["videos_skipped"] += 1
            rows.append({"location": location, "source": str(video), "status": "skip_orientation", "reason": source.orientation})
            continue

        selected_count += 1
        summary["videos_selected"] += 1
        letterbox = detect_letterbox_crop(video, path_id(video))
        classification = classify_from_metadata(video)
        dest_dir = destination_dir_for(scene_library, classification)
        keyword = classification.subcategory or classification.primary_category.split("_", 1)[-1]
        detection = detect_crop(video, location)
        rect = detection.get("rect", {"x": 0.0, "y": 0.0, "w": 100.0, "h": 88.0})
        rect, letterbox_reason = merge_letterbox_and_subtitle_rect(rect, letterbox)
        if float(rect.get("h", 0.0)) < 32.0:
            summary["videos_skipped"] += 1
            rows.append({
                "run_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "location": location,
                "source": str(video),
                "source_video_id": source.video_id,
                "crop_rect": json.dumps(rect, ensure_ascii=False),
                "crop_confidence": detection.get("confidence", 0),
                "crop_reason": detection.get("reason", ""),
                "status": "skip_overcropped",
                "reason": f"裁黑边/字幕后保留高度过低：h={rect.get('h')}%",
            })
            append_csv(manifest_path, rows)
            rows = []
            continue
        pseudo_reason = "" if letterbox.get("detected") else should_skip_pseudo_vertical(detection, source)
        if pseudo_reason:
            summary["videos_skipped"] += 1
            rows.append({
                "run_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "location": location,
                "source": str(video),
                "source_video_id": source.video_id,
                "crop_rect": json.dumps(rect, ensure_ascii=False),
                "crop_confidence": detection.get("confidence", 0),
                "crop_reason": f"{detection.get('reason', '')}; {letterbox_reason}",
                "status": "skip_pseudo_vertical",
                "reason": pseudo_reason,
            })
            append_csv(manifest_path, rows)
            rows = []
            continue
        start_floor = max(
            float(getattr(browser_server, "DEFAULT_HEAD_TRIM_SECONDS", 0.08)),
            float(detection.get("suggested_start") or 0.0),
        )
        cuts = detect_scenes(video, source.duration, args.detector, args.threshold, args.min_scene_len)
        if args.max_scenes_per_video:
            cuts = cuts[: args.max_scenes_per_video]
        summary["scenes_detected"] += len(cuts)

        for scene in cuts:
            key = f"{source.sha256}:{scene.scene_id}:{rect}:{round(start_floor, 3)}"
            row_base = {
                "run_time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "location": location,
                "source": str(video),
                "source_video_id": source.video_id,
                "scene_id": scene.scene_id,
                "start": round(scene.start_time, 3),
                "end": round(scene.end_time, 3),
                "crop_rect": json.dumps(rect, ensure_ascii=False),
                "crop_confidence": detection.get("confidence", 0),
                "crop_reason": f"{detection.get('reason', '')}; {letterbox_reason}",
                "start_floor": round(start_floor, 3),
                "category": classification.primary_category,
                "keyword": keyword,
            }
            if key in processed and not args.force:
                summary["clips_skipped"] += 1
                rows.append({**row_base, "status": "skip_processed", "output": ""})
                continue
            if scene_has_large_title_overlay(ffmpeg, source, scene, system_dir / "title_check_frames"):
                summary["clips_skipped"] += 1
                rows.append({**row_base, "status": "skip_title_overlay", "reason": "分镜抽帧检测到大标题/贴纸覆盖，作为废料跳过", "output": ""})
                continue
            output_name = build_output_name(location, keyword, source, scene, serial)
            output = ensure_unique_path(dest_dir / output_name)
            if args.dry_run:
                summary["clips_skipped"] += 1
                rows.append({**row_base, "status": "dry_run", "output": str(output)})
                continue
            ok, error = write_split_clip(
                ffmpeg=ffmpeg,
                source=source,
                scene=scene,
                output_path=output,
                rect=rect,
                start_floor=start_floor,
                crf=args.crf,
                preset=args.preset,
            )
            if ok:
                processed.add(key)
                serial += 1
                summary["clips_written"] += 1
                written_outputs.append(output)
                rows.append({**row_base, "status": "written", "output": str(output)})
            elif error in {"skip_intro_waste", "skip_too_short"}:
                summary["clips_skipped"] += 1
                reason = "开头封面/大标题废料段，跳过" if error == "skip_intro_waste" else "片段过短，跳过"
                rows.append({**row_base, "status": error, "reason": reason, "output": str(output)})
            else:
                summary["clips_failed"] += 1
                rows.append({**row_base, "status": "failed", "reason": error, "output": str(output)})
        append_csv(manifest_path, rows)
        rows = []
        save_processed(record_path, processed)

    if rows:
        append_csv(manifest_path, rows)
    save_processed(record_path, processed)
    if written_outputs and not args.dry_run:
        make_contact_sheet(ffmpeg, written_outputs, system_dir / f"自检抽帧_{datetime.now().strftime('%Y%m%d_%H%M%S')}.jpg")
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def main() -> int:
    args = parse_args()
    ffmpeg = find_ffmpeg()
    ffprobe = find_ffprobe()
    summaries = []
    for location in args.locations:
        summaries.append(process_location(args, ffmpeg, ffprobe, location))
    record_root = first_existing_path(
        Path(args.library_root) / "90_待整理与记录" / "._采集记录",
        Path(args.library_root) / "._采集记录",
    )
    out = record_root / f"裁剪分割批处理汇总_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"summary_file": str(out), "locations": summaries}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
