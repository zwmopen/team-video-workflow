from __future__ import annotations

from pathlib import Path
import csv
import json
import shutil
import tempfile

import cv2
import numpy as np

from .ffmpeg_utils import find_ffmpeg, find_ffprobe, probe_video, run_command
from .path_utils import sanitize_name


VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".m4v", ".webm", ".avi"}


def clean_materials(
    input_dir: Path,
    output_dir: Path | None = None,
    mode: str = "crop-bottom",
    bottom_pct: float = 0.16,
    top_pct: float = 0.0,
    max_files: int | None = None,
    crf: int = 20,
) -> dict[str, object]:
    input_dir = input_dir.expanduser().resolve()
    if not input_dir.exists():
        raise FileNotFoundError(f"Input directory does not exist: {input_dir}")

    output_dir = output_dir or (input_dir.parent / f"{sanitize_name(input_dir.name)}_clean_{mode}")
    output_dir = output_dir.expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    report_dir = output_dir / "._clean_report"
    report_dir.mkdir(parents=True, exist_ok=True)

    ffmpeg = find_ffmpeg()
    ffprobe = find_ffprobe()
    videos = [
        path
        for path in input_dir.rglob("*")
        if path.is_file()
        and path.suffix.lower() in VIDEO_EXTENSIONS
        and "._系统记录" not in path.parts
        and "._clean_report" not in path.parts
    ]
    videos.sort()
    if max_files:
        videos = videos[:max_files]

    rows: list[dict[str, object]] = []
    for source in videos:
        rel = source.relative_to(input_dir)
        destination = output_dir / rel
        destination.parent.mkdir(parents=True, exist_ok=True)
        info = probe_video(source, ffmpeg, ffprobe)
        status = "written"
        reason = ""
        chosen_bottom_pct = bottom_pct
        source_text_score = 0.0
        try:
            if mode == "adaptive-crop":
                source_text_score, chosen_bottom_pct = estimate_subtitle_crop(source, bottom_pct)
                render_clean_clip(source, destination, "crop-bottom", chosen_bottom_pct, top_pct, crf, ffmpeg)
            elif mode == "above-subtitle-crop":
                source_text_score, chosen_bottom_pct = estimate_above_subtitle_crop(source, bottom_pct)
                render_clean_clip(source, destination, "above-subtitle-crop", chosen_bottom_pct, top_pct, crf, ffmpeg)
            elif mode == "above-subtitle-fixed":
                render_clean_clip(source, destination, "above-subtitle-crop", bottom_pct, top_pct, crf, ffmpeg)
            elif mode == "subtitle-watermark-inpaint":
                source_text_score = estimate_subtitle_watermark_residue(source, bottom_pct)
                render_subtitle_watermark_inpaint(source, destination, bottom_pct)
            elif mode == "bottom-subtitle-inpaint":
                source_text_score = estimate_bottom_text_residue(source, bottom_pct)
                render_bottom_subtitle_inpaint(source, destination, bottom_pct)
            elif mode == "opencv-inpaint-text":
                source_text_score = estimate_text_residue(source)
                render_opencv_inpaint_text(source, destination)
            else:
                render_clean_clip(source, destination, mode, bottom_pct, top_pct, crf, ffmpeg)
        except Exception as exc:  # noqa: BLE001 - batch cleanup should continue.
            status = "failed"
            reason = str(exc)[-500:]
        if status == "written" and mode == "subtitle-watermark-inpaint":
            output_text_score = estimate_subtitle_watermark_residue(destination, bottom_pct)
        elif status == "written" and mode == "bottom-subtitle-inpaint":
            output_text_score = estimate_bottom_text_residue(destination, bottom_pct)
        else:
            output_text_score = estimate_text_residue(destination) if status == "written" else 0.0
        rows.append(
            {
                "source_path": str(source),
                "output_path": str(destination),
                "mode": mode,
                "bottom_pct": bottom_pct,
                "chosen_bottom_pct": chosen_bottom_pct,
                "top_pct": top_pct,
                "source_text_score": round(source_text_score, 6),
                "output_text_score": round(output_text_score, 6),
                "duration": info.get("duration", ""),
                "width": info.get("width", ""),
                "height": info.get("height", ""),
                "status": status,
                "reason": reason,
            }
        )

    report_csv = report_dir / "clean_materials.csv"
    write_report(report_csv, rows)
    summary = {
        "input_dir": str(input_dir),
        "output_dir": str(output_dir),
        "mode": mode,
        "bottom_pct": bottom_pct,
        "top_pct": top_pct,
        "files": len(videos),
        "written": sum(1 for row in rows if row["status"] == "written"),
        "failed": sum(1 for row in rows if row["status"] == "failed"),
        "likely_residue": sum(1 for row in rows if float(row.get("output_text_score") or 0.0) > 0.018),
        "report_csv": str(report_csv),
    }
    (report_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def render_clean_clip(
    source: Path,
    destination: Path,
    mode: str,
    bottom_pct: float,
    top_pct: float,
    crf: int,
    ffmpeg: Path,
) -> None:
    if mode == "crop-bottom":
        keep_height = max(0.55, 1.0 - max(0.0, bottom_pct) - max(0.0, top_pct))
        crop_y = max(0.0, top_pct)
        vf = (
            f"crop=iw:ih*{keep_height:.4f}:0:ih*{crop_y:.4f},"
            "scale=1080:1920:force_original_aspect_ratio=increase,"
            "crop=1080:1920,setsar=1"
        )
    elif mode == "above-subtitle-crop":
        keep_height = max(0.45, min(1.0, 1.0 - max(0.0, bottom_pct)))
        crop_y = max(0.0, top_pct)
        vf = f"crop=iw:trunc(ih*{keep_height:.4f}/2)*2:0:ih*{crop_y:.4f},setsar=1"
    elif mode == "blur-bottom":
        bottom_h = max(0.01, min(0.45, bottom_pct))
        y_expr = f"ih*(1-{bottom_h:.4f})"
        vf = f"split[base][blur];[blur]crop=iw:ih*{bottom_h:.4f}:0:{y_expr},boxblur=18:2[blurred];[base][blurred]overlay=0:{y_expr},setsar=1"
    else:
        raise ValueError(f"Unsupported clean mode: {mode}")

    result = run_command(
        [
            ffmpeg,
            "-y",
            "-i",
            source,
            "-vf",
            vf,
            "-an",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-crf",
            str(crf),
            "-pix_fmt",
            "yuv420p",
            destination,
        ],
        timeout=300,
    )
    if result.returncode != 0:
        raise RuntimeError(f"Failed to clean {source}: {result.stderr[-1000:]}")


def render_opencv_inpaint_text(source: Path, destination: Path) -> None:
    process_video_with_inpaint(source, destination, lambda frame: text_like_mask(frame), radius=3)


def render_bottom_subtitle_inpaint(source: Path, destination: Path, bottom_pct: float) -> None:
    process_video_with_inpaint(source, destination, lambda frame: bottom_subtitle_mask(frame, bottom_pct), radius=4)


def render_subtitle_watermark_inpaint(source: Path, destination: Path, bottom_pct: float) -> None:
    process_video_with_inpaint(source, destination, lambda frame: subtitle_watermark_mask(frame, bottom_pct), radius=4)


def process_video_with_inpaint(source: Path, destination: Path, mask_fn, radius: int) -> None:
    source_for_cv, temp_source_dir = cv_safe_source(source)
    temp_output_dir = Path(tempfile.mkdtemp(prefix="tb_scene_cv_out_"))
    temp_output = temp_output_dir / f"output{destination.suffix.lower() or '.mp4'}"

    cap = cv2.VideoCapture(str(source_for_cv))
    if not cap.isOpened():
        cleanup_temp(temp_source_dir)
        cleanup_temp(temp_output_dir)
        raise RuntimeError(f"Failed to open video: {source}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 0)
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 0)
    if width <= 0 or height <= 0:
        cap.release()
        cleanup_temp(temp_source_dir)
        cleanup_temp(temp_output_dir)
        raise RuntimeError(f"Invalid video size: {source}")

    writer = cv2.VideoWriter(str(temp_output), cv2.VideoWriter_fourcc(*"mp4v"), fps, (width, height))
    if not writer.isOpened():
        cap.release()
        cleanup_temp(temp_source_dir)
        cleanup_temp(temp_output_dir)
        raise RuntimeError(f"Failed to open writer: {destination}")
    try:
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            mask = mask_fn(frame)
            if np.count_nonzero(mask) > 0:
                frame = cv2.inpaint(frame, mask, radius, cv2.INPAINT_TELEA)
            writer.write(frame)
    finally:
        writer.release()
        cap.release()
        cleanup_temp(temp_source_dir)

    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(temp_output), str(destination))
    cleanup_temp(temp_output_dir)


