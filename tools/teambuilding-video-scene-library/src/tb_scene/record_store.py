from __future__ import annotations

from pathlib import Path
import csv
import json
import sqlite3
from datetime import datetime
from typing import Iterable

from .models import ClipRecord, VideoInfo
from .path_utils import seconds_to_timestamp


SCENE_FIELDS = [
    "location",
    "source_video_id",
    "source_video_name",
    "source_video_path",
    "source_video_hash",
    "scene_id",
    "source_start_time",
    "source_end_time",
    "source_start_frame",
    "source_end_frame",
    "duration",
    "width",
    "height",
    "fps",
    "codec",
    "output_path",
    "keyframe_start_path",
    "keyframe_middle_path",
    "keyframe_end_path",
    "sharpness_average",
    "brightness_average",
    "black_ratio",
    "overexposure_ratio",
    "quality_level",
    "quality_reasons",
    "category_top1",
    "confidence_top1",
    "category_top2",
    "confidence_top2",
    "category_top3",
    "confidence_top3",
    "primary_category",
    "secondary_categories",
    "semantic_tags",
    "usage_tags",
    "duplicate_group",
    "duplicate_score",
    "selected_as_best",
    "skipped_as_duplicate",
    "manual_category",
    "manual_output_path",
    "manual_locked",
    "processing_status",
    "skip_reason",
    "created_at",
    "updated_at",
]


SOURCE_FIELDS = [
    "source_video_id",
    "source_video_name",
    "source_video_path",
    "source_video_hash",
    "size",
    "mtime_ns",
    "duration",
    "width",
    "height",
    "fps",
    "codec",
    "orientation",
    "status",
    "skip_reason",
    "updated_at",
]


