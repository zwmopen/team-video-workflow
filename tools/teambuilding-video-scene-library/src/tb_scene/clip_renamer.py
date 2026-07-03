from __future__ import annotations

from pathlib import Path
import csv
import json
import re
import sqlite3
from dataclasses import dataclass
from datetime import datetime

from .models import MAIN_CATEGORIES
from .path_utils import ensure_unique_path, sanitize_name
from .record_store import RecordStore


@dataclass(slots=True)
class RenameItem:
    record: dict[str, object]
    current_path: Path
    target_path: Path
    category: str
    keyword: str
    serial: int
    status: str


def rename_library_clips(library_root: Path, move_files: bool = True) -> dict[str, object]:
    library_root = library_root.expanduser().resolve()
    system_dir = library_root / "._系统记录"
    db_path = system_dir / "project.sqlite"
    if not db_path.exists():
        raise FileNotFoundError(f"Missing project database: {db_path}")

    rows = load_written_scene_records(db_path)
    existing_by_name = index_clips_by_name(library_root)
    location_name = infer_location_name(library_root)
    items: list[RenameItem] = []
    missing_records: list[dict[str, object]] = []
    serials_by_folder: dict[Path, int] = {}

    for record in sorted(rows, key=record_sort_key):
        current_path = resolve_current_clip_path(library_root, record, existing_by_name)
        if current_path is None:
            missing_records.append(record)
            continue
        category, keyword = infer_category_keyword(library_root, current_path, record)
        serials_by_folder[current_path.parent] = serials_by_folder.get(current_path.parent, 0) + 1
        serial = serials_by_folder[current_path.parent]
        target_name = build_readable_clip_name(location_name, keyword, record, serial)
        target_path = current_path.parent / target_name
        if current_path.resolve() == target_path.resolve():
            status = "unchanged"
        else:
            target_path = ensure_unique_path(target_path)
            status = "renamed"
        items.append(
            RenameItem(
                record=record,
                current_path=current_path,
                target_path=target_path,
                category=category,
                keyword=keyword,
                serial=serial,
                status=status,
            )
        )

    actual_renamed = 0
    if move_files:
        for item in items:
            if item.status == "renamed":
                item.current_path.rename(item.target_path)
                actual_renamed += 1
            update_scene_record(db_path, item)
        mark_missing_records(db_path, missing_records)
        export_project_files(library_root, items, actual_renamed, missing_records)

    write_rename_report(library_root, items, missing_records, move_files, actual_renamed)
    return {
        "library_root": str(library_root),
        "processed": len(items),
        "missing_records": len(missing_records),
        "renamed": actual_renamed,
        "would_rename": sum(1 for item in items if item.status == "renamed"),
        "move_files": move_files,
        "report_csv": str(system_dir / ("clip_rename.csv" if move_files else "clip_rename_preview.csv")),
    }


def load_written_scene_records(db_path: Path) -> list[dict[str, object]]:
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    try:
        records: list[dict[str, object]] = []
        for row in con.execute("select record_json, output_path from scenes where processing_status='written'"):
            if row["record_json"]:
                record = json.loads(row["record_json"])
                if not record.get("output_path") and row["output_path"]:
                    record["output_path"] = row["output_path"]
                records.append(record)
        return records
    finally:
        con.close()


def index_clips_by_name(library_root: Path) -> dict[str, list[Path]]:
    clips: dict[str, list[Path]] = {}
    for path in library_root.rglob("*.mp4"):
        if "._系统记录" in path.parts:
            continue
        clips.setdefault(path.name, []).append(path)
    return clips


def resolve_current_clip_path(
    library_root: Path,
    record: dict[str, object],
    existing_by_name: dict[str, list[Path]],
) -> Path | None:
    output_path = Path(str(record.get("output_path") or ""))
    if output_path.exists():
        return output_path
    if output_path.name:
        candidates = existing_by_name.get(output_path.name, [])
        if len(candidates) == 1:
            return candidates[0]
    source_id = str(record.get("source_video_id") or "")
    scene_id = str(record.get("scene_id") or "")
    if source_id and scene_id:
        pattern = f"*__{source_id}_{scene_id}.mp4"
        candidates = list(library_root.rglob(pattern))
        candidates = [path for path in candidates if "._系统记录" not in path.parts]
        if len(candidates) == 1:
            return candidates[0]
        readable_pattern = f"*_{source_id}_{scene_id}.mp4"
        candidates = list(library_root.rglob(readable_pattern))
        candidates = [path for path in candidates if "._系统记录" not in path.parts]
        if len(candidates) == 1:
            return candidates[0]
    return None


def infer_category_keyword(library_root: Path, clip_path: Path, record: dict[str, object]) -> tuple[str, str]:
    try:
        relative = clip_path.relative_to(library_root)
        parts = relative.parts
    except ValueError:
        parts = ()
    if len(parts) >= 3 and parts[0] in MAIN_CATEGORIES:
        return parts[0], parts[1]
    category_top1 = str(record.get("category_top1") or "")
    if "/" in category_top1:
        category, keyword = category_top1.split("/", 1)
        return category, keyword
    category = str(record.get("primary_category") or "90_待人工分类")
    return category, "未细分"