def estimate_subtitle_crop(path: Path, default_bottom_pct: float) -> tuple[float, float]:
    frame = read_middle_frame(path)
    if frame is None:
        return 0.0, default_bottom_pct
    score, top_ratio = text_like_score_and_top(frame)
    if score <= 0.012:
        return score, max(0.18, min(default_bottom_pct, 0.28))
    crop_pct = 1.0 - max(0.45, min(0.88, top_ratio - 0.03))
    crop_pct = max(default_bottom_pct, min(0.45, crop_pct))
    return score, crop_pct


def estimate_above_subtitle_crop(path: Path, default_bottom_pct: float) -> tuple[float, float]:
    frame = read_middle_frame(path)
    if frame is None:
        return 0.0, default_bottom_pct
    score, top_y = detect_bottom_caption_top(frame)
    if top_y is None:
        return score, 0.0
    height = frame.shape[0]
    top_y = max(0, int(top_y) - int(height * 0.018))
    keep_ratio = max(0.48, min(1.0, float(top_y) / float(height)))
    crop_pct = max(0.0, min(0.55, 1.0 - keep_ratio))
    return score, crop_pct


def detect_bottom_caption_top(frame: np.ndarray) -> tuple[float, int | None]:
    height, width = frame.shape[:2]
    y0 = int(height * 0.50)
    y1 = int(height * 0.90)
    x0 = int(width * 0.08)
    x1 = int(width * 0.92)
    roi = frame[y0:y1, x0:x1]
    if roi.size == 0:
        return 0.0, None

    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 60, 180)
    yellow = cv2.inRange(hsv, np.array([14, 70, 120]), np.array([45, 255, 255]))
    white = ((gray > 185) & (hsv[:, :, 1] < 95)).astype(np.uint8) * 255
    color_mask = cv2.bitwise_or(yellow, white)
    mask = cv2.bitwise_and(color_mask, cv2.dilate(edges, np.ones((3, 3), np.uint8), iterations=1))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 3), np.uint8), iterations=1)

    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    boxes: list[tuple[int, int, int, int]] = []
    for contour in contours:
        x, y, w, h = cv2.boundingRect(contour)
        area = w * h
        if area < 20 or area > roi.shape[0] * roi.shape[1] * 0.03:
            continue
        if h < 6 or h > roi.shape[0] * 0.26:
            continue
        if w < 4 or w > roi.shape[1] * 0.45:
            continue
        boxes.append((x, y, w, h))

    if len(boxes) < 3:
        return float(np.count_nonzero(mask)) / float(mask.size or 1), None

    centers = np.array([y + h / 2 for _, y, _, h in boxes])
    median_y = float(np.median(centers))
    line_boxes = [box for box in boxes if abs((box[1] + box[3] / 2) - median_y) <= roi.shape[0] * 0.08]
    if len(line_boxes) < 3:
        return float(np.count_nonzero(mask)) / float(mask.size or 1), None

    min_x = min(x for x, _, _, _ in line_boxes)
    max_x = max(x + w for x, _, w, _ in line_boxes)
    min_y = min(y for _, y, _, _ in line_boxes)
    coverage = float(max_x - min_x) / float(roi.shape[1])
    score = float(np.count_nonzero(mask)) / float(mask.size or 1)
    if coverage < 0.12 or score < 0.00035:
        return score, None
    return score, y0 + min_y


