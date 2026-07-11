from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "tools" / "teambuilding-video-scene-library" / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

from tb_scene.clip_renamer import rename_library_clips
from tb_scene.visual_corrector import VisualCorrection, apply_visual_corrections


def create_scene_database(library_root: Path, records: list[dict[str, object]]) -> Path:
    system_dir = library_root / "._系统记录"
    system_dir.mkdir(parents=True, exist_ok=True)
    db_path = system_dir / "project.sqlite"
    con = sqlite3.connect(db_path)
    try:
        con.execute(
            """
            create table scenes (
                location text,
                source_video_id text,
                source_video_path text,
                scene_id text,
                source_start_time real,
                source_end_time real,
                output_path text,
                primary_category text,
                confidence_top1 real,
                quality_level text,
                processing_status text,
                skip_reason text,
                record_json text,
                updated_at text,
                primary key (source_video_path, scene_id)
            )
            """
        )
        for record in records:
            con.execute(
                """
                insert into scenes values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    record.get("location", "千岛湖"),
                    record["source_video_id"],
                    record["source_video_path"],
                    record["scene_id"],
                    0.0,
                    1.0,
                    record["output_path"],
                    record.get("primary_category", "01_环境空镜"),
                    0.8,
                    record.get("quality_level", "A"),
                    "written",
                    "",
                    json.dumps(record, ensure_ascii=False),
                    "2026-07-11T00:00:00",
                ),
            )
        con.commit()
    finally:
        con.close()
    return db_path


def read_scene(db_path: Path, source_video_path: str, scene_id: str) -> sqlite3.Row:
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    try:
        row = con.execute(
            "select output_path, primary_category, record_json from scenes where source_video_path=? and scene_id=?",
            (source_video_path, scene_id),
        ).fetchone()
        assert row is not None
        return row
    finally:
        con.close()


class ClipRenameTransactionTests(unittest.TestCase):
    def test_preview_and_failure_rollback_keep_filesystem_and_database_aligned(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            library = Path(temp) / "千岛湖智能镜头分类"
            folder = library / "01_环境空镜" / "湖景"
            folder.mkdir(parents=True)
            clip = folder / "旧文件名.mp4"
            clip.write_bytes(b"video")
            source_path = str(Path(temp) / "source-1.mp4")
            record = {
                "location": "千岛湖",
                "source_video_id": "V001",
                "source_video_path": source_path,
                "scene_id": "S001",
                "output_path": str(clip),
                "primary_category": "01_环境空镜",
                "category_top1": "01_环境空镜/湖景",
                "quality_level": "A",
            }
            db_path = create_scene_database(library, [record])

            preview = rename_library_clips(library)
            self.assertEqual(preview["mode"], "preview")
            self.assertEqual(preview["would_rename"], 1)
            self.assertTrue(clip.exists())
            self.assertEqual(read_scene(db_path, source_path, "S001")["output_path"], str(clip))

            with self.assertRaisesRegex(ValueError, "confirm_token"):
                rename_library_clips(library, move_files=True)

            with self.assertRaisesRegex(RuntimeError, "simulated failure"):
                rename_library_clips(
                    library,
                    move_files=True,
                    confirm_token="RENAME",
                    _test_fail_after_renames=1,
                )

            self.assertTrue(clip.exists())
            self.assertEqual(read_scene(db_path, source_path, "S001")["output_path"], str(clip))
            self.assertEqual(len(list(folder.glob("*.mp4"))), 1)

    def test_successful_rename_commits_file_and_database_together(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            library = Path(temp) / "千岛湖智能镜头分类"
            folder = library / "01_环境空镜" / "湖景"
            folder.mkdir(parents=True)
            clip = folder / "旧文件名.mp4"
            clip.write_bytes(b"video")
            source_path = str(Path(temp) / "source-2.mp4")
            record = {
                "location": "千岛湖",
                "source_video_id": "V002",
                "source_video_path": source_path,
                "scene_id": "S002",
                "output_path": str(clip),
                "primary_category": "01_环境空镜",
                "category_top1": "01_环境空镜/湖景",
                "quality_level": "A",
            }
            db_path = create_scene_database(library, [record])

            summary = rename_library_clips(library, move_files=True, confirm_token="RENAME")
            self.assertEqual(summary["renamed"], 1)
            row = read_scene(db_path, source_path, "S002")
            renamed = Path(row["output_path"])
            self.assertTrue(renamed.exists())
            self.assertFalse(clip.exists())
            self.assertIn("V002_S002", renamed.name)


class VisualCorrectionTransactionTests(unittest.TestCase):
    def test_preview_and_failure_rollback_keep_file_and_database_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            library = Path(temp) / "千岛湖智能镜头分类"
            folder = library / "05_项目活动" / "玩水互动"
            folder.mkdir(parents=True)
            clip = folder / "001_千岛湖_玩水互动__A_V003_S003.mp4"
            clip.write_bytes(b"video")
            source_path = str(Path(temp) / "source-3.mp4")
            record = {
                "location": "千岛湖",
                "source_video_id": "V003",
                "source_video_path": source_path,
                "scene_id": "S003",
                "output_path": str(clip),
                "primary_category": "05_项目活动",
                "category_top1": "05_项目活动/玩水互动",
                "quality_level": "A",
            }
            db_path = create_scene_database(library, [record])
            correction = VisualCorrection(
                source_video_id="V003",
                scene_id="S003",
                category="05_项目活动",
                keyword="皮划艇",
                reason="visual review",
                clip_path=str(clip),
            )

            preview = apply_visual_corrections(library, [correction])
            self.assertEqual(preview["mode"], "preview")
            self.assertEqual(preview["would_move"], 1)
            self.assertTrue(clip.exists())
            self.assertFalse((library / "05_项目活动" / "皮划艇").exists())
            self.assertEqual(read_scene(db_path, source_path, "S003")["output_path"], str(clip))
            self.assertEqual(preview["report_csv"], "")

            with self.assertRaisesRegex(ValueError, "confirm_token"):
                apply_visual_corrections(library, [correction], apply=True)

            with self.assertRaisesRegex(RuntimeError, "simulated failure"):
                apply_visual_corrections(
                    library,
                    [correction],
                    apply=True,
                    confirm_token="APPLY",
                    _test_fail_after_moves=1,
                )

            self.assertTrue(clip.exists())
            self.assertFalse((library / "05_项目活动" / "皮划艇").exists())
            row = read_scene(db_path, source_path, "S003")
            self.assertEqual(row["output_path"], str(clip))
            self.assertEqual(row["primary_category"], "05_项目活动")

    def test_successful_visual_correction_commits_file_and_database_together(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            library = Path(temp) / "千岛湖智能镜头分类"
            folder = library / "05_项目活动" / "玩水互动"
            folder.mkdir(parents=True)
            clip = folder / "001_千岛湖_玩水互动__A_V004_S004.mp4"
            clip.write_bytes(b"video")
            source_path = str(Path(temp) / "source-4.mp4")
            record = {
                "location": "千岛湖",
                "source_video_id": "V004",
                "source_video_path": source_path,
                "scene_id": "S004",
                "output_path": str(clip),
                "primary_category": "05_项目活动",
                "category_top1": "05_项目活动/玩水互动",
                "quality_level": "A",
            }
            db_path = create_scene_database(library, [record])
            correction = VisualCorrection(
                source_video_id="V004",
                scene_id="S004",
                category="05_项目活动",
                keyword="皮划艇",
                reason="visual review",
                clip_path=str(clip),
            )

            summary = apply_visual_corrections(
                library,
                [correction],
                apply=True,
                confirm_token="APPLY",
            )
            self.assertEqual(summary["moved"], 1)
            row = read_scene(db_path, source_path, "S004")
            corrected = Path(row["output_path"])
            self.assertTrue(corrected.exists())
            self.assertFalse(clip.exists())
            self.assertEqual(corrected.parent.name, "皮划艇")
            record_json = json.loads(row["record_json"])
            self.assertEqual(record_json["manual_locked"], "true")
            self.assertEqual(record_json["category_top1"], "05_项目活动/皮划艇")


if __name__ == "__main__":
    unittest.main()
