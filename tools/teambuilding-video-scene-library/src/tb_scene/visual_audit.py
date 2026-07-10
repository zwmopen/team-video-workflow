from __future__ import annotations

from pathlib import Path
import csv
import math

from PIL import Image, ImageDraw, ImageFont

from .ffmpeg_utils import find_ffmpeg, run_command
from .script_matcher import load_clip_records

VIDEO_EXTENSIONS = {".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm"}


def build_visual_audit_contact_sheets(
    library_root: Path,
    output_dir: Path | None = None,
    group_by: str = "folder",
    clips_per_sheet: int = 12,
    max_clips: int | None = None,
) -> dict[str, object]:
    library_root = library_root.expanduser().resolve()
    if not library_root.exists():
        raise FileNotFoundError(f"Library root does not exist: {library_root}")

    output_dir = output_dir or (library_root / "._系统记录" / "visual_audit")
    output_dir = output_dir.expanduser().resolve()
    frames_dir = output_dir / "frames"
    sheets_dir = output_dir / "contact_sheets"
    frames_dir.mkdir(parents=True, exist_ok=True)
    sheets_dir.mkdir(parents=True, exist_ok=True)

    records = [record for record in load_clip_records(library_root) if clip_path(record).exists()]
    if not records:
        records = load_clip_records_from_filesystem(library_root)
    if max_clips:
        records = records[:max_clips]

    ffmpeg = find_ffmpeg()
    rows: list[dict[str, str]] = []
    grouped: dict[str, list[dict[str, object]]] = {}
    for index, record in enumerate(records, start=1):
        path = clip_path(record)
        frame_path = frames_dir / f"{index:05d}_{path.stem}.jpg"
        if not frame_path.exists():
            extract_middle_frame(path, frame_path, ffmpeg)
        current_category, current_keyword = infer_current_label(library_root, path)
        group_key = group_key_for(record, current_category, current_keyword, group_by)
        grouped.setdefault(group_key, []).append(
            {
                "index": index,
                "record": record,
                "clip_path": path,
                "frame_path": frame_path,
                "current_category": current_category,
                "current_keyword": current_keyword,
            }
        )

    for group_key, items in grouped.items():
        for sheet_index, chunk in enumerate(chunks(items, clips_per_sheet), start=1):
            sheet_name = safe_sheet_name(f"{group_key}_{sheet_index:02d}.jpg")
            sheet_path = sheets_dir / sheet_name
            make_contact_sheet(chunk, sheet_path)
            for item in chunk:
                record = item["record"]
                rows.append(
                    {
                        "audit_index": str(item["index"]),
                        "source_video_id": str(record.get("source_video_id", "")),
                        "scene_id": str(record.get("scene_id", "")),
                        "current_category": str(item["current_category"]),
                        "current_keyword": str(item["current_keyword"]),
                        "suggested_category": "",
                        "suggested_keyword": "",
                        "reason": "",
                        "clip_path": str(item["clip_path"]),
                        "frame_path": str(item["frame_path"]),
                        "contact_sheet": str(sheet_path),
                    }
                )

    csv_path = output_dir / "visual_audit_corrections_template.csv"
    write_audit_csv(csv_path, rows)
    markdown_path = output_dir / "README.md"
    markdown_path.write_text(build_readme(library_root, sheets_dir, csv_path, len(records), len(grouped)), encoding="utf-8")
    return {
        "library_root": str(library_root),
        "output_dir": str(output_dir),
        "contact_sheets": str(sheets_dir),
        "corrections_template": str(csv_path),
        "clips": len(records),
        "groups": len(grouped),
        "sheets": len(list(sheets_dir.glob("*.jpg"))),
    }


def clip_path(record: dict[str, object]) -> Path:
    return Path(str(record.get("output_path", "")))


def load_clip_records_from_filesystem(library_root: Path) -> list[dict[str, object]]:
    records: list[dict[str, object]] = []
    for path in sorted(library_root.rglob("*"), key=lambda item: str(item).lower()):
        if not path.is_file() or path.suffix.lower() not in VIDEO_EXTENSIONS:
            continue
        if any(part.startswith("._") for part in path.parts):
            continue
        records.append(
            {
                "output_path": str(path),
                "source_video_id": "",
                "source_video_name": "",
                "scene_id": path.stem,
            }
        )
    return records