def estimate_text_residue(path: Path) -> float:
    frame = read_middle_frame(path)
    if frame is None:
        return 0.0
    score, _ = text_like_score_and_top(frame)
    return score


def estimate_bottom_text_residue(path: Path, bottom_pct: float) -> float:
    frame = read_middle_frame(path)
    if frame is None:
        return 0.0
    mask = bottom_subtitle_mask(frame, bottom_pct)
    return float(np.count_nonzero(mask)) / float(mask.size or 1)


def estimate_subtitle_watermark_residue(path: Path, bottom_pct: float) -> float:
    frame = read_middle_frame(path)
    if frame is None:
        return 0.0
    mask = subtitle_watermark_mask(frame, bottom_pct)
    return float(np.count_nonzero(mask)) / float(mask.size or 1)


def read_middle_frame(path: Path) -> np.ndarray | None:
    source_for_cv, temp_source_dir = cv_safe_source(path)
    cap = cv2.VideoCapture(str(source_for_cv))
    if not cap.isOpened():
        cleanup_temp(temp_source_dir)
        return None
    try:
        frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        if frames > 2:
            cap.set(cv2.CAP_PROP_POS_FRAMES, max(0, frames // 2))
        ok, frame = cap.read()
        return frame if ok else None
    finally:
        cap.release()
        cleanup_temp(temp_source_dir)


def cv_safe_source(path: Path) -> tuple[Path, Path | None]:
    try:
        str(path).encode("ascii")
        return path, None
    except UnicodeEncodeError:
        temp_dir = Path(tempfile.mkdtemp(prefix="tb_scene_cv_src_"))
        temp_path = temp_dir / f"input{path.suffix.lower() or '.mp4'}"
        shutil.copy2(path, temp_path)
        return temp_path, temp_dir


def cleanup_temp(temp_dir: Path | None) -> None:
    if temp_dir and temp_dir.exists():
        shutil.rmtree(temp_dir, ignore_errors=True)


def text_like_score_and_top(frame: np.ndarray) -> tuple[float, float]:
    mask = text_like_mask(frame)
    height = frame.shape[0]
    y_nonzero = np.where(mask > 0)[0]
    score = float(np.count_nonzero(mask)) / float(mask.size or 1)
    if len(y_nonzero) == 0:
        return score, 1.0
    return score, float(int(y_nonzero.min())) / float(height)


def text_like_mask(frame: np.ndarray) -> np.ndarray:
    height, width = frame.shape[:2]
    y0 = int(height * 0.36)
    roi = frame[y0:, :]
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 80, 180)
    bright = ((gray > 180) & (hsv[:, :, 1] < 110)).astype(np.uint8) * 255
    yellow = cv2.inRange(hsv, np.array([15, 40, 110]), np.array([45, 255, 255]))
    red1 = cv2.inRange(hsv, np.array([0, 60, 120]), np.array([10, 255, 255]))
    red2 = cv2.inRange(hsv, np.array([170, 60, 120]), np.array([180, 255, 255]))
    colored = cv2.bitwise_or(yellow, cv2.bitwise_or(red1, red2))
    mask = cv2.bitwise_or(bright, colored)
    mask = cv2.bitwise_and(mask, cv2.dilate(edges, np.ones((3, 3), np.uint8), iterations=1))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 2), np.uint8), iterations=1)

    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    kept_roi = np.zeros_like(mask)
    top_y = roi.shape[0]
    for contour in contours:
        x, y, w, h = cv2.boundingRect(contour)
        area = w * h
        if area < 20 or area > width * height * 0.08:
            continue
        if h > roi.shape[0] * 0.28:
            continue
        if w < 4 or h < 4:
            continue
        cv2.rectangle(kept_roi, (x, y), (x + w, y + h), 255, thickness=-1)
        top_y = min(top_y, y)
    kept_roi = cv2.dilate(kept_roi, np.ones((5, 9), np.uint8), iterations=1)
    full = np.zeros((height, width), dtype=np.uint8)
    full[y0:, :] = kept_roi
    return full