class RecordStore:
    def __init__(self, system_dir: Path) -> None:
        self.system_dir = system_dir
        self.system_dir.mkdir(parents=True, exist_ok=True)
        self.db_path = system_dir / "project.sqlite"
        self.conn = sqlite3.connect(self.db_path)
        self.conn.row_factory = sqlite3.Row
        self._init_schema()

    def close(self) -> None:
        self.conn.commit()
        self.conn.close()

    def _init_schema(self) -> None:
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS source_videos (
                source_video_id TEXT,
                source_video_path TEXT PRIMARY KEY,
                source_video_name TEXT,
                source_video_hash TEXT,
                size INTEGER,
                mtime_ns INTEGER,
                duration REAL,
                width INTEGER,
                height INTEGER,
                fps REAL,
                codec TEXT,
                orientation TEXT,
                status TEXT,
                skip_reason TEXT,
                updated_at TEXT
            )
            """
        )
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS scenes (
                location TEXT,
                source_video_id TEXT,
                source_video_path TEXT,
                scene_id TEXT,
                source_start_time REAL,
                source_end_time REAL,
                output_path TEXT,
                primary_category TEXT,
                confidence_top1 REAL,
                quality_level TEXT,
                processing_status TEXT,
                skip_reason TEXT,
                record_json TEXT,
                updated_at TEXT,
                PRIMARY KEY (source_video_path, scene_id)
            )
            """
        )
        self.conn.commit()

    def source_processed(self, source: VideoInfo) -> bool:
        row = self.conn.execute(
            """
            SELECT status, source_video_hash, size, mtime_ns
            FROM source_videos
            WHERE source_video_path = ?
            """,
            (str(source.path),),
        ).fetchone()
        if not row:
            return False
        return (
            row["status"] in {"processed", "skipped_duplicate_source", "skipped_orientation"}
            and row["source_video_hash"] == source.sha256
            and int(row["size"]) == source.size
            and int(row["mtime_ns"]) == source.mtime_ns
        )

    def known_hash_owner(self, digest: str) -> str | None:
        row = self.conn.execute(
            """
            SELECT source_video_path
            FROM source_videos
            WHERE source_video_hash = ? AND status = 'processed'
            LIMIT 1
            """,
            (digest,),
        ).fetchone()
        return str(row["source_video_path"]) if row else None

    def scene_completed(self, source: VideoInfo, scene_id: str) -> bool:
        row = self.conn.execute(
            """
            SELECT output_path, processing_status
            FROM scenes
            WHERE source_video_path = ? AND scene_id = ?
            """,
            (str(source.path), scene_id),
        ).fetchone()
        if not row:
            return False
        status = str(row["processing_status"])
        output_path = str(row["output_path"] or "")
        if status == "skipped_low_quality":
            return True
        if status == "written" and output_path:
            return Path(output_path).exists()
        return False

    def record_source(self, source: VideoInfo) -> None:
        now = datetime.now().isoformat(timespec="seconds")
        self.conn.execute(
            """
            INSERT INTO source_videos VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(source_video_path) DO UPDATE SET
                source_video_id = excluded.source_video_id,
                source_video_name = excluded.source_video_name,
                source_video_hash = excluded.source_video_hash,
                size = excluded.size,
                mtime_ns = excluded.mtime_ns,
                duration = excluded.duration,
                width = excluded.width,
                height = excluded.height,
                fps = excluded.fps,
                codec = excluded.codec,
                orientation = excluded.orientation,
                status = excluded.status,
                skip_reason = excluded.skip_reason,
                updated_at = excluded.updated_at
            """,
            (
                source.video_id,
                str(source.path),
                source.path.name,
                source.sha256,
                source.size,
                source.mtime_ns,
                source.duration,
                source.width,
                source.height,
                source.fps,
                source.codec,
                source.orientation,
                source.status,
                source.skip_reason,
                now,
            ),
        )
        self.conn.commit()

    def record_scene(self, record: ClipRecord) -> None:
        row = scene_record_to_row(record)
        now = row["updated_at"]
        self.conn.execute(
            """
            INSERT INTO scenes VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(source_video_path, scene_id) DO UPDATE SET
                output_path = excluded.output_path,
                primary_category = excluded.primary_category,
                confidence_top1 = excluded.confidence_top1,
                quality_level = excluded.quality_level,
                processing_status = excluded.processing_status,
                skip_reason = excluded.skip_reason,
                record_json = excluded.record_json,
                updated_at = excluded.updated_at
            """,
            (
                row["location"],
                row["source_video_id"],
                row["source_video_path"],
                row["scene_id"],
                record.scene.start_time,
                record.scene.end_time,
                row["output_path"],
                row["primary_category"],
                row["confidence_top1"],
                row["quality_level"],
                row["processing_status"],
                row["skip_reason"],
                json.dumps(row, ensure_ascii=False),
                now,
            ),
        )
        self.conn.commit()

    def export_files(self, summary: dict[str, object]) -> None:
        source_rows = [dict(row) for row in self.conn.execute("SELECT * FROM source_videos ORDER BY source_video_id")]
        scene_rows = [
            json.loads(row["record_json"])
            for row in self.conn.execute("SELECT record_json FROM scenes ORDER BY source_video_id, scene_id")
            if row["record_json"]
        ]
        write_csv(self.system_dir / "source_videos.csv", SOURCE_FIELDS, source_rows)
        write_csv(self.system_dir / "scenes.csv", SCENE_FIELDS, scene_rows)
        (self.system_dir / "project.json").write_text(
            json.dumps({"summary": summary, "sources": source_rows, "scenes": scene_rows}, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )


def scene_record_to_row(record: ClipRecord) -> dict[str, object]:
    now = datetime.now().isoformat(timespec="seconds")
    keyframes = [str(path) for path in record.keyframes]
    category = record.classification.primary_category
    if record.classification.subcategory:
        category = f"{category}/{record.classification.subcategory}"
    return {
        "location": record.location,
        "source_video_id": record.source.video_id,
        "source_video_name": record.source.path.name,
        "source_video_path": str(record.source.path),
        "source_video_hash": record.source.sha256,
        "scene_id": record.scene.scene_id,
        "source_start_time": seconds_to_timestamp(record.scene.start_time),
        "source_end_time": seconds_to_timestamp(record.scene.end_time),
        "source_start_frame": record.scene.start_frame,
        "source_end_frame": record.scene.end_frame,
        "duration": round(record.scene.duration, 3),
        "width": record.source.width,
        "height": record.source.height,
        "fps": round(record.source.fps, 3),
        "codec": record.source.codec,
        "output_path": str(record.output_path) if record.output_path else "",
        "keyframe_start_path": keyframes[0] if len(keyframes) > 0 else "",
        "keyframe_middle_path": keyframes[1] if len(keyframes) > 1 else "",
        "keyframe_end_path": keyframes[2] if len(keyframes) > 2 else "",
        "sharpness_average": record.quality.sharpness_average,
        "brightness_average": record.quality.brightness_average,
        "black_ratio": record.quality.black_ratio,
        "overexposure_ratio": record.quality.overexposure_ratio,
        "quality_level": record.quality.quality_level,
        "quality_reasons": ";".join(record.quality.quality_reasons),
        "category_top1": category,
        "confidence_top1": record.classification.confidence,
        "category_top2": "",
        "confidence_top2": "",
        "category_top3": "",
        "confidence_top3": "",
        "primary_category": record.classification.primary_category,
        "secondary_categories": "",
        "semantic_tags": ";".join(record.classification.semantic_tags),
        "usage_tags": ";".join(record.classification.usage_tags),
        "duplicate_group": record.extra.get("duplicate_group", ""),
        "duplicate_score": record.extra.get("duplicate_score", ""),
        "selected_as_best": record.extra.get("selected_as_best", ""),
        "skipped_as_duplicate": record.extra.get("skipped_as_duplicate", ""),
        "manual_category": "",
        "manual_output_path": "",
        "manual_locked": "false",
        "processing_status": record.processing_status,
        "skip_reason": record.skip_reason,
        "created_at": now,
        "updated_at": now,
    }


def write_csv(path: Path, fields: list[str], rows: Iterable[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow(row)