def extract_middle_frame(video_path: Path, frame_path: Path, ffmpeg: Path) -> None:
    result = run_command(
        [
            ffmpeg,
            "-y",
            "-i",
            video_path,
            "-vf",
            "select=eq(n\\,floor(n/2)),scale=360:640:force_original_aspect_ratio=increase,crop=360:640",
            "-frames:v",
            "1",
            frame_path,
        ],
        timeout=60,
    )
    if result.returncode != 0:
        result = run_command(
            [
                ffmpeg,
                "-y",
                "-ss",
                "0.2",
                "-i",
                video_path,
                "-frames:v",
                "1",
                "-vf",
                "scale=360:640:force_original_aspect_ratio=increase,crop=360:640",
                frame_path,
            ],
            timeout=60,
        )
    if result.returncode != 0:
        raise RuntimeError(f"Failed to extract audit frame from {video_path}: {result.stderr[-1000:]}")


def infer_current_label(library_root: Path, path: Path) -> tuple[str, str]:
    try:
        rel = path.resolve().relative_to(library_root.resolve())
    except ValueError:
        return "", ""
    parts = rel.parts
    category = parts[0] if len(parts) >= 2 else ""
    keyword = parts[1] if len(parts) >= 3 else ""
    return category, keyword


def group_key_for(record: dict[str, object], category: str, keyword: str, group_by: str) -> str:
    if group_by == "source":
        return str(record.get("source_video_id") or record.get("source_video_name") or "unknown_source")
    if group_by == "category":
        return category or "unknown_category"
    return f"{category}_{keyword}" if keyword else category or "unknown_folder"


def chunks(items: list[dict[str, object]], size: int) -> list[list[dict[str, object]]]:
    return [items[index : index + size] for index in range(0, len(items), max(1, size))]


def make_contact_sheet(items: list[dict[str, object]], output_path: Path) -> None:
    columns = 3
    tile_width = 360
    tile_height = 720
    rows = max(1, math.ceil(len(items) / columns))
    sheet = Image.new("RGB", (columns * tile_width, rows * tile_height), "white")
    draw = ImageDraw.Draw(sheet)
    font = load_font(24)
    small_font = load_font(18)
    for offset, item in enumerate(items):
        x = (offset % columns) * tile_width
        y = (offset // columns) * tile_height
        frame = Image.open(Path(str(item["frame_path"]))).convert("RGB").resize((tile_width, 640))
        sheet.paste(frame, (x, y))
        label = f"{item['index']:03d} {item['current_keyword'] or item['current_category']}"
        clip_name = Path(str(item["clip_path"])).name
        draw.rectangle((x, y + 640, x + tile_width, y + tile_height), fill=(245, 245, 245))
        draw.text((x + 8, y + 646), label[:28], font=font, fill=(0, 0, 0))
        draw.text((x + 8, y + 680), clip_name[:34], font=small_font, fill=(60, 60, 60))
    sheet.save(output_path, quality=92)


def load_font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for candidate in [
        Path(r"C:\Windows\Fonts\msyh.ttc"),
        Path(r"C:\Windows\Fonts\simsun.ttc"),
        Path(r"C:\Windows\Fonts\arial.ttf"),
    ]:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size=size)
    return ImageFont.load_default()


def safe_sheet_name(value: str) -> str:
    for char in '<>:"/\\|?*':
        value = value.replace(char, "_")
    return value[:160]


def write_audit_csv(path: Path, rows: list[dict[str, str]]) -> None:
    fields = [
        "audit_index",
        "source_video_id",
        "scene_id",
        "current_category",
        "current_keyword",
        "suggested_category",
        "suggested_keyword",
        "reason",
        "clip_path",
        "frame_path",
        "contact_sheet",
    ]
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def build_readme(library_root: Path, sheets_dir: Path, csv_path: Path, clips: int, groups: int) -> str:
    return "\n".join(
        [
            "# 视觉复核接触图",
            "",
            f"- 素材库：`{library_root}`",
            f"- 接触图目录：`{sheets_dir}`",
            f"- 纠错表模板：`{csv_path}`",
            f"- 片段数：{clips}",
            f"- 分组数：{groups}",
            "",
            "使用方式：",
            "",
            "1. 打开 `contact_sheets` 看每个编号画面。",
            "2. 发现错分，在 CSV 里填写 `suggested_category`、`suggested_keyword`、`reason`。",
            "3. 用 `apply-visual-corrections` 应用纠错表，系统会移动文件并更新记录。",
        ]
    )