def bottom_subtitle_mask(frame: np.ndarray, bottom_pct: float) -> np.ndarray:
    height, width = frame.shape[:2]
    band_pct = max(0.08, min(0.42, bottom_pct))
    y0 = int(height * (1.0 - band_pct))
    roi = frame[y0:, :]
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 60, 170)

    white = ((gray > 165) & (hsv[:, :, 1] < 135)).astype(np.uint8) * 255
    yellow = cv2.inRange(hsv, np.array([12, 35, 95]), np.array([48, 255, 255]))
    cyan = cv2.inRange(hsv, np.array([80, 25, 105]), np.array([110, 255, 255]))
    red1 = cv2.inRange(hsv, np.array([0, 45, 100]), np.array([12, 255, 255]))
    red2 = cv2.inRange(hsv, np.array([168, 45, 100]), np.array([180, 255, 255]))
    colored = cv2.bitwise_or(yellow, cv2.bitwise_or(cyan, cv2.bitwise_or(red1, red2)))

    mask = cv2.bitwise_or(white, colored)
    mask = cv2.bitwise_and(mask, cv2.dilate(edges, np.ones((3, 3), np.uint8), iterations=1))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((9, 3), np.uint8), iterations=1)

    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    kept_roi = np.zeros_like(mask)
    for contour in contours:
        x, y, w, h = cv2.boundingRect(contour)
        area = w * h
        if area < 18:
            continue
        if area > width * height * 0.05:
            continue
        if h > roi.shape[0] * 0.45:
            continue
        if w < 4 or h < 3:
            continue
        cv2.rectangle(kept_roi, (x, y), (x + w, y + h), 255, thickness=-1)

    kept_roi = cv2.dilate(kept_roi, np.ones((9, 15), np.uint8), iterations=2)
    full = np.zeros((height, width), dtype=np.uint8)
    full[y0:, :] = kept_roi
    return full