def build_readable_clip_name(location_name: str, keyword: str, record: dict[str, object], serial: int) -> str:
    quality = sanitize_name(str(record.get("quality_level") or "A"), "A")
    source_id = sanitize_name(str(record.get("source_video_id") or "V000"), "V000")
    scene_id = sanitize_name(str(record.get("scene_id") or "S000"), "S000")
    safe_location = sanitize_name(location_name, "地点")
    safe_keyword = sanitize_name(keyword, "未细分")
    return f"{serial:03d}_{safe_location}_{safe_keyword}__{quality}_{source_id}_{scene_id}.mp4"


def infer_location_name(library_root: Path) -> str:
    name = library_root.name
    name = re.sub(r"智能镜头分类$", "", name)
    name = re.sub(r"-原视频素材$", "", name)
    return sanitize_name(name.strip() or "地点", "地点")


def record_sort_key(record: dict[str, object]) -> tuple[int, str, str, str, str]:
    category = str(record.get("primary_category") or "")
    try:
        category_rank = MAIN_CATEGORIES.index(category)
    except ValueError:
        category_rank = 999
    category_top1 = str(record.get("category_top1") or "")
    return (
        category_rank,
        category_top1,
        str(record.get("source_video_id") or ""),
        str(record.get("scene_id") or ""),
        str(record.get("output_path") or ""),
    )


def update_scene_record(db_path: Path, item: RenameItem) -> None:
    record = dict(item.record)
    previous_path = str(record.get("output_path") or item.current_path)
    record["output_path"] = str(item.target_path)
    record["renamed_from_path"] = previous_path
    record["rename_serial"] = f"{item.serial:03d}"
    record["rename_keyword"] = item.keyword
    record["updated_at"] = datetime.now().isoformat(timespec="seconds")
    con = sqlite3.connect(db_path)
    try:
        con.execute(
            """
            update scenes
            set output_path=?, record_json=?, updated_at=datetime('now')
            where source_video_path=? and scene_id=?
            """,
            (
                str(item.target_path),
                json.dumps(record, ensure_ascii=False),
                str(record.get("source_video_path") or ""),
                str(record.get("scene_id") or ""),
            ),
        )
        con.commit()
    finally:
        con.close()


def mark_missing_records(db_path: Path, missing_records: list[dict[str, object]]) -> None:
    if not missing_records:
        return
    con = sqlite3.connect(db_path)
    try:
        for record in missing_records:
            updated_record = dict(record)
            updated_record["processing_status"] = "missing_output_file"
            updated_record["skip_reason"] = "output file missing during rename self-check"
            updated_record["updated_at"] = datetime.now().isoformat(timespec="seconds")
            con.execute(
                """
                update scenes
                set processing_status='missing_output_file',
                    skip_reason='output file missing during rename self-check',
                    record_json=?,
                    updated_at=datetime('now')
                where source_video_path=? and scene_id=?
                """,
                (
                    json.dumps(updated_record, ensure_ascii=False),
                    str(record.get("source_video_path") or ""),
                    str(record.get("scene_id") or ""),
                ),
            )
        con.commit()
    finally:
        con.close()


def export_project_files(
    library_root: Path,
    items: list[RenameItem],
    actual_renamed: int,
    missing_records: list[dict[str, object]],
) -> None:
    store = RecordStore(library_root / "._系统记录")
    try:
        store.export_files(
            {
                "library_root": str(library_root),
                "rename_processed": len(items),
                "rename_renamed": actual_renamed,
                "rename_missing_records": len(missing_records),
            }
        )
    finally:
        store.close()


def write_rename_report(
    library_root: Path,
    items: list[RenameItem],
    missing_records: list[dict[str, object]],
    move_files: bool,
    actual_renamed: int,
) -> None:
    system_dir = library_root / "._系统记录"
    system_dir.mkdir(parents=True, exist_ok=True)
    csv_name = "clip_rename.csv" if move_files else "clip_rename_preview.csv"
    summary_name = "clip_rename_summary.json" if move_files else "clip_rename_preview_summary.json"
    with (system_dir / csv_name).open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                "old_path",
                "new_path",
                "status",
                "serial",
                "category",
                "keyword",
                "source_video_id",
                "scene_id",
            ],
        )
        writer.writeheader()
        for item in items:
            writer.writerow(
                {
                    "old_path": str(item.current_path),
                    "new_path": str(item.target_path),
                    "status": item.status,
                    "serial": f"{item.serial:03d}",
                    "category": item.category,
                    "keyword": item.keyword,
                    "source_video_id": item.record.get("source_video_id", ""),
                    "scene_id": item.record.get("scene_id", ""),
                }
            )
        for record in missing_records:
            writer.writerow(
                {
                    "old_path": str(record.get("output_path") or ""),
                    "new_path": "",
                    "status": "missing_output_file",
                    "serial": "",
                    "category": str(record.get("primary_category") or ""),
                    "keyword": str(record.get("category_top1") or ""),
                    "source_video_id": record.get("source_video_id", ""),
                    "scene_id": record.get("scene_id", ""),
                }
            )
    summary = {
        "processed": len(items),
        "missing_records": len(missing_records),
        "renamed": actual_renamed,
        "would_rename": sum(1 for item in items if item.status == "renamed"),
        "move_files": move_files,
    }
    (system_dir / summary_name).write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
