from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import csv
import json
import math

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

from .clean_materials import VIDEO_EXTENSIONS, bottom_subtitle_mask, cleanup_temp, cv_safe_source, text_like_mask_in_roi


SYSTEM_DIR_NAMES = {"._clean_report", "._系统记录", "._采集记录"}


@dataclass(frozen=True)
class OverlayScores:
    bottom_subtitle: float
    top_watermark: float
    center_overlay: float
    corner_watermark: float

    @property
    def max_score(self) -> float:
        return max(self.bottom_subtitle, self.top_watermark, self.center_overlay, self.corner_watermark)


def audit_overlays(
    input_dir: Path,
    output_dir: Path | None = None,
    max_files: int | None = None,
    dirty_threshold: float = 0.006,
    contact_sheet_limit: int = 160,
) -> dict[str, object]:
    input_dir = input_dir.expanduser().resolve()
    if not input_dir.exists():
        raise FileNotFoundError(f"Input directory does not exist: {input_dir}")

    output_dir = output_dir or (input_dir / "._clean_report" / "overlay_audit")
    output_dir = output_dir.expanduser().resolve()
    frames_dir = output_dir / "frames"
    sheets_dir = output_dir / "contact_sheets"
    frames_dir.mkdir(parents=True, exist_ok=True)
    sheets_dir.mkdir(parents=True, exist_ok=True)

    videos = [
        path
        for path in input_dir.rglob("*")
        if path.is_file()
        and path.suffix.lower() in VIDEO_EXTENSIONS
        and not any(part in SYSTEM_DIR_NAMES or part.startswith("._clean_report") for part in path.parts)
    ]
    videos.sort()
    if max_files:
        videos = videos[:max_files]

    rows: list[dict[str, object]] = []
    contact_items: list[dict[str, object]] = []
    for index, video in enumerate(videos, start=1):
        rel = video.relative_to(input_dir)
        status = "ok"
        reason = ""
        scores = OverlayScores(0.0, 0.0, 0.0, 0.0)
        label = "clean"
        suggested_action = "keep"
        frame_path = frames_dir / f"{index:05d}_{safe_name(video.stem)}.jpg"
        try:
            frames = sample_frames(video)
            if not frames:
                raise RuntimeError("No readable frames")
            scores = aggregate_scores([score_frame(frame) for frame in frames])
            label, suggested_action = classify_scores(scores, dirty_threshold)
            if not frame_path.exists():
                save_review_frame(frames[len(frames) // 2], frame_path)
        except Exception as exc:  # noqa: BLE001 - batch audit should continue.
            status = "failed"
            reason = str(exc)[-500:]
            label = "failed"
            suggested_action = "manual_check"

        row = {
            "index": index,
            "relative_path": str(rel),
            "source_path": str(video),
            "status": status,
            "label": label,
            "suggested_action": suggested_action,
            "bottom_subtitle_score": round(scores.bottom_subtitle, 6),
            "top_watermark_score": round(scores.top_watermark, 6),
            "center_overlay_score": round(scores.center_overlay, 6),
            "corner_watermark_score": round(scores.corner_watermark, 6),
            "max_score": round(scores.max_score, 6),
            "frame_path": str(frame_path) if frame_path.exists() else "",
            "reason": reason,
        }
        rows.append(row)
        if frame_path.exists() and label != "clean" and len(contact_items) < contact_sheet_limit:
            contact_items.append(row)

    csv_path = output_dir / "overlay_audit.csv"
    write_rows(csv_path, rows)
    json_path = output_dir / "overlay_audit.json"
    json_path.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")

    sheet_paths = make_contact_sheets(contact_items, sheets_dir)
    summary = {
        "input_dir": str(input_dir),
        "output_dir": str(output_dir),
        "videos": len(videos),
        "clean": sum(1 for row in rows if row["label"] == "clean"),
        "dirty": sum(1 for row in rows if row["label"] != "clean" and row["status"] == "ok"),
        "bottom_subtitle": sum(1 for row in rows if row["label"] == "bottom_subtitle"),
        "top_or_center_watermark": sum(1 for row in rows if row["label"] in {"top_watermark", "center_overlay", "mixed_overlay"}),
        "failed": sum(1 for row in rows if row["status"] == "failed"),
        "report_csv": str(csv_path),
        "contact_sheets": [str(path) for path in sheet_paths],
    }
    (output_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    (output_dir / "README.md").write_text(build_readme(summary), encoding="utf-8")
    return summary


def sample_frames(video: Path) -> list[np.ndarray]:
    source_for_cv, temp_source_dir = cv_safe_source(video)
    cap = cv2.VideoCapture(str(source_for_cv))
    if not cap.isOpened():
        cleanup_temp(temp_source_dir)
        return []
    try:
        frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
        positions = [0.2, 0.5, 0.8]
        frames: list[np.ndarray] = []
        for pos in positions:
            if frame_count > 5:
                cap.set(cv2.CAP_PROP_POS_FRAMES, min(frame_count - 1, max(0, int(frame_count * pos))))
            ok, frame = cap.read()
            if ok and frame is not None:
                frames.append(frame)
        return frames
    finally:
        cap.release()
        cleanup_temp(temp_source_dir)


def score_frame(frame: np.ndarray) -> OverlayScores:
    height, width = frame.shape[:2]
    bottom_mask = bottom_subtitle_mask(frame, 0.32)
    bottom_score = region_score(bottom_mask, int(height * 0.68), height, 0, width)

    top_y1 = int(height * 0.42)
    center_y0 = int(height * 0.16)
    center_y1 = int(height * 0.72)
    corner_h = int(height * 0.25)
    corner_w = int(width * 0.52)

    top_score = mask_roi_score(frame, 0, top_y1, 0, width)
    center_score = mask_roi_score(frame, center_y0, center_y1, int(width * 0.08), int(width * 0.92))
    corner_score = max(
        mask_roi_score(frame, 0, corner_h, 0, corner_w),
        mask_roi_score(frame, 0, corner_h, width - corner_w, width),
        mask_roi_score(frame, height - corner_h, height, 0, corner_w),
        mask_roi_score(frame, height - corner_h, height, width - corner_w, width),
    )
    return OverlayScores(bottom_score, top_score, center_score, corner_score)


def mask_roi_score(frame: np.ndarray, y0: int, y1: int, x0: int, x1: int) -> float:
    roi = frame[y0:y1, x0:x1]
    if roi.size == 0:
        return 0.0
    mask = text_like_mask_in_roi(roi)
    contrast_mask = local_contrast_text_mask(roi)
    combined = cv2.bitwise_or(mask, contrast_mask)
    return float(np.count_nonzero(combined)) / float(combined.size or 1)


def local_contrast_text_mask(roi: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
    blur = cv2.GaussianBlur(gray, (0, 0), 5)
    contrast = cv2.absdiff(gray, blur)
    low_sat = hsv[:, :, 1] < 190
    brightish = gray > 105
    edges = cv2.Canny(gray, 25, 110)
    mask = ((contrast > 9) & brightish & low_sat).astype(np.uint8) * 255
    mask = cv2.bitwise_and(mask, cv2.dilate(edges, np.ones((3, 3), np.uint8), iterations=1))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((7, 3), np.uint8), iterations=1)
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    kept = np.zeros_like(mask)
    height, width = roi.shape[:2]
    for contour in contours:
        x, y, w, h = cv2.boundingRect(contour)
        area = w * h
        if area < 12 or area > width * height * 0.20:
            continue
        if h > height * 0.50 or w > width * 0.98:
            continue
        cv2.rectangle(kept, (x, y), (x + w, y + h), 255, thickness=-1)
    return cv2.dilate(kept, np.ones((5, 9), np.uint8), iterations=1)


def region_score(mask: np.ndarray, y0: int, y1: int, x0: int, x1: int) -> float:
    roi = mask[y0:y1, x0:x1]
    return float(np.count_nonzero(roi)) / float(roi.size or 1)


def aggregate_scores(scores: list[OverlayScores]) -> OverlayScores:
    if not scores:
        return OverlayScores(0.0, 0.0, 0.0, 0.0)
    return OverlayScores(
        bottom_subtitle=max(item.bottom_subtitle for item in scores),
        top_watermark=max(item.top_watermark for item in scores),
        center_overlay=max(item.center_overlay for item in scores),
        corner_watermark=max(item.corner_watermark for item in scores),
    )


def classify_scores(scores: OverlayScores, threshold: float) -> tuple[str, str]:
    strong = threshold
    flags = {
        "bottom_subtitle": scores.bottom_subtitle >= strong,
        "top_watermark": scores.top_watermark >= strong,
        "center_overlay": scores.center_overlay >= strong * 1.2,
        "corner_watermark": scores.corner_watermark >= strong,
    }
    active = [name for name, value in flags.items() if value]
    if not active:
        return "clean", "keep"
    if active == ["bottom_subtitle"]:
        return "bottom_subtitle", "deep_subtitle_inpaint"
    if len(active) == 1:
        name = active[0]
        return name, "deep_inpaint_or_replace"
    return "mixed_overlay", "manual_mask_or_replace"


def save_review_frame(frame: np.ndarray, frame_path: Path) -> None:
    frame_path.parent.mkdir(parents=True, exist_ok=True)
    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    image = Image.fromarray(rgb)
    image.thumbnail((420, 746))
    canvas = Image.new("RGB", (420, 746), "black")
    x = (420 - image.width) // 2
    y = (746 - image.height) // 2
    canvas.paste(image, (x, y))
    canvas.save(frame_path, quality=92)


def make_contact_sheets(items: list[dict[str, object]], sheets_dir: Path) -> list[Path]:
    paths: list[Path] = []
    for sheet_index, chunk in enumerate(chunks(items, 12), start=1):
        path = sheets_dir / f"dirty_overlay_{sheet_index:03d}.jpg"
        draw_sheet(chunk, path)
        paths.append(path)
    return paths


def draw_sheet(items: list[dict[str, object]], output_path: Path) -> None:
    columns = 3
    tile_width = 420
    tile_height = 835
    rows = max(1, math.ceil(len(items) / columns))
    sheet = Image.new("RGB", (columns * tile_width, rows * tile_height), "white")
    draw = ImageDraw.Draw(sheet)
    font = load_font(22)
    small_font = load_font(16)
    for offset, item in enumerate(items):
        x = (offset % columns) * tile_width
        y = (offset // columns) * tile_height
        frame = Image.open(str(item["frame_path"])).convert("RGB").resize((420, 746))
        sheet.paste(frame, (x, y))
        draw.rectangle((x, y + 746, x + tile_width, y + tile_height), fill=(245, 245, 245))
        label = f"{int(item['index']):04d} {item['label']} {item['max_score']}"
        draw.text((x + 8, y + 752), label[:36], font=font, fill=(0, 0, 0))
        draw.text((x + 8, y + 784), Path(str(item["relative_path"])).name[:42], font=small_font, fill=(60, 60, 60))
        draw.text((x + 8, y + 808), str(item["suggested_action"])[:42], font=small_font, fill=(120, 0, 0))
    sheet.save(output_path, quality=92)


def chunks(items: list[dict[str, object]], size: int) -> list[list[dict[str, object]]]:
    return [items[index : index + size] for index in range(0, len(items), max(1, size))]


def load_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for candidate in [
        Path(r"C:\Windows\Fonts\msyh.ttc"),
        Path(r"C:\Windows\Fonts\simsun.ttc"),
        Path(r"C:\Windows\Fonts\arial.ttf"),
    ]:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size=size)
    return ImageFont.load_default()


def safe_name(value: str) -> str:
    for char in '<>:"/\\|?*':
        value = value.replace(char, "_")
    return value[:120]


def write_rows(path: Path, rows: list[dict[str, object]]) -> None:
    fields = [
        "index",
        "relative_path",
        "source_path",
        "status",
        "label",
        "suggested_action",
        "bottom_subtitle_score",
        "top_watermark_score",
        "center_overlay_score",
        "corner_watermark_score",
        "max_score",
        "frame_path",
        "reason",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def build_readme(summary: dict[str, object]) -> str:
    return "\n".join(
        [
            "# 素材字幕水印审计",
            "",
            "这个目录只做判断和抽检，不改动原素材。",
            "",
            f"- 输入：`{summary['input_dir']}`",
            f"- 视频数：{summary['videos']}",
            f"- 初判干净：{summary['clean']}",
            f"- 疑似有字幕/水印：{summary['dirty']}",
            f"- 普通底部字幕：{summary['bottom_subtitle']}",
            f"- 顶部/中部水印或大字：{summary['top_or_center_watermark']}",
            f"- 失败：{summary['failed']}",
            f"- 表格：`{summary['report_csv']}`",
            "",
            "处理原则：",
            "",
            "1. `clean` 可以直接进入可用库。",
            "2. `bottom_subtitle` 优先走 VSR/ProPainter 等深度去字幕。",
            "3. `top_watermark`、`center_overlay`、`mixed_overlay` 不批量硬抹，先人工/AI复核；修坏画面就替换素材。",
            "4. 修复前后必须保留原尺寸，并生成对比图抽检。",
        ]
    )