def subtitle_watermark_mask(frame: np.ndarray, bottom_pct: float) -> np.ndarray:
    height, width = frame.shape[:2]
    mask = bottom_subtitle_mask(frame, bottom_pct)

    top_h = int(height * 0.44)
    corner_w = int(width * 0.48)
    bottom_h = int(height * 0.18)
    regions = [
        (0, 0, width, top_h),
        (0, height - bottom_h, corner_w, height),
        (width - corner_w, height - bottom_h, width, height),
    ]
    for x0, y0, x1, y1 in regions:
        roi = frame[y0:y1, x0:x1]
        if roi.size == 0:
            continue
        region_mask = text_like_mask_in_roi(roi)
        mask[y0:y1, x0:x1] = cv2.bitwise_or(mask[y0:y1, x0:x1], region_mask)
    return mask


def text_like_mask_in_roi(roi: np.ndarray) -> np.ndarray:
    height, width = roi.shape[:2]
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 35, 140)
    white = ((gray > 130) & (hsv[:, :, 1] < 180)).astype(np.uint8) * 255
    yellow = cv2.inRange(hsv, np.array([12, 30, 90]), np.array([50, 255, 255]))
    cyan = cv2.inRange(hsv, np.array([78, 20, 95]), np.array([112, 255, 255]))
    red1 = cv2.inRange(hsv, np.array([0, 40, 95]), np.array([12, 255, 255]))
    red2 = cv2.inRange(hsv, np.array([168, 40, 95]), np.array([180, 255, 255]))
    colored = cv2.bitwise_or(yellow, cv2.bitwise_or(cyan, cv2.bitwise_or(red1, red2)))
    mask = cv2.bitwise_or(white, colored)
    mask = cv2.bitwise_and(mask, cv2.dilate(edges, np.ones((3, 3), np.uint8), iterations=1))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((7, 3), np.uint8), iterations=1)

    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    kept = np.zeros_like(mask)
    for contour in contours:
        x, y, w, h = cv2.boundingRect(contour)
        area = w * h
        if area < 10:
            continue
        if area > width * height * 0.18:
            continue
        if h > height * 0.55 or w > width * 0.95:
            continue
        cv2.rectangle(kept, (x, y), (x + w, y + h), 255, thickness=-1)
    return cv2.dilate(kept, np.ones((7, 11), np.uint8), iterations=1)


def write_report(path: Path, rows: list[dict[str, object]]) -> None:
    fields = [
        "source_path",
        "output_path",
        "mode",
        "bottom_pct",
        "chosen_bottom_pct",
        "top_pct",
        "source_text_score",
        "output_text_score",
        "duration",
        "width",
        "height",
        "status",
        "reason",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
